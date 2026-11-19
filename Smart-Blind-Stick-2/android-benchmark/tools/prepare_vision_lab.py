#!/usr/bin/env python3
"""Fetch pinned, SHA-verified public inputs; export/check models or verify the final offline pack."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'vision-lab/dependencies.lock.json').read_text())
MANIFEST = ROOT / 'vision-lab/src/main/assets/vision-models.json'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def matches(path, entry):
    return path.is_file() and path.stat().st_size == entry['bytes'] and sha(path) == entry['sha256']


def download(entry, destination):
    if matches(destination, entry):
        print('Verified', entry['file'], flush=True)
        return
    import requests
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + '.partial')
    try:
        with requests.get(entry['url'], stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            with partial.open('wb') as output:
                total = 0
                for block in response.iter_content(1024 * 1024):
                    total += len(block)
                    if total > entry['bytes']:
                        raise ValueError('Download larger than pinned size')
                    output.write(block)
        if not matches(partial, entry):
            raise ValueError('Checksum/size mismatch: ' + entry['file'])
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)
    print('Downloaded and verified', entry['file'], flush=True)


def verify_pack():
    for entry in json.loads(MANIFEST.read_text())['models']:
        if not matches(ROOT / 'model-packs' / entry['file'], entry):
            raise ValueError('Missing or mismatched offline model: ' + entry['file'])
        print('Verified final model', entry['role'], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=ROOT / '.vision-deps')
    parser.add_argument('--verify-only', action='store_true', help='No network; check final pack only')
    parser.add_argument('--source-only', action='store_true', help='Prepare only pinned native source for building an existing pack')
    parser.add_argument('--export', action='store_true', help='Also export/check YOLOE and metric depth')
    args = parser.parse_args()
    if args.verify_only:
        verify_pack()
        return
    cache = args.cache.resolve()
    for entry in LOCK['downloads']:
        if args.source_only and entry['file'] != 'llama-source.tar.gz':
            continue
        path = (ROOT / 'model-packs' if entry['model_pack'] else cache) / entry['file']
        download(entry, path)
    source = cache / 'llama.cpp'
    marker = source / '.sbs-pinned-commit'
    if not marker.is_file() or marker.read_text().strip() != LOCK['llama_commit']:
        if source.exists():
            raise ValueError('Existing native source has no matching marker; choose a fresh --cache directory')
        staging = cache / 'extracting-llama'
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir()
        with tarfile.open(cache / 'llama-source.tar.gz') as archive:
            archive.extractall(staging, filter='data')
        extracted, = staging.iterdir()
        extracted.rename(source)
        staging.rmdir()
        marker.write_text(LOCK['llama_commit'] + '\n')
    print('Native source:', source)
    if args.export:
        # The text encoder is an export-time input only. Ultralytics resolves it
        # from the checkpoint working directory; it is never copied to the phone.
        environment = os.environ.copy()
        environment['HF_HOME'] = str(cache / 'hf-cache')
        environment['YOLO_CONFIG_DIR'] = str(cache / 'ultralytics-config')
        subprocess.run([sys.executable, str(ROOT / 'tools/export_vision_models.py'),
                        '--yolo', str(cache / 'yoloe-26n-seg.pt'),
                        '--depth', str(cache / 'depth-hf'), '--reference', str(cache / 'reference/bus.jpg')],
                       cwd=cache, env=environment, check=True)
        # Exported bytes can change with tools. The app only accepts the reviewed
        # manifest: a deliberate new export needs review and a manifest update.
        verify_pack()


if __name__ == '__main__':
    main()
