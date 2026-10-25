// Guarded CONNECT proxy for all browser traffic (brief section 16.2): Chromium is configured to send every request
// through it, so DNS is resolved here, every answer must be a public address, and the tunnel connects to the pinned
// address it checked. TLS is never decrypted. It listens on loopback only and serves only processes of the same user,
// verified by the kernel (SO_PEERCRED): the browser's network process. Since it can only ever reach public addresses,
// it offers another local process nothing that process could not already do directly.
#ifndef OTETV_BROWSER_WORKER_PROXY_H_
#define OTETV_BROWSER_WORKER_PROXY_H_

#include <atomic>
#include <string>
#include <thread>

namespace otetv {

class GuardedProxy {
 public:
  bool Start();
  void Stop();
  int port() const { return port_; }

 private:
  void AcceptLoop();
  void Serve(int client);

  int listen_fd_ = -1;
  int port_ = 0;
  std::atomic<bool> running_{false};
  std::atomic<int> active_{0};
  std::thread acceptor_;
};

}  // namespace otetv

#endif  // OTETV_BROWSER_WORKER_PROXY_H_
