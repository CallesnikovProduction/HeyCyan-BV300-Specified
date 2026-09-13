#!/usr/bin/env bash
# Paid, network-backed smoke test using the emulator's already verified account.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERIAL="${1:?Usage: bash tools/hil/run_pro_live_audio.sh <adb-serial>}"
MODE="${2:-private}"
case "$MODE" in private|economy) ;; *) echo "Mode must be private or economy" >&2; exit 2 ;; esac
OUT="$ROOT/build/hil/pro-live-audio"
mkdir -p "$OUT"
adb -s "$SERIAL" get-state >/dev/null
adb -s "$SERIAL" shell pm grant com.fersaiyan.cyanbridge android.permission.RECORD_AUDIO
adb -s "$SERIAL" shell am instrument -w -r \
  -e proLiveMode "$MODE" \
  -e class com.fersaiyan.cyanbridge.hil.audio.AudioEmulatorHarnessTest#geminiLive_real_ifPro_repliesWithRedFlowers \
  com.fersaiyan.cyanbridge.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/result.txt"
# am instrument can exit zero even when JUnit fails or skips a test.
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]|AssumptionViolated' "$OUT/result.txt" ||
   ! grep -q 'OK (1 test)' "$OUT/result.txt"; then
  echo "Pro Live audio test failed or skipped; see $OUT/result.txt" >&2
  exit 1
fi
