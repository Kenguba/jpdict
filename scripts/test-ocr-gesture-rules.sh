#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
javac_command=javac
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then
    javac_command="$JAVA_HOME/bin/javac"
    java_command="$JAVA_HOME/bin/java"
fi
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/jpdict-ocr-rules.XXXXXX")
trap 'rm -r "$test_dir"' 0
trap 'exit 1' HUP INT TERM
"$javac_command" -encoding UTF-8 -d "$test_dir" \
    "$repo_dir/app/src/main/java/com/yuen/jpdict/OcrDwellGate.java" \
    "$repo_dir/app/src/main/java/com/yuen/jpdict/OcrPickupMode.java" \
    "$repo_dir/app/src/test/java/com/yuen/jpdict/OcrGestureRulesTest.java"
"$java_command" -cp "$test_dir" com.yuen.jpdict.OcrGestureRulesTest
