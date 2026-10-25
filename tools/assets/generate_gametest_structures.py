#!/usr/bin/env python3
"""Writes the structure templates used by OteTV's Forge GameTests.

    python3 tools/assets/generate_gametest_structures.py

The only template is otetv:platform, a 17 x 6 x 17 smooth-stone floor with air above. It is wider than a chunk, so
every test area contains a chunk boundary along both horizontal axes. Uncompressed structure NBT is written with a
small standard-library encoder and then gzipped, exactly as vanilla stores structure templates.
"""
import gzip
import os
import struct

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'src', 'gameTest', 'resources', 'data', 'otetv', 'structures')
DATA_VERSION = 3465  # Minecraft 1.20.1

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def name(n):
    b = n.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def payload(tag_type, value):
    if tag_type == TAG_INT:
        return struct.pack('>i', value)
    if tag_type == TAG_STRING:
        return name(value)
    if tag_type == TAG_LIST:
        element_type, items = value
        return struct.pack('>bi', element_type if items else TAG_END, len(items)) + b''.join(payload(element_type, i) for i in items)
    if tag_type == TAG_COMPOUND:
        return b''.join(struct.pack('>b', t) + name(k) + payload(t, v) for k, (t, v) in value.items()) + struct.pack('>b', TAG_END)
    raise ValueError(tag_type)


def platform(width=17, height=6, depth=17):
    blocks = [{'pos': (TAG_LIST, (TAG_INT, [x, 0, z])), 'state': (TAG_INT, 0)} for x in range(width) for z in range(depth)]
    root = {
        'DataVersion': (TAG_INT, DATA_VERSION),
        'size': (TAG_LIST, (TAG_INT, [width, height, depth])),
        'palette': (TAG_LIST, (TAG_COMPOUND, [{'Name': (TAG_STRING, 'minecraft:smooth_stone')}])),
        'blocks': (TAG_LIST, (TAG_COMPOUND, blocks)),
        'entities': (TAG_LIST, (TAG_COMPOUND, [])),
    }
    return struct.pack('>b', TAG_COMPOUND) + name('') + payload(TAG_COMPOUND, root)


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, 'platform.nbt')
    # mtime=0 keeps the gzip header, and so the committed file, reproducible.
    with open(path, 'wb') as f:
        f.write(gzip.compress(platform(), mtime=0))
    print(os.path.relpath(path, ROOT), os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    main()
