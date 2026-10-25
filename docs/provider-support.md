# Provider and source support

Status values follow the brief (section 13.6): `UNTESTED`, `RESEARCHING`, `EXTERNAL_ONLY`, `BLOCKED`, `EXPERIMENTAL`,
`VERIFIED`. "Verified" is scoped to the environment in [test-results.md](test-results.md) (CachyOS Linux x86_64,
2026-10-25). This table must match what the packaged build actually does; update it with every retest.

## Sources that play inside a TV

| Source type | How a TV gets it | Shared? | Status | Tested behaviour and limits |
| --- | --- | --- | --- | --- |
| `bundled` (demo) | channel 1 by default, or `/otetv channels add <n> <name> demo` | every client has the asset | VERIFIED (Linux) | 60 s original clip, captions; WebM or the Ogg encode chosen by the runtime's codecs; offline once the runtime exists |
| `https_video` | Video link tab, channels, queue | each client fetches the same approved URL after its own consent | VERIFIED for the transport (Linux), codec coverage per plugins | ranged and non-ranged progressive media; HTML pages, redirects to blocked hosts and non-public addresses refused; plain http only with a per-client LAN grant |
| `hls_clear` | as above | as above | VERIFIED for unencrypted VOD over MPEG-TS (Linux) | every playlist, rendition and segment fetched by OteTV; encrypted/key-bearing playlists refused with a precise message; live streams supported by the parser and timeline but not exercised against a real live server |
| `youtube` | Video link or YouTube tab (paste a watch, short or embed link) | shared intent; each client runs its own official player | VERIFIED on Linux for a public, embedding-enabled video; BLOCKED on Windows (no sandboxed browser build yet) | official IFrame API only, own controls, ads and captions; best-effort sync (divergence shown, never chased); pauses when hidden, too small (<320x180 px projected), occluded, unfocused or behind another screen; no login, no search |
| `shared_local_asset` | a channel or source that names a content hash | only clients that imported a file with that SHA-256 | implemented; not exercised in-world | each client maps the hash to its own file; the server never sees a path |
| `local_file` (private) | My files tab | never: local to one player | implemented; not exercised in-world (a unit test pins that it can never be shared) | files are granted explicitly and re-validated by real path before every open |

Network sources need the server's source policy to allow them (`sourcePolicy`, `approvedHosts`, `allowYouTube`) **and**
each client's own consent for that host on that server. A server cannot grant consent or LAN access.

## Subscription services: external only

The four services named in the brief open in the player's own web browser after a confirmation screen; there is no
in-world playback, no login inside Minecraft, no logos and no Play button that implies otherwise. The Services tab
shows the status below. Feature flags (`subscriptionInWorldAdapters`) cannot be enabled in this release.

| Field | Netflix | Hulu | Crunchyroll | Prime Video |
| --- | --- | --- | --- | --- |
| Status | EXTERNAL_ONLY | EXTERNAL_ONLY | EXTERNAL_ONLY | EXTERNAL_ONLY |
| Date / environment | 2026-10-25, design review on CachyOS Linux x86_64 | same | same | same |
| Runtime / CDM | none: no CEF/CDM path is built for DRM services | same | same | same |
| Capabilities passed (of the eight in section 13.1) | website navigation only, in the external browser | same | same | same |
| Not attempted | account login in-world, entitled playback, embedded runtime playback, CDM distribution, permitted texture output, user control, multiplayer sync | same | same | same |
| Test method | opening the fixed homepage from the Services tab through Minecraft's confirm-link screen | same | same | same |
| Known limits | DRM (Widevine) requires a license agreement and a CDM-enabled, provider-approved runtime; capturing protected output into a texture is not permitted | same, plus US-only service and no assumption of Linux support | same | same |
| Evidence needed for the next status | a written provider or Widevine agreement, a qualified CDM-enabled runtime, and a provider-approved output path | same | same | same |
| Retest triggers / owner | any change in provider terms, CEF CDM support or this mod's browser runtime; maintainer: otectus | same | same | same |

No unofficial extraction, recording, rebroadcasting or account sharing exists or will be added as a "compatibility"
feature.

## Not supported in this release

- Arbitrary web browsing (`allowArbitraryWebBrowsing` is a placeholder that stays false).
- YouTube playlists, search and account features (paste-link playback and a mod-managed queue only).
- RTSP, RTMP, DASH, WebRTC, Icecast and other transports; encrypted HLS; HEVC, AV1 and HDR.
