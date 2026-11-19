#!/bin/sh
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/sbs-vision.XXXXXX")
trap 'rm -rf "$CLASSES"' EXIT HUP INT TERM
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
SOURCE="$ROOT/vision-lab/src/main/java/eg/sbs/benchmark"
"$JAVAC" --release 17 -d "$CLASSES" "$SOURCE/DepthMath.java" "$SOURCE/LabBoxes.java" "$SOURCE/CaptionGuard.java" "$ROOT/tools/VisionLabCheck.java"
"$JAVA" -cp "$CLASSES" eg.sbs.benchmark.VisionLabCheck
