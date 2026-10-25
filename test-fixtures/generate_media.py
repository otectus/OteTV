#!/usr/bin/env python3
"""Generates OteTV's original media: the bundled 60-second demo and the worker test fixtures.

All media is synthesized here with ffmpeg test sources; nothing is downloaded or copied, so it is redistributable
under the project licence. Usage:

    python3 test-fixtures/generate_media.py            # demo + fixtures
    python3 test-fixtures/generate_media.py --demo     # only the bundled demo

The demo checks picture and sound end to end: colour bars, motion, frame numbers and timecode, a white flash with a
1 kHz beep at every whole second (lip-sync marker), left-only and right-only tones, and timed WebVTT captions.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DEMO_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'otetv_media')
FIXTURES = os.path.join(ROOT, 'test-fixtures', 'media')
FONT = next((p for p in ['/usr/share/fonts/liberation/LiberationSans-Bold.ttf', '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf'] if os.path.exists(p)), None)
FFMPEG = shutil.which('ffmpeg')

# Left channel: per-second sync beep, plus a 440 Hz tone at 10-12 s and 40-42 s.
# Right channel: per-second sync beep, plus an 880 Hz tone at 14-16 s and 44-46 s.
BEEP = "0.45*sin(2*PI*1000*t)*lt(mod(t,1),0.05)"
LEFT = BEEP + "+0.25*sin(2*PI*440*t)*(between(t,10,12)+between(t,40,42))"
RIGHT = BEEP + "+0.25*sin(2*PI*880*t)*(between(t,14,16)+between(t,44,46))"


def run(args):
    print('ffmpeg', ' '.join(a if len(a) < 80 else a[:77] + '...' for a in args[1:]))
    subprocess.run([FFMPEG, '-y', '-loglevel', 'error'] + args, check=True)


def text(t, x, y, size, extra=''):
    font = ':fontfile=' + FONT if FONT else ''
    return ("drawtext=text='%s'%s:x=%s:y=%s:fontsize=%d:fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=6%s"
            % (t, font, x, y, size, extra))


def demo():
    os.makedirs(DEMO_DIR, exist_ok=True)
    vf = ','.join([
        "drawbox=x=0:y=240:w=640:h=120:color=0x1c2840@1:t=fill",
        "drawbox=x='mod(t*160,700)-60':y=262:w=56:h=56:color=0xF2C14E@1:t=fill",
        "drawbox=enable='lt(mod(t,1),0.067)':x=528:y=262:w=96:h=76:color=white@1:t=fill",
        text('OteTV demo - original test signal', 16, 14, 24),
        text('frame %{frame_num}', 16, 52, 20, ':start_number=0'),
        text('%{pts\\:hms}', 470, 52, 20),
        text('LEFT', 24, 300, 26, ":enable='between(t,10,12)+between(t,40,42)'"),
        text('RIGHT', 260, 300, 26, ":enable='between(t,14,16)+between(t,44,46)'"),
    ])
    sources = ['-f', 'lavfi', '-i', 'smptehdbars=size=640x360:rate=30:duration=60',
               '-f', 'lavfi', '-i', "aevalsrc=exprs='%s|%s':s=48000:d=60" % (LEFT, RIGHT), '-vf', vf]
    out = os.path.join(DEMO_DIR, 'welcome.webm')
    run(sources + ['-c:v', 'libvpx', '-b:v', '420k', '-crf', '14', '-auto-alt-ref', '0', '-g', '60', '-deadline', 'good',
                   '-c:a', 'libvorbis', '-q:a', '3', '-map_metadata', '-1', '-fflags', '+bitexact', '-shortest', out])
    # The same clip as Theora/Vorbis Ogg, for runtimes with only GStreamer's base plugins (no Matroska demuxer).
    ogv = os.path.join(DEMO_DIR, 'welcome.ogv')
    run(sources + ['-c:v', 'libtheora', '-q:v', '5', '-g', '60', '-c:a', 'libvorbis', '-q:a', '3', '-map_metadata', '-1',
                   '-fflags', '+bitexact', '-shortest', ogv])
    cues = [
        (0, 5, 'Welcome to OteTV.'),
        (5, 10, 'This original clip checks picture and sound.'),
        (10, 12, 'Tone in the LEFT speaker.'),
        (14, 16, 'Tone in the RIGHT speaker.'),
        (16, 22, 'Every second: a white flash and a short beep.\nThey should line up exactly.'),
        (22, 30, 'Use the remote to pause, seek and change channels.'),
        (30, 38, 'Everyone watching this TV shares one timeline.'),
        (40, 42, 'LEFT speaker again.'),
        (44, 46, 'RIGHT speaker again.'),
        (46, 54, 'Captions are personal: each viewer chooses.'),
        (54, 60, 'Thanks for watching OteTV.'),
    ]

    def ts(s):
        return '%02d:%02d:%02d.000' % (s // 3600, (s % 3600) // 60, s % 60)

    with open(os.path.join(DEMO_DIR, 'welcome.vtt'), 'w', encoding='utf-8') as f:
        f.write('WEBVTT - OteTV demo captions\n\n')
        for i, (a, b, t) in enumerate(cues, 1):
            f.write('%d\n%s --> %s\n%s\n\n' % (i, ts(a), ts(b), t))
    for f in (out, ogv):
        print('demo:', os.path.relpath(f, ROOT), os.path.getsize(f), 'bytes')


def fixtures():
    os.makedirs(FIXTURES, exist_ok=True)
    src = ['-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25:duration=10',
           '-f', 'lavfi', '-i', "aevalsrc=exprs='%s|%s':s=48000:d=10" % (BEEP, BEEP)]
    run(src + ['-c:v', 'libvpx', '-b:v', '200k', '-c:a', 'libvorbis', '-q:a', '2', '-shortest', os.path.join(FIXTURES, 'vp8_vorbis.webm')])
    run(src + ['-c:v', 'libvpx-vp9', '-b:v', '200k', '-row-mt', '1', '-c:a', 'libopus', '-b:a', '64k', '-shortest', os.path.join(FIXTURES, 'vp9_opus.webm')])
    run(src + ['-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart',
               '-shortest', os.path.join(FIXTURES, 'h264_aac.mp4')])
    run(src + ['-c:v', 'libtheora', '-q:v', '5', '-c:a', 'libvorbis', '-q:a', '2', '-shortest', os.path.join(FIXTURES, 'theora_vorbis.ogv')])
    run(['-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25:duration=6', '-c:v', 'libvpx', '-b:v', '150k', '-an',
         os.path.join(FIXTURES, 'silent_vp8.webm')])
    run(['-f', 'lavfi', '-i', "aevalsrc=exprs='%s|%s':s=48000:d=6" % (BEEP, BEEP), '-c:a', 'libopus', '-b:a', '64k',
         os.path.join(FIXTURES, 'audio_only.opus')])
    # Malformed: a valid WebM header followed by garbage.
    with open(os.path.join(FIXTURES, 'vp8_vorbis.webm'), 'rb') as f:
        head = f.read(4096)
    with open(os.path.join(FIXTURES, 'malformed.webm'), 'wb') as f:
        f.write(head + bytes((i * 37 + 11) % 256 for i in range(20000)))
    # Clear HLS VOD with MPEG-TS segments.
    hls = os.path.join(FIXTURES, 'hls')
    os.makedirs(hls, exist_ok=True)
    run(['-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=25:duration=12', '-f', 'lavfi', '-i', "aevalsrc=exprs='%s|%s':s=48000:d=12" % (BEEP, BEEP),
         '-c:v', 'libx264', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-g', '25', '-c:a', 'aac', '-b:a', '64k', '-shortest',
         '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod', '-hls_segment_filename', os.path.join(hls, 'seg%02d.ts'),
         os.path.join(hls, 'index.m3u8')])
    with open(os.path.join(FIXTURES, 'captions.srt'), 'w', encoding='utf-8') as f:
        f.write('1\n00:00:00,500 --> 00:00:03,000\nFixture caption one\n\n2\n00:00:03,500 --> 00:00:06,000\n<i>Fixture</i> caption two\n')
    with open(os.path.join(FIXTURES, 'encrypted.m3u8'), 'w') as f:
        f.write('#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.invalid/k"\n#EXTINF:4.0,\nseg00.ts\n#EXT-X-ENDLIST\n')
    print('fixtures:', os.path.relpath(FIXTURES, ROOT))


if __name__ == '__main__':
    if not FFMPEG:
        sys.exit('ffmpeg is required')
    demo()
    if '--demo' not in sys.argv:
        fixtures()
