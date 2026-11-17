# Offline smart decision layer — v0.3.0

The original `AlertPolicy.java` is unchanged. `DecisionEngine` selects the smart or original policy per run. This work targets **android-benchmark**, the camera application that already has bundled YOLO models and Arabic audio. The separate BLE/ESP32 `mobile-app` starter is unchanged. No phone connection was used for this change.

## Behavior

| Observation | Default automatic response | On demand |
|---|---|---|
| Confirmed peripheral object | Quiet | Describe its class and image direction |
| Relevant object in the lower central image | Notice | Describe with a brief attention cue |
| Large relevant central object | Caution | Suggest slowing and checking with the cane |
| Central vehicle | Caution | Attention to a vehicle, without claiming it is moving |
| Sustained image motion toward the center | Caution | Describe image motion, without a physical trajectory claim |
| Sustained central image expansion | Caution | Describe image-size growth, without a distance claim |
| Selected search class | Announce when confirmed | Higher-priority caution still takes precedence |
| Empty, unconfirmed or stale detections | Quiet | No claim of a clear or safe path |
| Stored JPEG | One ordinary description | Never infer motion or urgency from repeated benchmark passes |

Suggested actions are `DESCRIBE`, `LOCATE_TARGET`, `PAY_ATTENTION`, or `SLOW_AND_CHECK`. There are no left/right walking instructions, “safe to cross” decisions, or metric distances.

## Tracking and evidence

`SceneTracker` filters nonfinite coordinates, invalid classes, low confidence, tiny boxes, and duplicate same-class overlapping boxes. It considers the top 40 eligible boxes, retains at most 80 tracks, and matches each track and box at most once using class and IoU ≥0.20. The selected model confidence is respected, with a minimum of 45%. This is a lightweight image tracker; identity can be lost or swapped under occlusion or rapid movement.

Confirmation requires at least three matched observations and 180 ms of capture-time evidence. A gap over 450 ms resets continuity; tracks expire after 900 ms. Image direction has the original boundary hysteresis. The lower central region also has entry/exit hysteresis. This region is an attention heuristic, **not** a mapped walking corridor. Class relevance is an explicit heuristic, not a trained collision predictor.

Inputs use monotonic milliseconds and the capture timestamp when available. Stale images over 500 ms, negative ages, future captures, duplicate captures, and reversed timestamps cannot accumulate confirmation. When the camera has no compatible capture clock, freshness is measured from JPEG receipt, as in v0.2.1.

Motion requires four observations spanning at least 400 ms, consistent changes, and a history capped at eight samples/1.2 seconds. A lateral image rate above 0.08 normalized image widths per second predicts entry into the center within 0.8 seconds. Expansion requires at least 40% accumulated image-area growth and three consistent increases. These are provisional attention thresholds for phone validation.

When three or more continuously matched objects share translation or scale changes, the engine suppresses individual motion escalation. A single visible object cannot disambiguate camera movement, zoom, and object movement. Audio and JSON deliberately use image-based wording. Severity/wording has a short 600 ms hold to reduce threshold flicker; absent objects are never held as valid candidates.

## Scheduling and speech

Class relevance, forward-image occupancy, visual prominence, motion evidence, and search intent contribute to an explicit priority. Higher caution outranks notice; notice outranks search and ordinary descriptions. If the highest-priority object was already announced, the scheduler can select another eligible candidate rather than starving it.

Ordinary announcements are separated by 2.5 seconds. Caution has a 1.6-second minimum gap; escalation from a lower level can bypass the ordinary gap after 750 ms. The same class and image direction is suppressed for 12 seconds, or six seconds for persistent caution. Stronger severity can bypass phrase suppression. Re-identification alone does not bypass suppression. Manual description is an explicit user request and may repeat.

The application has one active speech plan: an optional bundled attention/action prefix followed by the original class/direction clip. Audio focus is retained across both clips. Validity is checked before each clip starts. Higher caution can interrupt lower-priority speech; lost or invalid evidence, stale camera updates, changing targets, stopping, changing tabs, leaving the foreground, or muting cancels obsolete audio. No sentence queue grows behind the live scene. Optional phone haptics use a double pulse for caution and a short pulse for other announcements.

All 248 audio files are bundled offline. Six new prefixes use the same macOS Majed voice as the original clips. `decision-speech.json`, `DecisionSpeech.java`, and the actual audio are verified together; audio decoding checks reject empty or silent files. Regeneration needs macOS `say`, Majed, `ffmpeg`, and `ffprobe`; playback requires none of those tools.

## Reports and replay

JSON includes policy mode/version, reason, level, suggested action, confidence, priority, target, normalized image area/growth, and image horizontal velocity. Metrics describe image evidence, not meters or collision probability. Speech records identify the **start** of a planned phrase, not proof that the entire phrase finished or was audible. Device audio and haptics remain physical tests.

The decision timeline records semantic state transitions, capped at 2,000 entries with an explicit dropped count. Camera reports retain the first 2,000 decision inputs (at most 40 boxes each), with capture age and search target; additional frames are counted as dropped. These limits bound diagnostic memory. Images and microphone audio are not recorded by this feature.

Convert a phone camera report later:

```sh
python3 tools/report_to_replay.py camera-report.json /tmp/camera-replay.csv
```

Compile `DecisionReplay.java` together with the four policy classes (see `tools/run_decision_checks.sh`), then run:

```sh
java -cp /path/to/classes eg.sbs.benchmark.DecisionReplay /tmp/camera-replay.csv /tmp/decisions.tsv
```

The replay reproduces **policy eligibility** assuming audio is available. It does not reproduce MediaPlayer preparation, interruption timing, focus loss, mute, or actual completed speech. CSV supports multiple detections per timestamp, empty frames, SMART/LEGACY mode, target changes, and optional expected decision/announcement reasons.

## Validation performed without a phone

- Original `AlertPolicyCheck` regression program.
- Fifteen production-engine scenario groups, including 10,000 synthetic crowded frames and more than 30,000 assertions.
- Nine golden CSV scenarios, 31 frames and 62 explicit expectations.
- Bundled speech file checks plus decoding the six new prefixes and checking nonzero signal energy.
- Android debug assembly and Lint. Existing warning categories concern UI string resources, rotation constants, backup rules, and the missing application icon; there are no Lint errors.

`android-benchmark/results/decision-desktop-validation.json` and `decision-scenario-replay.tsv` contain the observed desktop results. Desktop policy latency is not phone latency or detector accuracy. Synthetic inputs validate policy behavior, not whether YOLO finds real obstacles.

## Physical-phone trial still required

1. Install the separately named v0.3.0 debug APK, test bundled sound, and compare SMART/LEGACY on the same stationary scene.
2. Confirm peripheral silence, central attention, manual description, and target search; verify that a central caution outranks the target.
3. Exercise camera pan, shake, size changes, occlusion, and target movement. Tune the image heuristics using exported evidence rather than asserting metric distance.
4. Stop, change tabs/target, mute, switch apps, deny camera access, and simulate slow frames. Check that speech stops and no obsolete sentence starts.
5. Verify low-volume/audio-focus cases, both haptic patterns, TalkBack, large font, and the accessibility of added controls.
6. Run the existing 15-minute performance/thermal test on Galaxy A35 and export JSON. Compare processing p95 and decision cost with the v0.2.1 measurements.

Sensor fusion, reliable distance/TTC, free-space segmentation, ground hazards, stairs/curbs, and road-crossing decisions remain separate work requiring suitable inputs/models and hardware validation. No BLE or sensor readings are invented by this camera-only engine.

See the repository's [rollback instructions](../../ROLLBACK.md) for restoring the original version.
