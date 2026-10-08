#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
javac_command=javac
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then
    javac_command="$JAVA_HOME/bin/javac"
    java_command="$JAVA_HOME/bin/java"
fi

if ! command -v "$javac_command" >/dev/null 2>&1 ||
        ! command -v "$java_command" >/dev/null 2>&1; then
    printf '%s\n' 'A JDK is required. Set JAVA_HOME or add javac and java to PATH.' >&2
    exit 1
fi

state_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/jpdict-state-test.XXXXXX")
trap 'rm -r "$state_test_dir"' 0
trap 'exit 1' HUP INT TERM

"$javac_command" -d "$state_test_dir" \
    "$repo_dir/app/src/main/java/com/yuen/jpdict/FloatingSearchState.java" \
    "$repo_dir/app/src/test/java/com/yuen/jpdict/FloatingSearchStateTest.java"
"$java_command" -cp "$state_test_dir" com.yuen.jpdict.FloatingSearchStateTest
