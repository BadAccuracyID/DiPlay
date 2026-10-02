# CarPlay Switch for GT6-CAR

CarPlay Switch gives the headunit two buttons: **Use DiPlay** and **Use ZLink**. It releases the current receiver before opening the selected app. Switching interrupts the current CarPlay session and may disconnect Wi-Fi ADB.

## Requirements

- The tested GT6-CAR firmware and Magisk root access.
- ZLink installed, with its original native `zlink5` service.
- A GT6 DiPlay debug build containing the protected wireless launch activity and metadata version 1.
- The existing `/data/adb/hotspot/helper.apk` helper for restoring ZLink's saved hotspot configuration.
- An iPhone already paired through the OEM Bluetooth settings.

## Use

1. Open **CarPlay Switch** from the headunit launcher.
2. Tap **Use DiPlay** or **Use ZLink**.
3. Allow Magisk access if requested. DiPlay also needs its own root grant.
4. Accept an iPhone CarPlay prompt if one appears, and wait for connection.

Use DiPlay stops ZLink's Android app and native supervisor, releases the selected phone's OEM projection profile, removes the previous P2P group, stops the saved hotspot, enables Wi-Fi client mode, and opens DiPlay with the GT6 transport and Wi-Fi Direct selected.

Use ZLink closes DiPlay and its P2P group, releases the selected phone's profile, starts the original saved hotspot, restarts the native ZLink supervisor, and opens ZLink. The saved hotspot credentials are handled by the existing root helper and are never embedded in the switcher.

The switcher validates the exact native firmware and checks the actual projection peer before changing apps or radios. It refuses another phone or an incompatible projection profile. Operations share a private file lock. Protocol release is an explicit user action; automatic DiPlay startup still refuses occupied sessions.

## Persistent changes and recovery

Selection is saved under `/data/adb/gt6-carplay-switch/`. A Magisk boot script at `/data/adb/service.d/gt6-carplay-switch.sh` stops the native ZLink supervisor after boot when DiPlay is selected. It does not automatically start CarPlay or change Wi-Fi at boot. If the selected DiPlay package is absent, it restores ZLink selection.

DiPlay mode pauses the existing hotspot recovery watcher using `/data/local/tmp/skip_softap_boot`. The switcher records whether it created that pause and only removes a pause it owns when selecting ZLink.

**Select Use ZLink before uninstalling CarPlay Switch or the GT6 DiPlay build.** This restores the native supervisor and the switcher's hotspot watcher pause. Removing the switcher alone does not remove its Magisk boot script. After restoring ZLink, the switcher's boot script and private state directory can be removed through root access.

If a switch reports an error, grant root access and retry **Use ZLink**. The failure path attempts to restart ZLink's supervisor and restore the watcher. An error does not prove that hotspot recovery or CarPlay reconnection completed.

## Build

Build the standalone switcher with `./gradlew :gt6switch:assembleDebug`. Its APK is `gt6switch/build/outputs/apk/debug/gt6switch-debug.apk`; it contains no MFi authentication assets. See `GT6_TRANSPORT.md` for the separate DiPlay build and authentication prerequisites. The wireless launch entry point and automated test activity are restricted to shell/root through Android's DUMP permission and are included only in debug builds.

## Hardware validation

On 3 October 2026, the installed switcher's **Use DiPlay** button stopped ZLink's native supervisor and opened a working wireless Waze CarPlay session under normal app root grants. The switcher ran as UID 10165 and DiPlay as UID 10164. Focused GT6 tests passed: 21 tests, zero failures.

The return to ZLink did not restore the phone’s wireless CarPlay connection. The user can open ZLink manually, but it does not make the phone join its Wi-Fi. Wi-Fi ADB dropped during the handoff and briefly became reachable after the return timer; the final switch reports were not retrieved before the session ended. Complete round-trip reconnection and boot behavior remain unverified. Successful app launch alone is insufficient to claim CarPlay reconnection.

## Next session priorities

- P1: physical scrolling knob input inside DiPlay CarPlay.
- P2: ZLink switcher restoration of the phone’s Wi-Fi handoff.
- P2: microphone input.
- P2: speaker audio output.

Device testing was stopped at the user’s request around 02:00 on 3 October 2026. No knob, microphone, or speaker fix was installed during this session.
