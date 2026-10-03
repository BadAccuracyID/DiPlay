package com.shilapi.xcertplay

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState

/** The GT6 MCU parser injects Tab / Up for rotation and D-pad Center for select. */
internal class Gt6CarPlayKnobInput(private val send: (AirPlayKnobState, String) -> Boolean) {
    private val capturedKeys = mutableSetOf<Int>()
    private var scrollRemainder = 0.0

    fun key(event: KeyEvent, active: Boolean): Boolean {
        if (!active) {
            reset()
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) return capturedKeys.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.isCanceled || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return false
        val report = when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> AirPlayKnobState(wheel = if (event.isShiftPressed) -1 else 1)
            KeyEvent.KEYCODE_DPAD_UP -> AirPlayKnobState(wheel = -1)
            // X/Y are absolute joystick axes, unlike the relative rotation wheel.
            // Use a full deflection within the descriptor's advertised -127..127 range.
            KeyEvent.KEYCODE_DPAD_DOWN -> AirPlayKnobState(y = 127)
            KeyEvent.KEYCODE_DPAD_LEFT -> AirPlayKnobState(x = -127)
            KeyEvent.KEYCODE_DPAD_RIGHT -> AirPlayKnobState(x = 127)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                AirPlayKnobState(select = true)
            else -> return false
        }
        // A held select must not activate several CarPlay items. Rotation may repeat.
        if (report.select && event.repeatCount > 0) return event.keyCode in capturedKeys
        if (!send(report, "key=${KeyEvent.keyCodeToString(event.keyCode)} repeat=${event.repeatCount}")) return false
        capturedKeys += event.keyCode
        return true
    }

    fun motion(event: MotionEvent, active: Boolean): Boolean {
        if (!active) {
            reset()
            return false
        }
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false
        val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL).toDouble()
        if (!delta.isFinite()) return false
        // Android positive scroll moves upward; the CarPlay next-item wheel uses positive steps.
        val accumulated = (scrollRemainder - delta).coerceIn(-127.0, 127.0)
        val steps = accumulated.toInt()
        if (steps == 0) {
            scrollRemainder = accumulated
            return true
        }
        scrollRemainder = accumulated - steps
        if (send(AirPlayKnobState(wheel = steps), "rotary delta=$delta")) return true
        scrollRemainder = 0.0
        return false
    }

    fun reset() {
        capturedKeys.clear()
        scrollRemainder = 0.0
    }
}
