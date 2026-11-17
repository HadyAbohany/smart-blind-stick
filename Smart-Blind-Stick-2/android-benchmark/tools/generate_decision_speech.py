#!/usr/bin/env python3
"""Regenerate only the six new bundled decision prefixes using the original macOS Majed voice."""
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/assets/decision-speech.json"


def main():
    if not all(shutil.which(tool) for tool in ("say", "ffmpeg", "ffprobe")):
        raise SystemExit("Regeneration requires macOS say (Majed voice) and ffmpeg. Bundled files need neither.")
    phrases = json.loads(MANIFEST.read_text())
    with tempfile.TemporaryDirectory(prefix="sbs-decision-speech-") as temporary:
        wav = Path(temporary) / "phrase.aiff"
        for name, phrase in phrases.items():
            subprocess.run(["say", "-v", "Majed", "-r", "185", "-o", str(wav), phrase], check=True)
            output = ROOT / "app/src/main/assets/speech" / f"{name}.m4a"
            candidate = Path(temporary) / f"{name}.m4a"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-ac", "1",
                            "-ar", "24000", "-c:a", "aac", "-b:a", "40k", str(candidate)], check=True)
            probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                    "-of", "json", str(candidate)], capture_output=True, text=True, check=True)
            duration = float(json.loads(probe.stdout).get("format", {}).get("duration", 0))
            if duration < .2 or candidate.stat().st_size < 1_000:
                raise RuntimeError("Speech synthesis returned empty audio. On macOS it needs speech-service access.")
            shutil.copyfile(candidate, output)
            print(f"Generated {name}: {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
