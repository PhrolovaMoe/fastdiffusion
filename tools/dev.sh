#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_work="$PWD/.work"
mkdir -p "$task_work/tmp"
export GRADLE_USER_HOME="$task_work/gradle-home"
export TMPDIR="$task_work/tmp"
# Gradle 8.8 needs a compatible launcher JDK as well as the Java 21 toolchain.
if [[ -n "${FASTDIFFUSION_JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$FASTDIFFUSION_JAVA_HOME"
elif [[ -d /usr/lib/jvm/openjdk21 ]]; then
    export JAVA_HOME=/usr/lib/jvm/openjdk21
fi
exec ./gradlew "$@"
