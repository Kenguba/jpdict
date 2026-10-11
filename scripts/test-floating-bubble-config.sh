#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
variant=${1:-arm64Debug}
case "$variant" in arm64Debug|arm64Release) ;; *) exit 1 ;; esac
# Gradle capitalizes the first letter in task directory names.
case "$variant" in
    arm64Debug) app_classes="$repo_dir/app/build/intermediates/javac/arm64Debug/compileArm64DebugJavaWithJavac/classes" ;;
    arm64Release) app_classes="$repo_dir/app/build/intermediates/javac/arm64Release/compileArm64ReleaseJavaWithJavac/classes" ;;
esac
android_jar="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platforms/android-35/android.jar"
test_javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
test_java=${JAVA_HOME:+$JAVA_HOME/bin/}java
if [ ! -d "$app_classes" ] || [ ! -f "$android_jar" ]; then
    printf '%s\n' 'Build the selected arm64 variant first and set ANDROID_HOME.' >&2
    exit 1
fi
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/jpdict-bubble-test.XXXXXX")
trap 'rm -r "$test_dir"' 0
trap 'exit 1' HUP INT TERM
test_classpath="$app_classes:$android_jar"
"$test_javac" -encoding UTF-8 -cp "$test_classpath" -d "$test_dir" \
    "$repo_dir/app/src/test/java/com/yuen/jpdict/FloatingBubbleAppearanceTest.java"
"$test_java" -cp "$test_dir:$test_classpath" com.yuen.jpdict.FloatingBubbleAppearanceTest
