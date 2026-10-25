#!/usr/bin/env python3
"""Generates assets/otetv/lang/en_us.json. Enum-driven key families are listed explicitly so check tools can
verify them; run tools/assets/check_lang.py afterwards to compare with the keys referenced in code."""
import json
import os
import sys

OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'assets', 'otetv', 'lang', 'en_us.json')

L = {}


def add(key, value):
    if key in L:
        raise SystemExit('duplicate key ' + key)
    L[key] = value


# Blocks, items, creative tab, keys
TVS = {
    'wooden_monochrome_tv': ('Wooden Monochrome TV', '1950s wooden console'),
    'woodgrain_color_tv': ('Woodgrain Color TV', '1970s woodgrain cabinet'),
    'portable_tv': ('Portable TV', '1980s portable set'),
    'crt_tv': ('CRT TV', '1990s CRT television'),
    'plasma_tv': ('Plasma TV', '2000s flat panel'),
    'oled_tv': ('OLED TV', 'Modern slim panel'),
}
for rid, (name, era) in TVS.items():
    add('block.otetv.' + rid, name)
    add('otetv.era.' + rid, era)
add('item.otetv.remote_control', 'Universal Remote')
add('itemGroup.otetv', 'OteTV')
add('key.categories.otetv', 'OteTV')
add('key.otetv.open_remote', 'Open held remote')
add('key.otetv.local_mute', 'Mute nearest TV for me')
add('key.otetv.viewing_mode', 'Enlarged viewing (nearest TV)')
add('channel.otetv.welcome', 'OteTV Welcome')

# Tooltips
add('otetv.tooltip.footprint', 'Footprint %s x %s x %s blocks, %s picture')
add('otetv.tooltip.aspect_classic', '4:3')
add('otetv.tooltip.aspect_wide', '16:9')
add('otetv.tooltip.wall_mountable', 'Place against a wall to mount it')

# Advancements
add('otetv.advancement.root.title', 'Prime Time')
add('otetv.advancement.root.description', 'Craft a television')
add('otetv.advancement.pair_remote.title', 'Couch Commander')
add('otetv.advancement.pair_remote.description', 'Pair a universal remote with a television')
add('otetv.advancement.play_demo.title', 'Test Pattern')
add('otetv.advancement.play_demo.description', 'Watch the bundled OteTV demo')

# Sound subtitles
for s, text in {'tv.power_on': 'Television turns on', 'tv.power_off': 'Television turns off', 'tv.crt_startup': 'Picture tube warms up',
                'tv.channel_click': 'Channel clicks', 'tv.knob_turn': 'Dial turns', 'tv.crt_hum': 'Television hums',
                'remote.beep': 'Remote beeps', 'remote.pair': 'Remote pairs', 'tv.stream': 'Television sound'}.items():
    add('subtitles.otetv.' + s, text)

# Placement
for k, v in {'outside_world': 'There is not enough room inside the world for this television.',
             'unloaded': 'Part of the space is not loaded.', 'world_border': 'The television would cross the world border.',
             'obstructed': 'Something is in the way.', 'no_permission': 'You cannot build here.',
             'entity_in_way': 'Something is standing in the way.', 'needs_wall': 'Wall mounting needs a solid wall behind every part.',
             'needs_floor': 'This television needs something solid to stand on.', 'limit': 'You have reached the television limit for this world.'}.items():
    add('otetv.placement.' + k, v)

# Remote
for k, v in {
    'paired': 'Remote paired', 'unpaired': 'Remote unpaired', 'pair_failed': 'Could not pair: stand closer to a working television.',
    'not_paired': 'Not paired', 'how_to_pair': 'Sneak and use it on a television to pair.', 'paired_with': 'Paired with %s',
    'not_paired_hint': 'This remote is not paired. Sneak and use it on a television.', 'hold_remote': 'Hold a remote to open it.',
    'unavailable': 'TV unavailable', 'unavailable_hint': 'It may be broken, unloaded, out of range or on another world.',
    'unpair': 'Unpair', 'power': 'Power', 'home': 'Home', 'back': 'Back', 'select': 'OK', 'play': 'Play', 'pause': 'Pause',
    'stop': 'Stop', 'ch_up': 'CH +', 'ch_down': 'CH -', 'cc_on': 'CC: On', 'cc_off': 'CC: Off', 'vol_up': 'TV Vol +',
    'vol_down': 'TV Vol -', 'mute_me': 'Mute for me', 'unmute_me': 'Unmute for me', 'my_volume': 'My volume: %s%%',
    'take_control': 'Take control', 'reclaim': 'Reclaim control', 'you_control': 'You have control',
    'controlled_by': 'Controlled by %s', 'muted_for_you': 'Muted for you', 'unmuted_for_you': 'Unmuted for you'}.items():
    add('otetv.remote.' + k, v)

