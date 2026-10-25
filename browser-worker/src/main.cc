// OteTV browser worker entry point. The same executable runs CEF's sub-processes (renderer, GPU, zygote, utility);
// those are recognised by their --type switch and handed straight to CEF. The browser process speaks the worker IPC
// on private copies of stdin/stdout, logs on stderr, and exits when its parent or the pipe goes away.
#include <dirent.h>
#include <fcntl.h>
#include <ftw.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "browser_host.h"
#include "include/cef_app.h"
#include "include/cef_command_line.h"
#include "include/cef_task.h"
#include "include/cef_version.h"
#include "ipc.h"
#include "policy.h"
#include "proxy.h"
#include "wrapper_page.h"

namespace otetv {
namespace {

constexpr int kMaxStartSeconds = 48 * 3600;              // Limits.MAX_START_SECONDS
constexpr int64_t kMaxPositionMs = 48LL * 3600 * 1000;   // Limits.MAX_POSITION_MS
constexpr const char* kRunMarker = ".otetv-browser-run";

void Log(const std::string& message) {
  std::fprintf(stderr, "[otetv-browser] %s\n", message.c_str());
}

class FunctionTask : public CefTask {
 public:
  explicit FunctionTask(std::function<void()> fn) : fn_(std::move(fn)) {}
  void Execute() override { fn_(); }

 private:
  std::function<void()> fn_;
  IMPLEMENT_REFCOUNTING(FunctionTask);
};

class WorkerApp : public CefApp, public CefBrowserProcessHandler {
 public:
  explicit WorkerApp(int proxy_port) : proxy_port_(proxy_port) {}

  void SetHost(CefRefPtr<BrowserHost> host) { host_ = host; }

  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override { return this; }

  void OnBeforeCommandLineProcessing(const CefString& process_type, CefRefPtr<CefCommandLine> command_line) override {
    if (!process_type.empty()) {
      return;  // Chromium forwards what each child needs from the browser process
    }
    // Windowless and headless: no visible helper window and no X11/Wayland connection (Gate D).
    command_line->AppendSwitchWithValue("ozone-platform", "headless");
    command_line->AppendSwitch("disable-gpu");
    command_line->AppendSwitch("disable-gpu-compositing");
    // Every request goes through the guarded proxy; loopback is not exempt, and no UDP path bypasses it.
    command_line->AppendSwitchWithValue("proxy-server", "http://127.0.0.1:" + std::to_string(proxy_port_));
    command_line->AppendSwitchWithValue("proxy-bypass-list", "<-loopback>");
    command_line->AppendSwitch("disable-quic");
    command_line->AppendSwitchWithValue("force-webrtc-ip-handling-policy", "disable_non_proxied_udp");
    // Nothing in the background: no LAN media-router discovery, component updates or other unsolicited traffic.
    command_line->AppendSwitchWithValue("disable-features",
                                        "MediaRouter,DialMediaRouteProvider,CastMediaRouteProvider,GlobalMediaControls,NetworkTimeServiceQuerying");
    command_line->AppendSwitch("disable-background-networking");
    command_line->AppendSwitch("disable-component-update");
    command_line->AppendSwitch("disable-extensions");
    command_line->AppendSwitch("disable-spell-checking");
    command_line->AppendSwitch("disable-notifications");
    command_line->AppendSwitch("deny-permission-prompts");
    command_line->AppendSwitch("no-first-run");
    // Deliberately absent: --no-sandbox and any autoplay-policy override.
    if (std::getenv("OTETV_BROWSER_DEBUG") != nullptr) {
      // Diagnostics only: extra switches for experiments, e.g. OTETV_BROWSER_EXTRA_SWITCHES="foo,bar=1".
      if (const char* extra = std::getenv("OTETV_BROWSER_EXTRA_SWITCHES")) {
        std::string list = extra;
        size_t start = 0;
        while (start < list.size()) {
          size_t comma = list.find(',', start);
          std::string item = list.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
          size_t eq = item.find('=');
          if (item == "no-sandbox" || item.rfind("autoplay-policy", 0) == 0) {
            // never, even for experiments
          } else if (eq == std::string::npos) {
            command_line->AppendSwitch(item);
          } else {
            command_line->AppendSwitchWithValue(item.substr(0, eq), item.substr(eq + 1));
          }
          if (comma == std::string::npos) {
            break;
          }
          start = comma + 1;
        }
      }
    }
  }

