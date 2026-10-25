# Administrator guide

## Server configuration (`<world>/serverconfig/otetv-server.toml`)

These settings narrow what clients may do. They can never enable a client's networking, consent or LAN access.

| Key | Default | Range / values | Meaning |
| --- | --- | --- | --- |
| `allowPublicControls` | false | | Lets owners enable playback controls for everyone (each TV must also opt in) |
| `remoteRangeBlocks` | 32 | 4-128 | Maximum distance for remote and TV actions, same dimension only |
| `viewerRangeBlocks` | 64 | 8-256 | Distance for joining a session and counting as a viewer |
| `maxTvsPerPlayer` | 16 | 0-1024 | Placement limit per player; operators are exempt |
| `maxActiveSessionsPerDimension` | 32 | 1-1024 | Simultaneously playing TVs per dimension |
| `maxChannels` | 128 | 1-128 | Catalog cap (datapack plus admin channels) |
| `maxQueueEntries` | 32 | 1-32 | Per-TV queue cap |
| `sourcePolicy` | `APPROVED_HOSTS` | `BUNDLED_ONLY`, `APPROVED_HOSTS`, `ANY_PUBLIC_HTTPS` | Which sources the server accepts |
| `approvedHosts` | `[]` | host names; prefix `.` or `*.` for subdomains | Hosts allowed for direct https/HLS under `APPROVED_HOSTS` |
| `allowYouTube` | true | | Lets eligible clients use the official YouTube player |
| `allowArbitraryWebBrowsing` | false | | Placeholder; not implemented |
| `allowPrivateNetworkSources` | false | | Allows private-network or plain-http URLs in sources; each client still needs its own LAN grant |
| `subscriptionInWorldAdapters` | `disabled` | `disabled` only | Subscription services are external-browser only |
| `autoPauseEmptyVOD` | true | | Pause on-demand sessions with nobody in range |
| `autoPauseGraceSeconds` | 15 | 1-600 | Grace period before that pause |
| `allowRedstoneControl` | true | | Lets each TV's redstone mode act |

`BUNDLED_ONLY` allows just the bundled demo. `APPROVED_HOSTS` adds YouTube (when allowed), shared local assets and
direct media from `approvedHosts`. `ANY_PUBLIC_HTTPS` accepts any public https host; clients still ask their players.

## Client configuration (`<game dir>/config/otetv-client.toml`)

| Section | Key | Default | Meaning |
| --- | --- | --- | --- |
| privacy | `externalMedia` | `ASK` | `ASK` confirms each new host per server; `ALLOW` remembers approval; `DENY` never contacts external hosts |
| privacy | `disableExternalMedia` | false | No external media at all (demo and local files still work) |
| privacy | `autoJoinSharedTvs` | false | Join powered TVs automatically when they load |
| privacy | `persistRecentHistory` | false | Keep a private recent-history list on disk |
| playback | `qualityPreset` | `BALANCED` | LOW 640x360@24, BALANCED 960x540@30, HIGH 1280x720@30, FULL_HD 1920x1080@30 |
| playback | `maxActivePlayers` | 2 | 1-2 sessions, at most one YouTube |
| playback | `localVolume` | 0.5 | Personal volume, multiplied by master, Records, TV speaker and distance |
| playback | `globalMute` | false | Mute every TV for this player only |
| playback | `maxAudioRangeBlocks` | 16 | 4-64 |
| playback | `pauseWhenUnfocused` | true | Pause local playback when the window loses focus (always on for YouTube) |
| playback | `captions` / `captionScale` | false / 1.0 | Captions for direct media (YouTube keeps its own) |
| accessibility | `reduceMotionAndFlicker` | true | No flicker, grain or animated static |
| accessibility | `disablePictureEffects` | false | No monochrome, scanlines or vignette on any TV |
| accessibility | `startupSounds` / `crtHum` / `highContrast` | true / false / false | |
| runtime | `runtimeAutoUpdate` | `NOTIFY` | Updates are only activated after confirmation |
| runtime | `runtimeManifest` | "" | Local path of an extra runtime manifest the player trusts (never taken from a server) |
| runtime | `safeMode` | false | Disables picture effects and worker hardware acceleration; security is unchanged |
| runtime | `debugLogging` | false | Verbose, still redacted logs, including worker output |

## Commands

