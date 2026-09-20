## Kanboard Task 35 — Chapter 4: Glasses Manager Baseline (Status UI + Capability Gating)

Date: 2026-02-12
Repo: `/home/fertroll10/Documents/ML/CyanBridgeSDK/android/CyanBridge`

### Goal (per repo root `AGENTS.md`)
Implement **Chapter 4** baseline for the **Glasses Manager** screen:
- Show manager home status: connection status + device class
- Show **battery/storage placeholders** **only when supported** (otherwise hidden)
- Capability gate the action/button panel:
  - **HEY_CYAN**: show an expanded controls panel (some buttons may be disabled / “coming soon”)
  - **GENERIC_AUDIO / UNKNOWN**: show **Meeting Capture only** (plus the basic connection/pairing UI)
- Add tests:
  - Unit tests for gating logic
  - UI test verifying visibility changes across device classes

---

## What was implemented

### 1) Capability gating model (Chapter 4)
Added a simple, explicit gating model that maps the **selected device class** to a set of allowed/visible UI actions.

- **New file:** `app/src/main/java/com/fersaiyan/cyanbridge/devices/GlassesManagerGating.kt`
  - Defines `GlassesManagerGating.Action` and `uiModel(...)`
  - Current MVP policy:
    - `DeviceClass.HEY_CYAN` → show extras panel + show battery/storage placeholders
    - all other classes → meeting capture only

This keeps the gating logic testable and decoupled from the Activity.

### 2) Glasses Manager UI updated (status + placeholders + gated panel)
Updated `acitivyt_main.xml` and `MainActivity.kt` so that:
- Status card always shows:
  - connection status (`status_text`)
  - selected device class (`tv_device_class`)
- Status placeholders are **capability-gated**:
  - battery row (`layout_battery` / `battery_text`)
  - storage row (`layout_storage` / `storage_text`)
  - entire metrics column (`layout_status_metrics`)
- The entire "HeyCyan extras" action area is grouped and gated:
  - container: `layout_heycyan_extras`
  - contains AI hijack tools, device info buttons, media controls, P2P sync button, and dev tools

**Key behavior:**
- If selected class is **HEY_CYAN** → `layout_heycyan_extras` is visible and status placeholders appear.
- If selected class is anything else (or no stored profile yet) → `layout_heycyan_extras` is `GONE`, and battery/storage placeholders are hidden.

### 3) Battery polling is now gated
Battery polling is now only active when the UI policy indicates the current profile supports battery status.

- Updated in `MainActivity.kt` inside `applyGlassesManagerGating(...)`:
  - If battery is not supported → stop polling and reset to `--%`
  - If supported → start polling (existing implementation already checks connection)

---

## Tests / Verification

### Unit tests (required)
- **New test:** `app/src/test/java/com/fersaiyan/cyanbridge/devices/GlassesManagerGatingTest.kt`
  - Verifies visible actions for HEY_CYAN vs GENERIC_AUDIO / null profile.

### UI test (androidTest)
- **New androidTest:** `app/src/androidTest/java/com/fersaiyan/cyanbridge/GlassesManagerUiGatingTest.kt`
  - Writes a `DeviceProfile` into `DeviceProfileStore`
  - Launches `MainActivity`
  - Asserts:
    - HEY_CYAN → extras + metrics visible
    - GENERIC_AUDIO → extras + metrics gone

Added AndroidX test dependencies to `app/build.gradle` for compiling androidTest targets.

### Build commands run
```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew testDebugUnitTest assembleDebug
```
Result: **BUILD SUCCESSFUL**

Additional compilation check for UI tests:
```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleDebugAndroidTest
```
Result: **BUILD SUCCESSFUL**

---

## Files changed/added

### Added
- `app/src/main/java/com/fersaiyan/cyanbridge/devices/GlassesManagerGating.kt`
- `app/src/test/java/com/fersaiyan/cyanbridge/devices/GlassesManagerGatingTest.kt`
- `app/src/androidTest/java/com/fersaiyan/cyanbridge/GlassesManagerUiGatingTest.kt`

### Modified
- `app/src/main/java/com/fersaiyan/cyanbridge/MainActivity.kt`
  - applies Chapter 4 gating to status placeholders + extras panel
  - gates battery polling
- `app/src/main/res/layout/acitivyt_main.xml`
  - adds battery/storage placeholder rows
  - groups HeyCyan-only action sections under `layout_heycyan_extras`
  - reorders sections so Meeting Capture is always visible
- `app/build.gradle`
  - adds `androidTestImplementation` deps required for the Chapter 4 UI gating test

---

## Notes / Follow-ups
- Storage value is currently a placeholder (`--`) because actual storage querying is not implemented yet; the UI is ready and properly gated.
- The gating policy is intentionally minimal and based on `DeviceProfile.selectedClass` (Chapter 3 persistence). Future chapters can extend the gating model to real capability detection beyond class.
