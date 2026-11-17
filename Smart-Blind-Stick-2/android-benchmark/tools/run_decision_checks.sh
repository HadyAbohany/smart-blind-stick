#!/bin/sh
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/sbs-decisions.XXXXXX")
trap 'rm -rf "$CLASSES"' EXIT HUP INT TERM
if [ -n "${JAVA_HOME:-}" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
else
    JAVAC=javac
    JAVA=java
fi
SOURCE="$ROOT/app/src/main/java/eg/sbs/benchmark"
"$JAVAC" --release 17 -d "$CLASSES" "$SOURCE/AlertPolicy.java" "$SOURCE/SceneTracker.java" \
    "$SOURCE/DecisionEngine.java" "$SOURCE/DecisionSpeech.java" \
    "$ROOT/tools/AlertPolicyCheck.java" "$ROOT/tools/DecisionEngineCheck.java" "$ROOT/tools/DecisionReplay.java"
"$JAVA" -cp "$CLASSES" eg.sbs.benchmark.AlertPolicyCheck
if [ "$#" -gt 0 ]; then
    "$JAVA" -cp "$CLASSES" eg.sbs.benchmark.DecisionEngineCheck "$1"
else
    "$JAVA" -cp "$CLASSES" eg.sbs.benchmark.DecisionEngineCheck
fi
"$JAVA" -cp "$CLASSES" eg.sbs.benchmark.DecisionReplay "$ROOT/tools/fixtures/decision-scenarios.csv" "${2:-$CLASSES/replay.tsv}"
python3 "$ROOT/tools/check_report_replay.py" "$CLASSES" "$JAVA"
