# GT6 OEM Bluetooth transport

Status: experimental, full wireless CarPlay tested on the car on 2026-10-03. Branch: `gt6-oem-bluetooth-transport`.

The installed standalone GT6 test app completed Bluetooth bootstrap, iPhone authentication, Wi-Fi Direct, encrypted AirPlay control, tunnel iAP2 handoff, and video rendering on 2026-10-03. The user confirmed wireless CarPlay and a screenshot shows Waze. Startup took approximately 31 seconds on the successful attempt. The first full attempt stalled, so repeatability and long-term stability remain unverified. The repository option defaults to off; it is enabled in the installed test package.

## Activation and peer verification

The GT6-CAR Android Bluetooth backend rejects Apple's iAP2 UUID before opening a socket. The OEM projection service at `127.0.0.1:3152` can carry that transport. Its phone-link request `0x201` has no registered handler on the tested firmware, so the revised GT6 path uses request `0x101` with profile mask `1`, selecting CarPlay.

A live probe confirmed the registered callback at native handle offset `0x2c8`. The native service reconnects its last phone and opens Apple's RFCOMM service. It also reconnects HFP and A2DP. The Android Bluetooth radio stays enabled. The adapter requires the last phone in the OEM configuration to match the selected phone before activating this path.

The service's basic connected status can appear before RFCOMM is connected, and its peer status leaves the remote address blank. Neither establishes phone ownership. `Gt6NativePeerInspector` therefore reads the actual Apple connection index, state, and remote address through root. It does not write process memory or modify firmware.

The inspector requires this exact `/system/bin/blink` SHA-256:

```text
e13e75e5b88724dd7f350801b66249002082bf45572c91f979cca8270a6382f6
```

It discovers the running native process and its load address, removes pointer tags, and checks the native connection table. Only a connected record named `iphone` with the selected phone's address confirms the stream. Firmware changes, missing root, another active projection profile, and a different last phone fail before activation. The adapter rechecks the peer before sending and accepting RFCOMM data.

The general abstract socket `com.chengqian.localsocket` replaces its existing Android Bluetooth client when another client connects. BTSuite owns the legacy serial receive port. The adapter uses the separate TCP projection service and read-only native inspection.

## Protocol and lifecycle

Frames have a 16-byte big-endian header: magic `0xffff`, version `0x101`, message type, and payload length. Payloads and queued bytes are limited to 64 KiB.

| Type | Meaning | Adapter behavior |
| --- | --- | --- |
| `0x101` | Initialize projection Bluetooth | Requests mask `1` after native preflight. Releases its owned profile with mask `0`. |
| `0x102` | Identity | Requires a vendor identity and valid OEM local Bluetooth address. |
| `0x10c` | Status with peer | Decodes status; a blank peer cannot confirm a phone. |
| `0x104` | Basic status | Can report disconnect; cannot establish peer ownership. |
| `0x105` | RFCOMM data | Carries raw binary bytes in both directions. |
| `0x201` | Request phone link | Retained as a protocol decoder and test fixture. The GT6 public constructor uses native profile activation. |

The adapter starts only from an idle projection profile. Closing, timeout, or a transport failure releases the profile when it still owns mask `1` and the active peer is either the selected phone or disconnected. It does not disable another phone's active profile. Native profile release can also disconnect the selected phone's OEM HFP/A2DP connections.

The controller keeps the same stream and iAP2 session after preflight and uses the OEM Bluetooth address during wireless identification. It requires an actual iAP2 handshake before starting configured AP/P2P setup. The existing Android RFCOMM backend remains the default.

## On-car evidence

The first probe timed out after request `0x201`. Read-only inspection proved its callback at offset `0x2e0` was null; the raw receive callback at `0x2b8` was registered.

The revised probe loaded the built adapter APK through a temporary root `app_process` entry point. It called the actual `Gt6OemBluetoothStream` and `Iap2Session.openWireless()`. Output included:

```text
GT6 native Apple RFCOMM peer confirmed
TARGET_STATUS_CONFIRMED
IAP2_LINK_READY=true
PASS Bluetooth/iAP2 only; Wi-Fi handoff not tested
```

Native logs independently showed the selected iPhone connecting and received RFCOMM payloads of 6, 29, and 16 bytes. After closing, the profile mask was `0` and the Apple connection index was `255` (disconnected). Wi-Fi stayed in client mode on the iPhone hotspot at `172.20.10.2`, and ADB remained usable. No AP/P2P setup, pairing, firmware changes, or service restart was performed.

That initial probe did not install or launch the DiPlay app. The subsequent app installation and root failure are recorded below. Private evidence is retained in the headunit workspace at `carplay-tests/gt6-transport-research/legacy-20261003-000734/`. The preceding failed test is in `live-20261002-235557/`. Device probe files were removed after testing.

## Local validation and build

All 19 focused tests passed. The shared and host Kotlin code compiled, and the debug APK build succeeded. Tests cover framing, malformed inputs, native peer decoding, stale status, target changes, data ownership, timeout, cancellation, profile cleanup, and the distinction between connected status and actual iAP2 readiness.

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
./gradlew :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.transport.gt6.*' \
  :common:compileDebugKotlin :mobile:assembleDebug --console=plain
