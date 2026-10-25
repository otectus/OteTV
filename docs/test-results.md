# Test results

All results below were produced on 2026-10-25 by the commands shown, on this environment:

| Item | Value |
| --- | --- |
| OS | CachyOS Linux (rolling), kernel 7.3.0-4-cachyos, x86_64 |
| CPU / GPU | Intel Core i7-13700HX; NVIDIA GeForce RTX 4050 Laptop GPU, driver 615.78.08 |
| Desktop / audio | Hyprland 0.56.2 (Wayland, game via XWayland); PipeWire 1.6.10 |
| Java | OpenJDK 17.0.20.1 |
| Minecraft / Forge | 1.20.1 / 47.4.23 (development runs) |
| GStreamer | 1.28.8: gstreamer, gst-plugins-base, gst-plugins-bad, gst-libav (no gst-plugins-good) |
| Browser runtime | OteTV browser worker on CEF 156.0.14+g4fa4ab5 / Chromium 156.0.8078.48 |

## Automated suites

| Suite | Command | Result |
| --- | --- | --- |
| Core unit and protocol tests | `gradlew-quiet.sh . :core:test` | 149 tests, 0 failures |
| Forge GameTests (dedicated-server dist) | `gradlew-quiet.sh . runGameTestServer` | 27 of 27 passed (fresh world) |
| Resource check | `check_mod.py .` | 0 errors, 0 warnings (6 informational notes: item models for loop-registered TV items) |
| Full build from a fresh clone of commit 8771b44 | `git clone` then `gradlew-quiet.sh <clone> build` | BUILD SUCCESSFUL, 16 of 16 tasks executed, 149 tests passed; `build/libs/otetv-0.1.0.jar` (5,096,562 bytes); the media worker jar was byte-identical to the development build (SHA-256 `d9485ed4...c4e9`) |

GameTests cover: every model at every facing through real item placement; obstructed placement places nothing and
keeps the item; floor and wall support; chunk-boundary assemblies on both axes; exactly one drop from any part of every
model; creative breaks drop nothing; pistons refused; floor and wall support loss drop once; missing-part repair;
occupied-cell dismantling; orphan removal; copied controllers get a fresh identity; remote pairing by identity and
stale remotes after replacement; packet-handler range and dimension checks; roles, leases and stale revisions; public
controls (server and owner opt-in); redstone power, rising-edge toggle and the one-second channel debounce; commands on
loaded, part, empty and unloaded positions with range limits; every recipe crafting its result; dropped TVs keep only
name and preset; item NBT cannot inject identity or ownership; state save/load with corrupted-source and newer-schema
fallbacks; world-data tracking.

## Media worker (tools/worker-harness/WorkerHarness.java)

The harness launches the worker exactly as the client does and simulates a perfect audio device (real-time
consumption, credits and clock reports with a 300 ms output latency). "A/V offset" is the frame's PTS minus the audible
PTS when the frame arrives; the worker targets a 25 ms lead.

| Source | Result |
| --- | --- |
| Local Ogg Theora/Vorbis 320x180@25 | 25 fps continuous; A/V p05 22-23 ms, p50 23 ms, p95 24 ms before and after a seek to 4.0 s; first audio after the seek at exactly 4000 ms; 700 ms pause delivered 0 frames; decoded colours correct |
| Bundled demo, Ogg encode 640x360@30 | A/V p05 17 ms, p50 22 ms, p95 23 ms; frame number and burned-in timecode agree with PTS (frame 46 at 1.533 s) |
| HLS VOD over HTTP (LAN grant), MPEG-TS H.264/AAC | plays; a seek re-opens at the segment containing the target (segment 1 for 4.0 s); A/V p50 23 ms, p95 24 ms after the seek |
| Progressive HTTP without Range | stream mode, capability without SEEK; A/V p50 23 ms, p95 24 ms |
| Sidecar SRT over HTTP | cues delivered, markup stripped (`<i>Fixture</i>` -> `Fixture`), CAPTIONS capability set |
| Silent Theora | clock-paced at 25 fps; seek, pause and end of stream correct (EOS at 5960 ms) |
| Ogg Opus, audio only | seek lands at 4000 ms; one EOS, sent only after all audio was delivered |

Error paths, each reported as one typed error:

| Input | Reported |
| --- | --- |
| Bundled WebM without gst-plugins-good | `UNSUPPORTED_CONTAINER: no demuxer for video/webm` |
| MP4 without gst-plugins-good | `UNSUPPORTED_CONTAINER: no demuxer for video/quicktime` |
| Ogg header followed by random bytes | `MEDIA_MALFORMED: No valid frames decoded before end of stream` |
| `http://127.0.0.1/...` without a LAN grant | `POLICY_BLOCKED_HOST: plain http requires a LAN grant for 127.0.0.1` |
| `https://169.254.169.254/` | `POLICY_BLOCKED_HOST: CLOUD_METADATA address for 169.254.169.254` |
| LAN grant for a different port | `POLICY_BLOCKED_HOST` (refused) |
| Encrypted HLS (AES-128) | `HLS_ENCRYPTED: ENCRYPTED: AES-128` (once; a failed session is not re-opened by seeking) |
| HTML page | `NOT_MEDIA: server returned a web page (text/html)` |
| HTTP 404 | `NETWORK: HTTP 404` |

### 30-minute drift run

