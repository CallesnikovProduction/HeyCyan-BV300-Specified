# Eyevue and TuneBuds data-sync command audit

Reviewed the merged `9369cb7` Android implementation against preserved vendor
JADX sources. This is a source audit, not physical-device verification.

## Eyevue: media startup differs from the vendor (high priority)

`eyevue-jadx/sources/defpackage/qfc.java:534-550` implements
`connectP2pWifitoReceivePhoto()`. It initializes the AP/P2P connector, then calls
`getWifiInfo(GET_WIFI_AP)` or `getWifiInfo(GET_WIFI_P2P)`.
`SendCommandViaBle.java:205-208` encodes command 57 (`0x39`), with parameters
48/49 (`0x30`/`0x31`) from `Command.java:104-105`.

Expected vendor media-start frames:

- AP: `AB 55 00 03 39 30 69`
- P2P: `AB 55 00 03 39 31 6A`

Our `EyevueMediaSync.kt:113-118` instead calls `startLiveAndAwaitSsid`, sending
`0x67`. That command is verified for live preview, not this vendor media-import
path. The recent replacement of `awaitWifiSsid` was therefore not justified by
the livestream notes. Whether both modes happen to expose the same file server
requires hardware verification. Prefer restoring the vendor media sequence in
a follow-up correction, with a test based on media rather than live behavior.

`EyevueReleaseSafetyTest.eyevueMediaSyncUsesVerifiedWifiActivationCommand`
currently asserts the new `startLiveAndAwaitSsid` call and rejects
`awaitWifiSsid`; passing it does not establish vendor compatibility.

Cleanup `0x44 0x30 0x01` matches vendor non-destructive cleanup:
`qfc.java:833` and `Command.java:154-156`. Keep the blocking cleanup improvement.

## Eyevue: peer matching can select a non-matching device

`EyevueWifiTransport.findTargetPeer()` now checks
`target.contains(peer.deviceName.orEmpty(), ignoreCase = true)`. An empty/null
device name becomes an empty string, which matches every target. This can
select the first unnamed peer even when several peers are present, bypassing
the documented single-candidate fallback. Require a nonblank device name
before either substring comparison. The single-peer fallback itself remains
an identity heuristic, not proof that the peer is the glasses.

## TuneBuds: core command order matches the vendor

`TuneBudsManager.startFileManager()` sends `0xE6` with mode 0 and hotspot
SSID/password/channel, then `0xE7`, and waits for the notified HTTP endpoint.
Vendor `AbMateWifiLocalHotspotConnector.java:50` follows exactly that order,
also using channel 0 and a 30-second endpoint timeout. `media.config` is also
used by vendor `MediaDownloadManager.java:83`. No evidence supports replacing
these commands with Eyevue commands; TuneBuds uses its own AB Mate protocol.

Vendor source root for these references:
`TuneBudsOfficialApp/tunebuds-jadx-clean/sources/com/topstep/`.

## TuneBuds: missed busy status

`aibuds/earphone/ABMateManager.java:1529-1543` maps file-manager statuses
**1, 3, and 5** to `WKFileTransferException(10)` (ERROR_BUSY). Our retry loop
handles only 1 and 3; status 5 fails immediately. Status 4 maps to a different
error and must not be treated as busy. Existing hardware logs established
successful retries for 1/3, not 5; adding bounded status-5 retry merits a
targeted follow-up test.

## TuneBuds: Wi-Fi failure notifications are ignored

Vendor `AbMateWifiConnector.java:203-224` monitors Wi-Fi state notifications
and fails on states 0, 4, and 5. The local-hotspot connector merges this monitor
with connection establishment (`AbMateWifiLocalHotspotConnector.java:60`).
Our manager's `handleFrame()` has no `0xEC` branch, so these failures can become
a generic endpoint timeout instead of a prompt, specific failure.

## TuneBuds: cleanup ordering is not awaited

`TuneBudsMediaSync.kt:205-208` calls `manager.finishTransfer()` and immediately
stops the hotspot. `finishTransfer()` launches asynchronous camera-close work,
so call order does not guarantee the `0xE2` command completes before Wi-Fi is
torn down. A suspending, cancellation-safe cleanup would make that ordering
explicit. This is a code-level race risk, not a hardware-confirmed failure.

## Verification limits

Only an emulator was connected during this task. These findings do not prove
that a particular glasses firmware fails. The prior Detailed/Clearer TuneBuds
hardware successes remain relevant. No Android command behavior was changed
as part of this audit.

## Follow-up implementation

Implemented after approval: media import uses Eyevue `0x39`, blank peer names
cannot substring-match, TuneBuds retries status 5 as busy and monitors `0xEC`
failures during connection establishment. TuneBuds media and Detailed photo
cleanup now await camera close before hotspot teardown in a cancellation-safe
context. Cancellation retains the media lease until cleanup finishes.
Hardware verification remains pending.