# Pairing
for k, v in {'title': 'Pair Remote', 'pair': 'Pair', 'switch': 'Switch to this TV', 'open_menu': 'Open TV menu',
             'unpaired_body': 'Pair this remote with this television?',
             'switch_body': 'This remote is paired with %s. Pair it with this television instead?',
             'another_tv': 'another television', 'no_permission_note': 'Pairing does not grant permission to change a TV.'}.items():
    add('otetv.pair.' + k, v)

# Screens
for k, v in {
    'menu.title': 'Television', 'remote.title': 'Universal Remote', 'picker.title': 'Choose Source', 'settings.title': 'TV Settings',
    'viewing.title': 'Viewing', 'viewing.exit': 'Back to world', 'viewing.click_to_interact': 'Click the picture to use the player',
    'viewing.input_captured': 'Player has input - Esc releases it',
    'loading': 'Loading...', 'watch': 'Watch', 'choose_source': 'Choose source', 'remote': 'Remote', 'settings': 'Settings',
    'viewing_mode': 'Enlarged viewing', 'privacy': 'Privacy', 'diagnostics': 'Diagnostics', 'done': 'Done',
    'owner': 'Owner: %s  (you: %s)', 'no_source': 'No source', 'channel': 'CH %s', 'state_line': 'Shared: %s  Here: %s  %s',
    'standby': 'Standby', 'join_viewing': 'Join viewing', 'paused_join': 'Paused - join to watch',
    'consent_needed': 'Needs your OK to contact %s', 'too_small': 'Too small to watch from here',
    'open_viewing': 'Open enlarged viewing to watch', 'press_play': 'Press Play to start watching',
    'excess': 'Paused: another TV is playing for you', 'diverged': 'Playback differs on this device',
    'private': 'Private: only you see this', 'locked': 'Saved by a newer OteTV: read-only',
    'allow_host': 'Allow connecting to %s...', 'install_runtime': 'Install / setup help', 'open_youtube': 'Open on YouTube',
    'select_matching_file': 'Select matching file...', 'retry': 'Retry', 'stop_private': 'Stop private playback'}.items():
    add('otetv.screen.' + k, v)

# Source picker
for k, v in {
    'tab.channels': 'Channels', 'tab.link': 'Video link', 'tab.files': 'My files', 'tab.youtube': 'YouTube', 'tab.services': 'Services',
    'tab.queue': 'Queue', 'disabled': 'disabled', 'needs_local_asset': 'needs matching local file',
    'no_channels': 'This server has no channels yet.', 'link': 'Video link (https)', 'label': 'Label (optional)',
    'play_on_tv': 'Play on TV', 'add_queue': 'Add to queue',
    'link_note': 'Hosts must be approved by the server. Viewers confirm before their computer connects.',
    'share': 'Share', 'forget': 'Forget', 'import': 'Import file...', 'open_folder': 'Open media folder', 'refresh': 'Refresh',
    'no_files': 'No local files yet: import one or put it in the OteTV media folder.',
    'files_note': 'Files play privately. "Share" sends only a content fingerprint, never the file or its path.',
    'playing_private': 'Playing privately on this TV', 'identifying': 'Fingerprinting file...', 'imported': 'File imported',
    'import_failed': 'Could not use that file', 'dialog_unavailable': 'No file dialog available: use the media folder instead.',
    'youtube_link': 'YouTube link or video id',
    'youtube_note': 'Official embedded player only. Age-restricted, private or members-only videos may not play.',
    'search_not_configured': 'Search is not configured: paste a link.',
    'open_in_browser': 'Open in browser', 'service_external': 'This service currently opens in your browser.',
    'services_note': 'No in-world playback for subscription services in this release.',
    'remove': 'Remove', 'next': 'Next item', 'clear': 'Clear queue'}.items():
    add('otetv.picker.' + k, v)

