# ADR 0002: The YouTube wrapper is served by the worker itself at https://com.otectus.otetv

Status: accepted (implemented in 0.1.0)

## Context

The official IFrame Player API must be used with its controls, branding, ads and captions intact (brief section 12).
YouTube's required minimum functionality asks embedded players to identify the client through the HTTP Referer;
the API documentation names a missing identity as error 153. A random or borrowed Referer is not acceptable
(section 12.2), and only the first-party top-level wrapper may use the native bridge.

Options considered:

1. Host a versioned wrapper on a project-controlled HTTPS domain. Requires registering a domain and operating a
   service (deployment, health checks, rollback, privacy disclosure). The brief forbids registering domains or
   operating services without the maintainer's authorization, which this implementation does not have.
2. Load the player from a `file://` or custom-scheme page. Such origins send no usable Referer, and embedded players
   from opaque origins fail with identity errors.
3. Serve the wrapper from memory inside the sandboxed browser worker under an application-identity origin that
   follows the documented desktop-application form `https://<application id>`.

## Decision

Option 3. The worker registers a scheme handler factory for `https` and the single host `com.otectus.otetv`
(`OteTvCore.APP_ID`), so `https://com.otectus.otetv/player` is answered from memory and never reaches the network or
DNS. The page is a fixed template: the only values written into it are a video id that matched
`[A-Za-z0-9_-]{11}` twice (client and worker), integers and booleans. It carries a per-page script nonce and a
Content-Security-Policy limiting scripts to the nonce plus `www.youtube.com`/`s.ytimg.com`, frames to `www.youtube.com`
and `www.youtube-nocookie.com`, and allowing no forms or base URL. The player is created with
`origin` and `widget_referrer` set to `https://com.otectus.otetv`; Chromium's default
`strict-origin-when-cross-origin` policy sends that origin as the Referer of the embed request.

The bridge is the page's console: the worker accepts `OTETV|generation|event|value|detail` lines only when the
message's source is the wrapper origin, which cross-origin frames (the player, ads) cannot claim. Commands go the other
way as fixed calls (`otetv.play()`, `otetv.seek(<seconds>)`, ...) with numeric arguments only.

## Consequences

- Verified on 2026-10-25: the official player loads, plays, pauses, seeks and reports state, time and duration in
  the worker harness and in-world (test-results.md). No error 153 was observed.
- If YouTube ever requires a publicly resolvable wrapper origin, option 1 becomes necessary; that needs the
  maintainer to authorize and operate a domain, and is recorded in maintenance.md as a retest trigger.
- The origin is not a real website, so nothing about viewing is sent to an OteTV-operated server: there is none.
