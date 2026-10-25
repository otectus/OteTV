// The first-party wrapper around the official YouTube IFrame Player API (brief section 12.2). It is served by the
// worker itself at https://com.otectus.otetv/player, so the player's origin and referrer identify the application.
// Only validated values reach it: an 11-character video id, integers and booleans; no user text is interpolated.
#ifndef OTETV_BROWSER_WORKER_WRAPPER_PAGE_H_
#define OTETV_BROWSER_WORKER_WRAPPER_PAGE_H_

#include <string>

namespace otetv {

struct PlayerConfig {
  int generation = 0;
  std::string video_id;  // [A-Za-z0-9_-]{11}, checked by IsValidVideoId before use
  int start_seconds = 0;
  bool autoplay = false;
  bool captions = false;
};

bool IsValidVideoId(const std::string& id);

// Page HTML plus the Content-Security-Policy header value that goes with it (per-page script nonce).
struct WrapperPage {
  std::string html;
  std::string content_security_policy;
};

WrapperPage BuildWrapperPage(const PlayerConfig& config, const std::string& nonce);

}  // namespace otetv

#endif  // OTETV_BROWSER_WORKER_WRAPPER_PAGE_H_
