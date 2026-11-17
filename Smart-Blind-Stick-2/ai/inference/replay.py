"""Offline visual reference on recorded images. Requires Python 3.11 and Ultralytics."""
import argparse
import json
from pathlib import Path
from time import perf_counter

LABELS = {0, 1, 2, 3, 5, 7, 13, 16, 56}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("images", type=Path)
    parser.add_argument("--size", type=int, default=320)
    args = parser.parse_args()
    if not args.model.is_file():
        parser.error("Supply a local model file. This tool will not download weights.")
    images = sorted(p for p in args.images.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if not images:
        parser.error("No images found.")
    from ultralytics import YOLO

    model = YOLO(str(args.model), task="detect")
    model.predict(str(images[0]), imgsz=args.size, device="cpu", verbose=False)
    for image in images:
        start = perf_counter()
        prediction = model.predict(str(image), imgsz=args.size, conf=.50, device="cpu", verbose=False)[0]
        detections = []
        width = prediction.orig_shape[1]
        for box in prediction.boxes:
            cls = int(box.cls.item())
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            if cls in LABELS and .25 <= (x1 + x2) / (2 * width) <= .75:
                detections.append({"label": prediction.names[cls], "confidence": box.conf.item(),
                                   "xyxy": [x1, y1, x2, y2]})
        print(json.dumps({"file": image.name, "wall_ms": round((perf_counter() - start) * 1000, 2),
                          "detections": detections}))

if __name__ == "__main__":
    main()
