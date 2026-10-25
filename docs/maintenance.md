# Maintenance

OteTV ships a browser, depends on a media framework and talks to an external provider. Each of those changes on its
own schedule; this is the procedure for keeping a release honest and safe.

## Updating Chromium (CEF)

Chromium receives security fixes every few weeks. A browser runtime older than the current stable CEF branch should
not be offered for new installations.

1. Pick the newest **stable** CEF build for each platform from the CEF build index and record its exact version and
   the SHA-1 of the minimal distribution in `gradle.properties` (`cef_version`, `cef_linux64_minimal_sha1`).
2. `python3 runtime-packaging/fetch_cef.py` (verifies the SHA-1 before extracting).
3. Rebuild: `cmake -S browser-worker -B browser-worker/build -G Ninja -DCMAKE_BUILD_TYPE=Release
   -DOTETV_WORKER_VERSION=<mod_version> && cmake --build browser-worker/build`. Treat compiler warnings as errors
   (the CEF macros already do); CEF API changes show up here, especially in handler signatures.
4. Run the browser harness, including the click test:
   `java -cp core/build/classes/java/main tools/worker-harness/BrowserHarness.java browser-worker/build/Release/otetv-browser-worker M7lc1UVf-VE 45 /tmp/frame.png`
   and `HARNESS_CLICK_TEST=1 ...`.
5. Check the sandbox on a running worker: every `--type=renderer` process must show `Seccomp: 2` and `NoNewPrivs: 1`
   in `/proc/<pid>/status` and its own user, PID and network namespaces in `/proc/<pid>/ns`.
6. `python3 runtime-packaging/package_browser_worker.py --url <your host>/<file>`, then
   `tools/run_smoke_test.sh youtube` (installs through the client's verified installer and plays in-world).
7. Publish the new zip and manifest yourself, bump the bundle version, and update docs/dependency-matrix.md and
   docs/test-results.md. Never replace a bundle in place: the version is part of its directory name.

## Updating GStreamer or the Java bindings

- gst1-java-core and JNA are pinned in `gradle.properties`; the worker jar is reproducible, and its SHA-256 is
  recorded automatically at build time.
- After any change (including a distribution upgrade of system GStreamer on the test machine), rerun the media
  harness on the fixtures (Ogg, Opus, silent video, HLS, progressive HTTP, error cases) and compare the A/V offsets with
  docs/test-results.md. Re-check that `hlsdemux`, `dashdemux` and GStreamer network sources are still refused.

## Provider changes (YouTube)

Retest the YouTube path when any of these change: the IFrame Player API (events, parameters, the `onAutoplayBlocked`
event), YouTube's required minimum functionality or developer policies, the embed identity rules (error 153), or the
browser runtime. Run the browser harness and `tools/run_smoke_test.sh youtube`, check error handling for an
embedding-disabled video, and update docs/provider-support.md with the date and result.

If YouTube begins to require a publicly resolvable wrapper origin, the worker-served origin in
[ADR 0002](adr/0002-youtube-wrapper-origin-and-identity.md) must be replaced by a maintainer-operated HTTPS wrapper,
which needs explicit authorization to register and operate a domain.

## Subscription services

They stay external-only. Revisit only with written provider or CDM agreements; record any new evidence in
docs/provider-support.md using the fields from brief section 13.6.

## Runtime rollback

The installer keeps the previously active bundle. Players (or support) can press **Rollback** in the runtime screen;
it swaps `active.json` back without touching running workers. If a published bundle is bad, publish a manifest that
no longer lists it and a fixed bundle with a new version; never edit a published zip.

## Data and protocol versions

- TV NBT has a schema version (`OteTvCore.TV_SCHEMA_VERSION`). Add a migration step in `TvState.load` for every change;
  newer data is preserved and locks the TV read-only, so downgrades are safe.
- The network protocol (`OteTvNetwork.PROTOCOL`) and the worker IPC version (`OteTvCore.IPC_PROTOCOL_VERSION`) must
  change with any wire change. `IpcCodecTest.nativeWorkerWireValuesArePinned` fails when a value the C++ worker
  hard-codes moves; update `browser-worker/src/ipc.h` in the same change.

## Retest obligations before a release

| Area | Minimum |
| --- | --- |
| Build | `gradlew-quiet.sh . build` from a clean checkout (includes core tests and the side-boundary check) |
| Data | `runData` produces no diff; `check_mod.py` reports no missing models, textures, language keys or loot |
| Gameplay | `runGameTestServer`: all GameTests pass |
| Direct media | media harness on every fixture; a 30-minute no-seek run with stable per-window A/V offsets |
| In world | `tools/run_smoke_test.sh` and `tools/run_smoke_test.sh youtube`: pass, and the screenshots inspected |
| Platforms | each platform claimed in docs/provider-support.md retested on a clean machine |
| Docs | docs/test-results.md, docs/dependency-matrix.md and CHANGELOG.md updated with real results |