`/otetv` subcommands resolve the controller of an already loaded TV from any of its cells and never load chunks. They
work with `execute in <dimension> run`. Mutations go through the same permission checks as the remote; operators
(level 2) act as operator role, and non-operators are held to the remote range.

| Command | Purpose |
| --- | --- |
| `/otetv help` | List the commands |
| `/otetv info <pos>` | Model, name, identity, owner, power, state, position, channel, source, session revision, viewers, lease |
| `/otetv power <pos> on\|off`, `play`, `pause`, `stop`, `seek <pos> <seconds>` | Shared playback |
| `/otetv channel <pos> <number>` | Tune a powered TV |
| `/otetv trust <pos> <player> viewer\|controller\|editor`, `/otetv untrust <pos> <player>` | Edit the TV's ACL |
| `/otetv publiccontrols <pos> true\|false` | Per-TV public controls (also needs `allowPublicControls`) |
| `/otetv reset <pos>` | Owner or operator: power off, clear source, channel and queue, restore speaker, picture and redstone settings (name, owner and trusted players stay) |
| `/otetv reload` (op) | Rebuild the channel catalog from datapacks and admin channels |
| `/otetv channels list\|add <n> "<name>" <link>\|remove <n>` (op) | Admin channels stored in the world (`demo` as link adds the bundled demo) |
| `/otetv hosts list\|add <host>\|remove <host>` (op) | Edit `approvedHosts` |
| `/otetv diagnostics` | Server summary; on a client it also opens the local diagnostics screen |

## Roles

`PUBLIC` and `VIEWER` may view and join; `CONTROLLER` adds power, playback and channels; `EDITOR` adds sources, the
queue and settings; `OWNER` (the placer) adds the ACL and reset; `OPERATOR` (permission level 2) has everything. A
30-second control lease prevents two controllers fighting; it renews with use, and owners and operators can reclaim it.
Copying a remote copies its pairing, never a permission.

## Channel datapacks

Channels are `data/<namespace>/otetv/channels/<name>.json` in any datapack (see `examples/datapacks/`). Reload with
`/reload` or `/otetv reload`. Each file is validated on its own; a bad file is reported in `/otetv channels list`
and skipped.

```json
{
  "schemaVersion": 1,
  "id": "example:lobby_loop",
  "number": 10,
  "displayName": "Lobby loop",
  "description": "Optional, shown in the channel list.",
  "enabled": true,
  "source": { "type": "https_video", "url": "https://media.example.com/lobby.webm", "captionsUrl": "https://media.example.com/lobby.vtt" }
}
```

- `id`: `namespace:path`, lower case. `number`: 1-9999, unique. `displayName`: a string or `{"translate": "key"}`.
- Source types and fields: `bundled` (`assetId`, currently `otetv:welcome`); `https_video` (`url`, optional
  `captionsUrl`, `durationMs`); `hls_clear` (`url`); `youtube` (`videoId`, optional `startSeconds`); `shared_local_asset`
  (`assetId`, `sha256`, optional `durationMs`). Unknown fields or types are errors. Optional `label` names the source in
  lists.
- A datapack can define sources but cannot grant any client network consent; the server's `sourcePolicy` still applies.

## Runtime bundles

Clients need a media runtime for direct media (system GStreamer on Linux, the official GStreamer installer on Windows)
and the OteTV browser runtime for in-world YouTube. The browser runtime is a separate, checksummed bundle:

1. Build the worker and package it: see `runtime-packaging/package_browser_worker.py` (it prints the zip and writes
   `runtime-packaging/out/manifest.json`, with `--url` set to where you will host the zip).
2. Host the zip yourself if you want one-click installs, and distribute the manifest. OteTV never uploads anything.
3. Players either set `runtimeManifest` to the manifest file and use **Install** (download from your URL, verified
   by size and SHA-256), or use **Install from file** with the zip they downloaded. Both verify, extract safely and
   activate atomically; **Rollback** returns to the previous bundle.

The built-in manifest in this release is empty, so without a configured manifest YouTube TVs offer the Install
action and Open on YouTube.

## Operations notes

- Idle, off and unloaded TVs cost nothing per tick; loaded sessions do bounded work every 20 ticks.
- TV data is saved with the chunk; positions are checkpointed every 30 seconds while playing. Saves from a newer OteTV
  lock that TV read-only instead of being misread.
- Audit lines for every accepted action are logged under the `OTETV_AUDIT` marker (player, action, TV, position).
