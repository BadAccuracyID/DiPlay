package com.shilapi.xcertplay.transport.gt6

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class Gt6ProjectionControlTest {
    private val target = "123456789ABC"
    @Test fun unrelatedPhoneAndOtherProjectionProfileCannotBeReleased() {
        for (state in listOf(Gt6NativePeerState(0, "AABBCCDDEEFF"),
            Gt6NativePeerState(1, "AABBCCDDEEFF"), Gt6NativePeerState(2, target),
            Gt6NativePeerState(-1, null))) {
            assertThrows(IOException::class.java) { Gt6ProjectionControl.requireReleasable(state, target) }
        }
    }
    @Test fun selectedPhoneCanBeReleasedEvenWithStaleZeroMask() {
        for (mask in 0..1) for (peer in listOf(null, target)) {
            Gt6ProjectionControl.requireReleasable(Gt6NativePeerState(mask, peer), target)
        }
    }
}
