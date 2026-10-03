# GT6 CarPlay knob input

The knob candidate is built and unit-tested. It has not been installed or physically tested on the headunit.

## Identified input path

The locally inspected GT6 EventCenter MCU parser handles button packets with command `0xA1/0x17`. For the OEM ZLink package it sends proprietary key broadcasts. For ordinary apps, including DiPlay, it injects Android navigation keys into the focused window. DiPlay previously handled the voice key but had no handler for these navigation inputs.

| MCU button | Android event for ordinary apps | DiPlay CarPlay report |
| --- | --- | --- |
| 6, one rotation direction | Tab, keycode 61 | Wheel +1 |
| 7, opposite rotation direction | Up, keycode 19 | Wheel -1 |
| 5, select | D-pad Center, keycode 23 | Select press and release |
| 1, Up | Up, keycode 19 | Wheel -1 |
| 2, Down | Down, keycode 20 | Wheel +1 |
| 3, Left | Left, keycode 21 | X -1 |
| 4, Right | Right, keycode 22 | X +1 |

The firmware uses the same Android Up key for both MCU buttons 1 and 7, so this receiver treats both as previous-item navigation. Physical clockwise direction and the actual packet IDs emitted by this car's knob remain to be checked. Enter, numpad Enter and Shift-Tab are also handled. Android rotary-encoder scroll events are supported with fractional-step accumulation and a signed HID range limit.

The phone already receives the advertised knob HID device and display feature. The new handler supplies its missing input reports through the existing encrypted AirPlay event channel.

## Scope and behavior

- Routing is enabled only on GT6-CAR while its CarPlay controller has an active AirPlay session and the CarPlay window is focused, outside the settings menu.
- Android Back, Home, volume, media keys, text keys and Ctrl/Alt/Meta shortcuts retain their existing handling.
- A select down/up pair sends one momentary press. Holding select does not repeatedly activate items; navigation repeats can continue moving focus.
- Input state resets after loss of focus and session replacement. Reopening the screen uses the persistent controller's active session.
- Writes run on the existing input worker. A queued report is dropped if its session has been replaced or the controller has closed.
- Each gesture and its neutral release share the event write lock. The diagnostic log distinguishes a rejected queue from an actual event-channel send result. A successful write does not prove that the phone moved focus.

This candidate uses the keys that EventCenter already delivers. The existing Vector button module does not need an update for this path. It changes no MCU commands, root services, radio state, microphone settings or audio routes.

## Local validation

On 3 October 2026, the standalone debug APK build succeeded and all 15 focused tests passed: 12 input tests and 3 controller dispatch tests. Coverage includes OEM rotation aliases, one select per press, navigation repeats, inactive input, ordinary Android keys, fractional scroll, rejected input, encrypted knob press/release payloads, existing-session reuse and stale-session rejection.

The APK remains a private local artifact because this standalone build includes authentication assets. Build it using the explicit authentication workflow in `GT6_TRANSPORT.md`. Only source and tests are committed to the fork.

## Headunit test still required

1. Verify the current receiver, Wi-Fi ADB connection and root service state. The previous ZLink handoff ended without a confirmed final device report.
2. Update `com.shihab.diplay.hudtest` with the prepared APK, then open wireless or wired CarPlay. An APK update interrupts the current session.
3. Open a CarPlay app list or menu. Rotate the knob two steps in each direction and press it once. Check focus movement, direction, selection and duplicate actions.
4. Open DiPlay settings and verify that the knob navigates Android controls instead of sending CarPlay input. Confirm the existing Back/Home behavior.
5. Reopen an already running CarPlay screen and repeat the rotation/press test.

Capture `adb logcat -s xcertplay-usb HeadunitButtons`. Accepted input logs contain `GT6 knob key=KEYCODE_TAB`, `KEYCODE_DPAD_UP` or `KEYCODE_DPAD_CENTER`, the report values, and `sent=true/false`. Compare those messages with EventCenter and the existing MCU observer if a direction or press is missing. Further Vector changes require confirmed physical input evidence.

## Remaining priorities

P1 is physical knob validation and any correction it reveals. P2 remains the ZLink phone Wi-Fi handoff, microphone input and speaker audio output.
