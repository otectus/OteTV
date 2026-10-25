#include "browser_host.h"

#include <sys/random.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "include/cef_app.h"
#include "include/wrapper/cef_helpers.h"
#include "ipc.h"
#include "policy.h"

namespace otetv {
namespace {

constexpr int kMinWidth = 480;   // the documented minimum 16:9 embed size (brief section 12.1)
constexpr int kMinHeight = 270;

void Log(const std::string& message) {
  std::fprintf(stderr, "[otetv-browser] %s\n", message.c_str());
}

// OTETV_BROWSER_DEBUG=1 logs page console output, request hosts and page loads to stderr (diagnostics only).
bool Debug() {
  static const bool debug = std::getenv("OTETV_BROWSER_DEBUG") != nullptr;
  return debug;
}

std::string RandomHex(size_t bytes) {
  std::vector<uint8_t> raw(bytes);
  size_t got = 0;
  while (got < bytes) {
    ssize_t n = getrandom(raw.data() + got, bytes - got, 0);
    if (n < 0) {
      if (errno == EINTR) {
        continue;
      }
      std::abort();  // no randomness means no safe script nonce
    }
    got += static_cast<size_t>(n);
  }
  static const char* hex = "0123456789abcdef";
  std::string out;
  for (uint8_t b : raw) {
    out += hex[b >> 4];
    out += hex[b & 15];
  }
  return out;
}

void SendError(int generation, int code, const std::string& detail) {
  PayloadWriter w;
  w.I32(generation).U16(code).Str(detail.substr(0, 1024));
  Out().Send(Type::kError, w);
}

void SendPlayerEvent(int generation, int event, int64_t value, const std::string& detail) {
  PayloadWriter w;
  w.I32(generation).U8(event).I64(value).Str(detail);
  Out().Send(Type::kPlayerEvent, w);
}

// Page-supplied detail text: short, printable ASCII only, so it is always valid UTF-8 on the wire.
std::string CleanDetail(const std::string& s) {
  std::string out;
  for (char c : s) {
    if (out.size() >= 64) {
      break;
    }
    if (std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '-' || c == '.' || c == ' ') {
      out += c;
    }
  }
  return out;
}

// GLFW modifier bits (SHIFT 1, CONTROL 2, ALT 4, SUPER 8, CAPS_LOCK 16, NUM_LOCK 32) to CEF event flags.
uint32_t CefModifiers(int glfw) {
  uint32_t flags = EVENTFLAG_NONE;
  if (glfw & 1) flags |= EVENTFLAG_SHIFT_DOWN;
  if (glfw & 2) flags |= EVENTFLAG_CONTROL_DOWN;
  if (glfw & 4) flags |= EVENTFLAG_ALT_DOWN;
  if (glfw & 8) flags |= EVENTFLAG_COMMAND_DOWN;
  if (glfw & 16) flags |= EVENTFLAG_CAPS_LOCK_ON;
  if (glfw & 32) flags |= EVENTFLAG_NUM_LOCK_ON;
  return flags;
}

// GLFW key codes (as Minecraft reports them) to Windows virtual-key codes, which CEF expects on every platform.
int VirtualKey(int glfw) {
  if ((glfw >= 'A' && glfw <= 'Z') || (glfw >= '0' && glfw <= '9') || glfw == ' ') {
    return glfw;  // identical ranges
  }
  if (glfw >= 290 && glfw <= 301) return 0x70 + (glfw - 290);  // F1..F12
  if (glfw >= 320 && glfw <= 329) return 0x60 + (glfw - 320);  // keypad digits
  switch (glfw) {
    case 39: return 0xDE;   // apostrophe
    case 44: return 0xBC;   // comma
    case 45: return 0xBD;   // minus
    case 46: return 0xBE;   // period
    case 47: return 0xBF;   // slash
    case 59: return 0xBA;   // semicolon
    case 61: return 0xBB;   // equal
    case 91: return 0xDB;   // left bracket
    case 92: return 0xDC;   // backslash
    case 93: return 0xDD;   // right bracket
    case 96: return 0xC0;   // grave accent
    case 256: return 0x1B;  // escape
    case 257: return 0x0D;  // enter
    case 258: return 0x09;  // tab
    case 259: return 0x08;  // backspace
    case 260: return 0x2D;  // insert
    case 261: return 0x2E;  // delete
    case 262: return 0x27;  // right
    case 263: return 0x25;  // left
    case 264: return 0x28;  // down
    case 265: return 0x26;  // up
    case 266: return 0x21;  // page up
    case 267: return 0x22;  // page down
    case 268: return 0x24;  // home
    case 269: return 0x23;  // end
    case 330: return 0x6E;  // keypad decimal
    case 331: return 0x6F;  // keypad divide
    case 332: return 0x6A;  // keypad multiply
    case 333: return 0x6D;  // keypad subtract
    case 334: return 0x6B;  // keypad add
    case 335: return 0x0D;  // keypad enter
    case 340: case 344: return 0x10;  // shift
    case 341: case 345: return 0x11;  // control
    case 342: case 346: return 0x12;  // alt
    default: return 0;
  }
}

// A response body served from memory (the wrapper page, or an empty 404).
class MemoryResourceHandler : public CefResourceHandler {
 public:
  MemoryResourceHandler(int status, std::string mime, std::string body, CefResponse::HeaderMap headers)
      : status_(status), mime_(std::move(mime)), body_(std::move(body)), headers_(std::move(headers)) {}