# Settings
for k, v in {
    'name': 'TV name', 'save': 'Save', 'picture': 'Picture: %s', 'redstone': 'Redstone: %s', 'speaker': 'TV speaker: %s%%',
    'public_on': 'Public controls: On', 'public_off': 'Public controls: Off', 'player': 'Player name', 'trust': 'Trust',
    'untrust': 'Remove %s (%s)', 'trusted_players': 'Trusted players', 'reset': 'Reset TV...', 'reset_title': 'Reset this TV?',
    'reset_body': 'Playback, channel, queue and settings return to defaults. Ownership and trusted players stay.'}.items():
    add('otetv.settings.' + k, v)

# Consent
add('otetv.consent.title', 'Connect to an external host?')
add('otetv.consent.body', 'To play this, your computer will connect directly to %s. That host can see ordinary network information such as your IP address. Server: %s')
add('otetv.consent.body_youtube', 'To play this, your computer will load the official YouTube player from %s. YouTube can see ordinary network information such as your IP address and may show ads. Server: %s')
add('otetv.consent.note', 'The Minecraft server cannot make this choice for you. Change it any time in OteTV Privacy.')
add('otetv.consent.once', 'Allow once')
add('otetv.consent.always', 'Always on this server')

# Privacy
for k, v in {'mode': 'External media: %s', 'mode.ask': 'Ask', 'mode.allow': 'Allow', 'mode.deny': 'Never',
             'external_enabled': 'External media: enabled', 'external_disabled': 'External media: disabled',
             'revoke': 'Revoke %s (%s)', 'no_grants': 'No remembered approvals.', 'lan_title': 'LAN media grants (this computer only)',
             'lan_host': 'Host', 'lan_port': 'Port', 'add_lan': 'Add grant', 'bad_lan': 'Enter a host and a port 1-65535.',
             'remove_lan': 'Remove LAN grant %s', 'revoke_all': 'Revoke all', 'clear_history': 'Clear history'}.items():
    add('otetv.privacy.' + k, v)

# Diagnostics
add('otetv.diagnostics.copy', 'Copy redacted details')
add('otetv.diagnostics.copied', 'Copied to clipboard')

# Runtime
for k, v in {
    'media_title': 'Video playback setup', 'browser_title': 'YouTube player setup',
    'media_body': 'Video playback needs an additional component. OteTV decodes video in a separate helper using the GStreamer media framework, which is not installed or is missing parts on this computer.',
    'browser_body': 'In-world YouTube needs the OteTV browser runtime: a sandboxed Chromium-based helper that hosts the official YouTube player. You can always open videos in your own browser instead.',
    'browser_sandbox_note': 'The runtime is only used with its sandbox enabled. A secure unsupported state is preferred over an unsandboxed browser.',
    'help': 'Installation help', 'check_again': 'Check again', 'open_folder': 'Open runtime folder', 'download': 'Download (%s)',
    'from_file': 'Install from file...', 'rollback': 'Roll back', 'uninstall': 'Uninstall', 'no_rollback': 'No previous version to roll back to.',
    'confirm_title': 'Install runtime?', 'confirm_body': 'Download %s (%s) and install it into %s? It is verified before use.',
    'installed': 'Installed %s', 'failed': 'Installation failed: %s', 'missing': 'missing'}.items():
    add('otetv.runtime.' + k, v)
for k, v in {'unknown': 'Not checked yet', 'probing': 'Checking...', 'available': 'GStreamer %s found',
             'missing': 'GStreamer not found', 'incompatible': 'Incompatible worker', 'error': 'Check failed'}.items():
    add('otetv.runtime.media_status.' + k, v)
for k, v in {'not_installed': 'Browser runtime not installed', 'installed': 'Installed: %s',
             'unsupported_platform': 'Not available on this platform'}.items():
    add('otetv.runtime.browser_status.' + k, v)
