# Privacy and security

OteTV lets a Minecraft server *suggest* what a TV shows. Whether a player's computer contacts anything is always that
player's decision, enforced on their own machine. This document is the reference for what crosses each boundary.

## Trust boundaries

Treated as untrusted: other players' packets, server-sent sources, labels and channel lists, media responses,
playlists, captions and redirects, browser pages and frames, worker messages, and resource packs or datapacks.

| Boundary | Enforcement |
| --- | --- |
| Server to client | Typed, bounded packets; sources are structurally validated on arrival and again before use; text is sanitized; a server can neither grant network consent nor create LAN grants |
| Client to server | Every action re-checked server-side: TV loaded, same dimension, distance, role (ACL from server facts only), lease, revision, rate limit |
| Mod to media worker | Separate process; argument arrays; jar verified by SHA-256 before each launch; framed IPC with direction checks and size ceilings before allocation |
| Mod to browser worker | Separate native process from a verified, explicitly installed bundle; same IPC rules; Chromium sandbox always on |
| Worker to network | Every request through OteTV's own guarded fetcher (media) or guarded CONNECT proxy (browser) |
| Resource/data packs | May restyle visuals and define channels; cannot ship executables, trusted domains, account settings or consent |

## Consent and grants (client side only)

- `privacy.externalMedia` (`ASK` by default): before the first contact with a new host on a given server, the player
  sees which host and why, and chooses Allow (optionally remembered for that server context) or Deny.
  `disableExternalMedia` blocks all external media; the bundled demo and local files still work.
- Seeing a TV never fetches anything. Joining a TV's session is explicit (`autoJoinSharedTvs` is off by default).
- LAN grants (`host:port`) are created only by the player on their own client, never by a server, and do not open
  any other private address.
- Local files are granted one at a time through a file dialog; the grant stores a content hash and the real path is
  re-validated before every open. Paths never leave the client.
- Consent, LAN grants, local-file grants and the optional private history live in `<game dir>/otetv/` with owner-only
  permissions where the OS supports them; never in world saves, block or item NBT, or packets.

## What the server and other players learn

- From a client: which TV it joined, its playback state for that TV's current source (at most once per second), and
  coarse capabilities (whether a direct-media or browser runtime is present). No URLs it chose privately, no file
  names, no consent decisions, no account or device identifiers.
- About a TV: public display state (power, channel, label, the source's host name, play state, owner name) to
  players tracking its chunk. The queue is sent on request to players within viewing range (its entries play
  publicly anyway); the trusted-player list goes only to players with the ACL permission (the owner and operators).

## Direct media destinations

The fetcher follows the OWASP SSRF guidance referenced by the brief: it parses URLs strictly (no credentials, no
alternate IP spellings, lookalike checks), resolves names itself, refuses loopback, private, CGNAT, link-local, cloud
metadata (`169.254.169.254`), multicast, unspecified, documentation, benchmarking and reserved addresses (IPv4 and every
IPv6 form that embeds one), refuses mixed public/private DNS answers, connects to the validated address while keeping
TLS host-name verification and SNI, re-checks the connected address, re-validates every redirect, refuses https-to-
http downgrades, bounds response sizes and times, and refuses HTML where media is expected. Plain http and private
addresses are reachable only for a host:port the player granted. GStreamer never fetches anything itself: network
source elements and adaptive demuxers are refused and demoted.

## Browser worker (YouTube)

- Process and sandbox: a separate native process; the Chromium sandbox is never disabled. On Linux the renderers run
  with seccomp-bpf and private user, PID and network namespaces (observed during testing); no setuid helper.
- No visible window and no display connection (headless Ozone). No persistent profile: a per-run directory under
  `<game dir>/otetv/runtime/browser-cache/` is deleted when the worker exits, stale ones after a day.
- Navigation: the top-level frame can only show OteTV's own wrapper (`https://com.otectus.otetv/player`, served from
  memory); embedded frames are limited to the official player and the provider's own consent, sign-in and advertising
  domains. Popups, new tabs, downloads, custom protocols, JavaScript dialogs, camera, microphone, notifications,
  clipboard reads and every permission prompt are refused.
- Network: all traffic (including Chromium's own background requests) goes through a guarded CONNECT proxy inside the
  worker. It accepts only TLS tunnels to port 443, resolves names itself, refuses any destination that resolves to a
  non-public address, connects to the address it checked and never decrypts TLS. It listens on loopback only and serves
  only processes of the same user (checked from the kernel's socket table). QUIC is disabled and WebRTC may not use
  non-proxied UDP. Resource filtering is a destination policy, not an ad blocker: provider ads and measurement are
  allowed to load.
- Bridge: only console messages whose source is the wrapper origin are accepted; commands back are fixed calls with
  numeric arguments. No URL, HTML or script crosses the IPC boundary (`LOAD_VIDEO` carries an 11-character id).
- YouTube data processing: the official embedded player processes data under Google's terms. OteTV does not promise
  that any embed mode prevents that, does not log in, and sends no viewing data to anyone else.

## Logging and diagnostics

Logs and the Diagnostics screen redact URLs (scheme, host and port plus the media file extension are kept; user
info, path, query and fragment are dropped), home-directory and absolute file paths, and anything that looks like a
token, key, cookie or password (`core/redact`). "Copy redacted details"
copies the same redacted text. Worker logs are kept in memory (last 200 lines) and written to the game log only when
`debugLogging` is on. `OTETV_BROWSER_DEBUG=1` is a developer switch that logs request host names and keeps the browser's
per-run directory; it is never set by OteTV.

## Integrity

- Worker jar: SHA-256 and size recorded at build time in `otetv_workers/workers.properties`, verified before launch.
- Runtime bundles: size and SHA-256 from a manifest the player trusts (built-in or a local file; never from a server),
  extracted with traversal, link and size checks, activated atomically with one rollback copy.
- Saved TV data from a newer OteTV is preserved verbatim and the TV is locked read-only rather than misread.

## Reporting security issues

Report privately to the maintainer (otectus) rather than in a public issue, with the OteTV version, platform and the
redacted diagnostics text.
