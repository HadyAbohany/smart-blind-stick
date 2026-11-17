#!/usr/bin/env python3
"""Check every bundled phrase and decode the new prefixes to reject silent/invalid audio."""
import array
import json
import math
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
manifest = json.loads((ASSETS / "decision-speech.json").read_text())
source = (ROOT / "app/src/main/java/eg/sbs/benchmark/DecisionSpeech.java").read_text()
expected = dict(re.findall(r'case "([a-z_]+)" -> "([^"]+)";', source))
assert manifest == expected, "Speech text and Java decision wording differ"
assert len((ASSETS / "coco-ar.txt").read_text().splitlines()) == 80
names = [f"{c}_{d}" for c in range(80) for d in range(3)] + ["test", "unavailable"] + list(manifest)
for name in names:
    path = ASSETS / "speech" / f"{name}.m4a"
    assert path.is_file() and path.stat().st_size >= 1_000, f"Missing or empty: {name}"
durations = {}
for name in manifest:
    result = subprocess.run(["ffmpeg", "-v", "error", "-i", str(ASSETS / "speech" / f"{name}.m4a"),
                             "-f", "f32le", "-ac", "1", "-ar", "24000", "-"],
                            capture_output=True, check=True)
    samples = array.array("f", result.stdout)
    duration = len(samples) / 24_000
    rms = math.sqrt(sum(x * x for x in samples) / max(1, len(samples)))
    assert duration >= .2 and rms > .0001, f"Silent or corrupt speech: {name}"
    durations[name] = round(duration, 3)
print(json.dumps({"bundled_assets_checked": len(names), "new_prefix_audio_decoded": len(manifest),
                  "new_prefix_duration_seconds": durations}, ensure_ascii=False))
