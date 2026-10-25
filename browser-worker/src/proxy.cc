#include "proxy.h"

#include "policy.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace otetv {
namespace {

constexpr int kMaxTunnels = 96;
constexpr size_t kMaxHead = 8192;
constexpr int kHeadTimeoutMs = 10000;
constexpr int kConnectTimeoutMs = 10000;
constexpr int kIdleTimeoutMs = 300000;

void Log(const std::string& message) {
  std::fprintf(stderr, "[otetv-browser] proxy: %s\n", message.c_str());
}

bool Debug() {
  static const bool debug = std::getenv("OTETV_BROWSER_DEBUG") != nullptr;
  return debug;
}

bool SendAll(int fd, const char* p, size_t n) {
  while (n > 0) {
    ssize_t w = send(fd, p, n, MSG_NOSIGNAL);
    if (w < 0) {
      if (errno == EINTR) {
        continue;
      }
      return false;
    }
    p += w;
    n -= static_cast<size_t>(w);
  }
  return true;
}

void Reply(int fd, const char* response) {
  SendAll(fd, response, std::strlen(response));
}

std::string Lower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return s;
}

int ConnectWithTimeout(const addrinfo* ai) {
  int fd = socket(ai->ai_family, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
  if (fd < 0) {
    return -1;
  }
  if (connect(fd, ai->ai_addr, ai->ai_addrlen) != 0) {
    if (errno != EINPROGRESS) {
      close(fd);
      return -1;
    }
    pollfd p{fd, POLLOUT, 0};
    int error = 0;
    socklen_t len = sizeof error;
    if (poll(&p, 1, kConnectTimeoutMs) != 1 || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) != 0 || error != 0) {
      close(fd);
      return -1;
    }
  }
  int flags = fcntl(fd, F_GETFL);
  fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
  return fd;
}

// The kernel records each socket's owner in /proc/net/tcp; the connecting end of a loopback connection to us is the
// line whose local port is the peer's port and whose remote port is ours. If /proc cannot be read (hidepid, unusual
// sandboxes) loopback connections are served anyway: the proxy only ever reaches public addresses.
bool SameUserPeer(int client, int listen_port) {
  sockaddr_in peer{};
  socklen_t len = sizeof peer;
  if (getpeername(client, reinterpret_cast<sockaddr*>(&peer), &len) != 0 || peer.sin_family != AF_INET) {
    return false;
  }
  FILE* f = std::fopen("/proc/net/tcp", "r");
  if (f == nullptr) {
    return true;
  }
  char line[512];
  bool found = false;
  bool same = false;
  int peer_port = ntohs(peer.sin_port);
  bool header = true;
  while (std::fgets(line, sizeof line, f) != nullptr) {
    if (header) {
      header = false;
      continue;
    }
    unsigned local_ip = 0;
    unsigned local_port = 0;
    unsigned remote_ip = 0;
    unsigned remote_port = 0;
    unsigned uid = 0;
    if (std::sscanf(line, " %*d: %8X:%4X %8X:%4X %*X %*X:%*X %*X:%*X %*X %u", &local_ip, &local_port, &remote_ip, &remote_port, &uid) == 5 &&
        static_cast<int>(local_port) == peer_port && static_cast<int>(remote_port) == listen_port) {
      found = true;
      same = uid == getuid();
      break;
    }
  }
  std::fclose(f);
  return !found || same;
}

void Relay(int a, int b) {
  std::vector<char> buf(64 * 1024);
  pollfd fds[2] = {{a, POLLIN, 0}, {b, POLLIN, 0}};
  while (true) {
    int r = poll(fds, 2, kIdleTimeoutMs);
    if (r <= 0) {
      return;  // idle timeout or error
    }
    for (int i = 0; i < 2; i++) {
      if (fds[i].revents & (POLLIN | POLLHUP | POLLERR)) {
        ssize_t n = recv(fds[i].fd, buf.data(), buf.size(), 0);
        if (n <= 0 || !SendAll(fds[1 - i].fd, buf.data(), static_cast<size_t>(n))) {
          return;
        }
      }
    }
  }
}

}  // namespace

bool GuardedProxy::Start() {
  listen_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (listen_fd_ < 0) {
    return false;
  }
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  addr.sin_port = 0;
  socklen_t len = sizeof addr;
  if (bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0 || listen(listen_fd_, 64) != 0 ||
      getsockname(listen_fd_, reinterpret_cast<sockaddr*>(&addr), &len) != 0) {
    close(listen_fd_);
    listen_fd_ = -1;
    return false;
  }
  port_ = ntohs(addr.sin_port);
  running_ = true;
  acceptor_ = std::thread(&GuardedProxy::AcceptLoop, this);
  return true;
}

