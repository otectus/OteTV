// Worker IPC: [int32 length][u8 type][payload] on the worker's private copies of stdin/stdout, big-endian, matching
// com.otectus.otetv.core.ipc (IpcType, PayloadWriter, PayloadReader, FrameHeader, Msg). Logs never enter this stream.
#ifndef OTETV_BROWSER_WORKER_IPC_H_
#define OTETV_BROWSER_WORKER_IPC_H_

#include <cstdint>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace otetv {

constexpr int kProtocolVersion = 1;  // OteTvCore.IPC_PROTOCOL_VERSION
constexpr int kRoleBrowser = 1;      // Msg.ROLE_BROWSER
constexpr int kFlagSandboxed = 2;    // Msg.HelloAck.FLAG_SANDBOXED
constexpr int kPixelBgra = 1;        // PixelFormat.BGRA ordinal
constexpr int kMaxFrameWidth = 3840; // FrameHeader.MAX_WIDTH
constexpr int kMaxFrameHeight = 2160;

// IpcType codes.
enum class Type : uint8_t {
  kHello = 0x01, kPlay = 0x03, kPause = 0x04, kSeek = 0x05, kStop = 0x06, kClose = 0x07, kPing = 0x0E,
  kShutdown = 0x0F, kLoadVideo = 0x10, kMouse = 0x11, kKey = 0x12, kSetVolume = 0x13, kSetVisible = 0x14,
  kResize = 0x15, kCaptionsToggle = 0x16,
  kHelloAck = 0x40, kVideoFrame = 0x43, kError = 0x48, kPong = 0x4A, kPlayerEvent = 0x4B,
};

// ErrorCode ordinals used by this worker. Pinned by core's IpcCodecTest so the enum cannot drift silently.
enum WireError : int {
  kErrRuntimeIncompatible = 2,
  kErrNetwork = 8,
  kErrBrowserUnavailable = 22,
  kErrWorkerCrash = 24,
  kErrInternal = 29,
};

// Msg.PlayerEvent kinds.
enum PlayerEvent : int {
  kEvReady = 0, kEvState = 1, kEvError = 2, kEvAutoplayBlocked = 3, kEvTime = 4, kEvDuration = 5, kEvApiChange = 6,
  kEvCaptions = 7, kEvLoaded = 8,
};

class ProtocolError : public std::runtime_error {
 public:
  using std::runtime_error::runtime_error;
};

class PayloadWriter {
 public:
  PayloadWriter& U8(int v);
  PayloadWriter& Bool(bool v) { return U8(v ? 1 : 0); }
  PayloadWriter& U16(int v);
  PayloadWriter& I32(int32_t v);
  PayloadWriter& I64(int64_t v);
  PayloadWriter& Str(const std::string& s);
  const std::vector<uint8_t>& bytes() const { return buf_; }

 private:
  std::vector<uint8_t> buf_;
};

class PayloadReader {
 public:
  explicit PayloadReader(const std::vector<uint8_t>& buf) : buf_(buf) {}
  int U8();
  bool Bool();
  int U16();
  int32_t I32();
  int32_t I32(int32_t min, int32_t max);
  int64_t I64();
  std::string Str(size_t max_bytes);
  void ExpectEnd() const;

 private:
  void Need(size_t n) const;
  const std::vector<uint8_t>& buf_;
  size_t pos_ = 0;
};

struct Frame {
  Type type;
  std::vector<uint8_t> payload;
};

// Reads frames from the client. Enforces direction and per-type payload ceilings before allocating.
class IpcReader {
 public:
  explicit IpcReader(int fd) : fd_(fd) {}
  // Returns nullopt on a clean end of stream; throws ProtocolError on malformed input.
  std::optional<Frame> Next();

 private:
  bool ReadFully(void* dst, size_t n, bool allow_eof);
  int fd_;
};

// Writes frames to the client. Thread-safe; a broken pipe marks the writer closed.
class IpcWriter {
 public:
  explicit IpcWriter(int fd) : fd_(fd) {}
  bool Send(Type type, const PayloadWriter& payload);
  // VIDEO_FRAME: FrameHeader followed by tightly packed BGRA rows.
  bool SendFrame(int generation, int sequence, int64_t pts_nanos, int width, int height, const void* bgra);
  bool closed() const { return closed_; }

 private:
  bool WriteAll(const void* src, size_t n);
  int fd_;
  std::mutex mutex_;
  bool closed_ = false;
};

// Process-wide writer, set up by main() before any handler can run.
IpcWriter& Out();
void InitOut(int fd);

int64_t MonotonicNanos();

}  // namespace otetv

#endif  // OTETV_BROWSER_WORKER_IPC_H_
