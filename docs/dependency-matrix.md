# Dependency matrix

Exact versions live in `gradle.properties` (Java and Gradle side) and are restated here with their purpose, license
and how they reach players. Nothing is resolved dynamically ("latest"); updating any row is a maintenance task with
the retests listed in [maintenance.md](maintenance.md).

## Build and platform

| Component | Version | Source of truth | Notes |
| --- | --- | --- | --- |
| Minecraft | 1.20.1 | `minecraft_version` | Range `[1.20.1,1.21)` in mods.toml |
| Forge | 47.4.23 | `forge_version` | Mod loads on 47.x per `forge_version_range` |
| ForgeGradle | 6.0.x (`[6.0,6.2)`) | `build.gradle` plugins block | Official mappings |
| Gradle wrapper | 8.8 | `gradle/wrapper/gradle-wrapper.properties` | |
| Java | 17 (toolchain) | `build.gradle` | Workers run on the same Java as the game |
| JUnit | 5.10.2 | `junit_version` | core tests only |
| Gson | 2.10.1 | `gson_version` | compileOnly for core; Minecraft ships Gson at runtime |
| CMake | 3.21 or newer | `browser-worker/CMakeLists.txt` | browser worker only; built with CMake 4.4.4 and GCC 16.2.1 here |

## Shipped inside the mod jar

| Component | Version | License | Where | Notes |
| --- | --- | --- | --- | --- |
| OteTV core | mod version | MIT | merged classes | no natives |
| Media worker | mod version | MIT | `otetv_workers/otetv-media-worker.jar` (+ SHA-256 in `workers.properties`) | reproducible fat jar |
| gst1-java-core | 1.4.0 | LGPL-3.0 | inside the media worker jar | Java bindings only; GStreamer itself is not shipped |
| JNA | 5.17.0 | Apache-2.0 or LGPL-2.1 | inside the media worker jar | strict pin; GStreamer loading |
| Demo media | original | MIT (project) | `otetv_media/welcome.webm`, `welcome.ogv`, `welcome.vtt` | synthesized by `test-fixtures/generate_media.py` |

## Native runtimes (installed on clients, never on servers)

| Runtime | Version tested | License | Delivery | Startup requirements |
| --- | --- | --- | --- | --- |
| GStreamer (Linux) | 1.28.8 system packages | LGPL-2.1+ (core, base), plugins vary | distribution packages | `gstreamer`, `gst-plugins-base`; `gst-plugins-good` for WebM/MP4/Matroska; `gst-libav` for H.264/AAC |
| GStreamer (Windows) | official 1.x MSVC runtime installer | as above | user installs it; detected via `GSTREAMER_1_0_ROOT_MSVC_X86_64` or the default path | not tested in this release |
| GStreamer bundle | none published | as above | `gstreamer` runtime bundle via manifest | supported by the installer; no bundle is built yet |
| CEF / Chromium | CEF `156.0.14+g4fa4ab5`, Chromium `156.0.8078.48` | BSD-3-Clause (CEF) plus Chromium's third-party licenses (`CREDITS.html`) | `otetv-browser` runtime bundle (139 MiB zip, 312 MiB installed) | Linux x86_64 with unprivileged user namespaces |

CEF archive pin: `cef_binary_156.0.14+g4fa4ab5+chromium-156.0.8078.48_linux64_minimal.tar.bz2`, SHA-1
`d091c82a8655113a34616c08cb340bf92310298d` (`cef_linux64_minimal_sha1`), fetched from the official CEF build CDN by
`runtime-packaging/fetch_cef.py`.

Browser runtime bundle built for this release: `otetv-browser-linux-x86_64-0.1.0+cef156.0.14.zip`, SHA-256
`85c50e77f3f4af5c5098fdb8b68fc2455cf10cf08302321ce87aa824553947a8`, 145,440,003 bytes (deterministic; rebuild with
`runtime-packaging/package_browser_worker.py` after building the worker). It is not hosted anywhere.

## GStreamer element requirements

The worker reports which of these elements exist (`HELLO_ACK`) and the client evaluates the codec matrix
(`core/capability/CodecMatrix`), shown under Diagnostics:

| Combination | Needs | Status in this release |
| --- | --- | --- |
| WebM VP8 + Vorbis | matroskademux, vp8dec or avdec_vp8, vorbisdec | supported |
| WebM VP9 + Opus | matroskademux, vp9dec or avdec_vp9, opusdec | supported |
| MP4 H.264 + AAC (8-bit SDR) | qtdemux, h264parse, an H.264 decoder, an AAC decoder | supported |
| Ogg Theora + Vorbis | oggdemux, theoradec, vorbisdec | supported (base plugins only) |
| Ogg Opus (audio only) | oggdemux, opusdec | supported |
| HLS MPEG-TS H.264 + AAC (clear) | tsdemux, h264parse, H.264 and AAC decoders | supported |
| HLS fMP4 H.264 + AAC (clear) | qtdemux, h264parse, H.264 and AAC decoders | supported |
| HEVC, AV1, any 10-bit/HDR | - | unsupported by policy (never shown as supported) |

On the test machine (no gst-plugins-good) WebM, MP4 and fMP4 HLS report missing components; Ogg and MPEG-TS HLS play.

## Verified startup requirements

- Media worker: Java 17 from the game, GStreamer 1.16 or newer loadable through JNA, a writable private registry
  path under `<game dir>/otetv/runtime`.
- Browser worker: Linux x86_64, glibc and the libraries `libcef.so` links against (a normal desktop has them),
  unprivileged user namespaces for the Chromium sandbox, no display server needed (headless Ozone).