for k, v in {'idle': 'Idle %s', 'downloading': 'Downloading %s', 'verifying': 'Verifying %s', 'extracting': 'Extracting %s',
             'activating': 'Activating %s', 'done': 'Done %s', 'failed': 'Failed %s', 'cancelled': 'Cancelled %s'}.items():
    add('otetv.runtime.phase.' + k, v)

# Source modes
for k, v in {'in_world': 'In-world playback', 'open_in_browser': 'Open in browser', 'experimental': 'Experimental',
             'unavailable_platform': 'Unavailable on this platform', 'unavailable_server': 'Disabled by this server'}.items():
    add('otetv.mode.' + k, v)

# Actions (PlayerAction)
for k, v in {'power': 'Power', 'home': 'Home', 'back': 'Back', 'navigate': 'Navigate', 'select': 'Select', 'play_pause': 'Play/Pause',
             'stop': 'Stop', 'seek_back': 'Back 10 seconds', 'seek_forward': 'Forward 10 seconds', 'channel_prev': 'Previous channel',
             'channel_next': 'Next channel', 'captions': 'Captions', 'speaker_volume': 'TV speaker volume', 'local_volume': 'My volume',
             'local_mute': 'Mute for me', 'join': 'Join viewing', 'leave': 'Leave viewing', 'rejoin_position': 'Rejoin current position',
             'change_source': 'Change source', 'open_external': 'Open externally', 'settings': 'Settings', 'trust': 'Trusted players',
             'power_on': 'Turn on', 'power_off': 'Turn off'}.items():
    add('otetv.action.' + k, v)

# Disabled reasons
for k, v in {'already_joined': 'You are already watching.', 'external_disabled': 'External media is disabled in your settings.',
             'lease_held': 'Another player is controlling this TV right now.', 'live_no_pause': 'Live streams cannot be paused.',
             'live_no_seek': 'Live streams cannot be seeked.', 'no_captions': 'No captions for this video.',
             'no_channels': 'This server has no channels.', 'no_external': 'Nothing to open externally.',
             'no_permission': 'Only the owner or trusted players can change this TV.', 'no_source': 'Nothing is selected.',
             'not_diverged': 'Playback is in sync.', 'not_joined': 'Join viewing first.', 'not_powered': 'The TV is off.',
             'not_seekable': 'This source does not support seeking.', 'tv_unavailable': 'TV unavailable.',
             'unsupported': 'Not supported for this source.'}.items():
    add('otetv.disabled.' + k, v)

# Local states
for k, v in {'idle': 'Idle', 'not_joined': 'Not watching', 'needs_consent': 'Waiting for your OK', 'runtime_missing': 'Needs a component',
             'file_missing': 'File not on this computer', 'loading': 'Loading', 'buffering': 'Buffering...', 'playing': 'Playing',
             'paused': 'Paused', 'locally_paused': 'Paused here', 'waiting_for_gesture': 'Press Play', 'ended': 'Ended',
             'diverged': 'Out of sync', 'excess_session': 'Waiting', 'unsupported': 'Unsupported', 'error': 'Error'}.items():
    add('otetv.local_state.' + k, v)
for k, v in {'stopped': 'Stopped', 'paused': 'Paused', 'playing': 'Playing'}.items():
    add('otetv.play_state.' + k, v)

