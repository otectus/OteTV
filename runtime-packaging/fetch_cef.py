#!/usr/bin/env python3
"""Downloads the pinned CEF binary distribution for the native browser worker and verifies it before extraction.

    python3 runtime-packaging/fetch_cef.py

The version and SHA-1 come from gradle.properties (cef_version, cef_linux64_minimal_sha1). The archive is fetched over
HTTPS from the official CEF build CDN into browser-worker/third_party/download/, checked against the pinned SHA-1, and
extracted with the tarfile "data" filter (no absolute paths, traversal, devices or links escaping the tree) into
browser-worker/third_party/cef/. Both directories are git-ignored.
"""
import hashlib
import os
import shutil
import sys
import tarfile
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
THIRD_PARTY = os.path.join(ROOT, 'browser-worker', 'third_party')
CDN = 'https://cef-builds.spotifycdn.com/'


def properties():
    out = {}
    with open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                out[k.strip()] = v.strip()
    return out


def main():
    props = properties()
    version = props['cef_version']
    expected = props['cef_linux64_minimal_sha1'].lower()
    name = 'cef_binary_%s_linux64_minimal.tar.bz2' % version
    download = os.path.join(THIRD_PARTY, 'download')
    os.makedirs(download, exist_ok=True)
    archive = os.path.join(download, 'cef_minimal.tar.bz2')
    if not os.path.exists(archive):
        url = CDN + urllib.parse.quote(name)
        print('downloading', url)
        with urllib.request.urlopen(url, timeout=60) as r, open(archive + '.part', 'wb') as f:
            shutil.copyfileobj(r, f, 1 << 20)
        os.replace(archive + '.part', archive)
    sha1 = hashlib.sha1()
    with open(archive, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            sha1.update(block)
    if sha1.hexdigest() != expected:
        sys.exit('checksum mismatch: %s != %s (archive kept for inspection)' % (sha1.hexdigest(), expected))
    target = os.path.join(THIRD_PARTY, 'cef')
    if os.path.isdir(target):
        shutil.rmtree(target)
    staging = os.path.join(THIRD_PARTY, 'cef.extract')
    if os.path.isdir(staging):
        shutil.rmtree(staging)
    os.makedirs(staging)
    with tarfile.open(archive, 'r:bz2') as tar:
        tar.extractall(staging, filter='data')
    entries = os.listdir(staging)
    if len(entries) != 1:
        sys.exit('unexpected archive layout: %s' % entries)
    os.replace(os.path.join(staging, entries[0]), target)
    os.rmdir(staging)
    print('CEF', version, 'ready in', os.path.relpath(target, ROOT))


if __name__ == '__main__':
    main()
