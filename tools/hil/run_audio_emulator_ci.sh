#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/android/CyanBridge"

./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :shared:portabilityTest

# The complete unit suite runs in Android self-hosted CI. Exercise only the logic owned by
# this audio workflow before starting its instrumentation harness.
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :app:testDebugUnitTest \
  --tests 'com.fersaiyan.cyanbridge.ai.live.*' \
  --tests 'com.fersaiyan.cyanbridge.ai.transcription.*' \
  --tests 'com.fersaiyan.cyanbridge.plugins.meetingsparknotes.*'

instrumentation_args=(
  --no-daemon
  --stacktrace
  -PtestAbi=x86_64
  :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest
)
if ! ./gradlew "${instrumentation_args[@]}"; then
  echo "Audio instrumentation failed; retrying once on the same emulator" >&2
  adb wait-for-device
  ./gradlew "${instrumentation_args[@]}"
fi
