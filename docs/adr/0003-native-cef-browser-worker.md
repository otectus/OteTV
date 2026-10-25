# ADR 0003: A native CEF worker instead of JCEF, with the sandbox always on

Status: accepted (implemented in 0.1.0, Linux; Windows not yet built)

## Context

Gate D (brief section 4) requires a current Chromium runtime rendering off-screen with no helper window, supported
sandbox operation on Windows and Linux, and isolation from Minecraft's LWJGL and classpath. The brief prefers a JCEF
worker but allows "a minimal native CEF worker using the same IPC contract" if the Java binding cannot provide the
sandbox, and forbids disabling the sandbox to keep a preferred binding.

JCEF's native launcher hardcodes `no_sandbox`, so a JCEF worker cannot run sandboxed. MCEF and similar mods inherit
the same constraint and ship older Chromium builds.

## Decision

Build `browser-worker/`, a small C++ host on the CEF binary distribution (pinned in `gradle.properties`: CEF
`156.0.14+g4fa4ab5`, Chromium `156.0.8078.48`):

- One executable is both the browser process and every Chromium sub-process. Sub-processes are dispatched to
  `CefExecuteProcess` before anything else and leave through `_exit` in a function without a stack protector, because
  zygote-forked children get a fresh stack canary.
- `CefSettings.no_sandbox` is false and no `--no-sandbox` switch exists anywhere (the debug extra-switch hook refuses
  it). On Linux, Chromium uses its unprivileged namespace sandbox; no setuid helper is shipped or required. Without
  unprivileged user namespaces Chromium refuses to start ("No usable sandbox"), the worker exits, and the TV shows the
  failure with Retry, Diagnostics and Open on YouTube: a secure unsupported state rather than an unsandboxed one.
  (That path follows from Chromium's documented behaviour; the test machine has user namespaces, so it was not run.)
- Windowless rendering with headless Ozone (`--ozone-platform=headless`, software compositing): no X11 or Wayland
  connection and no window. Painted BGRA frames go through a latest-frame mailbox to a writer thread.
- It speaks the existing worker IPC (`HELLO`/`HELLO_ACK` with `FLAG_SANDBOXED`, `LOAD_VIDEO`, playback commands,
  `MOUSE`/`KEY`, `SET_VOLUME`, `SET_VISIBLE`, `RESIZE`, `PING`; `VIDEO_FRAME`, `PLAYER_EVENT`, `ERROR`, `PONG`), so
  the Java side (`BrowserBackend`) did not change shape.
- Network traffic is forced through an in-process guarded CONNECT proxy (ADR-level detail in
  privacy-and-security.md).
- The runtime ships as a separately installed, checksummed bundle (`runtime-packaging/package_browser_worker.py`),
  never inside the mod jar.

## Consequences

- Verified on Linux (2026-10-25): renderers run with seccomp-bpf (`Seccomp: 2`, `NoNewPrivs: 1`) in their own user,
  PID and network namespaces; the official player plays in-world.
- Windows needs its own port: the sandbox bootstrap (`cef_sandbox` with the sandbox information passed to
  `CefExecuteProcess`/`CefInitialize`), a Winsock version of the proxy and Windows stdio handling. Until that is built
  and tested on Windows, YouTube on Windows reports the browser runtime as unavailable and offers Open on YouTube.
- The bundle is large (139 MiB compressed, 312 MiB installed with a stripped `libcef.so`), which is why it is an
  explicit, optional installation with a size shown before download.
- Chromium security updates are the maintainer's responsibility: maintenance.md describes the bump procedure.
