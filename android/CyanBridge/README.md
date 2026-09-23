# BlackVingadorre Android app

This is the Android application for Blackview BV300 / MoYoung-compatible glasses. The project directory, Kotlin packages, and application ID still use `CyanBridge`/`com.fersaiyan.cyanbridge` for compatibility; the user-facing product name is BlackVingadorre.

## Active path

- `MainActivity` wires lifecycle, dashboard, and navigation to existing glasses and assistant components.
- `devices/moyoung/` and the HeyCyan vendor SDK handle BV300 connection and controls.
- `localai/` and `localmodels/` contain the Vosk → Gemma → Supertonic assistant pipeline, including image questions, streaming speech, and cancellation.
- `assistant/`, `glasses/`, and `diagnostics/` support button/input handling, session ownership, and BV300 development diagnostics.
- `media/`, `ui/recordings/`, and `ota/` contain media transfer, gallery, and firmware tools.
- `plugins/` contains optional life-capture capabilities and extension infrastructure; individual plugins are not necessarily part of the core BV300 path.

## Build and checks

Use JDK 17 and a configured Android SDK. From this directory:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The default APK is `app/build/outputs/apk/debug/app-debug.apk`. For device verification, use a physical Android phone paired with BV300; do not treat successful compilation as proof of BLE, Wi-Fi Direct, locked-screen, camera, or audio behavior. The current Windows Gradle test runner may report `ClassNotFoundException` despite compiled test classes; inspect the test report rather than interpreting that as an assertion failure.

For the confirmed media-transfer sequence and routing caveats, see [`../AGENTS.md`](../AGENTS.md). Keep the BV300 assistant, diagnostics, local models, Wi-Fi ADB, OTA, media sync, and life-capture paths intact during cleanup.
