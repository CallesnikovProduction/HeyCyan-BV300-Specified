# EYEVUE Livestream Reverse-Engineering Findings

Date: 2026-07-29

## Source APK

- Package: `com.eyevue.glassapp`
- Version: `1.0.68-c` (`versionCode=68`)
- SHA-256: `4df9bbc406cf8d763a46eea8bb05ca6068b72e2523f2a5467d52cee1ddd90cfb`
- Preserved JADX output: `android/eyevue-jadx` (original temporary path: `/tmp/opencode/eyevue-jadx`).
- JADX version: `1.5.6`

## Important Scope Finding

Media import is a separate flow: the vendor `defpackage/qfc.java`
`connectP2pWifitoReceivePhoto()` sends `getWifiInfo` (`0x39`), not the
live-preview command `0x67`. Do not infer media-start commands from this
livestream document. See `DATA_SYNC_COMMAND_AUDIT_20260909.md` for the audit.

The APK does not implement TikTok/Douyin publishing. No TikTok package, intent, SDK call, RTMP publisher, or app-owned MediaProjection path was found.

Its glasses `Live` feature is a local camera preview:

1. Send a BLE live-mode command to the glasses.
2. Receive the glasses Wi-Fi SSID over BLE.
3. Connect the phone to the glasses AP or Wi-Fi Direct network.
4. Start the glasses HTTP/RTSP path as required by the model.
5. Play the RTSP stream locally with VLC.

The bundled Zego code is separate video-call functionality. It uses `STANDARD_VIDEO_CALL` and publishes the phone camera, not the glasses camera.

## BLE Protocol

Vendor source: `SendCommandViaBle.configSendCommand`, `Datagram.convertLeHeaderToBytes`, `LeHeader.setPayload`, and `Command`.

Outbound datagram format:

```text
AB 55 len_hi len_lo command_id payload... crc
```

The vendor sets `len = payload.size + 2`. The CRC is `(command_id + sum(payload)) & 0xff`; the length and start-of-frame bytes are not included in the CRC.

Live commands:

| Operation | Command | Payload | Complete frame |
| --- | ---: | ---: | --- |
| Start live over AP | `0x67` (`103`) | `0x30` (`48`) | `AB 55 00 03 67 30 97` |
| Start live over P2P | `0x67` (`103`) | `0x31` (`49`) | `AB 55 00 03 67 31 98` |
| Exit/cleanup | `0x44` (`68`) | `0x30 0x01` | `AB 55 00 04 44 30 01 75` |

The BLE command is written through the vendor SDK. The vendor GATT implementation uses service `0000aa12-0000-1000-8000-00805f9b34fb`, command-write characteristic `0000aa13-0000-1000-8000-00805f9b34fb`, and command-notify characteristic `0000aa14-0000-1000-8000-00805f9b34fb`.

The glasses' Wi-Fi response is command `0x25` (`37`). Its payload is decoded as a UTF-8 SSID; the APK supplies the fixed password `12345678` instead of reading one from the response.

## Model-Specific Flow

The APK determines the family from the device project/customer response:

- Project containing `SK`: `bw6.r=true`, `bw6.s=true`; AP flow at `192.168.1.254`.
- Project containing `T` but not `SK`: `bw6.r=true`, `bw6.s=false`; T-series AP flow at `192.168.169.1`.
- Other projects: `bw6.r=false`, `bw6.s=false`; P2P flow at `192.168.49.207`.

T-series flow:

- Send the AP live frame.
- Connect to the returned SSID with password `12345678`.
- Play `rtsp://192.168.169.1/h264`.
- The T activity does not send the `cmd=3001` HTTP request first.

SK/AP and other/P2P flow:

- Send the matching AP or P2P live frame.
- Connect to the returned network.
- Send `GET http://<base-ip>/?custom=1&cmd=3001&par=1`.
- Play `rtsp://<base-ip>/xxx.mov`.

The APK uses port 80 for the HTTP control request and VLC RTSP playback options including TCP transport, network caching, live caching, and late-frame dropping.

## CyanBridge Integration Requirements

- Add an explicit `EYEVUE` device class and display label to the Scan device-type choices alongside Meta, HeyCyan, Meizu/audio-only, and existing choices.
- Detect EYEVUE advertisements without stealing existing HeyCyan or Meta classifications. Keep manual selection and per-MAC persistence working.
- Route selected EYEVUE devices through an Eyevue-specific BLE/network adapter rather than assuming the Oudmon HeyCyan protocol.
- Keep the Glasses tab/dashboard layout and core button behavior equivalent to HeyCyan where the hardware supports it: connection, battery/status, photo, video, audio, media count, Wi-Fi sync, and live preview.
- Wire every dashboard action to an implementation or an explicit capability-safe disabled state. Do not leave Eyevue buttons as silent no-ops.
- For live preview, use the actual `0x67` command and the returned SSID/AP or P2P model flow. The existing CyanBridge passive RTSP probe must not be treated as the production Eyevue path.
- Correct the existing `EyevueProtocol.kt` length calculation to match the vendor (`payload.size + 2`) before using it for live or cleanup commands.
- Preserve exclusive BLE/P2P session ownership and cleanup after live preview, including the vendor cleanup frame and Wi-Fi/P2P teardown.

## Decompiled Source References

- `com/eyevue/glassapp/view/home/HomeFragment.java`
- `com/eyevue/glassapp/view/live/EyevueLiveActivity.java`
- `com/eyevue/glassapp/view/live/EyevueTLiveActivity.java`
- `com/eyevue/glassapp/bluetooth/manager/SendCommandViaBle.java`
- `com/eyevue/glassapp/bluetooth/manager/ModBleResponse.java`
- `com/eyevue/glassapp/bluetooth/protocol/Command.java`
- `com/eyevue/glassapp/bluetooth/protocol/Datagram.java`
- `defpackage/r00.java`
- `defpackage/q10.java`
- `defpackage/uz8.java`
- `defpackage/buh.java`
