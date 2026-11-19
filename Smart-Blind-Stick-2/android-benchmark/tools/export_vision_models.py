#!/usr/bin/env python3
"""Export the real YOLOE room profile and metric Depth Anything model; check ORT/PyTorch parity."""
import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def export_yolo(checkpoint, reference, pack, report):
    import ultralytics
    from ultralytics import YOLOE
    profile_path = ROOT / "vision-lab/src/main/assets/yoloe-labels.json"
    profile = json.loads(profile_path.read_text())
    labels = [label["en"] for label in profile["labels"]]
    torch.set_num_threads(2)
    model = YOLOE(str(checkpoint))
    model.set_classes(labels)
    assert len(model.names) == len(labels)
    exported = Path(model.export(format="onnx", imgsz=320, batch=1, dynamic=False,
                                 simplify=False, opset=17, nms=False, device="cpu"))
    destination = pack / "yoloe-room-320.onnx"
    if destination != exported:
        import shutil
        shutil.copyfile(exported, destination)
    # Do not let a wall-clock export date change the reviewed pack checksum.
    graph = onnx.load(str(destination))
    for metadata in graph.metadata_props:
        if metadata.key == "date":
            metadata.value = "2026-11-19T00:00:00Z"
    onnx.save(graph, str(destination))
    onnx.checker.check_model(str(destination))
    runtime = session(destination)
    image = ImageOps.exif_transpose(Image.open(reference)).convert("RGB")
    scale = min(320 / image.width, 320 / image.height)
    w, h = max(1, round(image.width * scale)), max(1, round(image.height * scale))
    canvas = Image.new("RGB", (320, 320), (114, 114, 114))
    canvas.paste(image.resize((w, h), Image.Resampling.BILINEAR), ((320-w)//2, (320-h)//2))
    tensor = np.ascontiguousarray(np.asarray(canvas).transpose(2, 0, 1)[None], dtype=np.float32) / 255
    started = time.monotonic()
    result = runtime.run(None, {runtime.get_inputs()[0].name: tensor})
    elapsed = (time.monotonic() - started) * 1000
    shape = result[0].shape
    end_to_end = shape[1] == 300 and shape[-1] >= 6
    profile["output_layout"] = "end2end" if end_to_end else "channels"
    profile_path.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
    assert np.isfinite(result[0]).all()
    rows = result[0][0]
    if end_to_end:
        confident = rows[rows[:, 4] >= .25]
        assert len(confident) > 0
        assert ((confident[:, 5] >= 0) & (confident[:, 5] < len(labels))).all()
        detected = sorted({labels[int(row[5])] for row in confident})
    else:
        assert rows.shape[0] >= 4 + len(labels)
        scores = rows[4:4+len(labels)]
        detected = sorted({labels[c] for c in np.where(scores.max(axis=1) >= .25)[0]})
        assert detected
    report["yoloe"] = {"file": destination.name, "sha256": sha(destination), "bytes": destination.stat().st_size,
                       "ultralytics": ultralytics.__version__, "checkpoint_sha256": sha(checkpoint),
                       "input_shape": runtime.get_inputs()[0].shape,
                       "output_shapes": [list(r.shape) for r in result], "output_layout": profile["output_layout"],
                       "vocabulary_size": len(labels), "reference_detections": detected,
                       "desktop_first_inference_ms": elapsed}


def export_depth(checkpoint_dir, reference, pack, report):
    from transformers import AutoModelForDepthEstimation
    torch.set_num_threads(2)
    depth = AutoModelForDepthEstimation.from_pretrained(str(checkpoint_dir), local_files_only=True,
                                                       attn_implementation="eager").eval().cpu()
    assert depth.config.depth_estimation_type == "metric"
    class Wrapper(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.depth = depth
        def forward(self, pixel_values):
            depth = self.depth(pixel_values=pixel_values).predicted_depth
            # The DPT head's native resolution differs from the camera tensor. Export the
            # same explicit post-resize for both runtimes so ROI coordinates remain aligned.
            return torch.nn.functional.interpolate(depth[:, None], size=pixel_values.shape[-2:],
                                                    mode="bilinear", align_corners=False)[:, 0]
    wrapper = Wrapper().eval()
    destination = pack / "depth-anything-v2-metric-indoor-small.onnx"
    # Transformers' head casts symbolic dimensions to int, freezing the trace to
    # landscape. Keep the same operations/weights, but preserve symbolic sizes.
    import types
    original_head = depth.head.forward
    def symbolic_head(self, hidden_states, patch_height, patch_width):
        value = self.conv1(hidden_states[self.head_in_index])
        value = torch.nn.functional.interpolate(value,
                    size=(patch_height * self.patch_size, patch_width * self.patch_size),
                    mode="bilinear", align_corners=True)
        return (self.activation2(self.conv3(self.activation1(self.conv2(value)))) * self.max_depth).squeeze(1)
    depth.head.forward = types.MethodType(symbolic_head, depth.head)
    torch.onnx.export(wrapper, (torch.zeros(1, 3, 392, 518),), str(destination),
                      input_names=["pixel_values"], output_names=["depth_meters"],
                      dynamic_axes={"pixel_values": {2: "height", 3: "width"},
                                    "depth_meters": {1: "height", 2: "width"}},
                      opset_version=17, dynamo=False, do_constant_folding=True)
    depth.head.forward = original_head
    onnx.checker.check_model(str(destination))
    runtime = session(destination)
    image = ImageOps.exif_transpose(Image.open(reference)).convert("RGB")
    comparisons = []
    mean = np.array([.485, .456, .406], dtype=np.float32)[None, :, None, None]
    std = np.array([.229, .224, .225], dtype=np.float32)[None, :, None, None]
    for width, height in [(518, 392), (392, 518), (518, 518)]:
        pixels = np.ascontiguousarray(np.asarray(image.resize((width, height), Image.Resampling.BILINEAR))
                                     .transpose(2, 0, 1)[None], dtype=np.float32) / 255
        values = (pixels - mean) / std
        with torch.inference_mode():
            expected = wrapper(torch.from_numpy(values)).numpy()
        started = time.monotonic()
        actual = runtime.run(None, {runtime.get_inputs()[0].name: values})[0]
        elapsed = (time.monotonic() - started) * 1000
        assert actual.shape == expected.shape == (1, height, width)
        assert np.isfinite(actual).all() and (actual > 0).all() and (actual <= 20).all()
        error = float(np.max(np.abs(expected - actual)))
        np.testing.assert_allclose(actual, expected, atol=.03, rtol=.01)
        comparisons.append({"width": width, "height": height, "max_absolute_error_m": error,
                            "desktop_inference_ms": elapsed, "median_prediction_m": float(np.median(actual))})
    report["metric_depth"] = {"file": destination.name, "sha256": sha(destination),
                               "bytes": destination.stat().st_size, "metric": True,
                               "checkpoint_sha256": sha(checkpoint_dir / "model.safetensors"),
                               "comparisons": comparisons,
                               "preprocessing": "bilinear RGB, aspect-preserving multiples of 14, ImageNet normalization",
                               "physical_distance_accuracy_validated": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yolo", type=Path)
    parser.add_argument("--depth", type=Path)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--pack", type=Path, default=ROOT / "model-packs")
    parser.add_argument("--report", type=Path, default=ROOT / "results/vision-model-desktop-validation.json")
    args = parser.parse_args()
    args.pack.mkdir(exist_ok=True, parents=True)
    report = json.loads(args.report.read_text()) if args.report.exists() else {}
    report.update({"reference_sha256": sha(args.reference), "desktop_onnxruntime": ort.__version__,
                   "physical_phone_tested": False})
    if args.yolo:
        export_yolo(args.yolo, args.reference, args.pack, report)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        print("YOLOE exported and checked", json.dumps(report["yoloe"]), flush=True)
    if args.depth:
        export_depth(args.depth, args.reference, args.pack, report)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
        print("Metric depth exported and checked", json.dumps(report["metric_depth"]), flush=True)


if __name__ == "__main__":
    main()
