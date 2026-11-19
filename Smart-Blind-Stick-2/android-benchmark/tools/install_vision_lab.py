#!/usr/bin/env python3
"""Install the separate lab APK and verified model pack on one explicitly selected USB phone."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from prepare_vision_lab import verify_pack

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'eg.sbs.visionlab'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True, help='Explicit USB device serial from adb devices')
    parser.add_argument('--apk', type=Path, default=ROOT / 'dist/SBS-Vision-Lab-v0.4.0-arm64-debug.apk')
    args = parser.parse_args()
    verify_pack()
    if not args.apk.is_file():
        parser.error('Build/copy the lab APK first')
    def adb(*parts, capture=False):
        return subprocess.run([args.adb, '-s', args.serial, *parts], check=True, text=True,
                              stdout=subprocess.PIPE if capture else None).stdout
    # Push into this separate app's external files. The original
    # eg.sbs.benchmark package is untouched.
    adb('install', '-r', str(args.apk))
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/eg.sbs.benchmark.LabActivity')
    time.sleep(2)
    folder = '/sdcard/Android/data/' + PACKAGE + '/files/models'
    adb('shell', 'mkdir', '-p', folder)
    for entry in json.loads((ROOT / 'vision-lab/src/main/assets/vision-models.json').read_text())['models']:
        destination = folder + '/' + entry['file']
        adb('push', str(ROOT / 'model-packs' / entry['file']), destination + '.partial')
        remote = adb('shell', 'sha256sum', destination + '.partial', capture=True).split()[0]
        if remote != entry['sha256']:
            raise ValueError('Phone model checksum mismatch: ' + entry['file'])
        adb('shell', 'mv', destination + '.partial', destination)
    print('Installed SBS Vision Lab beside the existing app; all four model files verified on phone.')
    print('Open the lab app and tap model status before the five comparison modes.')


if __name__ == '__main__':
    main()
