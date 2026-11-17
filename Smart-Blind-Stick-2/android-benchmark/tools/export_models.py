#!/usr/bin/env python3
"""Export fixed NCHW FP32 models and verify each graph and example prediction."""
import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import ultralytics
from PIL import Image, ImageOps
from ultralytics import YOLO


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_model(path, size, image_path, names):
    onnx.checker.check_model(str(path))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(path), sess_options=options,
                                   providers=["CPUExecutionProvider"])
    inputs, outputs = session.get_inputs(), session.get_outputs()
    assert len(inputs) == len(outputs) == 1
    assert inputs[0].shape == [1, 3, size, size] and inputs[0].type == "tensor(float)"
    assert outputs[0].shape == [1, 300, 6] and outputs[0].type == "tensor(float)"
    with Image.open(image_path) as source:
        image = ImageOps.exif_transpose(source).convert("RGB")
    scale = min(size / image.width, size / image.height)
    width, height = round(image.width * scale), round(image.height * scale)
    left, top = (size - width) // 2, (size - height) // 2
    letterbox = Image.new("RGB", (size, size), (114, 114, 114))
    letterbox.paste(image.resize((width, height), Image.Resampling.BILINEAR), (left, top))
    tensor = np.ascontiguousarray(np.asarray(letterbox).transpose(2, 0, 1)[None], dtype=np.float32) / 255
    rows = session.run(None, {inputs[0].name: tensor})[0][0]
    assert np.isfinite(rows).all()
    assert ((0 <= rows[:, 4]) & (rows[:, 4] <= 1)).all()
    assert np.equal(rows[:, 5], rows[:, 5].astype(int)).all()
    assert ((0 <= rows[:, 5]) & (rows[:, 5] < len(names))).all()
    results = []
    for x1, y1, x2, y2, confidence, label in rows[rows[:, 4] >= .25]:
        box = np.clip(
            [(x1 - left) / width, (y1 - top) / height,
             (x2 - left) / width, (y2 - top) / height], 0, 1)
        if box[2] > box[0] and box[3] > box[1]:
            results.append({"class": names[int(label)], "confidence": float(confidence),
                            "box_xyxy_normalized": box.tolist()})
    if not results:
        raise RuntimeError(f"{size}: reference JPEG returned no boxes at confidence >= 0.25")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path, help="Official Ultralytics yolo26n.pt")
    parser.add_argument("reference_jpeg", type=Path, help="Reference JPEG (e.g. Ultralytics bus.jpg)")
    parser.add_argument("--assets", type=Path, default=Path(__file__).resolve().parents[1] / "app/src/main/assets")
    args = parser.parse_args()
    args.assets.mkdir(parents=True, exist_ok=True)
    report = {
        "checkpoint_sha256": digest(args.checkpoint),
        "reference_sha256": digest(args.reference_jpeg),
        "ultralytics": ultralytics.__version__,
        "desktop_onnxruntime": ort.__version__,
        "models": [],
    }
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        checkpoint = temp / "yolo26n.pt"
        shutil.copyfile(args.checkpoint, checkpoint)
        model = YOLO(str(checkpoint))
        names = model.names
        assert model.task == "detect" and len(names) == 80 and model.model.end2end
        for size in (640, 320):
            exported = Path(model.export(format="onnx", imgsz=size, batch=1, dynamic=False,
                                         nms=False, simplify=False, opset=17, device="cpu"))
            predictions = verify_model(exported, size, args.reference_jpeg, names)
            name = f"yolo26n-{size}.onnx"
            shutil.move(exported, temp / name)
            report["models"].append({"asset": name, "input": [1, 3, size, size],
                                     "output": [1, 300, 6],
                                     "sha256": digest(temp / name), "reference_detections": predictions})
        for entry in report["models"]:
            shutil.copyfile(temp / entry["asset"], args.assets / entry["asset"])
        (args.assets / "coco-labels.txt").write_text(
            "".join(f"{names[index]}\n" for index in range(80)), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
