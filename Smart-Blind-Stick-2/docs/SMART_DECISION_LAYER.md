# Room description and short attention cues — v0.3.1

The camera application describes visible room objects and their image directions. It does not give cane-use or walking instructions. The unchanged original `AlertPolicy.java` remains available through the **الوصف الذكي للأوضة** switch: turn it off after stopping a run to select the original policy.

## Current behavior

| Input | Response |
|---|---|
| Confirmed ordinary object, including peripheral objects | Object name and image direction |
| Several room objects | Automatic descriptions are paced; **وصف الآن** summarizes up to three distinct confirmed class/direction phrases |
| Sustained visually prominent object in the lower central image | One short **انتبه** prefix, then its name and image direction |
| Small central object, ordinary vehicle, or image movement alone | Ordinary description; no proximity warning solely from class or motion |
| Selected search class | Prioritized within ordinary descriptions; foreground attention still takes precedence |
| Unconfirmed or stale data | No new description starts |
| Stored JPEG | Ordinary descriptions only; repeated inference is not motion evidence |

Visual prominence is an approximate camera cue, **not a measured distance**. The current detector has no metric depth input. No warning asserts centimeters/meters, a safe path, physical approach speed, or a walking direction. COCO does not provide general room geometry, walls, stairs, or ground-hazard coverage.

## Evidence and scheduling

`SceneTracker` validates finite normalized boxes and class/confidence ranges, removes duplicate same-class boxes, considers at most 40 boxes, and retains at most 80 class/IoU tracks. Confirmation needs three matched observations spanning 180 ms. Capture age must be at most 500 ms. Duplicate/reversed timestamps and stale/invalid frames reset confirmation. The configured confidence is respected with a floor of 45%.

A foreground-attention candidate must overlap the lower central image, extend to at least normalized y=0.72, and occupy at least 32% of the image. It needs three such observations spanning 180 ms. Once confirmed, an exit threshold of 26% reduces boundary flicker. One large frame is insufficient. These thresholds describe **image occupancy**, not physical range, and require scene-based phone evaluation.

Ordinary descriptions have a 2.5-second minimum spacing, a 30-second same-class/direction repeat interval, and a maximum of three non-target context announcements in fifteen seconds. Search descriptions may repeat after twelve seconds. Persistent foreground attention may repeat after ten seconds, with a 1.6-second global minimum; escalation can bypass an ordinary cue after 750 ms. Candidate selection skips suppressed items so a repeated large object does not starve other descriptions.

Image motion/growth is retained as diagnostic evidence but no longer triggers attention by itself. The original class/direction clips remain unchanged. The current package contains 244 bundled clips: 240 class/direction phrases, sound test, unavailable-camera message, and the two attention/search prefixes. Movement/cane instruction prefixes have been removed from the current package.

## Speech continuity fix

Version 0.3.0 was installed on Galaxy A35, and the user reported audible but interrupted speech. The former playback path canceled an accepted sentence whenever a detection or exact reason disappeared on a subsequent frame, and applied the 500 ms start-freshness cutoff to in-progress audio.

`SpeechPlan` now distinguishes starting from continuing:

- A new plan requires fresh, confirmed evidence at preparation time.
- An accepted short plan can finish through confidence, box, reason, or identity jitter while usable camera updates continue.
- A 1.5-second loss of usable camera updates cancels playback. Old/invalid frames cannot keep this watchdog alive.
- Explicit mute, stop, tab/target change, backgrounding, and higher-priority attention can still cancel/preempt speech.
- A plan is bounded to at most twenty seconds for starting further clips and is not queued behind other plans.

The UI invalidates stale descriptions after 500 ms independently of the playback watchdog. Audio focus stays owned across a prefix and its object clip. JSON now distinguishes started plans (`spoken_alerts`) from fully completed plans (`completed_speech`), so a prefix starting is not mistaken for a completed sentence.

## Diagnostics and replay

Decision JSON records policy mode/version, reason, level, suggested action, confidence, priority, target, image area/growth, and normalized image velocity. Timeline and replay inputs are each capped at 2,000 entries, with explicit dropped counts. Images/microphone audio are not recorded by these diagnostics.

From `android-benchmark`, convert a later camera export:

```sh
python3 tools/report_to_replay.py camera-report.json /tmp/camera-replay.csv
```

Compile `DecisionReplay.java` together with the policy classes, as shown in `tools/run_decision_checks.sh`, then run:

```sh
java -cp /path/to/classes eg.sbs.benchmark.DecisionReplay /tmp/camera-replay.csv /tmp/decisions.tsv
```

Replay reproduces policy eligibility, not Android MediaPlayer focus/preparation, user mute, or actual acoustic output.

## Validation

Desktop checks run the production policy and `SpeechPlan` directly using Java 17-compatible compilation: seventeen scenario groups, more than 30,000 assertions, 10,000 crowded synthetic frames, ten golden CSV scenarios with 68 expectations, and a report-to-replay round trip. Tests cover room context pacing, three-item summaries, sustained prominence, no motion-only warning, fresh-start versus accepted-speech continuity, camera timeout, and explicit cancellation.

`results/room-desktop-validation.json` and `results/room-scenario-replay.tsv` contain the current results. `tools/verify_speech_assets.py` verifies manifest/wording consistency, all 244 files, and decodes both attention/search prefixes to reject silent audio. Android debug assembly and lint passed. The earlier `decision-*` result files refer to v0.3.0 and are retained as historical evidence.

Version 0.3.1 is installed on the connected Galaxy A35. Completion of the live listening/scene test is recorded separately; installation and desktop checks alone do not establish that the observed audio interruption is fixed acoustically. Camera accuracy, accessibility, haptics, and sustained thermal performance require physical trials. Phone screenshots and raw local test evidence are kept outside Git.

The source and original APK are preserved before changes. See [rollback instructions](../../ROLLBACK.md). The separate BLE/ESP32 `mobile-app` and firmware are unchanged.
