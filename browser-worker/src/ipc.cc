#include "ipc.h"

#include <cerrno>
#include <cstring>
#include <ctime>
#include <memory>
#include <unistd.h>

namespace otetv {
namespace {

// Client -> worker message ceilings, mirroring IpcType.maxPayload for every TO_WORKER type.
int MaxInboundPayload(uint8_t code) {
  switch (code) {
    case 0x01: return 4096;       // HELLO
    case 0x02: return 16 * 1024;  // OPEN (media worker only)
    case 0x03: case 0x04: case 0x06: case 0x07: case 0x09: case 0x14: case 0x16: return 16;
    case 0x05: case 0x08: case 0x0B: case 0x0C: case 0x0D: case 0x0E: case 0x13: case 0x15: return 32;
    case 0x0A: case 0x11: case 0x12: return 64;
    case 0x0F: return 8;          // SHUTDOWN
    case 0x10: return 512;        // LOAD_VIDEO
    default: return -1;           // unknown, or a worker -> client type
  }
}

std::unique_ptr<IpcWriter> g_out;

}  // namespace

PayloadWriter& PayloadWriter::U8(int v) {
  buf_.push_back(static_cast<uint8_t>(v));
  return *this;
}

PayloadWriter& PayloadWriter::U16(int v) {
  buf_.push_back(static_cast<uint8_t>(v >> 8));
  buf_.push_back(static_cast<uint8_t>(v));
  return *this;
}

PayloadWriter& PayloadWriter::I32(int32_t v) {
  uint32_t u = static_cast<uint32_t>(v);
  for (int shift = 24; shift >= 0; shift -= 8) {
    buf_.push_back(static_cast<uint8_t>(u >> shift));
  }
  return *this;
}

PayloadWriter& PayloadWriter::I64(int64_t v) {
  uint64_t u = static_cast<uint64_t>(v);
  I32(static_cast<int32_t>(u >> 32));
  return I32(static_cast<int32_t>(u & 0xFFFFFFFFu));
}

PayloadWriter& PayloadWriter::Str(const std::string& s) {
  size_t n = s.size() > 0xFFFF ? 0xFFFF : s.size();
  U16(static_cast<int>(n));
  buf_.insert(buf_.end(), s.begin(), s.begin() + static_cast<long>(n));
  return *this;
}

void PayloadReader::Need(size_t n) const {
  if (buf_.size() - pos_ < n) {
    throw ProtocolError("payload too short");
  }
}

int PayloadReader::U8() {
  Need(1);
  return buf_[pos_++];
}

bool PayloadReader::Bool() {
  int v = U8();
  if (v > 1) {
    throw ProtocolError("bad boolean");
  }
  return v == 1;
}

int PayloadReader::U16() {
  Need(2);
  int v = (buf_[pos_] << 8) | buf_[pos_ + 1];
  pos_ += 2;
  return v;
}

int32_t PayloadReader::I32() {
  Need(4);
  uint32_t v = (static_cast<uint32_t>(buf_[pos_]) << 24) | (static_cast<uint32_t>(buf_[pos_ + 1]) << 16) |
               (static_cast<uint32_t>(buf_[pos_ + 2]) << 8) | buf_[pos_ + 3];
  pos_ += 4;
  return static_cast<int32_t>(v);
}

int32_t PayloadReader::I32(int32_t min, int32_t max) {
  int32_t v = I32();
  if (v < min || v > max) {
    throw ProtocolError("value out of range");
  }
  return v;
}

int64_t PayloadReader::I64() {
  uint64_t hi = static_cast<uint32_t>(I32());
  uint64_t lo = static_cast<uint32_t>(I32());
  return static_cast<int64_t>((hi << 32) | lo);
}

std::string PayloadReader::Str(size_t max_bytes) {
  size_t len = static_cast<size_t>(U16());
  if (len > max_bytes) {
    throw ProtocolError("string too long");
  }
  Need(len);
  std::string s(reinterpret_cast<const char*>(buf_.data() + pos_), len);
  pos_ += len;
  return s;
}

void PayloadReader::ExpectEnd() const {
  if (pos_ != buf_.size()) {
    throw ProtocolError("trailing bytes");
  }
}

bool IpcReader::ReadFully(void* dst, size_t n, bool allow_eof) {
  auto* p = static_cast<uint8_t*>(dst);
  size_t done = 0;
  while (done < n) {
    ssize_t r = read(fd_, p + done, n - done);
    if (r == 0) {
      if (allow_eof && done == 0) {
        return false;
      }
      throw ProtocolError("stream ended inside a frame");
    }
    if (r < 0) {
      if (errno == EINTR) {
        continue;
      }
      throw ProtocolError(std::string("read failed: ") + std::strerror(errno));
    }
    done += static_cast<size_t>(r);
  }
  return true;
}

std::optional<Frame> IpcReader::Next() {
  uint8_t head[5];
  if (!ReadFully(head, 1, true)) {
    return std::nullopt;
  }
  ReadFully(head + 1, 4, false);
  int32_t length = static_cast<int32_t>((static_cast<uint32_t>(head[0]) << 24) | (static_cast<uint32_t>(head[1]) << 16) |
                                        (static_cast<uint32_t>(head[2]) << 8) | head[3]);
  if (length < 1) {
    throw ProtocolError("bad frame length");
  }
  uint8_t code = head[4];
  int max = MaxInboundPayload(code);
  if (max < 0) {
    throw ProtocolError("unknown or misdirected message type");
  }
  int payload = length - 1;
  if (payload > max) {
    throw ProtocolError("payload exceeds its ceiling");
  }
  Frame frame{static_cast<Type>(code), std::vector<uint8_t>(static_cast<size_t>(payload))};
  if (payload > 0) {
    ReadFully(frame.payload.data(), static_cast<size_t>(payload), false);
  }
  return frame;
}

bool IpcWriter::WriteAll(const void* src, size_t n) {
  const auto* p = static_cast<const uint8_t*>(src);
  size_t done = 0;
  while (done < n) {
    ssize_t w = write(fd_, p + done, n - done);
    if (w < 0) {
      if (errno == EINTR) {
        continue;
      }
      closed_ = true;
      return false;
    }
    done += static_cast<size_t>(w);
  }
  return true;
}

bool IpcWriter::Send(Type type, const PayloadWriter& payload) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (closed_) {
    return false;
  }
  const auto& body = payload.bytes();
  uint32_t length = static_cast<uint32_t>(body.size() + 1);
  uint8_t head[5] = {static_cast<uint8_t>(length >> 24), static_cast<uint8_t>(length >> 16), static_cast<uint8_t>(length >> 8),
                     static_cast<uint8_t>(length), static_cast<uint8_t>(type)};
  return WriteAll(head, sizeof head) && (body.empty() || WriteAll(body.data(), body.size()));
}

