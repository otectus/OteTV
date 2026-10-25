# OteTV architecture

OteTV is a Forge 1.20.1 mod (mod id `otetv`) built from four parts that never share a process boundary they do not
need:

| Part | Where | Runs in | Depends on |
| --- | --- | --- | --- |
| Core library | `core/` | merged into the mod jar, and inside the media worker | Gson only; no Minecraft, no natives |
| Mod: common and server | `src/main/java/com/otectus/otetv/` (everything outside `client/`) | client and dedicated server | Minecraft, Forge, core |
| Mod: client | `src/main/java/com/otectus/otetv/client/` | client only | the above, plus Minecraft client classes |
| Media worker | `media-worker/` | a separate Java process per direct-media session | core, gst1-java-core, JNA, system/bundled GStreamer |
| Browser worker | `browser-worker/` (C++) | a separate native process per YouTube session | CEF 156 (Chromium), no Java |

The server decides *what* plays and *when*; each client decides *whether and how* to play it on its own machine.
Native media code never runs in the Minecraft JVM, so a decoder or browser crash becomes a recoverable TV error.

## Repository layout

```
core/              pure-Java protocol, policy, sync and parsing code (+ 149 unit tests)
src/main/          the mod (common, server, client, datagen) and its resources
src/generated/     datagen output (recipes, loot, tags, advancements) - regenerate, never edit
src/gameTest/      27 Forge GameTests (structure: data/otetv/structures/platform.nbt)
src/smokeTest/     test-only agent mod that drives a dev client (runClient -PsmokeTest)
media-worker/      out-of-process GStreamer worker (fat jar embedded in the mod jar)
browser-worker/    native CEF host for the official YouTube player (CMake)
runtime-packaging/ CEF fetch script and browser runtime bundle packaging
tools/             asset/structure generators, worker harnesses, smoke-test runner
test-fixtures/     generator and original media fixtures (WebM, MP4, Ogg, HLS, captions)
docs/              this documentation; docs/adr/ holds the architecture decision records
examples/          example channel datapack
```

## Side boundary (Gate A)

Common code must not reference client classes: a class is resolved when it is referenced, so an `isClientSide` guard
does not protect a dedicated server. `checkSideBoundaries` (run after every `compileJava` and by `check`) scans
`src/main/java` outside `client/` and `datagen/` for `net.minecraft.client.`, `com.mojang.blaze3d.`,
`net.minecraftforge.client.`, AWT/Swing/sound, LWJGL, GStreamer, CEF, JNA and `com.otectus.otetv.client.` references.
The single sanctioned bridge is `client/ClientHooks`, reached only through `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)`.
The GameTest server (`runGameTestServer`, a `DEDICATED_SERVER` dist) loads and exercises every common class.

## Blocks, assemblies and persistence

- Six models (`block/TvModel`): wooden monochrome 2x2, woodgrain color 2x1, portable 1x1, CRT 2x2, plasma 3x2 (stand or
  wall), OLED 3x2 (stand or wall). Footprints, anchor column and collision boxes are code-registered.
- Every cell is the same `TvBlock` with a `part` property; only the anchor cell has the controller
  `TvBlockEntity` and renders the model. One geometry convention (`block/TvGeometry`) is shared by placement,
  breaking, collision, rendering and tests: the front faces north in model space, columns run along
  `facing.getClockWise()`, rows run up, and blockstate `y` rotation is north 0 / east 90 / south 180 / west 270.
- Placement (`TvPlacement`) validates every cell first (build height, loaded chunk, world border, replaceability,
  entity obstruction, permissions, floor or wall support, per-player limit) and then commits all cells in one tick.
- Removal (`TvAssembly.dismantle`) runs exactly once from `onRemove`, drops one item (none in creative), and clears
  the other cells without drops. Pistons are refused (`PushReaction.BLOCK`). Support loss, orphan parts and missing
  parts are handled by scheduled ticks on the anchor and random ticks on parts; nothing force-loads a chunk.
- `TvBlockEntity` owns the durable `TvState` (schema-versioned NBT: identity, owner, ACL, power, channel, source,
  queue, cursor, preset, redstone mode). A newer schema is kept verbatim and locks the TV read-only; a corrupted
  source falls back to standby. Item NBT may carry only cosmetics (name, preset); identity, owner and ACL never come
  from an item. `OteTvWorldData` tracks every placed TV per world (owner counts, dimension, position).

## Shared sessions (server)

`session/TvSessionService` holds one transient `TvSession` per loaded TV, keyed by its random persistent identity:

- Time is a random per-run epoch plus monotonic nanoseconds (`System.nanoTime`), never game ticks. A
  `core/sync/TimelineAnchor` (state, position, anchor time, rate, duration, live) describes the shared timeline.
  Direct media starts 500 ms in the future so every client can begin together; YouTube uses 250 ms.
- Every mutation is a typed `TvActionType` through `TvActions.perform`: role check (`TvAccess`: public, viewer,
  controller, editor, owner, operator), stale-revision check for non-idempotent actions, a 30-second control lease
  (renewed by use, reclaimable by owners and operators), then the change, a revision bump and an ordered delta.
- Periodic work runs every 20 ticks for loaded sessions only: lease expiry, auto-pause of empty on-demand sessions
  after 15 s, end-of-item queue advance, 2-second timing broadcasts, 30-second cursor checkpoints and 10-second
  assembly integrity checks.
- The service starts on `ServerAboutToStartEvent`, before any level loads, so controllers in spawn chunks get
  sessions too.

## Network protocol

