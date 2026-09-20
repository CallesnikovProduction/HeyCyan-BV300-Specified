## Task 34 — Chapter 3: Device Scanning & Pairing (Device classification + override)

### Summary
Implemented Chapter 3 requirements for Bluetooth scanning + pairing UI in `android/CyanBridge`:
- Scan + display nearby BLE devices
- Guess device class (HEY_CYAN / GENERIC_AUDIO / UNKNOWN)
- Per-device manual override (2 alternate classes + “Other”)
- Persist last-selected device profile (MAC + detected/selected class + override flag)
- Show last selected device class in Glasses Manager status card

### Implementation details
**Scanning**
- Uses existing vendor scan wrapper (`BleScannerHelper` / `ScanWrapperCallback`).
- Updates list via:
  - `onLeScan(...)` (name + MAC + RSSI)
  - `onParsedData(...)` (service UUIDs via `ScanRecord.serviceUuids` when available)
- Deduplicates by MAC and sorts by RSSI.

**Classification**
- Added `DeviceClassifier.guessDeviceClass(name, uuids)` with name heuristics:
  - HEY_CYAN: contains `HeyCyan`/`Cyan` or starts with `O_` / `Q_`
  - GENERIC_AUDIO: simple headphone/earbud keywords
  - UNKNOWN fallback
- Service UUID list is captured and plumbed through, ready for future UUID-based heuristics.

**Pairing screen UI**
- Updated device list row to include:
  - device class icon + “Detected: …” label
  - ChipGroup for overrides: 2 alternate classes + “Other” (UNKNOWN)
- Override selection is stored in-memory per discovered device and persisted per-MAC when the user connects.

**Persistence**
- Added `DeviceProfileStore` (SharedPreferences):
  - `saveLastSelected()` / `loadLastSelected()`
  - per-MAC override storage (`override_<MAC>`)

**Glasses Manager status**
- `MainActivity` now reads `DeviceProfileStore.loadLastSelected()` and shows the selected class in the status card (`tv_device_class`).

### Key files
- `app/src/main/java/com/fersaiyan/cyanbridge/devices/`
  - `DeviceClass.kt`, `DeviceClassifier.kt`, `ScannedDevice.kt`, `DeviceProfile.kt`, `DeviceProfileStore.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ui/DeviceBindActivity.kt`
- `app/src/main/java/com/fersaiyan/cyanbridge/ui/DeviceListAdapter.kt`
- `app/src/main/res/layout/recycleview_item_device.xml`
- `app/src/main/res/drawable/`
  - `ic_device_heycyan.xml`, `ic_device_meta.xml`, `ic_device_generic_audio.xml`, `ic_device_unknown.xml`
- Tests: `app/src/test/java/com/fersaiyan/cyanbridge/devices/DeviceClassifierTest.kt`

### Verification
Build + unit tests:
```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew testDebugUnitTest assembleDebug
```
Result: **BUILD SUCCESSFUL**.

### Notes / TODOs
- UUID-based classification is scaffolded (UUIDs collected) but currently only name heuristics are implemented (no known UUID mapping yet).