# Errors (section 7.4)
for k, v in {
    'none': 'No problem', 'runtime_missing': 'Video playback needs an additional component.',
    'runtime_incompatible': 'The installed playback component is incompatible with this OteTV version.',
    'local_file_absent': "This video is stored on another player's computer.",
    'local_file_mismatch': 'Your copy of this file is different from the shared one.',
    'unsupported_codec': 'This video uses a codec this computer cannot decode. WebM (VP8/VP9) and MP4 (H.264/AAC) work best.',
    'unsupported_container': 'This file format is not supported.', 'media_malformed': 'The video file is damaged or not a video.',
    'network': 'The video host could not be reached.', 'http_status': 'The video host refused the request.',
    'timeout': 'The video host took too long to answer.', 'policy_blocked_host': 'This address is blocked for your safety.',
    'not_media': 'That link is a web page, not a video file.', 'hls_encrypted': 'Encrypted streams are not supported.',
    'hls_unsupported': 'This stream format is not supported.', 'range_unsupported': 'This host does not allow seeking.',
    'youtube_embed_disabled': 'This video cannot play in embedded players.', 'youtube_unavailable': 'This video is unavailable or private.',
    'youtube_invalid': 'That YouTube video id is not valid.', 'youtube_html5': 'The player could not play this video here.',
    'youtube_identity': 'YouTube could not identify this player.', 'autoplay_blocked': 'Press Play to start watching.',
    'browser_unavailable': 'The YouTube player is not available on this computer.',
    'subscription_external': 'This service currently opens in your browser.',
    'worker_crash': 'Playback stopped unexpectedly.', 'worker_hung': 'Playback stopped responding.',
    'consent_required': 'Waiting for your OK to connect.', 'external_media_disabled': 'External media is disabled in your settings.',
    'too_many_sessions': 'Too many TVs are playing for you.', 'internal': 'Something went wrong.'}.items():
    add('otetv.error.' + k, v)

# Rejections
for k, v in {
    'none': 'OK', 'not_found': 'TV unavailable.', 'not_loaded': 'That TV is not loaded.', 'wrong_dimension': 'That TV is in another dimension.',
    'wrong_world': 'That TV is on another world.', 'out_of_range': 'You are too far from that TV.',
    'no_permission': 'Only the owner or trusted players can change this TV.', 'lease_held': 'Another player is controlling this TV.',
    'lease_held_by': '%s is controlling this TV.', 'stale_revision': 'The TV changed meanwhile; try again.',
    'rate_limited': 'Slow down a little.', 'invalid_argument': 'That request is not valid.', 'source_rejected': 'That source is not valid.',
    'source_not_allowed': 'This server does not allow that source.', 'youtube_disabled': 'This server has disabled YouTube.',
    'channel_missing': 'No such channel.', 'channel_disabled': 'That channel is disabled.', 'no_channels': 'There are no channels.',
    'not_powered': 'The TV is off.', 'no_source': 'Nothing is selected.', 'not_seekable': 'This source cannot be seeked.',
    'queue_full': 'The queue is full.', 'queue_empty': 'The queue is empty.', 'limit_reached': 'A limit was reached.',
    'incompatible_data': 'This TV was saved by a newer OteTV.', 'player_unknown': 'Unknown player.', 'too_fast': 'Too fast.',
    'internal': 'Something went wrong.'}.items():
    add('otetv.reject.' + k, v)

# Roles, redstone, presets, source types, provider statuses
for k, v in {'public': 'Public', 'viewer': 'Viewer', 'controller': 'Controller', 'editor': 'Editor', 'owner': 'Owner', 'operator': 'Operator'}.items():
    add('otetv.role.' + k, v)
for k, v in {'ignore': 'Ignore', 'power_when_high': 'Power when on', 'toggle_power_on_rising_edge': 'Toggle on pulse',
             'next_channel_on_rising_edge': 'Next channel on pulse'}.items():
    add('otetv.redstone.' + k, v)
for k, v in {'authentic': 'Authentic', 'clear': 'Clear'}.items():
    add('otetv.preset.' + k, v)
for k, v in {'bundled': 'Bundled demo', 'https_video': 'Video link', 'hls_clear': 'HLS stream', 'youtube': 'YouTube',
             'shared_local_asset': 'Shared local file', 'local_file': 'Private local file'}.items():
    add('otetv.source_type.' + k, v)
for k, v in {'untested': 'Untested', 'researching': 'Researching', 'external_only': 'Opens in your browser', 'blocked': 'Blocked',
             'experimental': 'Experimental', 'verified': 'Verified'}.items():
    add('otetv.provider_status.' + k, v)
for k, v in {'supported': 'Supported', 'missing_components': 'Missing components', 'unsupported': 'Not supported'}.items():
    add('otetv.codec.status.' + k, v)