```

The source build is `mobile/build/outputs/apk/debug/mobile-debug.apk`, package `com.shihab.diplay.hudtest`. Ordinary `assembleDebug` includes no MFi accessory identity. A standalone car build requires the existing explicit authentication input and `:mobile:assembleStandaloneDebug`; see `mobile/build.gradle.kts`. No credentials or vendor binaries were added to this repository.

## Standalone app check, 2026-10-03

`DiPlay GT6 Test` (`com.shihab.diplay.hudtest`, version `0.2.8-hud-test`) is installed alongside production DiPlay and ZLink. The explicit standalone build used the two authentication assets from the existing local DiPlay 0.2.8 release. The packaged assets matched the selected inputs byte for byte. They remain in the ignored private input directory and are not repository source.

The debug-only `Gt6BluetoothTestActivity` is protected by `android.permission.DUMP` and runs under the normal app UID (10164 on this device). It loaded the certificate and matching private key successfully. This proves local asset provisioning and key consistency; successful authentication by the iPhone remains untested.

The check saves auto-connect off, the selected phone, GT6 transport on, and wireless off in the test package only. It never starts the wireless controller or Wi-Fi APIs. A root preflight allows 30 seconds for the first Magisk permission prompt, followed by the existing short read-only native inspections.

The first app check failed before native profile activation: root inspection was unavailable. Both shell and app `su` requests now report `Cannot connect to daemon: Connection refused (os error 111)`. Root worked at the beginning of this installation session. After a read-only Magisk CLI policy query returned `failed to fill whole buffer`, subsequent requests failed. The cause is unconfirmed. `magisk --daemon` from the unprivileged shell did not restore it; this production firmware also rejects `adb root`. Existing root processes remain alive.

Magisk displayed a setup dialog that would reboot; it was cancelled. No reboot, Magisk repair, module change, or Wi-Fi mode change was performed. The user can provide USB ADB for recovery and further testing; the USB connection has not yet been observed. Wi-Fi ADB still works at `172.20.10.2`.

To rerun the Bluetooth-only check after root is restored:

```sh
adb -s DEVICE_SERIAL shell am start \
  -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.Gt6BluetoothTestActivity \
  --es phone YOUR_PAIRED_IPHONE_ADDRESS
adb -s DEVICE_SERIAL shell logcat -d -s Gt6AppTest:I
```

The report is also saved in the test app's private `files/gt6-bt-check.txt`. This firmware rejects `run-as` because `/data` has mode 777; use the tagged logcat output or root after recovery. Private installation evidence is retained in `carplay-tests/gt6-transport-research/app-20261003-0101/` in the headunit workspace.


## Full wireless CarPlay works, 2026-10-03

This result supersedes the earlier root blocker and unverified wireless status. The user authorized a headunit reboot and subsequently authorized Wi-Fi Direct/hotspot handoff for the full test. The reboot restored Magisk root access and Wi-Fi ADB reconnected at `172.20.10.2`. No Magisk setup, module replacement, or firmware change was required.

The installed `DiPlay GT6 Test` app (`com.shihab.diplay.hudtest`, UID 10164) passed root, selected-phone RFCOMM, and iAP2 negotiation from its ordinary app process. After reboot, the OEM projection service still held the selected phone even with profile mask zero. The first app preflight correctly refused that occupied session. ZLink's Android app was force-stopped, and a diagnostic request released only the confirmed selected phone's profile before retrying. Its native service was retained.

Full wireless authentication was accepted by the iPhone. Wi-Fi Direct formed on 5 GHz/channel 149 and the phone joined. The first full attempt stayed at Opening CarPlay; a three-minute recovery timer stopped that attempt and restored Wi-Fi client mode. The cause of that first stall is unconfirmed.

A second attempt with a fresh group connected completely. The logs show encrypted AirPlay control, the AirPlay session, tunnel iAP2 authentication, video stream setup, and the first rendered frame. Startup to the first frame took approximately 31 seconds. The user confirmed wireless CarPlay, and a screenshot independently shows Waze rendered on the headunit. The Bluetooth bootstrap was released after tunnel readiness; native state returned to profile mask zero and Apple index 255 while wireless CarPlay continued.

The advertised display is 1920x720 with a maximum of 30 FPS. Later Waze samples show approximately 20 received/displayed FPS. The user reported lag and suspected phone overheating; that cause is unverified. This establishes a working connection, not repeatable startup, long-term stability, or an overheating diagnosis.

The successful Wi-Fi Direct session coexists with Wi-Fi ADB at `172.20.10.2`. The second recovery timer was cancelled so the working CarPlay session could stay connected. Diagnostic packet capture and logcat processes were stopped, temporary device files and the temporary hotspot-recovery pause marker were removed, and the diagnostic TCP forward was removed. Auto-connect remains off in the test app; GT6 transport and Wi-Fi Direct are selected. ZLink remains installed, with its Android app closed for this session.

Audio output, microphone, and scrolling-knob fixes remain deferred and were not retested. Reconnects, boot-time contention with ZLink, repeated cold starts, and sustained use still need validation.

Private evidence: `carplay-tests/gt6-transport-research/app-20261003-0101/` (validation metadata, normal-app report, wireless logs, packet captures, screenshots, and device state). The exact installed APK is saved at `carplay-tests/DiPlay-GT6-Test-20261003.apk`; its SHA-256 matches the final local standalone build and is recorded in `build.json`.

## Remaining car tests

1. Verify repeatable cold starts and reconnects, including contention with the OEM projection service after boot. The adapter refuses an occupied session.
2. Measure startup and lag during sustained use, including phone temperature and display settings. One successful connection does not establish reliability.
3. Test coexistence with the OEM Bluetooth app and deliberate switching back to ZLink.

Audio output, microphone, and scrolling-knob forwarding remain separate deferred compatibility work.

## GT6 knob candidate, 2026-10-03

A GT6 navigation-key handler and controller input dispatch are implemented. The standalone APK builds and 15 focused tests pass. Installation and physical knob behavior remain unverified because the user resumed from home without ADB. See [GT6_KNOB.md](GT6_KNOB.md) for the identified firmware input path and the headunit test.
