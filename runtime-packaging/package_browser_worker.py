#!/usr/bin/env python3
"""Packages the built native browser worker as an OteTV runtime bundle and writes its manifest entry.

    cmake -S browser-worker -B browser-worker/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DOTETV_WORKER_VERSION=<mod_version>
    cmake --build browser-worker/build
    python3 runtime-packaging/package_browser_worker.py [--url https://host/path/<file>]

Output (git-ignored): runtime-packaging/out/<bundle>.zip and runtime-packaging/out/manifest.json. The zip is
deterministic (sorted entries, fixed timestamps), libcef.so is stripped of debug symbols, and only the en-US locale is
included because the worker forces that locale. The manifest's URL is where a maintainer intends to host the zip; this
script never uploads anything. Players can also install the zip directly through "Install from file" with that
manifest configured as runtimeManifest (see docs/admin-guide.md).
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
BUILD = os.path.join(ROOT, 'browser-worker', 'build', 'Release')
CEF = os.path.join(ROOT, 'browser-worker', 'third_party', 'cef')
OUT = os.path.join(ROOT, 'runtime-packaging', 'out')
EXECUTABLE = 'otetv-browser-worker'
RUNTIME_FILES = [
    EXECUTABLE, 'libcef.so', 'libvk_swiftshader.so', 'libvulkan.so.1', 'vk_swiftshader_icd.json', 'v8_context_snapshot.bin',
    'icudtl.dat', 'resources.pak', 'chrome_100_percent.pak', 'chrome_200_percent.pak', 'locales/en-US.pak',
]
STRIP = {EXECUTABLE, 'libcef.so', 'libvk_swiftshader.so'}


def properties():
    out = {}
    with open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                out[k.strip()] = v.strip()
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', help='https URL the zip will be published at (written into the manifest only)')
    args = parser.parse_args()
    props = properties()
    cef_short = props['cef_version'].split('+')[0]
    version = '%s+cef%s' % (props['mod_version'], cef_short)
    bundle_id = 'otetv-browser'
    name = '%s-linux-x86_64-%s.zip' % (bundle_id, version)
    staging = os.path.join(OUT, 'staging')
    if os.path.isdir(staging):
        shutil.rmtree(staging)
    os.makedirs(os.path.join(staging, 'locales'))
    os.makedirs(os.path.join(staging, 'licenses'))
    for rel in RUNTIME_FILES:
        src = os.path.join(BUILD, rel)
        if not os.path.isfile(src):
            sys.exit('missing %s: build the worker first' % os.path.relpath(src, ROOT))
        dst = os.path.join(staging, rel)
        if rel in STRIP:
            subprocess.run(['strip', '--strip-unneeded', '-o', dst, src], check=True)
        else:
            shutil.copyfile(src, dst)
    licenses = {
        'licenses/CEF-LICENSE.txt': os.path.join(CEF, 'LICENSE.txt'),
        'licenses/CHROMIUM-CREDITS.html': os.path.join(CEF, 'CREDITS.html'),
        'licenses/OTETV-LICENSE.txt': os.path.join(ROOT, 'LICENSE'),
        'licenses/THIRD_PARTY_NOTICES.md': os.path.join(ROOT, 'THIRD_PARTY_NOTICES.md'),
    }
    for rel, src in licenses.items():
        shutil.copyfile(src, os.path.join(staging, rel))
    entries = sorted(RUNTIME_FILES + list(licenses))
    os.makedirs(OUT, exist_ok=True)
    zip_path = os.path.join(OUT, name)
    unpacked = 0
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in entries:
            path = os.path.join(staging, rel)
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if rel == EXECUTABLE else 0o644) << 16
            with open(path, 'rb') as f:
                data = f.read()
            unpacked += len(data)
            z.writestr(info, data)
    shutil.rmtree(staging)
    url = args.url or 'https://github.com/otectus/otetv/releases/download/runtime-%s/%s' % (version, name)
    if not url.startswith('https://'):
        sys.exit('bundle URLs must be https')
    bundle = {
        'id': bundle_id,
        'version': version,
        'os': 'linux',
        'arch': 'x86_64',
        'urls': [url],
        'sha256': sha256(zip_path),
        'size': os.path.getsize(zip_path),
        'unpackedSize': unpacked,
        'protocol': {'min': 1, 'max': 1},
        'entrypoint': EXECUTABLE,
        'executables': [EXECUTABLE],
        'licenses': sorted(licenses),
        'minimumSystem': 'Linux x86_64 with unprivileged user namespaces (Chromium namespace sandbox)',
        'notice': 'Chromium Embedded Framework %s; hosts the official YouTube embedded player only.' % cef_short,
    }
    with open(os.path.join(OUT, 'manifest.json'), 'w', encoding='utf-8') as f:
        json.dump({'schemaVersion': 1, 'bundles': [bundle]}, f, indent=2)
        f.write('\n')
    print(os.path.relpath(zip_path, ROOT), '%.1f MiB' % (bundle['size'] / 1048576.0), 'unpacked %.1f MiB' % (unpacked / 1048576.0))
    print('sha256', bundle['sha256'])


if __name__ == '__main__':
    main()