bool IpcWriter::SendFrame(int generation, int sequence, int64_t pts_nanos, int width, int height, const void* bgra) {
  if (width < 1 || height < 1 || width > kMaxFrameWidth || height > kMaxFrameHeight) {
    return false;
  }
  int stride = width * 4;
  PayloadWriter header;  // FrameHeader: i32 generation, i32 sequence, i64 pts, i32 width, i32 height, i32 stride, u8 format
  header.I32(generation).I32(sequence).I64(pts_nanos).I32(width).I32(height).I32(stride).U8(kPixelBgra);
  size_t pixels = static_cast<size_t>(stride) * static_cast<size_t>(height);
  uint32_t length = static_cast<uint32_t>(header.bytes().size() + pixels + 1);
  uint8_t head[5] = {static_cast<uint8_t>(length >> 24), static_cast<uint8_t>(length >> 16), static_cast<uint8_t>(length >> 8),
                     static_cast<uint8_t>(length), static_cast<uint8_t>(Type::kVideoFrame)};
  std::lock_guard<std::mutex> lock(mutex_);
  if (closed_) {
    return false;
  }
  return WriteAll(head, sizeof head) && WriteAll(header.bytes().data(), header.bytes().size()) && WriteAll(bgra, pixels);
}

IpcWriter& Out() {
  return *g_out;
}

void InitOut(int fd) {
  g_out = std::make_unique<IpcWriter>(fd);
}

int64_t MonotonicNanos() {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

}  // namespace otetv
