// The windowless CEF browser that hosts the wrapper page: off-screen paint into VIDEO_FRAMEs, the typed player bridge,
// and the browser-side policy (navigation, popups, downloads, permissions, schemes, proxy credentials).
#ifndef OTETV_BROWSER_WORKER_BROWSER_HOST_H_
#define OTETV_BROWSER_WORKER_BROWSER_HOST_H_

#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "include/cef_client.h"
#include "include/cef_scheme.h"
#include "proxy.h"
#include "wrapper_page.h"

namespace otetv {

// Latest-frame mailbox: OnPaint copies into it on the UI thread and a writer thread sends whatever is newest, so a
// slow client never stalls Chromium's UI thread and stale frames are dropped rather than queued.
class FrameMailbox {
 public:
  void Start();
  void Stop();
  void Put(int generation, int width, int height, const void* bgra);

 private:
  void Run();
  std::mutex mutex_;
  std::condition_variable cv_;
  std::vector<uint8_t> pending_;
  int generation_ = 0;
  int width_ = 0;
  int height_ = 0;
  int sequence_ = 0;
  bool has_frame_ = false;
  bool stop_ = false;
  std::thread thread_;
};

class BrowserHost : public CefClient,
                    public CefRenderHandler,
                    public CefDisplayHandler,
                    public CefLifeSpanHandler,
                    public CefRequestHandler,
                    public CefResourceRequestHandler,
                    public CefLoadHandler,
                    public CefPermissionHandler,
                    public CefDownloadHandler,
                    public CefJSDialogHandler {
 public:
  BrowserHost(GuardedProxy* proxy, FrameMailbox* frames);

  // Commands; UI thread only.
  void LoadVideo(const PlayerConfig& config);
  void Play();
  void Pause();
  void Stop();
  void Seek(int64_t position_ms);
  void SetVolume(int percent, bool muted);
  void SetVisible(bool visible);
  void SetCaptions(bool on);
  void Resize(int width, int height);
  void Mouse(int kind, int x, int y, int button, int modifiers, int wheel);
  void Key(int kind, int key_code, int scan_code, int modifiers, int code_point);
  void Shutdown();

  // The wrapper page for the current generation, for the scheme handler (IO thread).
  WrapperPage CurrentPage();

  // CefClient
  CefRefPtr<CefRenderHandler> GetRenderHandler() override { return this; }
  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefRequestHandler> GetRequestHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefPermissionHandler> GetPermissionHandler() override { return this; }
  CefRefPtr<CefDownloadHandler> GetDownloadHandler() override { return this; }
  CefRefPtr<CefJSDialogHandler> GetJSDialogHandler() override { return this; }

  // CefRenderHandler
  void GetViewRect(CefRefPtr<CefBrowser> browser, CefRect& rect) override;
  void OnPaint(CefRefPtr<CefBrowser> browser, PaintElementType type, const RectList& dirty_rects, const void* buffer, int width,
               int height) override;

  // CefDisplayHandler
  bool OnConsoleMessage(CefRefPtr<CefBrowser> browser, cef_log_severity_t level, const CefString& message, const CefString& source,
                        int line) override;

  // CefLifeSpanHandler
  bool OnBeforePopup(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, int popup_id, const CefString& target_url,
                     const CefString& target_frame_name, cef_window_open_disposition_t target_disposition, bool user_gesture,
                     const CefPopupFeatures& popup_features, CefWindowInfo& window_info, CefRefPtr<CefClient>& client,
                     CefBrowserSettings& settings, CefRefPtr<CefDictionaryValue>& extra_info, bool* no_javascript_access) override;
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;

  // CefRequestHandler
  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, CefRefPtr<CefRequest> request, bool user_gesture,
                      bool is_redirect) override;
  bool OnOpenURLFromTab(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& target_url,
                        cef_window_open_disposition_t target_disposition, bool user_gesture) override;
  CefRefPtr<CefResourceRequestHandler> GetResourceRequestHandler(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                                                                 CefRefPtr<CefRequest> request, bool is_navigation, bool is_download,
                                                                 const CefString& request_initiator,
                                                                 bool& disable_default_handling) override;
  bool GetAuthCredentials(CefRefPtr<CefBrowser> browser, const CefString& origin_url, bool is_proxy, const CefString& host, int port,
                          const CefString& realm, const CefString& scheme, CefRefPtr<CefAuthCallback> callback) override;
  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser, TerminationStatus status, int error_code,
                                 const CefString& error_string) override;

  // CefResourceRequestHandler
  ReturnValue OnBeforeResourceLoad(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, CefRefPtr<CefRequest> request,
                                   CefRefPtr<CefCallback> callback) override;
  void OnProtocolExecution(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, CefRefPtr<CefRequest> request,
                           bool& allow_os_execution) override;

  // CefLoadHandler
  void OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, int http_status_code) override;
  void OnLoadError(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, ErrorCode error_code, const CefString& error_text,
                   const CefString& failed_url) override;

  // CefPermissionHandler
  bool OnRequestMediaAccessPermission(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& requesting_origin,
                                      uint32_t requested_permissions, CefRefPtr<CefMediaAccessCallback> callback) override;
  bool OnShowPermissionPrompt(CefRefPtr<CefBrowser> browser, uint64_t prompt_id, const CefString& requesting_origin,
                              uint32_t requested_permissions, CefRefPtr<CefPermissionPromptCallback> callback) override;

  // CefDownloadHandler
  bool CanDownload(CefRefPtr<CefBrowser> browser, const CefString& url, const CefString& request_method) override;

  // CefJSDialogHandler
  bool OnJSDialog(CefRefPtr<CefBrowser> browser, const CefString& origin_url, JSDialogType dialog_type, const CefString& message_text,
                  const CefString& default_prompt_text, CefRefPtr<CefJSDialogCallback> callback, bool& suppress_message) override;
  bool OnBeforeUnloadDialog(CefRefPtr<CefBrowser> browser, const CefString& message_text, bool is_reload,
                            CefRefPtr<CefJSDialogCallback> callback) override;

 private:
  void Run(const std::string& script);
  void ApplyDesiredState();
  void HandleBridge(const std::string& message);

  GuardedProxy* proxy_;
  FrameMailbox* frames_;
  CefRefPtr<CefBrowser> browser_;
  bool closing_ = false;
  bool visible_ = true;
  int width_ = 1280;
  int height_ = 720;
  // Desired player state, replayed when the page reports ready (commands may arrive before it exists).
  bool want_playing_ = false;
  int volume_ = -1;
  bool muted_ = false;
  bool captions_ = false;
  std::mutex page_mutex_;
  PlayerConfig config_;
  std::string nonce_;

  IMPLEMENT_REFCOUNTING(BrowserHost);
};

// Serves https://com.otectus.otetv/ from memory; nothing for that origin ever reaches the network.
class WrapperSchemeFactory : public CefSchemeHandlerFactory {
 public:
  explicit WrapperSchemeFactory(CefRefPtr<BrowserHost> host) : host_(host) {}
  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame, const CefString& scheme_name,
                                       CefRefPtr<CefRequest> request) override;

 private:
  CefRefPtr<BrowserHost> host_;
  IMPLEMENT_REFCOUNTING(WrapperSchemeFactory);
};

}  // namespace otetv

#endif  // OTETV_BROWSER_WORKER_BROWSER_HOST_H_
