#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root/android/CyanBridge"

# Keep hosted-emulator artifacts compact and fail quickly on portable logic regressions.
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :shared:portabilityTest \
  :app:testDebugUnitTest

# Pass both classes through one property; repeated Gradle properties overwrite each other.
./gradlew --no-daemon --stacktrace -PtestAbi=x86_64 \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.fersaiyan.cyanbridge.ui.MetaPairingMockFlowTest,com.fersaiyan.cyanbridge.ui.MetaPairingScreenTest