  void OnContextInitialized() override {
    CefRegisterSchemeHandlerFactory("https", kWrapperHost, new WrapperSchemeFactory(host_));
    ready_ = true;
    std::vector<std::function<void()>> deferred;
    deferred.swap(deferred_);
    for (auto& fn : deferred) {
      fn();
    }
  }

  // UI thread: runs |fn| now, or once the context is initialised.
  void RunWhenReady(std::function<void()> fn) {
    if (ready_) {
      fn();
    } else {
      deferred_.push_back(std::move(fn));
    }
  }

 private:
  int proxy_port_;
  CefRefPtr<BrowserHost> host_;
  bool ready_ = false;
  std::vector<std::function<void()>> deferred_;
  IMPLEMENT_REFCOUNTING(WorkerApp);
};

CefRefPtr<WorkerApp> g_app;
CefRefPtr<BrowserHost> g_host;
std::atomic<bool> g_quitting{false};

void PostUi(std::function<void()> fn) {
  CefPostTask(TID_UI, new FunctionTask([fn = std::move(fn)]() mutable { g_app->RunWhenReady(std::move(fn)); }));
}

void SendError(int generation, int code, const std::string& detail) {
  PayloadWriter w;
  w.I32(generation).U16(code).Str(detail);
  Out().Send(Type::kError, w);
}

// Closes the browser and quits the message loop; a watchdog guarantees the process exits even if CEF hangs.
void Quit(int exit_code) {
  if (g_quitting.exchange(true)) {
    return;
  }
  CefPostTask(TID_UI, new FunctionTask([] { g_host->Shutdown(); }));
  std::thread([exit_code] {
    std::this_thread::sleep_for(std::chrono::seconds(5));
    _exit(exit_code);
  }).detach();
}

void Dispatch(const Frame& frame, bool& hello_done) {
  PayloadReader r(frame.payload);
  switch (frame.type) {
    case Type::kHello: {
      int protocol = r.I32();
      r.Str(64);  // client version, informational
      int role = r.U8();
      r.Bool();   // safe mode: there are no optional browser effects to disable
      r.ExpectEnd();
      if (protocol != kProtocolVersion || role != kRoleBrowser) {
        SendError(0, kErrRuntimeIncompatible, "protocol " + std::to_string(protocol) + " role " + std::to_string(role));
        Quit(2);
        return;
      }
      hello_done = true;
      PayloadWriter ack;
      // The sandbox is never disabled: Chromium refuses to start without a usable one, so reaching this point means
      // the renderer runs sandboxed (namespace sandbox on Linux; see docs/privacy-and-security.md).
      ack.I32(kProtocolVersion).Str(OTETV_WORKER_VERSION).Str(std::string("CEF ") + CEF_VERSION).I32(kFlagSandboxed).U16(0);
      Out().Send(Type::kHelloAck, ack);
      return;
    }
    case Type::kPing: {
      int64_t nonce = r.I64();
      int64_t client_nanos = r.I64();
      r.ExpectEnd();
      PayloadWriter pong;
      pong.I64(nonce).I64(client_nanos).I64(MonotonicNanos());
      Out().Send(Type::kPong, pong);
      return;
    }
    default:
      break;
  }
  if (!hello_done) {
    throw ProtocolError("message before HELLO");
  }
  switch (frame.type) {
    case Type::kLoadVideo: {
      PlayerConfig config;
      config.generation = r.I32();
      config.video_id = r.Str(11);
      if (!IsValidVideoId(config.video_id)) {
        throw ProtocolError("bad video id");
      }
      config.start_seconds = r.I32(0, kMaxStartSeconds);
      config.autoplay = r.Bool();
      config.captions = r.Bool();
      r.ExpectEnd();
      PostUi([config] { g_host->LoadVideo(config); });
      return;
    }
    case Type::kPlay:
    case Type::kPause:
    case Type::kStop: {
      r.I32();  // generation: the player follows whatever video is loaded
      r.ExpectEnd();
      Type type = frame.type;
      PostUi([type] {
        if (type == Type::kPlay) {
          g_host->Play();
        } else if (type == Type::kPause) {
          g_host->Pause();
        } else {
          g_host->Stop();
        }
      });
      return;
    }
    case Type::kSeek: {
      r.I32();
      int64_t position = r.I64();
      if (position < 0 || position > kMaxPositionMs) {
        throw ProtocolError("seek out of range");
      }
      r.Bool();
      r.ExpectEnd();
      PostUi([position] { g_host->Seek(position); });
      return;
    }
    case Type::kSetVolume: {
      r.I32();
      int percent = r.U8();
      bool muted = r.Bool();
      r.ExpectEnd();
      if (percent > 100) {
        throw ProtocolError("volume out of range");
      }
      PostUi([percent, muted] { g_host->SetVolume(percent, muted); });
      return;
    }
    case Type::kSetVisible: {
      bool visible = r.Bool();
      r.ExpectEnd();
      PostUi([visible] { g_host->SetVisible(visible); });
      return;
    }
    case Type::kCaptionsToggle: {
      bool on = r.Bool();
      r.ExpectEnd();
      PostUi([on] { g_host->SetCaptions(on); });
      return;
    }
    case Type::kResize: {
      int width = r.I32(200, kMaxFrameWidth);
      int height = r.I32(200, kMaxFrameHeight);
      r.ExpectEnd();
      PostUi([width, height] { g_host->Resize(width, height); });
      return;
    }
    case Type::kMouse: {
      r.I32();
      int kind = r.U8();
      int x = r.I32(-1, kMaxFrameWidth);
      int y = r.I32(-1, kMaxFrameHeight);
      int button = r.U8();
      int modifiers = r.I32();
      int wheel = r.I32(-10000, 10000);
      r.ExpectEnd();
      if (kind > 4 || button > 2) {
        throw ProtocolError("bad mouse event");
      }
      PostUi([=] { g_host->Mouse(kind, x, y, button, modifiers, wheel); });
      return;
    }
    case Type::kKey: {
      r.I32();
      int kind = r.U8();
      int key_code = r.I32(-1, 1024);
      int scan_code = r.I32();
      int modifiers = r.I32();
      int code_point = r.I32(0, 0x10FFFF);
      r.ExpectEnd();
      if (kind > 2) {
        throw ProtocolError("bad key event");
      }
      PostUi([=] { g_host->Key(kind, key_code, scan_code, modifiers, code_point); });
      return;
    }
    case Type::kClose:
    case Type::kShutdown:
      Quit(0);
      return;
    default:
      return;  // media-worker messages (OPEN, CLOCK_REPORT, ...) have no meaning here
  }
}

void ReadLoop(int fd) {
  IpcReader reader(fd);
  bool hello_done = false;
  try {
    while (auto frame = reader.Next()) {
      Dispatch(*frame, hello_done);
    }
    Log("client closed the protocol stream");
  } catch (const ProtocolError& e) {
    Log(std::string("protocol error: ") + e.what());
  }
  Quit(0);
}

// Exits when the parent (Minecraft) is gone, so the worker can never be orphaned (section 17.3).
void WatchParent(long parent_pid) {
  while (true) {
    std::this_thread::sleep_for(std::chrono::seconds(1));
    if (kill(static_cast<pid_t>(parent_pid), 0) != 0 && errno == ESRCH) {
      Log("parent process exited");
      Quit(0);
      return;
    }
  }
}

int RemoveEntry(const char* path, const struct stat*, int, struct FTW*) {
  return remove(path);
}

// Deletes a run directory this worker created: only paths carrying our marker, never following symlinks.
void RemoveRunDirectory(const std::string& dir) {
  if (std::getenv("OTETV_BROWSER_DEBUG") != nullptr) {
    std::fprintf(stderr, "[otetv-browser] debug: keeping %s\n", dir.c_str());
    return;
  }
  struct stat st {};
  if (dir.empty() || lstat((dir + "/" + kRunMarker).c_str(), &st) != 0) {
    return;
  }
  nftw(dir.c_str(), RemoveEntry, 16, FTW_DEPTH | FTW_PHYS);
}

// Removes run directories left by workers that crashed more than a day ago.
void CleanStaleRuns(const std::string& cache) {
  DIR* d = opendir(cache.c_str());
  if (d == nullptr) {
    return;
  }
  time_t now = time(nullptr);
  while (dirent* e = readdir(d)) {
    std::string name = e->d_name;
    if (name.rfind("run-", 0) != 0) {
      continue;
    }
    std::string path = cache + "/" + name;
    struct stat st {};
    if (lstat((path + "/" + kRunMarker).c_str(), &st) == 0 && now - st.st_mtime > 24 * 3600) {
      RemoveRunDirectory(path);
    }
  }
  closedir(d);
}

std::string SwitchValue(int argc, char* argv[], const std::string& name) {
  std::string prefix = "--" + name + "=";
  for (int i = 1; i < argc; i++) {
    if (std::strncmp(argv[i], prefix.c_str(), prefix.size()) == 0) {
      return argv[i] + prefix.size();
    }
  }
  return "";
}

// Processes forked from the zygote get a fresh stack canary (--change-stack-guard-on-fork). No frame that was live at
// the fork may check its canary on the way out, and GCC checks even before no-return calls, so the child path lives
// here: no protector, and it leaves through _exit without returning to main().
[[noreturn]] __attribute__((noinline, no_stack_protector)) void RunChildProcess(const CefMainArgs& args) {
  _exit(CefExecuteProcess(args, nullptr, nullptr));
}

}  // namespace
}  // namespace otetv

