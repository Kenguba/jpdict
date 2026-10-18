#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
android_jar=${ANDROID_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platforms/android-35/android.jar}
if [ ! -f "$android_jar" ]; then
    printf '%s\n' 'Set ANDROID_JAR or ANDROID_HOME to an installed Android 35 SDK.' >&2
    exit 1
fi
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/jpdict-drawer-test.XXXXXX")
trap 'rm -r "$test_dir"' 0
trap 'exit 1' HUP INT TERM
javac_command=${JAVA_HOME:+$JAVA_HOME/bin/}javac
java_command=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$javac_command" -encoding UTF-8 -cp "$android_jar" -d "$test_dir" \
    "$repo_dir/app/src/main/java/com/yuen/jpdict/DrawerStyleSettings.java" \
    "$repo_dir/app/src/main/java/com/yuen/jpdict/DrawerMotionController.java" \
    "$repo_dir/app/src/test/java/com/yuen/jpdict/DrawerMotionTest.java"
"$java_command" -cp "$test_dir:$android_jar" com.yuen.jpdict.DrawerMotionTest
