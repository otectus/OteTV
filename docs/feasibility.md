# Feasibility gates

The brief (section 4) puts five gates in front of the full feature set. This records what each gate required, what was
built, and the evidence. Measurements and environments are in [test-results.md](test-results.md).

| Gate | Requirement | Outcome on Linux (tested) | Windows |
| --- | --- | --- | --- |
| A | Dedicated server and client side isolation; no natives or browser on servers | Passed | Same code; not run |
| B | Direct media decoded out of process, bounded, cancellable, safe network ingestion | Passed | Code supports the official GStreamer runtime; not run |
| C | Audio clock strategy, lip sync, no unbounded queues, no cumulative drift | Passed | Not run |
| D | Current browser, off-screen pixels, no helper window, supported sandbox | Passed with a native CEF worker | Not built (needs a Windows sandbox port) |
| E | Real official YouTube playback | Passed (embedding-enabled public video) | Blocked until Gate D exists on Windows |

## Gate A: side isolation

- `checkSideBoundaries` fails the build if common or server code references client, rendering, audio, native or
  browser classes; `ClientHooks` is the only bridge and is reached through `DistExecutor`.
- All 27 GameTests ran on the GameTest server, which is a `DEDICATED_SERVER` dist with no display: every block, item,
  session, network handler, command and data path loaded and ran without a client class.
- A full `runServer` launch was not performed here because it requires accepting the Minecraft EULA in `run-server/`,
  which is the server owner's decision; the GameTest server exercises the same dedicated-server code paths.

## Gate B: direct media

- The media worker is a separate JVM (`-Xmx512m`, serial GC, headless) launched with argument arrays, its jar verified
  by SHA-256 before every launch, exiting with its parent.
- Every byte goes through OteTV's fetcher: `appsrc` with OteTV byte sources; adaptive demuxers and GStreamer network
  sources are refused by `autoplug-select` and demoted to rank NONE, so no native element can fetch a URL itself.
- The guarded HTTP client pins the validated address, verifies TLS host names with SNI, re-validates every redirect,
  bounds sizes and times, refuses HTML and non-public destinations unless the player granted that LAN host, and
  supports ranged and non-ranged progressive media and unencrypted HLS (encrypted HLS is refused precisely).
- Tested end to end with the harness: local Ogg Theora/Vorbis, local Ogg Opus (audio only), silent Theora, HLS VOD
  over HTTP with a LAN grant (segment re-open on seek), progressive HTTP without Range (no seek capability), and the
  error paths (missing demuxer, malformed file, plain HTTP without a grant, cloud-metadata address, HTML page, HTTP 404,
  wrong-port grant, encrypted HLS).
- Codec coverage depends on the installed GStreamer plugins. The test machine has base, bad and libav but not
  gst-plugins-good, so WebM and MP4 report "container unsupported" with the missing demuxer named in diagnostics; the
  bundled demo therefore also ships as an Ogg Theora/Vorbis encode, chosen automatically.

## Gate C: audio clock and lip sync

See [ADR 0001](adr/0001-worker-audio-clock.md). Worker harness: A/V offset at frame arrival p05 17-23 ms, p50 22-24 ms,
p95 23-24 ms across play, seek, pause and resume; a 30-minute run held p05 23 / p50 24 / p95 24 ms in every five-minute
window at a steady 25 fps. In world, one Minecraft client played the demo through OpenAL with zero dropped frames, the shared-timeline
error stayed under the 150 ms correction threshold (p95 19-67 ms across runs), pause held position exactly, and after
a seek to 0:30 playback resumed at 30.1-30.3 s.

Not measured here: lip sync with a camera and microphone against a physical display, device hot-plug, and two-hour
sessions (recorded as open items in test-results.md).

## Gate D: browser off-screen rendering and sandbox

JCEF hardcodes `no_sandbox`, so the brief's fallback applies: [ADR 0003](adr/0003-native-cef-browser-worker.md).
The native worker on CEF 156.0.14 / Chromium 156.0.8078.48 paints 1280x720 BGRA frames from a windowless,
headless-Ozone browser (no window, no X11/Wayland connection), and its renderers were observed with seccomp-bpf
filtering and private user, PID and network namespaces (`/proc/<pid>/status`, `/proc/<pid>/ns`). Forwarded pointer
input reaches the page: a click on the playing official player paused it 251 ms later (harness click test). Volume and
mute use the player's documented calls; the automated runs were muted, so loudness was not judged. Keyboard and
Unicode input are implemented and mapped from GLFW codes but were not exercised by an automated test. Popups are
refused by design.

## Gate E: real official YouTube playback

The official IFrame Player API inside the first-party wrapper ([ADR 0002](adr/0002-youtube-wrapper-origin-and-identity.md))
played Google's IFrame API sample video (`M7lc1UVf-VE`): ready, duration, play, pause, seek while paused, resume and
YouTube's own captions, about 29-31 frames per second, both in the harness and on an in-world plasma TV (installed
through the client's verified installer, consent granted, focus and projected-size policy satisfied). No error 153.
Ads, age-restricted, members-only and embedding-disabled videos were not part of the test.

## What remains blocked

- Windows browser worker (Gate D/E on Windows): needs the CEF Windows sandbox bootstrap, a Winsock proxy and a Windows
  build host. Until then YouTube on Windows offers Open on YouTube instead of in-world playback.
- Hosted runtime bundles: the manifest format, installer and packaging exist, but publishing bundles requires the
  maintainer to choose and operate a download host (the brief forbids publishing without authorization). The
  built-in manifest is empty; players can install a bundle from a file with a local manifest.
- Subscription services (Netflix, Hulu, Crunchyroll, Prime Video): external browser only, by design; see
  [provider-support.md](provider-support.md).
