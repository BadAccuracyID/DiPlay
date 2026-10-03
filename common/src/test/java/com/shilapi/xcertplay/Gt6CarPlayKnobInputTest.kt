package com.shilapi.xcertplay

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Gt6CarPlayKnobInputTest {
    private val sent = mutableListOf<AirPlayKnobState>()
    private var channelReady = true
    private val input = Gt6CarPlayKnobInput { report, _ ->
        if (channelReady) sent += report
        channelReady
    }

    @Test fun oemRotationKeysProduceOppositeWheelReports() {
        press(KeyEvent.KEYCODE_TAB)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), AirPlayHid.knobReport(sent[0]))
        assertArrayEquals(byteArrayOf(0, 0, 0, -1), AirPlayHid.knobReport(sent[1]))
    }

    @Test fun downUpPairSendsOnlyOneMomentaryReport() {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(AirPlayKnobState(select = true)), sent)
        assertFalse(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER), true))
    }

    @Test fun holdingSelectDoesNotActivateSeveralItems() {
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), true))
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeat = 1), true))
        assertTrue(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER), true))
        assertEquals(1, sent.size)
        assertFalse(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeat = 2), true))
    }

    @Test fun navigationRepeatsStillMoveFocus() {
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB), true))
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, repeat = 1), true))
        assertEquals(listOf(1, 1), sent.map { it.wheel })
    }

    @Test fun backSendsCarPlayBackOnceAndConsumesItsRelease() {
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true))
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, repeat = 1), true))
        assertTrue(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true))
        assertEquals(listOf(AirPlayKnobState(back = true)), sent)
        assertArrayEquals(byteArrayOf(4, 0, 0, 0), AirPlayHid.knobReport(sent.single()))
        assertFalse(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true))
        assertFalse(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), false))
    }

    @Test fun directionKeysAndShiftTabAreSupported() {
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        assertEquals(listOf(AirPlayKnobState(y = 127), AirPlayKnobState(x = -127),
            AirPlayKnobState(x = 127), AirPlayKnobState(wheel = -1)), sent)
    }

    @Test fun ordinaryAndroidButtonsAndShortcutsPassThrough() {
        for (code in listOf(KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_A)) {
            assertFalse(input.key(key(KeyEvent.ACTION_DOWN, code), true))
            assertFalse(input.key(key(KeyEvent.ACTION_UP, code), true))
        }
        for (meta in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON, KeyEvent.META_META_ON)) {
            assertFalse(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, meta = meta), true))
        }
        assertTrue(sent.isEmpty())
    }

    @Test fun inactiveCarPlayDoesNotConsumeKeysOrKeepTheirReleases() {
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB), true))
        assertFalse(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER), false))
        assertFalse(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB), true))
        assertEquals(1, sent.size)
    }

    @Test fun failedEventChannelLeavesAndroidHandlingAvailable() {
        channelReady = false
        assertFalse(input.key(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB), true))
        assertFalse(input.key(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB), true))
        assertFalse(scroll(-1f))
        assertTrue(sent.isEmpty())
    }

    @Test fun fractionalEncoderStepsAccumulateAndOppositeRotationCancels() {
        assertTrue(scroll(-0.5f))
        assertTrue(scroll(0.5f))
        assertTrue(sent.isEmpty())
        assertTrue(scroll(-0.5f))
        assertTrue(scroll(-0.5f))
        assertTrue(scroll(2f))
        assertEquals(listOf(1, -2), sent.map { it.wheel })
    }

    @Test fun inactiveOrResetInputDropsFractionalScroll() {
        assertTrue(scroll(-0.5f))
        assertFalse(scroll(-0.5f, active = false))
        assertTrue(scroll(-0.5f))
        assertTrue(sent.isEmpty())
        input.reset()
        assertTrue(scroll(-0.5f))
        assertTrue(sent.isEmpty())
    }

    @Test fun nonEncoderScrollAndInvalidValuesAreNotCaptured() {
        assertFalse(scroll(1f, source = InputDevice.SOURCE_MOUSE))
        assertFalse(scroll(Float.NaN))
        assertFalse(scroll(Float.POSITIVE_INFINITY))
        assertTrue(sent.isEmpty())
    }

    @Test fun largeEncoderBurstsFitSignedHidRange() {
        assertTrue(scroll(-500f))
        assertTrue(scroll(500f))
        assertEquals(listOf(127, -127), sent.map { it.wheel })
    }

    private fun press(code: Int, meta: Int = 0) {
        assertTrue(input.key(key(KeyEvent.ACTION_DOWN, code, meta = meta), true))
        assertTrue(input.key(key(KeyEvent.ACTION_UP, code, meta = meta), true))
    }

    private fun key(action: Int, code: Int, repeat: Int = 0, meta: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat, meta)

    private fun scroll(delta: Float, active: Boolean = true, source: Int = InputDevice.SOURCE_ROTARY_ENCODER): Boolean {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN }
        val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_SCROLL, delta) }
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_SCROLL, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, source, 0)
        return try { input.motion(event, active) } finally { event.recycle() }
    }
}
