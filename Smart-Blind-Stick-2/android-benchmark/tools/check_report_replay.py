#!/usr/bin/env python3
"""Round-trip the exported report schema through CSV and the production JVM policy."""
import csv
import json
import subprocess
import sys
import tempfile
from pathlib import Path

tools = Path(__file__).resolve().parent
classes, java = sys.argv[1:]
with tempfile.TemporaryDirectory(prefix="sbs-report-replay-") as temporary:
    temporary = Path(temporary)
    report_path = temporary / "report.json"
    csv_path = temporary / "replay.csv"
    tsv_path = temporary / "decisions.tsv"
    report = {
        "mode": "live_camera", "decision_mode": "SMART", "dropped_replay_frames": 7,
        "decision_replay": [
            {"now_ms": at, "age_ms": 30, "target_class": -1, "still_image": False,
             "boxes": [[0, .9, .41, .4, .59, .85]]} for at in (1000, 1100, 1200)
        ],
    }
    report_path.write_text(json.dumps(report))
    result = subprocess.run([sys.executable, str(tools / "report_to_replay.py"), str(report_path), str(csv_path)],
                            capture_output=True, text=True, check=True)
    assert "dropped 7 frames" in result.stdout, "Report truncation must be visible"
    subprocess.run([java, "-cp", classes, "eg.sbs.benchmark.DecisionReplay", str(csv_path), str(tsv_path)],
                   capture_output=True, text=True, check=True)
    with tsv_path.open() as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    assert len(rows) == 3 and rows[0]["reason"] == "NONE"
    assert rows[-1]["reason"] == "DESCRIPTION" and rows[-1]["eligible_announcement"] == "DESCRIPTION"
    for invalid in ({**report, "mode": "stored_jpeg"}, {**report, "decision_replay": []}):
        report_path.write_text(json.dumps(invalid))
        result = subprocess.run([sys.executable, str(tools / "report_to_replay.py"), str(report_path), str(csv_path)],
                                capture_output=True, text=True)
        assert result.returncode != 0, "JPEG or old reports cannot masquerade as temporal evidence"
print("Report replay round-trip and invalid-report rejection passed")