# Validation messages
for k, v in {
    'empty': 'Enter a link.', 'unknown_type': 'Unknown source type.', 'not_shareable': 'That source is private.',
    'bad_url': 'That is not a valid link.', 'credentials_in_url': 'Links with a user name or password are not accepted.',
    'not_https': 'Only https links are accepted.', 'non_public_host': 'Private network addresses are not accepted here.',
    'bad_video_id': 'That is not a valid YouTube video id.', 'lookalike_host': 'That address only looks like YouTube.',
    'bad_start_time': 'That start time is not valid.', 'bad_asset_id': 'That asset id is not valid.',
    'bad_digest': 'That file fingerprint is not valid.', 'bad_duration': 'That duration is not valid.',
    'bad_label': 'That label is too long.', 'bad_captions_url': 'That captions link is not valid.',
    'unknown_bundled_asset': 'Unknown bundled video.', 'not_a_youtube_url': 'Use the YouTube tab for YouTube links.',
    'unsupported_youtube_form': 'Paste a link to a single YouTube video.', 'too_long': 'That is too long.'}.items():
    add('otetv.source_issue.' + k, v)
for k, v in {'empty': 'Empty link', 'too_long': 'Link too long', 'illegal_character': 'Link contains illegal characters',
             'malformed': 'Malformed link', 'unsupported_scheme': 'Only http(s) links are supported',
             'credentials': 'Links with credentials are not accepted', 'bad_host': 'Invalid host name',
             'ambiguous_numeric_host': 'Ambiguous numeric address', 'single_label_host': 'Host name must be fully qualified',
             'bad_port': 'Invalid port', 'zone_id': 'IPv6 zone ids are not accepted'}.items():
    add('otetv.url_rejected.' + k, v)
for k, v in {'public': 'public internet', 'loopback': 'this computer', 'private': 'a private network', 'unique_local': 'a private network',
             'carrier_grade_nat': 'a carrier network', 'link_local': 'the local link', 'cloud_metadata': 'cloud metadata',
             'multicast': 'multicast', 'broadcast': 'broadcast', 'unspecified': 'an unspecified address',
             'documentation': 'a documentation range', 'benchmarking': 'a benchmarking range', 'reserved': 'a reserved range'}.items():
    add('otetv.address_category.' + k, v)

# Commands
for k, v in {
    'help.help': '/otetv help - this list', 'help.info': '/otetv info <pos> - show a TV',
    'help.power': '/otetv power <pos> <on|off>', 'help.channel': '/otetv channel <pos> <number>',
    'help.play': '/otetv play|pause|stop <pos>', 'help.seek': '/otetv seek <pos> <seconds>',
    'help.trust': '/otetv trust <pos> <player> <viewer|controller|editor>', 'help.untrust': '/otetv untrust <pos> <player>',
    'help.reset': '/otetv reset <pos> - owner or operator', 'help.reload': '/otetv reload - rebuild the channel catalog (operators)',
    'help.diagnostics': '/otetv diagnostics - server and your local details', 'help.channels': '/otetv channels list|add|remove (operators)',
    'help.hosts': '/otetv hosts list|add|remove (operators)',
    'no_tv': 'No loaded television there.', 'done': 'Done: %s', 'bad_role': 'Role must be viewer, controller or editor.',
    'reloaded': 'Channel catalog rebuilt: %s channel(s), %s problem(s).', 'no_channels': 'No channels.',
    'channel_added': 'Channel %s added.', 'channel_removed': 'Channel %s removed.',
    'channel_not_admin': 'Channel %s is not an operator channel (datapack channels are edited in the datapack).',
    'hosts': 'Source policy %s; approved hosts: %s', 'host_added': 'Approved host %s', 'host_removed': 'Removed host %s',
    'bad_host': 'Not a valid host name.',
    'info.header': '%s "%s"', 'info.identity': 'Identity %s, owner %s', 'info.state': 'Power %s, %s at %ss, channel %s',
    'info.source': 'Source: %s', 'info.session': 'Revision %s, generation %s, viewers %s, controller %s, trusted %s',
    'info.locked': 'Saved by a newer OteTV version: read-only.'}.items():
    add('otetv.command.' + k, v)


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(dict(sorted(L.items())), f, indent=2, ensure_ascii=False)
        f.write('\n')
    print('wrote %d keys to %s' % (len(L), os.path.relpath(OUT)))


if __name__ == '__main__':
    main()
