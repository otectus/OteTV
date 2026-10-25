#include "wrapper_page.h"

#include <cctype>

namespace otetv {
namespace {

// The bridge is console output from this top-level page only: the worker accepts "OTETV|..." lines whose source is
// the wrapper origin, so cross-origin frames (the player itself, ads) can never speak for it (brief section 12.2).
// Commands come back as calls to window.otetv with fixed names and numeric arguments.
const char* kScript = R"JS(
(function () {
  'use strict';
  var player = null, ready = false, lastDuration = -1, wantPlaying = cfg.autoplay;
  var pending = {volume: -1, muted: false, captions: cfg.cc};
  function send(event, value, detail) {
    console.log('OTETV|' + cfg.gen + '|' + event + '|' + Math.round(value || 0) + '|' + (detail || ''));
  }
  function tick() {
    if (!player || !ready) { return; }
    send('time', (player.getCurrentTime ? player.getCurrentTime() : 0) * 1000);
    var d = player.getDuration ? player.getDuration() : 0;
    if (d > 0 && d !== lastDuration) { lastDuration = d; send('duration', d * 1000); }
  }
  function applyPending() {
    if (pending.volume >= 0) {
      player.setVolume(pending.volume);
      if (pending.muted) { player.mute(); } else { player.unMute(); }
    }
    captions(pending.captions);
  }
  function captions(on) {
    // The player's own caption module; its CC button stays available to the viewer either way.
    try { if (on) { player.loadModule('captions'); } else { player.unloadModule('captions'); } } catch (e) { }
  }
  window.onYouTubeIframeAPIReady = function () {
    player = new YT.Player('player', {
      width: '100%', height: '100%', videoId: cfg.id,
      playerVars: {start: cfg.start, autoplay: cfg.autoplay ? 1 : 0, cc_load_policy: cfg.cc ? 1 : 0, playsinline: 1, rel: 0,
                   enablejsapi: 1, origin: 'https://com.otectus.otetv', widget_referrer: 'https://com.otectus.otetv'},
      events: {
        onReady: function () {
          ready = true;
          send('ready', 0);
          applyPending();
          if (wantPlaying) {
            player.playVideo();
            // Some browsers refuse silently: report it so the viewer is asked for a real click.
            setTimeout(function () {
              var s = player.getPlayerState ? player.getPlayerState() : -1;
              if (wantPlaying && (s === -1 || s === 5)) { send('autoplay_blocked', 0); }
            }, 4000);
          }
          tick();
        },
        onStateChange: function (e) { send('state', e.data); tick(); },
        onError: function (e) { send('error', e.data); },
        onApiChange: function () { send('api_change', 0); },
        onAutoplayBlocked: function () { send('autoplay_blocked', 0); }
      }
    });
    setInterval(tick, 500);
  };
  window.otetv = {
    play: function () { wantPlaying = true; if (ready) { player.playVideo(); } },
    pause: function () { wantPlaying = false; if (ready) { player.pauseVideo(); } },
    stop: function () { wantPlaying = false; if (ready) { player.stopVideo(); } },
    seek: function (seconds) { if (ready) { player.seekTo(seconds, true); } },
    volume: function (percent, muted) { pending.volume = percent; pending.muted = muted; if (ready) { applyPending(); } },
    captions: function (on) { pending.captions = on; if (ready) { captions(on); } }
  };
  var api = document.createElement('script');
  api.src = 'https://www.youtube.com/iframe_api';
  api.onerror = function () { send('error', -1, 'api_unreachable'); };
  document.head.appendChild(api);
})();
)JS";

}  // namespace

bool IsValidVideoId(const std::string& id) {
  if (id.size() != 11) {
    return false;
  }
  for (char c : id) {
    if (!std::isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '-') {
      return false;
    }
  }
  return true;
}

WrapperPage BuildWrapperPage(const PlayerConfig& config, const std::string& nonce) {
  // Only checked values are written into the page.
  std::string id = IsValidVideoId(config.video_id) ? config.video_id : "";
  int start = config.start_seconds < 0 ? 0 : config.start_seconds;
  std::string cfg = "var cfg = {gen: " + std::to_string(config.generation) + ", id: '" + id + "', start: " + std::to_string(start) +
                    ", autoplay: " + (config.autoplay ? "true" : "false") + ", cc: " + (config.captions ? "true" : "false") + "};";
  WrapperPage page;
  page.html =
      "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>OteTV player</title>"
      "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
      "<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}"
      "#player{position:absolute;left:0;top:0;width:100%;height:100%;border:0}</style></head>"
      "<body><div id=\"player\"></div><script nonce=\"" + nonce + "\">" + cfg + kScript + "</script></body></html>";
  page.content_security_policy =
      "default-src 'none'; script-src 'nonce-" + nonce + "' https://www.youtube.com https://s.ytimg.com; "
      "frame-src https://www.youtube.com https://www.youtube-nocookie.com; style-src 'unsafe-inline'; img-src https: data:; "
      "base-uri 'none'; form-action 'none'";
  return page;
}

}  // namespace otetv