  bool Open(CefRefPtr<CefRequest> request, bool& handle_request, CefRefPtr<CefCallback> callback) override {
    handle_request = true;
    return true;
  }

  void GetResponseHeaders(CefRefPtr<CefResponse> response, int64_t& response_length, CefString& redirect_url) override {
    response->SetStatus(status_);
    response->SetStatusText(status_ == 200 ? "OK" : "Not Found");
    response->SetMimeType(mime_);
    response->SetCharset("utf-8");
    response->SetHeaderMap(headers_);
    response_length = static_cast<int64_t>(body_.size());
  }

  bool Read(void* data_out, int bytes_to_read, int& bytes_read, CefRefPtr<CefResourceReadCallback> callback) override {
    size_t left = body_.size() - offset_;
    if (left == 0 || bytes_to_read <= 0) {
      bytes_read = 0;
      return false;
    }
    size_t n = std::min(left, static_cast<size_t>(bytes_to_read));
    std::memcpy(data_out, body_.data() + offset_, n);
    offset_ += n;
    bytes_read = static_cast<int>(n);
    return true;
  }

  void Cancel() override {}

 private:
  int status_;
  std::string mime_;
  std::string body_;
  CefResponse::HeaderMap headers_;
  size_t offset_ = 0;
  IMPLEMENT_REFCOUNTING(MemoryResourceHandler);
};

}  // namespace

// ---- FrameMailbox -------------------------------------------------------------------------------------------------

void FrameMailbox::Start() {
  thread_ = std::thread(&FrameMailbox::Run, this);
}

void FrameMailbox::Stop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    stop_ = true;
  }
  cv_.notify_all();
  if (thread_.joinable()) {
    thread_.join();
  }
}

void FrameMailbox::Put(int generation, int width, int height, const void* bgra) {
  const auto* p = static_cast<const uint8_t*>(bgra);
  size_t n = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
  std::lock_guard<std::mutex> lock(mutex_);
  pending_.assign(p, p + n);  // keeps its capacity between frames
  generation_ = generation;
  width_ = width;
  height_ = height;
  has_frame_ = true;
  cv_.notify_one();
}

void FrameMailbox::Run() {
  std::vector<uint8_t> sending;
  while (true) {
    int generation;
    int width;
    int height;
    {
      std::unique_lock<std::mutex> lock(mutex_);
      cv_.wait(lock, [this] { return stop_ || has_frame_; });
      if (stop_) {
        return;
      }
      sending.swap(pending_);
      generation = generation_;
      width = width_;
      height = height_;
      has_frame_ = false;
    }
    if (!Out().SendFrame(generation, ++sequence_, MonotonicNanos(), width, height, sending.data()) && Out().closed()) {
      return;
    }
  }
}

// ---- BrowserHost: commands ------------------------------------------------------------------------------------------

BrowserHost::BrowserHost(GuardedProxy* proxy, FrameMailbox* frames) : proxy_(proxy), frames_(frames) {}

