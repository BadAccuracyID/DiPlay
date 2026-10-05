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

Selection is saved under `/data/adb/gt6-carplay-switch/`. A Magisk watcher at `/data/adb/service.d/gt6-carplay-switch.sh` waits for Android boot, Bluetooth and ACC readiness. Version 0.5 prepares selected DiPlay in the background at boot/wake, leaving the current screen visible. It does not open DiPlay or request a phone connection. ZLink retains its existing startup path. Camera focus defers preparation, and the watcher's lock prevents duplicate watchers.

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

## Offline candidate 0.3.0, 4 October 2026

Version 0.3.0 is built locally and has not been installed or tested on the headunit. No ADB connection or device command was attempted during this session.

The startup watcher now treats ACC off/on as a wake event even if Android remains Awake. Failed power or window-focus reads defer launch. Failed Android activity launches retry up to three times, including failures where `am` prints an error and returns exit status zero. The coordinator also checks these launch errors before saving a new receiver choice. The root command runner drains stdout and stderr concurrently, bounds retained output, and enforces process timeouts.

Selecting ZLink starts a read-only diagnostic collector after launching the receiver and saving its selection. It records at most 30 samples, with a three-minute process limit. It stops sampling when the saved receiver changes. The report records hotspot configuration match/state, Java/native service presence, local projection interface addresses, native TCP listener ports when `ss` is available, foreground connection mode, and fixed event counts. It does not copy credentials, certificates, Bluetooth addresses or raw vendor log lines. Native counts cover the latest rotating log, so they can include earlier events; interpret them alongside sample times and state changes.

Use **Save connection report** to export the latest capture into `Downloads/CarPlaySwitch`. The file can be saved while capture is still running. This storage/export path needs headunit validation. Opening the switcher can affect ZLink's foreground eligibility; the capture starts while ZLink is open so it can record the earlier handoff.

Offline review of the 3 October WPA2 test found successful iPhone authentication, followed by ZLink's own `wirelessCmd stopHotspot`. Android reported the AP disabling, then the native receiver reported AP disconnected and its iAP watchdog timed out. This happened twice in the saved trace. It establishes that ZLink requested the stop; it does not establish why it did so or prove a fix. This candidate does not suppress that cleanup command.

Local verification: 22 tests pass across the coordinator, actual generated watcher shell with synthetic Android responses, root process I/O/timeouts, and diagnostic filtering. The APK builds and both generated scripts pass host shell syntax checking. No authentication asset files were found in the candidate APK.

Next in-car sequence: install 0.3.0, choose DiPlay once to install its updated watcher, load/configure the selected-receiver Navi hook, then test boot and ignition wake. Run one controlled ZLink handoff with a return timer and inspect the captured report alongside the native AirPlay endpoint/service state. Preserve the working DiPlay build and the WPA2 hotspot configuration. ZLink CarPlay reconnection remains unresolved.

## Version 0.4: wake recovery, 2026-10-05

The user reported that morning startup failed and required killing/reopening the switcher and choosing DiPlay again. No ADB was available to retrieve the failure log. The last verified installed switcher was 0.2; the 0.3 candidate was built offline. The specific morning failure remains unconfirmed.

Version 0.4 addresses the known gaps:

- Treat ACC off/on and a polling gap of at least 20 seconds in `/proc/uptime` as wake events. This handles a suspend that hides the entire off/on transition from the poller. Failed power, ACC, or focus reads still defer startup.
- Wait for a request-specific coordinator result. Android accepting `am start` does not disarm startup. DiPlay startup also requires its connection service to appear. This confirms receiver startup, not iPhone connection or rendered CarPlay.
- Retry failed launches or coordinator operations after 15 seconds. After three failures, wait 60 seconds before another batch. Defer retries while another app or the camera is focused. A pending request expires after four minutes; changing the saved receiver invalidates it.
- Keep a pending launch across missing Bluetooth readiness and camera focus. Boot readiness no longer expires permanently after four minutes.
- Queue a new startup intent if the existing activity is working. Finish a failed automatic attempt so the failure screen does not remain over the launcher.
- Refresh the watcher when opening CarPlay Switch without switching receivers or resetting radios. If the installed script changes, stop only shell interpreters whose second argument is the exact watcher path, then start the replacement detached from the app. The file lock still allows one watcher. Child commands close the watcher's lock descriptor.
- Include fixed startup events and the latest result in **Save connection report**. Vendor payloads, authentication material, and raw `am` output are excluded.

Install both new candidate APKs when ADB is available. Open CarPlay Switch once after updating it to replace the old watcher; root permission must already be granted or accepted. Verify `watcher-version=4`, a live watcher, and service startup after reboot and ignition wake. Installing an APK alone does not replace an already running root watcher.

The watcher intentionally opens the saved receiver at boot/wake. After successful startup it does not continuously bring CarPlay over other apps. A delayed-phone connection is owned by DiPlay's existing reconnect controller; its reliability needs the phone-absent/later-arrival car test.

## Version 0.5: prepare DiPlay without opening it

The user requested background preparation at startup and control through DiPlay's **Automatic connection** setting. Version 0.5 supersedes the DiPlay opening behavior described for earlier versions above.

- Boot/wake starts the shell/root-only `PrepareSelectedService`, a short foreground service with no activity. It validates the saved receiver and native peer, stops ZLink's Android app and native supervisor, releases only an occupied compatible profile belonging to the verified last phone, clears the previous projection network, and leaves Wi-Fi ready for DiPlay.
- An already active DiPlay service is preserved, including its screen and radios. Preparation never force-stops either DiPlay package, opens a DiPlay activity, requests a new CarPlay connection, or edits DiPlay preferences.
- The request-specific success receipt now confirms radio preparation for DiPlay. It does not require a DiPlay connection service and does not claim the phone connected. Failures retain the existing retries and cooldown.
- DiPlay's setting is **connect when DiPlay opens**. With it off, opening the app leaves connection under its **Connect phone** button. With it on, normal opening uses DiPlay's saved connection type and selected phone. Boot/wake itself opens neither app nor CarPlay.
- The GT6 manual connection helper no longer resets Automatic connection to off. The switcher's explicit **Use DiPlay** handoff remains an intentional connection request. Its setting is preserved.
- Existing DiPlay `BootReceiver` overrides remain disabled on this GT6 so a separate boot launcher cannot race the selected-receiver watcher. Startup selection remains with CarPlay Switch.

Install the updated DiPlay APK and CarPlay Switch 0.5, then open the switcher once to replace the watcher. Expected `watcher-version=5`. Test a real reboot: launcher stays visible, DiPlay has no new connection service, ZLink native service is stopped, radio preparation has a matching receipt, and the saved Automatic connection value is unchanged. Then test normal app opening with the setting off and on. Existing ignition sleep/wake and physical headlight tests still require the car.

## Version 0.6: faster Navi connection

After a successful background preparation, the switcher saves a receipt tied to the current Android boot and installed DiPlay package. Navi checks that receipt plus the live ZLink supervisor, hotspot, Wi-Fi, and OEM projection peer state. When they still match the prepared state, it starts DiPlay's existing wireless connection helper directly, skipping the repeated app force-stop, peer release, P2P reset, and hotspot stop. An active DiPlay session keeps its existing reopen path. A missing or stale receipt, or changed radio state, uses the checked full handoff. The receipt is cleared before any new handoff, including a switch to ZLink.
