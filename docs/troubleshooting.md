# Troubleshooting

Start with the TV menu's **Diagnostics** button (or `/otetv diagnostics`). It shows the runtime versions, the codec
matrix, the session state, the last worker log lines and the redacted error detail; **Copy redacted details** is what
to paste into a bug report.

## The TV says the media runtime is missing

- Linux: install GStreamer from your distribution (`gstreamer` and `gst-plugins-base` at least). For WebM, MP4 and
  Matroska also install `gst-plugins-good`; for H.264 and AAC install `gst-libav` (or your distribution's equivalent).
  Then press **Check again** in the runtime screen.
- Windows: install the official GStreamer 1.x MSVC x86_64 runtime. OteTV finds it through
  `GSTREAMER_1_0_ROOT_MSVC_X86_64` or the default install path.
- The diagnostics line `media runtime: MISSING` now ends with the worker's own explanation, for example
  `Unable to access jarfile` (a broken mod install) or a GStreamer loading error.

## "Container unsupported" or "no decoder" for a file that plays elsewhere

Diagnostics names the missing element, for example `no demuxer for video/webm` (install `gst-plugins-good`) or
`no decoder for video/x-h264` (install `gst-libav`). HEVC, AV1 and HDR are deliberately unsupported. The bundled demo
falls back to its Ogg encode automatically when no Matroska demuxer exists.

## No sound

- Check Minecraft's Master and **Jukebox/Note Blocks** (Records) sliders: TV audio follows them. Check OteTV's
  personal volume, Mute for me and `globalMute`.
- Direct media plays through Minecraft's OpenAL device. On PipeWire systems make sure the PipeWire Pulse or ALSA
  bridge is installed; if Minecraft itself is silent, OteTV will be too. After switching audio devices, re-open the
  TV or use Retry: playback resumes from the shared position.
- YouTube audio comes from the official player inside the browser worker, so it uses the system's default output
  rather than Minecraft's device, and it is never positional.
- If nothing can be heard (both sliders at zero, no free streaming channel), OteTV advances the picture on a silent
  clock instead of freezing.

## Wayland and focus

Minecraft 1.20.1 runs through XWayland on most Wayland compositors. OteTV's workers never open windows (the browser
worker uses headless rendering), so they behave the same on Wayland and X11. YouTube pauses while the game window is
unfocused; that is policy, not a bug. If your compositor reports focus oddly, click into the game and press the TV's
resume action. Tiling window managers may size the game window unexpectedly; that does not affect OteTV.

## YouTube

| What you see | Meaning | What to do |
| --- | --- | --- |
| "YouTube could not identify this player" (error 153) | The embed request reached YouTube without the application identity | Retry; if it persists, report it with diagnostics. OteTV never works around this with other playback URLs |
| "This video cannot play in embedded players" (101/150) | The uploader disabled embedding | Open on YouTube |
| Video unavailable (100), invalid (2), HTML5 error (5) | Removed, private, malformed, or a playback failure | Open on YouTube or pick another video |
| "Press play" / waiting for a gesture | The browser refused autoplay with sound | Use the TV's resume action or click the picture: a real click is forwarded to the player |
| "Too small - open enlarged viewing" | The TV covers less than 320x180 pixels on screen | Move closer, or choose Enlarged viewing in the TV menu (a key can be bound to it) |
| Paused when you look away | Hidden, occluded, unfocused or behind another screen | Resume when you look back; only your own player paused |
| Browser runtime not installed | The optional OteTV browser runtime is not present | Install it from the runtime screen (see the admin guide), or Open on YouTube |
| Worker stopped right after starting (Linux) | Chromium found no usable sandbox | Enable unprivileged user namespaces (below) |

### Linux: the browser needs unprivileged user namespaces

OteTV never runs Chromium without its sandbox. Check `cat /proc/sys/user/max_user_namespaces` (must be above 0) and,
where it exists, `cat /proc/sys/kernel/unprivileged_userns_clone` (must be 1). Ubuntu 24.04 and later additionally
restrict them through AppArmor (`kernel.apparmor_restrict_unprivileged_userns`); allowing the OteTV browser worker
there needs an AppArmor profile for its executable, which is your system administrator's decision. OteTV does not ship
or require a setuid helper.

## Network sources

- "Blocked: plain http requires a LAN grant" or a private-address message: the URL points at your network. Add a LAN
  grant for exactly that host and port in OteTV's privacy screen if you trust it.
- "Server returned a web page": the link is a page, not a media file or playlist.
- "Encrypted HLS is not supported": key-bearing playlists are refused by design.
- The server may also refuse a host (`sourcePolicy`, `approvedHosts`); the message then comes from the server.

## Local files

A shared local asset only plays for players who imported a file with the same content hash. If the file moved or
changed, choose **Select matching file** in the TV menu.

## The TV or a remote does nothing

- Remotes work within 32 blocks (server setting) in the same dimension, on a loaded TV.
- A broken and replaced TV has a new identity: pair the remote again (sneak-use on the TV).
- Your role may not allow the action, or another player holds the 30-second control lease.
- A TV saved by a newer OteTV version is read-only until the mod is updated.

## Performance

Lower `qualityPreset`, keep `maxActivePlayers` at 1, or enable `safeMode`. Only two sessions (one YouTube) run at
once; further TVs show a standby card. TVs out of sight stop decoding video while keeping their sound within range.

## Logs

The game log contains OteTV lines prefixed `co.ot.ot.OteTV`. Worker output is written there only with `debugLogging`
on. Developers can set `OTETV_WORKER_DEBUG=1` (media worker scheduling trace) or `OTETV_BROWSER_DEBUG=1` (browser
console, request hosts, Chromium log kept under `<game dir>/otetv/runtime/browser-cache/`).