void BrowserHost::LoadVideo(const PlayerConfig& config) {
  CEF_REQUIRE_UI_THREAD();
  if (closing_ || !IsValidVideoId(config.video_id)) {
    return;
  }
  {
    std::lock_guard<std::mutex> lock(page_mutex_);
    config_ = config;
    nonce_ = RandomHex(16);
  }
  want_playing_ = config.autoplay;
  captions_ = config.captions;
  std::string url = std::string(kWrapperOrigin) + "/player?g=" + std::to_string(config.generation);
  if (browser_) {
    browser_->GetMainFrame()->LoadURL(url);
    return;
  }
  CefWindowInfo window_info;
  window_info.SetAsWindowless(kNullWindowHandle);
  CefBrowserSettings settings;
  settings.windowless_frame_rate = 30;
  settings.background_color = CefColorSetARGB(255, 0, 0, 0);
  settings.javascript_access_clipboard = STATE_DISABLED;
  settings.javascript_dom_paste = STATE_DISABLED;
  CefBrowserHost::CreateBrowser(window_info, this, url, settings, nullptr, nullptr);
}

void BrowserHost::Run(const std::string& script) {
  if (browser_) {
    browser_->GetMainFrame()->ExecuteJavaScript(script, std::string(kWrapperOrigin) + "/player", 0);
  }
}

void BrowserHost::Play() {
  want_playing_ = true;
  Run("window.otetv&&otetv.play()");
}

void BrowserHost::Pause() {
  want_playing_ = false;
  Run("window.otetv&&otetv.pause()");
}

void BrowserHost::Stop() {
  want_playing_ = false;
  Run("window.otetv&&otetv.stop()");
}

void BrowserHost::Seek(int64_t position_ms) {
  char buf[64];
  std::snprintf(buf, sizeof buf, "window.otetv&&otetv.seek(%.3f)", static_cast<double>(position_ms) / 1000.0);
  Run(buf);
}

void BrowserHost::SetVolume(int percent, bool muted) {
  volume_ = std::clamp(percent, 0, 100);
  muted_ = muted;
  Run("window.otetv&&otetv.volume(" + std::to_string(volume_) + "," + (muted_ ? "true" : "false") + ")");
}

void BrowserHost::SetCaptions(bool on) {
  captions_ = on;
  Run(std::string("window.otetv&&otetv.captions(") + (on ? "true" : "false") + ")");
}

void BrowserHost::SetVisible(bool visible) {
  visible_ = visible;
  if (browser_) {
    browser_->GetHost()->WasHidden(!visible);
  }
}

void BrowserHost::Resize(int width, int height) {
  width_ = std::clamp(width, kMinWidth, kMaxFrameWidth);
  height_ = std::clamp(height, kMinHeight, kMaxFrameHeight);
  if (browser_) {
    browser_->GetHost()->WasResized();
  }
}

void BrowserHost::ApplyDesiredState() {
  if (volume_ >= 0) {
    Run("window.otetv&&otetv.volume(" + std::to_string(volume_) + "," + (muted_ ? "true" : "false") + ")");
  }
  Run(std::string("window.otetv&&otetv.captions(") + (captions_ ? "true" : "false") + ")");
  if (want_playing_) {
    Run("window.otetv&&otetv.play()");
  }
}

void BrowserHost::Mouse(int kind, int x, int y, int button, int modifiers, int wheel) {
  if (!browser_) {
    return;
  }
  CefRefPtr<CefBrowserHost> host = browser_->GetHost();
  CefMouseEvent event;
  event.x = std::clamp(x, 0, width_ - 1);
  event.y = std::clamp(y, 0, height_ - 1);
  event.modifiers = CefModifiers(modifiers);
  // Minecraft numbers buttons left 0, right 1, middle 2.
  cef_mouse_button_type_t type = button == 1 ? MBT_RIGHT : button == 2 ? MBT_MIDDLE : MBT_LEFT;
  uint32_t held = type == MBT_RIGHT ? EVENTFLAG_RIGHT_MOUSE_BUTTON : type == MBT_MIDDLE ? EVENTFLAG_MIDDLE_MOUSE_BUTTON : EVENTFLAG_LEFT_MOUSE_BUTTON;
  switch (kind) {
    case 0:
      host->SendMouseMoveEvent(event, false);
      break;
    case 1:
      // A real, local click: this is what satisfies the browser's user-gesture rule (brief section 12.3).
      host->SetFocus(true);
      host->SendMouseMoveEvent(event, false);
      event.modifiers |= held;
      host->SendMouseClickEvent(event, type, false, 1);
      break;
    case 2:
      host->SendMouseClickEvent(event, type, true, 1);
      break;
    case 3:
      host->SendMouseWheelEvent(event, 0, wheel);
      break;
    case 4:
      host->SendMouseMoveEvent(event, true);
      break;
    default:
      break;
  }
}

