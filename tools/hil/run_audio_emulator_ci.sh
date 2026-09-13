#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/android/CyanBridge"

./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :shared:portabilityTest \
  :app:testDebugUnitTest

./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest
