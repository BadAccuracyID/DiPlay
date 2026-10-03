# CarPlay Switch for GT6-CAR

CarPlay Switch gives the headunit two buttons: **Use DiPlay** and **Use ZLink**. It releases the current receiver before opening the selected app. Switching interrupts the current CarPlay session and may disconnect Wi-Fi ADB.

## Requirements

- The tested GT6-CAR firmware and Magisk root access.
- ZLink installed, with its original native `zlink5` service.
- A GT6 DiPlay debug build containing the protected wireless launch activity and metadata version 1.
- Root helper `hotspotctl.sh` beside `helper.apk`, with the original saved hotspot configuration.
- The existing `/data/adb/hotspot/helper.apk` helper for restoring ZLink's saved hotspot configuration.
- An iPhone already paired through the OEM Bluetooth settings.

## Use

1. Open **CarPlay Switch** from the headunit launcher.
2. Tap **Use DiPlay** or **Use ZLink**.
3. Allow Magisk access if requested. DiPlay also needs its own root grant.
4. Accept an iPhone CarPlay prompt if one appears, and wait for connection.

Use DiPlay stops ZLink's Android app and native supervisor, releases the selected phone's OEM projection profile, removes the previous P2P group, stops the saved hotspot, enables Wi-Fi client mode, and opens DiPlay with the GT6 transport and Wi-Fi Direct selected.

Use ZLink closes DiPlay and its P2P group, releases the selected phone's profile, repairs the saved hotspot country/channel/configuration, restarts the native ZLink supervisor, and opens ZLink with its normal launcher action and category. The saved hotspot credentials are handled by the existing root helper and are never embedded in the switcher.

The switcher validates the exact native firmware and checks the actual projection peer before changing apps or radios. It refuses another phone or an incompatible projection profile. Operations share a private file lock. The coordinator releases only the verified last phone with a compatible projection profile. An active session is preserved by the shortcut path.

## Persistent changes and recovery

Selection is saved under `/data/adb/gt6-carplay-switch/`. Version 0.2 installs a Magisk watcher at `/data/adb/service.d/gt6-carplay-switch.sh`. After Android boot and Bluetooth readiness, it opens the saved receiver through the same coordinator used by the app. It also opens the receiver after Android reports a sleep-to-awake transition, deferring while the reversing camera is focused. The watcher's lock prevents duplicate watchers.

An active DiPlay session is reopened without changing radios or releasing its phone. ZLink with a running native process and matching hotspot is also reopened without resetting it. Otherwise, the coordinator performs the checked handoff. Missing patched DiPlay falls back through the full ZLink handoff. If the switcher is removed, the watcher restores the native ZLink supervisor and its owned hotspot pause, then exits; that recovery alone does not prove CarPlay connected.

DiPlay mode pauses the existing hotspot recovery watcher using `/data/local/tmp/skip_softap_boot`. The switcher records whether it created that pause and only removes a pause it owns when selecting ZLink.

**Select Use ZLink before uninstalling CarPlay Switch or the GT6 DiPlay build.** This restores the native supervisor and the switcher's hotspot watcher pause. Removing the switcher alone does not remove its Magisk boot script. After restoring ZLink, the switcher's boot script and private state directory can be removed through root access.

If a switch reports an error, grant root access and retry from CarPlay Switch. A failed operation preserves the previous saved choice, although earlier service or radio steps may already have run. The app does not silently select another receiver. Selection is saved only after the requested receiver launch succeeds; this is not proof the phone connected.

## Build

Build the standalone switcher with `./gradlew :gt6switch:assembleDebug`. Its APK is `gt6switch/build/outputs/apk/debug/gt6switch-debug.apk`; it contains no MFi authentication assets. See `GT6_TRANSPORT.md` for the separate DiPlay build and authentication prerequisites. The wireless launch entry point and automated test activity are restricted to shell/root through Android's DUMP permission and are included only in debug builds.

## Hardware validation

On 3 October 2026, the installed switcher's **Use DiPlay** button stopped ZLink's native supervisor and opened a working wireless Waze CarPlay session under normal app root grants. The switcher ran as UID 10165 and DiPlay as UID 10164. Focused GT6 tests passed: 21 tests, zero failures.

The return to ZLink did not restore the phone’s wireless CarPlay connection. The user can open ZLink manually, but it does not make the phone join its Wi-Fi. Wi-Fi ADB dropped during the handoff and briefly became reachable after the return timer; the final switch reports were not retrieved before the session ended. Complete round-trip reconnection and boot behavior remain unverified. Successful app launch alone is insufficient to claim CarPlay reconnection.

## Earlier paused-session priorities

- P1: physical scrolling knob input inside DiPlay CarPlay.
- P2: ZLink switcher restoration of the phone’s Wi-Fi handoff.
- P2: microphone input.
- P2: speaker audio output.

Device testing was stopped at the user’s request around 02:00 on 3 October 2026. No knob, microphone, or speaker fix was installed during this session.

## Validation before default setup, 2026-10-03

The user resumed testing and confirmed wireless CarPlay, knob rotation/select/Back, speaker audio and microphone input with the patched DiPlay package `com.shihab.diplay.hudtest`. The switcher remains installed. ZLink's native service is stopped, and the saved selection is DiPlay. The original DiPlay package `com.shihab.diplay` and OEM ZLink remain installed.

Readback confirms the existing Magisk selection script only stops ZLink after a 30-second boot delay. It does not launch DiPlay. The patched app's BootReceiver is disabled by a device component override, despite being enabled in the mobile manifest. Automatic startup, reconnects after sleep and a full reboot have not been tested.

## Default receiver plan recorded before version 0.2

The tested patched package can remain installed as the primary receiver. Replacing the original package is optional: it needs compatible signing or a controlled data/settings migration, and the current switcher already prefers the patched package. The private standalone build must retain its authentication provisioning.

The next implementation should use the switcher's saved selection as the authority for both startup and the car shortcut:

1. Extend startup to wait for Android boot and Bluetooth readiness, stop ZLink only when DiPlay is selected, release only a confirmed previous connection owned by the selected phone, and launch the tested GT6 wireless path with that phone. Reuse the existing selection handling and serialize it with manual switching. A fixed boot delay alone is not a readiness check.
2. Route the CarPlay/Navi shortcut to the selected receiver. The current Vector button configuration has a ZLink mapping; an app shortcut should open the selected receiver without tearing down an already active CarPlay session. Keep camera and power mode handling.
3. Preserve CarPlay Switch and its ZLink action. Fix the unresolved return-to-ZLink Wi-Fi handoff before claiming a complete reversible default setup. Selecting ZLink must also suppress DiPlay auto-start.
4. Test a full reboot, sleep/wake, phone absence and later arrival, and a complete DiPlay-to-ZLink-to-DiPlay round trip. Keep the working private APK as the restore artifact.

Enabling the ordinary DiPlay BootReceiver alone is insufficient: it opens the DiPlay home screen, the tested GT6 launch path sets auto-connect off, and it has no switcher-selection coordination. A coordinated startup path is needed to avoid racing ZLink or relaunching DiPlay after the user selects ZLink.

This default setup is a proposed next change. No boot, radio, package removal or shortcut change was made while recording this plan.

## Version 0.2 implementation and diagnostics

The button module's **Open selected CarPlay app** action targets the coordinator. Configure Navi with this action; keep the chooser's **Use DiPlay** and **Use ZLink** buttons for actual switching. The original DiPlay stays installed beside the patched build. Its ordinary BootReceiver is not used for GT6 startup.

Eight coordinator tests cover active-session reopening, invalid selection, another phone, radio/native/launch ordering, launch/repair failures, and missing patched DiPlay. These are host tests; reboot and ignition sleep/wake still need device confirmation. Startup reports are in the switcher's private files; the root watcher records `startup.log` in its state directory.

A 3 October handoff test exposed an OEM supervisor quirk: `zlink5.sh` greps process command lines for its native child name. A long polling shell containing that name made the supervisor think the child was already running. Version 0.2 polls with short commands and a split quoted name. The next device test started the native process, Android DaemonService and BluetoothService, and restored the saved hotspot. The user reported ZLink opened without CarPlay.

That failed test sent native `link_mode=0`, while ZLink logged `isAllowConn=false` even in the foreground. Its background-connection setting remained OFF. Subsequent diagnostic probes enabled the foreground gate and produced mode 2063 with background connection still OFF. This confirms the background setting is not required for foreground connection. The cause of the initial gate state and complete phone handoff are still under investigation. Private vendor payloads and logs are excluded from this repository.

## End of session, 3 October 2026

The second handoff test had `isAllowConn=true`, native mode 2063, and active Java/native services. The iPhone rejected the hotspot with a changed-security warning. The saved Android hotspot configuration used type 2 (WPA2/WPA3 SAE transition), while ZLink's legacy configuration reported WPA2 PSK. The advertised password matched the saved password.

The saved hotspot configuration was backed up on the headunit as `/data/adb/hotspot/diego_softap.xml.pre-zlink-wpa2-20261003`, then changed to type 1 (WPA2 PSK). SSID and password were preserved. The user confirmed the iPhone could join afterward, but CarPlay still failed. WPA2-only joining is confirmed; a complete ZLink CarPlay return remains unresolved.

The return timer restored DiPlay. Live logs showed incoming CarPlay video and touch responses, and root readback confirmed `selected=diplay` and `init.svc.zlink5=stopped`. Switcher 0.2.0 and the updated watcher were installed; the watcher passed Android shell syntax checking and SHA-256 readback. The button APK is version 0.8, but EventCenter has not restarted to load that hook. Navi retains its existing direct DiPlay mapping.

The user requested wrapping up. No reboot or further handoff test was run. Next session: configure Navi's selected-receiver action and reboot to load the new hook, verify selected startup and ignition sleep/wake, and trace ZLink's remaining CarPlay session failure. Do not report those checks as completed.