void BrowserHost::Key(int kind, int key_code, int scan_code, int modifiers, int code_point) {
  if (!browser_) {
    return;
  }
  CefRefPtr<CefBrowserHost> host = browser_->GetHost();
  uint32_t flags = CefModifiers(modifiers);
  auto send_char = [&](char16_t unit) {
    CefKeyEvent c;
    c.type = KEYEVENT_CHAR;
    c.character = unit;
    c.unmodified_character = unit;
    c.windows_key_code = unit;
    c.modifiers = flags;
    host->SendKeyEvent(c);
  };
  if (kind == 2) {
    if (code_point >= 0x10000) {
      int v = code_point - 0x10000;
      send_char(static_cast<char16_t>(0xD800 + (v >> 10)));
      send_char(static_cast<char16_t>(0xDC00 + (v & 0x3FF)));
    } else if (code_point > 0) {
      send_char(static_cast<char16_t>(code_point));
    }
    return;
  }
  int vk = VirtualKey(key_code);
  if (vk == 0) {
    return;
  }
  CefKeyEvent event;
  event.type = kind == 0 ? KEYEVENT_RAWKEYDOWN : KEYEVENT_KEYUP;
  event.windows_key_code = vk;
  event.native_key_code = scan_code;
  event.modifiers = flags;
  host->SendKeyEvent(event);
  if (kind == 0 && vk == 0x0D) {
    send_char(u'\r');  // Enter activates focused controls through its character event
  }
}

void BrowserHost::Shutdown() {
  CEF_REQUIRE_UI_THREAD();
  if (closing_) {
    return;
  }
  closing_ = true;
  if (browser_) {
    browser_->GetHost()->CloseBrowser(true);
  } else {
    CefQuitMessageLoop();
  }
}

WrapperPage BrowserHost::CurrentPage() {
  std::lock_guard<std::mutex> lock(page_mutex_);
  return BuildWrapperPage(config_, nonce_);
}

// ---- BrowserHost: CEF callbacks ---------------------------------------------------------------------------------------

void BrowserHost::GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) {
  rect = CefRect(0, 0, width_, height_);
}

void BrowserHost::OnPaint(CefRefPtr<CefBrowser> browser, PaintElementType type, const RectList& dirty_rects, const void* buffer,
                          int width, int height) {
  if (type != PET_VIEW || !visible_ || width > kMaxFrameWidth || height > kMaxFrameHeight) {
    return;
  }
  int generation;
  {
    std::lock_guard<std::mutex> lock(page_mutex_);
    generation = config_.generation;
  }
  frames_->Put(generation, width, height, buffer);
}

bool BrowserHost::OnConsoleMessage(CefRefPtr<CefBrowser> browser, cef_log_severity_t level, const CefString& message,
                                   const CefString& source, int line) {
  std::string text = message.ToString();
  if (Debug()) {
    Log("console " + ParseUrl(source.ToString()).host + ":" + std::to_string(line) + " " + text.substr(0, 300));
  }
  if (text.rfind("OTETV|", 0) == 0 && source.ToString().rfind(std::string(kWrapperOrigin) + "/", 0) == 0) {
    // Only the first-party top-level wrapper speaks for the player; frames from other origins never match.
    HandleBridge(text);
  }
  return true;  // page console output stays out of the browser log
}

