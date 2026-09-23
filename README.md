# BlackVingadorre

BlackVingadorre is a personal Android application for Blackview BV300 / MoYoung-compatible glasses. This repository is not a general-purpose smart-glasses SDK. The Android project lives in [`android/CyanBridge`](android/CyanBridge); its package and application ID retain the historical `com.fersaiyan.cyanbridge` name for compatibility.

The active path combines HeyCyan/MoYoung BLE connection and controls, Wi-Fi Direct media transfer, diagnostics, and a local assistant. The assistant can capture speech through the glasses, transcribe it with Vosk, answer with Gemma (including a fresh glasses image when requested), and speak through Supertonic to the BV300 audio route. Local chat, cancellation/barge-in, and background assistant operation are part of this path. Experimental tools remain in the app only where they support BV300 development or existing life-capture features.

## Build

Open `android/CyanBridge` in Android Studio with JDK 17 and the Android SDK installed, or run from that directory:

```bash
./gradlew :app:assembleDebug
```

The APK is produced under `android/CyanBridge/app/build/outputs/apk/debug/`. Device behavior requires a physical phone and BV300 glasses; an APK build alone does not verify Bluetooth, camera, media transfer, or audio routing.

## Where to look

- [`android/CyanBridge/README.md`](android/CyanBridge/README.md): Android app structure and verification.
- [`android/AGENTS.md`](android/AGENTS.md): confirmed BLE → Wi-Fi Direct media-transfer details.
- [`WIFI_TRANSFER_ARCHITECTURE.md`](WIFI_TRANSFER_ARCHITECTURE.md): transfer design and protocol background.
- [`heycyan-core/`](heycyan-core/): reusable Android connectivity and media modules.
- `android/HeyCyanOfficialApp/`: vendor app reference for protocol research.

The bundled vendor artifacts and decompiled references may have separate licensing and redistribution restrictions. Review those terms before distributing the repository or an APK.
