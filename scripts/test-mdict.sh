#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT HUP INT TERM
if [ -n "${JAVA_HOME:-}" ]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
"$JAVAC" -encoding UTF-8 -d "$WORK" \
  "$ROOT/app/src/main/java/com/yuen/jpdict/MdictReader.java" \
  "$ROOT/app/src/main/java/com/yuen/jpdict/MdictIndexDecoder.java" \
  "$ROOT/app/src/main/java/com/yuen/jpdict/LzoDecoder.java" \
  "$ROOT/app/src/main/java/com/yuen/jpdict/DictionaryScheduler.java" \
  "$ROOT/tests/MdictReaderTest.java" \
  "$ROOT/tests/DictionarySchedulerTest.java"
"$JAVA" -Xmx128m -cp "$WORK" com.yuen.jpdict.MdictReaderTest "$ROOT/tests/vectors"
"$JAVA" -Xmx128m -cp "$WORK" com.yuen.jpdict.DictionarySchedulerTest