void BrowserHost::HandleBridge(const std::string& message) {
  std::vector<std::string> parts;
  size_t start = 0;
  while (parts.size() < 4) {
    size_t bar = message.find('|', start);
    if (bar == std::string::npos) {
      return;
    }
    parts.push_back(message.substr(start, bar - start));
    start = bar + 1;
  }
  parts.push_back(message.substr(start));
  int generation;
  {
    std::lock_guard<std::mutex> lock(page_mutex_);
    generation = config_.generation;
  }
  char* end = nullptr;
  long long page_generation = std::strtoll(parts[1].c_str(), &end, 10);
  if (end == parts[1].c_str() || page_generation != generation) {
    return;  // a stale page from an earlier video
  }
  const std::string& name = parts[2];
  long long value = std::strtoll(parts[3].c_str(), nullptr, 10);
  std::string detail = CleanDetail(parts[4]);
  if (name == "ready") {
    ApplyDesiredState();
    SendPlayerEvent(generation, kEvReady, 0, "");
  } else if (name == "state") {
    SendPlayerEvent(generation, kEvState, value, "");
  } else if (name == "error") {
    if (value < 0) {
      SendError(generation, kErrNetwork, "the official YouTube player could not be reached");
    } else {
      SendPlayerEvent(generation, kEvError, value, "");
    }
  } else if (name == "autoplay_blocked") {
    SendPlayerEvent(generation, kEvAutoplayBlocked, 0, "");
  } else if (name == "time") {
    SendPlayerEvent(generation, kEvTime, std::max(0LL, value), "");
  } else if (name == "duration") {
    SendPlayerEvent(generation, kEvDuration, std::max(0LL, value), "");
  } else if (name == "api_change") {
    SendPlayerEvent(generation, kEvApiChange, 0, detail);
  }
}

bool BrowserHost::OnBeforePopup(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, int popup_id, const CefString& target_url,
                                const CefString& target_frame_name, cef_window_open_disposition_t target_disposition, bool user_gesture,
                                const CefPopupFeatures& popup_features, CefWindowInfo& window_info, CefRefPtr<CefClient>& client,
                                CefBrowserSettings& settings, CefRefPtr<CefDictionaryValue>& extra_info, bool* no_javascript_access) {
  // No new windows or tabs; "Watch on YouTube" is offered by the mod's own Open-on-YouTube action instead.
  Log("blocked a popup to " + ParseUrl(target_url.ToString()).host);
  return true;
}

void BrowserHost::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  browser_ = browser;
  if (!visible_) {
    browser->GetHost()->WasHidden(true);
  }
}

void BrowserHost::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  browser_ = nullptr;
  if (closing_) {
    CefQuitMessageLoop();
  }
}

bool BrowserHost::OnBeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, CefRefPtr<CefRequest> request,
                                 bool user_gesture, bool is_redirect) {
  std::string url = request->GetURL().ToString();
  bool main = frame->IsMain();
  bool allowed = main ? AllowMainFrameNavigation(url) : AllowSubframeNavigation(url);
  if (!allowed) {
    Log(std::string("blocked a ") + (main ? "top-level" : "frame") + " navigation to " + ParseUrl(url).host);
  }
  return !allowed;
}

bool BrowserHost::OnOpenURLFromTab(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& target_url,
                                   cef_window_open_disposition_t target_disposition, bool user_gesture) {
  return true;  // never open another tab or window
}

CefRefPtr<CefResourceRequestHandler> BrowserHost::GetResourceRequestHandler(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                                                            CefRefPtr<CefRequest> request, bool is_navigation,
                                                                            bool is_download, const CefString& request_initiator,
                                                                            bool& disable_default_handling) {
  return this;
}

bool BrowserHost::GetAuthCredentials(CefRefPtr<CefBrowser> browser, const CefString& origin_url, bool is_proxy, const CefString& host,
                                     int port, const CefString& realm, const CefString& scheme, CefRefPtr<CefAuthCallback> callback) {
  return false;  // no credential prompts, for sites or proxies
}

void BrowserHost::OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser, TerminationStatus status, int error_code,
                                            const CefString& error_string) {
  int generation;
  {
    std::lock_guard<std::mutex> lock(page_mutex_);
    generation = config_.generation;
  }
  SendError(generation, kErrWorkerCrash, "the player page process stopped (status " + std::to_string(static_cast<int>(status)) + ")");
}

