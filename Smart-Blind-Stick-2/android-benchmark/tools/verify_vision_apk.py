#!/usr/bin/env python3
"""Check the lab APK identity, signature, manifest, model pack and native alignment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--apk', type=Path, default=ROOT / 'dist/SBS-Vision-Lab-v0.4.0-arm64-debug.apk')
    args = parser.parse_args()
    build = args.sdk / 'build-tools/35.0.0'
    llvm, = (args.sdk / 'ndk/28.2.13676358/toolchains/llvm/prebuilt').glob('*/bin')
    def run(*command):
        return subprocess.check_output([str(c) for c in command], text=True)
    badging = run(build / 'aapt', 'dump', 'badging', args.apk)
    permissions = run(build / 'aapt', 'dump', 'permissions', args.apk)
    assert "name='eg.sbs.visionlab'" in badging and "versionName='0.4.0-lab'" in badging
    assert 'eg.sbs.benchmark.LabActivity' in badging
    assert 'android.permission.INTERNET' not in permissions
    assert "sdkVersion:'33'" in badging
    run(build / 'apksigner', 'verify', '--verbose', args.apk)
    run(build / 'zipalign', '-c', '-P', '16', '4', args.apk)
    expected = json.loads((ROOT / 'vision-lab/src/main/assets/vision-models.json').read_text())
    alignments = {}
    with tempfile.TemporaryDirectory(prefix='sbs-apk-') as directory, zipfile.ZipFile(args.apk) as archive:
        assert json.loads(archive.read('assets/vision-models.json')) == expected
        assert len(json.loads(archive.read('assets/yoloe-labels.json'))['labels']) == 140
        archive.read('assets/THIRD_PARTY_NOTICES.txt')
        libraries = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
        assert libraries and all(n.startswith('lib/arm64-v8a/') for n in libraries)
        for name in libraries:
            library = Path(directory) / Path(name).name
            library.write_bytes(archive.read(name))
            headers = run(llvm / 'llvm-readelf', '-lW', library)
            values = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith('LOAD')]
            assert values and min(values) >= 16384, name + ' is not 16KiB aligned'
            alignments[library.name] = min(values)
            if library.name == 'libsbs_qwen.so':
                symbols = run(llvm / 'llvm-nm', '-D', library)
                for method in ['begin', 'cancel', 'describe']:
                    assert 'Java_eg_sbs_benchmark_QwenNative_' + method in symbols
    result = dict(apk=args.apk.name, bytes=args.apk.stat().st_size,
                  sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                  package='eg.sbs.visionlab', native_abi='arm64-v8a', offline_manifest=True,
                  signature_verified=True, zip_16k_alignment=True, elf_load_alignment=alignments,
                  model_manifest_matches_apk=True, native_jni_entrypoints_verified=True)
    (ROOT / 'results/vision-lab-apk-validation.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
