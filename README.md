# Smart Blind Stick

Latest tested APK: [v0.3.1 — room descriptions](https://github.com/HadyAbohany/smart-blind-stick/releases/tag/v0.3.1). The release source and APK match the current Galaxy A35 trial.

Offline Android vision and Arabic alerts, ESP32 cane and camera firmware, and hardware documentation.

- Original project: [`Smart-Blind-Stick-2/`](Smart-Blind-Stick-2/README.md).
- Current camera application: [`android-benchmark`](Smart-Blind-Stick-2/android-benchmark/README.md).
- Smart decision layer and validation: [design and test notes](Smart-Blind-Stick-2/docs/SMART_DECISION_LAYER.md).
- Return to the original version: [rollback instructions](ROLLBACK.md).

`main` and the immutable `v0.2.1-baseline` tag preserve the original project. Development of the new offline decision layer is on `codex/smart-decision-layer`. The ONNX models and Arabic speech assets are included so a fresh clone can build the camera application.

The v0.3.1 room-description engine has desktop regression, speech-continuity, and synthetic replay coverage. A Galaxy A35 camera/audio smoke test recorded 10.65 average processed FPS over about 152 seconds; the user confirmed clear, complete speech. Detailed detector accuracy, haptics, accessibility, and sustained thermal performance remain unvalidated. The camera-only application does not measure distance, verify walkable ground, or authorize road crossings.

## Checks without a phone

With JDK 17 or 21 installed:

```sh
sh Smart-Blind-Stick-2/android-benchmark/tools/run_decision_checks.sh
python3 Smart-Blind-Stick-2/android-benchmark/tools/verify_speech_assets.py
```

The second command needs `ffmpeg` to decode the bundled audio. Neither command connects to a phone. GitHub Actions runs these checks on pushes and pull requests. Android assembly and lint require SDK 35, Build Tools 35.0.0, Gradle 8.9, and JDK 17 or 21; see the application README.
