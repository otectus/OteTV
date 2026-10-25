# Changelog

## 0.1.0 (unreleased)

First implementation for Minecraft 1.20.1 / Forge 47. Statuses follow docs/provider-support.md; "tested" means on
the Linux machine recorded in docs/test-results.md.

### Implemented

- Six televisions (1950s wooden monochrome, 1970s woodgrain color, 1980s portable, 1990s CRT, 2000s plasma, modern
  OLED) as multi-block assemblies with atomic placement, per-cell collision, floor and wall mounting (plasma, OLED),
  exactly-once breaking with a single drop, support loss, repair of missing parts and orphan cleanup.
- Universal remote: pairing by persistent TV identity, range and dimension checks, a replaced TV is never controlled
  by an old remote, remote GUI with power, navigation, playback, channels, captions and volume.
- Shared, server-authoritative sessions with roles (viewer, controller, editor, owner, operator), public controls,
  control leases, revisions, scheduled starts, timing broadcasts, auto-pause of empty on-demand sessions, queues and
  checkpoints. Commands under `/otetv`, channel datapacks and admin channels, server source policy.
- Direct media in an out-of-process GStreamer worker: bundled demo (WebM, with an Ogg fallback chosen automatically),
  local files (private and shared by content hash), public HTTPS progressive media with and without byte ranges,
  unencrypted HLS; guarded fetching with SSRF protections and per-client consent and LAN grants; captions (sidecar and
  in-band); lip sync paced by the client's audible OpenAL clock.
- In-world rendering on every model with letterboxing, period effects for direct media, status text and captions;
  enlarged viewing mode; positional audio in the world and stereo in viewing mode, following Minecraft's volume
  sliders.
- Official YouTube embedded player through a native, sandboxed CEF browser worker (Linux): first-party wrapper with
  application identity, typed events, documented controls, visibility and focus policy, autoplay gesture handling,
  guarded network proxy. Runtime bundles install from a manifest or a file with checksum verification and rollback.
- Redstone modes (power while high, toggle on rising edge, next channel on rising edge with a one-second debounce) and
  a comparator signal (off, standby, paused, playing).
- Advancements for crafting a TV, pairing a remote and playing the demo; recipes, loot tables, tags and language
  files from datagen; diagnostics with redacted copy; privacy screen; accessibility options.
- Tests: 149 core unit tests, 27 GameTests, worker harnesses and an in-world client smoke test (see
  docs/test-results.md).

### Experimental

- Live HLS: implemented in the parser, broker and timeline, not yet exercised against a real live server.
- Shared local assets and private local files: implemented, not yet exercised in a running client.
- Keyboard and Unicode input into the YouTube player: implemented, not exercised by an automated test.

### External only

- Netflix, Hulu, Crunchyroll and Prime Video open in the player's own browser after confirmation. No in-world
  playback, login or logos.
- YouTube videos that disable embedding, need sign-in or are restricted open on YouTube.

### Unavailable in this release

- In-world YouTube on Windows (the browser worker has no Windows sandbox build yet); Windows offers Open on YouTube.
- Hosted runtime downloads: the built-in runtime manifest is empty; maintainers publish bundles themselves.
- Arbitrary web browsing, YouTube search and playlists, DRM services, encrypted HLS, HEVC, AV1, HDR, rate-based drift
  correction.