void GuardedProxy::Stop() {
  if (!running_.exchange(false)) {
    return;
  }
  if (acceptor_.joinable()) {
    acceptor_.join();
  }
  close(listen_fd_);
  listen_fd_ = -1;
}

void GuardedProxy::AcceptLoop() {
  while (running_) {
    pollfd p{listen_fd_, POLLIN, 0};
    if (poll(&p, 1, 500) <= 0) {
      continue;
    }
    int client = accept4(listen_fd_, nullptr, nullptr, SOCK_CLOEXEC);
    if (client < 0) {
      continue;
    }
    if (!SameUserPeer(client, port_)) {
      close(client);  // another user's process
      continue;
    }
    if (active_.load() >= kMaxTunnels) {
      Reply(client, "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      close(client);
      continue;
    }
    active_++;
    // The proxy lives for the whole process, so detached tunnel threads never outlive it.
    std::thread([this, client] {
      Serve(client);
      close(client);
      active_--;
    }).detach();
  }
}

void GuardedProxy::Serve(int client) {
  std::string head;
  char buf[2048];
  auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kHeadTimeoutMs);
  while (head.find("\r\n\r\n") == std::string::npos) {
    if (head.size() > kMaxHead) {
      Reply(client, "HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    int left = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count());
    pollfd p{client, POLLIN, 0};
    if (left <= 0 || poll(&p, 1, left) != 1) {
      return;
    }
    ssize_t n = recv(client, buf, sizeof buf, 0);
    if (n <= 0) {
      return;
    }
    head.append(buf, static_cast<size_t>(n));
  }
  size_t end = head.find("\r\n\r\n");
  std::string early = head.substr(end + 4);  // bytes a client sent ahead of the tunnel reply
  head.resize(end);

  size_t eol = head.find("\r\n");
  std::string line = head.substr(0, eol);
  size_t s1 = line.find(' ');
  size_t s2 = s1 == std::string::npos ? std::string::npos : line.find(' ', s1 + 1);
  if (s1 == std::string::npos || s2 == std::string::npos) {
    Reply(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  std::string method = line.substr(0, s1);
  std::string target = line.substr(s1 + 1, s2 - s1 - 1);
  if (Debug()) {
    Log(method + " " + target);
  }
  if (method != "CONNECT") {
    // Plain-http requests arrive here as absolute-form GETs; only TLS tunnels are allowed.
    Reply(client, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  std::string host;
  std::string port;
  if (!target.empty() && target.front() == '[') {
    size_t close_bracket = target.find(']');
    if (close_bracket == std::string::npos || target.compare(close_bracket + 1, 1, ":") != 0) {
      Reply(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    host = target.substr(1, close_bracket - 1);
    port = target.substr(close_bracket + 2);
  } else {
    size_t colon = target.rfind(':');
    host = colon == std::string::npos ? target : target.substr(0, colon);
    port = colon == std::string::npos ? "" : target.substr(colon + 1);
  }
  host = Lower(host);
  if (port != "443") {
    Reply(client, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  addrinfo hints{};
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_family = AF_UNSPEC;
  bool literal_public = false;
  if (ClassifyLiteral(host, &literal_public)) {
    if (!literal_public) {
      Log("refused a non-public address literal");
      Reply(client, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    hints.ai_flags = AI_NUMERICHOST;
  } else if (!IsDnsName(host)) {
    Reply(client, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  addrinfo* results = nullptr;
  if (getaddrinfo(host.c_str(), "443", &hints, &results) != 0 || results == nullptr) {
    Reply(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  // Every answer must be public: mixed public and non-public answers are a rebinding signal and are refused.
  for (addrinfo* ai = results; ai != nullptr; ai = ai->ai_next) {
    if (!IsPublicAddress(ai->ai_addr)) {
      freeaddrinfo(results);
      Log("refused " + host + ": it resolves to a non-public address");
      Reply(client, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
  }
  int upstream = -1;
  for (addrinfo* ai = results; ai != nullptr && upstream < 0; ai = ai->ai_next) {
    upstream = ConnectWithTimeout(ai);
  }
  freeaddrinfo(results);
  if (upstream < 0) {
    Reply(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  sockaddr_storage peer{};
  socklen_t peer_len = sizeof peer;
  if (getpeername(upstream, reinterpret_cast<sockaddr*>(&peer), &peer_len) != 0 || !IsPublicAddress(reinterpret_cast<sockaddr*>(&peer))) {
    close(upstream);
    Reply(client, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
    return;
  }
  if (Debug()) {
    Log("tunnel open to " + host);
  }
  Reply(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
  if (early.empty() || SendAll(upstream, early.data(), early.size())) {
    Relay(client, upstream);
  }
  close(upstream);
}

}  // namespace otetv
