#!/usr/bin/env python3
"""Convert a v0.3 camera JSON export into CSV accepted by DecisionReplay."""
import argparse
import csv
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("report", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
report = json.loads(args.report.read_text())
if report.get("mode") != "live_camera":
    raise SystemExit("Only camera reports contain temporal evidence. JPEG repetitions are not a video.")
frames = report.get("decision_replay", [])
if not frames:
    raise SystemExit("This report has no decision replay inputs. Export a report from v0.3 or newer.")
with args.output.open("w", newline="") as stream:
    writer = csv.writer(stream, lineterminator="\n")
    writer.writerow("scenario,mode,target_class,now_ms,age_ms,class_id,confidence,x1,y1,x2,y2,expected_reason,expected_announcement".split(","))
    for frame in frames:
        if frame.get("still_image"):
            raise SystemExit("A live camera report unexpectedly contains still images.")
        for box in frame["boxes"] or [[""] * 6]:
            writer.writerow(["phone_replay", report["decision_mode"], frame["target_class"],
                             frame["now_ms"], frame["age_ms"], *box, "", ""])
print(f"Wrote {len(frames)} frames; report dropped {report.get('dropped_replay_frames', 0)} frames at its memory limit.")