int main(int argc, char* argv[]) {
  using namespace otetv;
  CefMainArgs main_args(argc, argv);
  for (int i = 1; i < argc; i++) {
    if (std::strncmp(argv[i], "--type=", 7) == 0) {
      if (std::getenv("OTETV_BROWSER_DEBUG") != nullptr) {
        std::fprintf(stderr, "[otetv-browser] child %s pid %d\n", argv[i] + 7, static_cast<int>(getpid()));
      }
      RunChildProcess(main_args);
    }
  }

  // Private copies of the protocol pipes; fd 0 becomes /dev/null and fd 1 stderr, so nothing CEF or a child process
  // prints can corrupt the framed stream.
  int ipc_in = fcntl(0, F_DUPFD_CLOEXEC, 3);
  int ipc_out = fcntl(1, F_DUPFD_CLOEXEC, 3);
  if (ipc_in < 0 || ipc_out < 0) {
    return 3;
  }
  int devnull = open("/dev/null", O_RDONLY | O_CLOEXEC);
  dup2(devnull, 0);
  close(devnull);
  dup2(2, 1);
  signal(SIGPIPE, SIG_IGN);
  InitOut(ipc_out);

  long parent_pid = std::atol(SwitchValue(argc, argv, "otetv-parent-pid").c_str());
  std::string cache = SwitchValue(argc, argv, "otetv-cache");
  if (cache.empty() || cache.front() != '/') {
    cache = "/tmp/otetv-browser-" + std::to_string(getuid());
  }
  mkdir(cache.c_str(), 0700);
  CleanStaleRuns(cache);
  std::string run_dir = cache + "/run-" + std::to_string(getpid());
  if (mkdir(run_dir.c_str(), 0700) != 0 && errno != EEXIST) {
    SendError(0, kErrBrowserUnavailable, "the browser cache directory is not writable");
    return 4;
  }
  if (FILE* marker = std::fopen((run_dir + "/" + kRunMarker).c_str(), "w")) {
    std::fclose(marker);
  }

  GuardedProxy proxy;
  if (!proxy.Start()) {
    SendError(0, kErrBrowserUnavailable, "the browser network guard could not start");
    RemoveRunDirectory(run_dir);
    return 4;
  }

  CefSettings settings;
  settings.no_sandbox = false;  // never disabled (brief section 4: a secure unsupported state beats an unsandboxed one)
  settings.windowless_rendering_enabled = true;
  settings.multi_threaded_message_loop = false;
  // root_cache_path holds this run only; cache_path stays empty, so the profile is in memory: no persistent cookies,
  // history or storage (section 16.4).
  CefString(&settings.root_cache_path) = run_dir;
  settings.persist_session_cookies = false;
  CefString(&settings.locale) = "en-US";
  CefString(&settings.log_file) = run_dir + "/cef.log";
  settings.log_severity = std::getenv("OTETV_BROWSER_DEBUG") != nullptr ? LOGSEVERITY_INFO : LOGSEVERITY_ERROR;
  settings.background_color = CefColorSetARGB(255, 0, 0, 0);
  settings.remote_debugging_port = 0;

  FrameMailbox frames;
  frames.Start();
  g_host = new BrowserHost(&proxy, &frames);
  g_app = new WorkerApp(proxy.port());
  g_app->SetHost(g_host);
  if (!CefInitialize(main_args, settings, g_app, nullptr)) {
    SendError(0, kErrBrowserUnavailable, "the browser runtime did not start");
    frames.Stop();
    proxy.Stop();
    RemoveRunDirectory(run_dir);
    return 5;
  }

  std::thread(ReadLoop, ipc_in).detach();
  if (parent_pid > 1) {
    std::thread(WatchParent, parent_pid).detach();
  }
  CefRunMessageLoop();

  frames.Stop();
  proxy.Stop();
  g_host = nullptr;
  CefShutdown();
  RemoveRunDirectory(run_dir);
  // The IPC reader may still be blocked in read(); nothing is left to flush or clean.
  _exit(0);
}