`network/OteTvNetwork` is a Forge `SimpleChannel` (protocol `1`, must match on both sides). Every packet is a typed,
bounded record decoded with explicit limits (`network/NetCodec`, `core/Limits`); per-player token buckets (10 actions
per second, burst 20) and a one-report-per-second viewer limit apply.

- Client to server: `RequestSnapshot`, `JoinViewing`, `LeaveViewing`, `AcquireControl`, `ReleaseControl`, `TvAction`,
  `ViewerStatus`, `ClockProbe`, `RequestCatalog`, `RequestQueue`, `RequestAcl`, `PairRemote`, `UnpairRemote`.
- Server to client: `Snapshot` (per player: role and permissions differ), `Delta` (bitmask of changed fields),
  `Timing`, `ActionResult`, `SessionRemoved`, `ClockReply`, `ChannelCatalog` pages, `QueuePage`, `AclList` (owner and
  operators only), `ShowDiagnostics`.
- Every request is re-checked server-side: TV loaded (never loaded on demand), same dimension, distance (remote
  range 32 blocks by default), role, lease and revision. No packet carries cookies, tokens, file paths, account
  identifiers or another player's private choices; local consent and LAN grants exist only on the client.

## Client playback

- `client/tv/ClientTvManager` keeps snapshots, joins sessions explicitly (seeing a TV never fetches media unless
  `autoJoinSharedTvs` is enabled) and enforces at most two active sessions, at most one of them YouTube.
- `client/tv/ClientPlaybackSession` maps shared intent onto a local backend: consent, runtime availability, scheduled
  start, drift correction (`core/sync/DriftController`: ignore under 150 ms; between 150 and 500 ms correct with a
  bounded rate only where a backend supports it, which no backend does in this release because no pitch-preserving
  path is qualified; seek after 1.5 s of drift beyond 500 ms, then a 5-second cooldown), YouTube divergence reporting
  (never chased), visibility policy and local pause/resume.
- `client/tv/ClockSync` estimates the server clock with NTP-style probes answered on the network thread.
- `client/media/DirectMediaBackend` and `BrowserBackend` talk to the workers through `WorkerProcess` (argument
  arrays, absolute paths, framed stdin/stdout IPC, stderr captured for diagnostics, 6-second heartbeat).
- `client/render/TvRenderer` uploads the newest frame to a per-session `VideoTexture` (`glTexSubImage2D`, RGBA from
  GStreamer, BGRA from CEF) and draws it full-bright inside the profile's aperture with letterboxing, optional period
  effects for direct media only, captions (direct media only; YouTube keeps its own) and status text.
- Audio for direct media is PCM pushed into a Minecraft streaming `SoundInstance` (`client/media/audio`): positional
  mono in the world, relative stereo in viewing mode, Records volume category, OpenAL queue position as the audio
  clock (via an access transformer on `Channel.source`), with a virtual drain when nothing can be heard. YouTube
  audio stays inside the browser player and is controlled only through its documented volume and mute calls.

## Worker IPC

`core/ipc` defines the framing `[int32 length][u8 type][payload]` (big-endian), direction-checked message types with
per-type size ceilings, and validated schemas (`Msg`, `SourceGrant`, `FrameHeader`). Both workers and the client use
the same definitions; the C++ worker mirrors them and `IpcCodecTest.nativeWorkerWireValuesArePinned` pins the values it
hard-codes.

## Media worker

`media-worker` (`worker/media`): `appsrc` fed by OteTV's own byte sources (file, guarded HTTPS with ranges, brokered
HLS) into `decodebin` (adaptive demuxers and network sources refused by `autoplug-select` and demoted by rank), then
RGBA video, S16LE 48 kHz stereo audio and text appsinks. Decoding is paced by the client's audio consumption (one
second of credit), frames are released against the client's audible timestamp (25 ms lead), flush barriers drop
pre-seek samples, EOS is forwarded only after both sinks drain, and every output carries the flush generation. The
worker is launched with the client's own Java, verified by SHA-256 against `otetv_workers/workers.properties`
before each launch, and exits when its parent exits.

## Browser worker

`browser-worker` (C++, CEF 156): a windowless, headless-Ozone browser that serves a first-party wrapper page from
memory at `https://com.otectus.otetv/player`, hosts the official IFrame Player API inside it, paints BGRA frames into
the IPC stream and reports typed player events. All traffic goes through an in-process guarded CONNECT proxy that
resolves names itself, refuses any non-public answer and connects to the pinned address. See
[ADR 0002](adr/0002-youtube-wrapper-origin-and-identity.md), [ADR 0003](adr/0003-native-cef-browser-worker.md) and
[privacy-and-security.md](privacy-and-security.md).

## Runtime installation

`client/media/RuntimeManager` and `RuntimeInstaller` install verified bundles from a manifest (built-in, or a local
file the player configures; never from a server): size and SHA-256 check, safe zip extraction (`core/runtime/
SafeZipExtractor`: no traversal, links or oversized trees), atomic activation through `active.json`, one retained
rollback copy. The media worker uses system GStreamer on Linux, the official GStreamer runtime on Windows, or an
installed `gstreamer` bundle; the browser worker is always an installed `browser` bundle.

## Testing layers

| Layer | Command | What it proves |
| --- | --- | --- |
| Unit and protocol | `gradlew-quiet.sh . :core:test` | parsers, policies, sync maths, IPC codecs (149 tests) |
| GameTests | `gradlew-quiet.sh . runGameTestServer` | assemblies, controls, ACLs, redstone, commands, data (27) |
| Worker harnesses | `tools/worker-harness/*.java` | each worker end to end through real IPC |
| Client smoke test | `tools/run_smoke_test.sh [youtube]` | real in-world playback, screens, screenshots |

Results and environments are recorded in [test-results.md](test-results.md).