CefResourceRequestHandler::ReturnValue BrowserHost::OnBeforeResourceLoad(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                                                         CefRefPtr<CefRequest> request, CefRefPtr<CefCallback> callback) {
  std::string url = request->GetURL().ToString();
  if (Debug()) {
    UrlParts u = ParseUrl(url);
    Log("request " + u.scheme + "://" + u.host);
  }
  if (!AllowSubresource(url)) {
    UrlParts u = ParseUrl(url);
    Log("blocked a " + u.scheme + " request" + (u.host.empty() ? "" : " to " + u.host));
    return RV_CANCEL;
  }
  return RV_CONTINUE;
}

void BrowserHost::OnProtocolExecution(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, CefRefPtr<CefRequest> request,
                                      bool& allow_os_execution) {
  allow_os_execution = false;  // no custom protocol handlers
}

void BrowserHost::OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, int http_status_code) {
  if (Debug()) {
    Log("loaded " + std::string(frame->IsMain() ? "page " : "frame ") + ParseUrl(frame->GetURL().ToString()).host + " status " +
        std::to_string(http_status_code));
  }
}

void BrowserHost::OnLoadError(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, ErrorCode error_code, const CefString& error_text,
                              const CefString& failed_url) {
  if (Debug()) {
    Log("load error " + std::to_string(static_cast<int>(error_code)) + " " + error_text.ToString() + " for " + ParseUrl(failed_url.ToString()).host);
  }
  if (!frame->IsMain() || error_code == ERR_ABORTED) {
    return;
  }
  int generation;
  {
    std::lock_guard<std::mutex> lock(page_mutex_);
    generation = config_.generation;
  }
  SendError(generation, kErrNetwork, "the player page failed to load (" + error_text.ToString() + ")");
}

bool BrowserHost::OnRequestMediaAccessPermission(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& requesting_origin,
                                                 uint32_t requested_permissions, CefRefPtr<CefMediaAccessCallback> callback) {
  callback->Cancel();  // no camera or microphone
  return true;
}

bool BrowserHost::OnShowPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, const CefString& requesting_origin,
                                         uint32_t requested_permissions, CefRefPtr<CefPermissionPromptCallback> callback) {
  callback->Continue(CEF_PERMISSION_RESULT_DENY);  // notifications, clipboard reads, location and the rest
  return true;
}

bool BrowserHost::CanDownload(CefRefPtr<CefBrowser> browser, const CefString& url, const CefString& request_method) {
  return false;
}

bool BrowserHost::OnJSDialog(CefRefPtr<CefBrowser> browser, const CefString& origin_url, JSDialogType dialog_type,
                             const CefString& message_text, const CefString& default_prompt_text, CefRefPtr<CefJSDialogCallback> callback,
                             bool& suppress_message) {
  suppress_message = true;  // a modal dialog nobody can see would stall the page
  return false;
}

bool BrowserHost::OnBeforeUnloadDialog(CefRefPtr<CefBrowser> browser, const CefString& message_text, bool is_reload,
                                       CefRefPtr<CefJSDialogCallback> callback) {
  callback->Continue(true, CefString());
  return true;
}

// ---- WrapperSchemeFactory ---------------------------------------------------------------------------------------------

CefRefPtr<CefResourceHandler> WrapperSchemeFactory::Create(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                                           const CefString& scheme_name, CefRefPtr<CefRequest> request) {
  std::string url = request->GetURL().ToString();
  std::string prefix = std::string(kWrapperOrigin) + "/player";
  bool player = url.rfind(prefix, 0) == 0 && (url.size() == prefix.size() || url[prefix.size()] == '?');
  if (!player) {
    return new MemoryResourceHandler(404, "text/plain", "", {});
  }
  WrapperPage page = host_->CurrentPage();
  CefResponse::HeaderMap headers;
  headers.insert({"Content-Security-Policy", page.content_security_policy});
  headers.insert({"Cache-Control", "no-store"});
  headers.insert({"X-Content-Type-Options", "nosniff"});
  headers.insert({"Referrer-Policy", "strict-origin-when-cross-origin"});
  return new MemoryResourceHandler(200, "text/html", page.html, headers);
}

}  // namespace otetv
