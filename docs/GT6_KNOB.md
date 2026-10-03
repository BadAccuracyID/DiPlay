# GT6 CarPlay knob input

On 3 October 2026, the user confirmed physical rotation and selection in wireless CarPlay. That test also exposed Back exiting to DiPlay and a white focus overlay across the video. The follow-up fixes below still require the user to confirm their behavior.

## Identified input path

The locally inspected GT6 EventCenter MCU parser handles button packets with command `0xA1/0x17`. For the OEM ZLink package it sends proprietary key broadcasts. For ordinary apps, including DiPlay, it injects Android navigation keys into the focused window. DiPlay previously handled the voice key but had no handler for these navigation inputs.

| MCU button | Android event for ordinary apps | DiPlay CarPlay report |
| --- | --- | --- |
| 6, one rotation direction | Tab, keycode 61 | Wheel +1 |
| 7, opposite rotation direction | Up, keycode 19 | Wheel -1 |
| 5, select | D-pad Center, keycode 23 | Select press and release |
| 12, Back | Back, keycode 4 | CarPlay Back press and release |
| 1, Up | Up, keycode 19 | Wheel -1 |
| 2, Down | Down, keycode 20 | Y +127 |
| 3, Left | Left, keycode 21 | X -127 |
| 4, Right | Right, keycode 22 | X +127 |

The firmware uses the same Android Up key for both MCU buttons 1 and 7, so this receiver treats both as previous-item navigation. The live MCU observer confirmed rotation packets 6/7, select packet 5 and Back packet 12. The user confirmed rotation and selection work; clockwise direction has not been separately recorded. Enter, numpad Enter and Shift-Tab are also handled. Android rotary-encoder scroll events are supported with fractional-step accumulation and a signed HID range limit.

The phone already receives the advertised knob HID device and display feature. The new handler supplies its missing input reports through the existing encrypted AirPlay event channel.

## Scope and behavior

- Routing is enabled only on GT6-CAR while its CarPlay controller has an active AirPlay session and the CarPlay window is focused, outside the settings menu.
- Back sends CarPlay Back while the GT6 CarPlay route is active. Outside that route, Android Back keeps its existing behavior. Home, volume, media keys, text keys and Ctrl/Alt/Meta shortcuts retain their existing handling.
- The full-screen touch layer is explicitly non-focusable and has Android default focus highlighting disabled. This prevents injected navigation keys from selecting a translucent overlay over the video.
- Use the existing three-finger downward swipe to open DiPlay settings from CarPlay.
- A select down/up pair sends one momentary press. Holding select or Back does not repeatedly activate items or leave multiple screens; navigation repeats can continue moving focus.
- Input state resets after loss of focus and session replacement. Reopening the screen uses the persistent controller's active session.
- Writes run on the existing input worker. A queued report is dropped if its session has been replaced or the controller has closed.
- Each gesture and its neutral release share the event write lock. The diagnostic log distinguishes a rejected queue from an actual event-channel send result. A successful write does not prove that the phone moved focus.

This candidate uses the keys that EventCenter already delivers. The existing Vector button module does not need an update for this path. It changes no MCU commands, root services, radio state, microphone settings or audio routes.

## Local validation

On 3 October 2026, the standalone debug APK build succeeded and all 16 focused tests passed: 12 input tests and 4 controller dispatch tests. Coverage includes OEM rotation aliases, one select per press, navigation repeats, inactive input, ordinary Android keys, fractional scroll, rejected input, encrypted knob press/release payloads, existing-session reuse stale-session rejection and an unavailable event channel.

The APK remains a private local artifact because this standalone build includes authentication assets. Build it using the explicit authentication workflow in `GT6_TRANSPORT.md`. Only source and tests are committed to the fork.

## Recheck against ZLink native CarPlay code

The protected APK exposes mostly a Java loader. Its native `libzjL10001.so` retains the CarPlay input and HID helper symbols, so the second review followed those functions directly. The evidence is stored privately alongside the candidate APK; no vendor binaries or disassembly are committed.

DiPlay's 70-byte knob descriptor is identical to the descriptor returned by ZLink's `HIDKnobCreateDescriptor`. ZLink's `HIDKnobFillReport` uses the same four-byte layout: button bits, X, Y and relative wheel. The native CarPlay key handler maps firmware codes 1501 and 1502 to wheel -1 and +1, followed immediately by a neutral report. Keycode 23 sets select bit 0 and keycode 4 sets Back bit 2; their releases clear the report. These rotation and select payloads match this candidate.

The review corrected directional axes. ZLink sends a full absolute joystick deflection, so a value of one was insufficient to reproduce its direction handling. This candidate now uses X -127/+127 and Y +127. ZLink uses -128 for a negative axis, although its descriptor advertises -127 as the minimum; this candidate stays within the declared range. Android Up remains the previous-rotation alias because the GT6 third-party path merges MCU Up and rotation into the same keycode.

The firmware diverts inputs to its own focus broadcast while a 360 dialog or Bluetooth phone state above 3 is active. The candidate follows ordinary injected keys and does not override those OEM modes. If the next physical test shows missing events, compare the MCU, phone state and focused package before adding a hook.

The expanded dispatch test decrypts the actual queued AirPlay commands and checks negative/positive wheel, select, directional axes and a neutral release after each gesture. A separate test confirms that an unavailable event channel is reported as a failed send. The rebuilt APK passes all 16 tests. The user subsequently confirmed physical rotation and select in wireless CarPlay. The follow-up adds Back to the same encrypted press/release test and suppresses held Back repeats.

## Back and focus follow-up

The follow-up build passed all 17 focused tests (13 input and 4 dispatch). Back is checked as button bit 2 with a neutral release, and a held key produces only one gesture. The build was installed after the user reported the two issues. Physical confirmation of the corrections is still pending.

## Follow-up headunit test

1. Verify the current receiver, Wi-Fi ADB connection and root service state. The previous ZLink handoff ended without a confirmed final device report.
2. Update `com.shihab.diplay.hudtest` with the prepared APK, then open wireless or wired CarPlay. An APK update interrupts the current session.
3. Open a CarPlay app list or menu. Rotate the knob two steps in each direction and press it once. Check focus movement, direction, selection and duplicate actions.
4. Open DiPlay settings and verify that the knob navigates Android controls instead of sending CarPlay input. Confirm Back stays inside CarPlay when its window is active, and retains Android handling outside CarPlay. Check that rotation no longer whitens the video. Home keeps its existing behavior.
5. Reopen an already running CarPlay screen and repeat the rotation/press test.

Capture `adb logcat -s xcertplay-usb HeadunitButtons` for transport and MCU evidence. Input report diagnostics are in the private app file `files/logs/diplay.log`, readable on this rooted headunit. Accepted input logs contain `GT6 knob key=KEYCODE_TAB`, `KEYCODE_DPAD_UP` or `KEYCODE_DPAD_CENTER`, the report values, and `sent=true/false`. Compare those messages with EventCenter and the existing MCU observer if a direction or press is missing. Further Vector changes require confirmed physical input evidence.

## Remaining priorities

P1 is confirming the Back and focus-overlay corrections on the headunit. P2 remains the ZLink phone Wi-Fi handoff, microphone input and speaker audio output.