`HARNESS_NO_SEEK=1 HARNESS_REPORT_EVERY=300 java ... WorkerHarness.java <worker> FILE long.ogv 1795` on an original
30-minute 320x180@25 Theora/Vorbis file with a beep every second:

| Window | Frames | A/V p05 | A/V p50 | A/V p95 |
| --- | --- | --- | --- | --- |
| 0-300 s | 7,455 (includes start-up) | 23 ms | 24 ms | 24 ms |
| 300-600 s | 7,500 | 23 ms | 24 ms | 24 ms |
| 600-900 s | 7,500 | 23 ms | 24 ms | 24 ms |
| 900-1200 s | 7,500 | 23 ms | 24 ms | 24 ms |
| 1200-1500 s | 7,500 | 23 ms | 24 ms | 24 ms |
| 1500-1795 s | 7,375 | 23 ms | 24 ms | 24 ms |

44,830 frames in 1,795 s (25.0 fps, none dropped after start-up), last frame PTS 1,793,160 ms, worker exit code 0.
The offset distribution did not move between the first and last window: no cumulative drift between decoded video and
consumed audio over 30 minutes.

## Client smoke test (tools/run_smoke_test.sh)

A dev client creates a flat world, places a plasma TV through the item, tunes channel 1, joins from the client and
checks real playback; then pauses, seeks and resumes through the client's own action packets and opens every TV
screen. Shared-timeline error is the observed position minus the server timeline's target at that moment.

| Check | Result (latest run; range over runs in brackets) |
| --- | --- |
| Media runtime | AVAILABLE, GStreamer 1.28.8; demo encoding chosen: `welcome.ogv` (no Matroska demuxer) |
| Picture | 640x360, 190 frames in 6 s, 0 dropped; correct in-world picture with captions (`otetv_smoke_playing.png`) |
| Sound | audible through Minecraft's OpenAL output |
| Shared-timeline error while playing | mean 9 ms, p95 19 ms, max 41 ms (p95 19-53 ms) |
| After a seek to 0:30 | resumed at 30.3 s (30.1-30.3 s); error mean 40 ms, p95 48 ms, max 67 ms (p95 48-67 ms) |
| Pause | position moved 0 ms in 1.5 s, no new frames, 0 ms from the shared position; captions match the frame |
| Captions | shown before and after the seek |
| Screens | menu, settings, sources, remote, diagnostics and enlarged viewing rendered (screenshots inspected) |

Defects found and fixed by these runs (before this release): the screen layers were drawn behind a black backing quad
(no picture in the world), relative worker paths made the runtime look missing in development, and the paused
position read 0:00 after a seek while paused.

## YouTube

### Browser worker harness (tools/worker-harness/BrowserHarness.java, video M7lc1UVf-VE, muted)

| Check | Result |
| --- | --- |
| Handshake | `HELLO_ACK` protocol 1, runtime CEF 156.0.14+g4fa4ab5+chromium-156.0.8078.48, sandboxed |
| Playback | ready, duration 1,344,000 ms, playing from 0 s; 1308 frames of 1280x720 BGRA in 45 s (about 29 fps) |
| Controls | pause at 6.1 s (state paused), seek to 30 s while paused, resume (buffering, then playing at 30.0 s) |
| Pointer input | a forwarded click on the playing player paused it 251 ms later |
| Shutdown | clean exit (code 0); no child-process aborts |

### Sandbox observation (`/proc/<pid>/status`, `/proc/<pid>/ns` during playback)

| Process | Seccomp | NoNewPrivs | Namespaces |
| --- | --- | --- | --- |
| Browser | 0 | 0 | host |
| Renderers (3) | 2 (filter) | 1 | own user, PID and network namespaces |
| GPU process | 2 | 1 | host |
| Storage service | 2 | 1 | own user, PID and network namespaces |
| Network service, audio service | 0 | 1 | host (Chromium's standard Linux layout) |

### In-world YouTube (tools/run_smoke_test.sh youtube)

The bundle `otetv-browser-linux-x86_64-0.1.0+cef156.0.14.zip` was installed through the client's installer (size and
SHA-256 verified, extracted, activated); the TV's source was set to the video; the client asked for consent for
`youtube.com`, which the test granted; the viewer stood three blocks from the screen.

| Check | Result |
| --- | --- |
| States | LOADING -> BUFFERING -> PLAYING within 2.0 s of the shared start (started 1.9 s behind the shared target; YouTube is best-effort and not chased) |
| Playback | still playing after 15 s, 482 frames (about 32 fps), YouTube's own captions visible |
| Screenshots | `otetv_smoke_youtube_world.png` (in-world plasma), `otetv_smoke_youtube_viewing.png` (enlarged viewing) |

## Not verified in this release

- Windows (any part), a second Linux distribution, Intel or AMD graphics, macOS.
- Two players on a dedicated server or LAN, late joining with real clients, network disconnects.
- Lip sync measured with a camera and microphone, device hot-plug, two-hour sessions, 100 place/break cycles under a
  profiler.
- Live HLS against a real live server, shared local assets and private files in a running client, YouTube ads, an
  embedding-disabled video, autoplay with sound (all runs were muted), keyboard input into the player.
- Coexistence with Oculus/Embeddium or MCEF/WebDisplays.
- A `runServer` launch (needs the EULA accepted by the server owner); the dedicated-server dist was exercised by the
  GameTest server instead.
