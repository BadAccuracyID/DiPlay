package com.shilapi.xcertplay.transport.gt6

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Gt6NativePeerInspectorTest {
    private fun table(state: Int = 2, name: String = "iphone"): ByteArray = ByteArray(0x188).also {
        val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        it[0] = 10
        val entry = 5 * 36
        b.putInt(entry + 4, state)
        name.toByteArray().copyInto(it, entry + 18)
        b.putInt(entry + 32, 0x123456)
        it[entry + 36] = 0xef.toByte()
        b.putShort(entry + 38, 0xabcd.toShort())
    }

    @Test fun actualAppleConnectionIncludesItsNativeRemoteAddress() {
        assertEquals(Gt6NativePeerState(1, "ABCDEF123456"), Gt6NativePeerInspector.decode(1, 5, table()))
    }

    @Test fun connectingAndDisconnectedStatesDoNotConfirmAPhone() {
        assertNull(Gt6NativePeerInspector.decode(1, 255, table()).peer)
        for (state in listOf(0, 1, 10, 12)) assertNull(Gt6NativePeerInspector.decode(1, 5, table(state)).peer)
    }

    @Test fun corruptTablesAndNonAppleConnectionsFailClosed() {
        for ((index, bytes) in listOf(-1 to table(), 10 to table(), 5 to ByteArray(3), 5 to table(name = "android"),
            5 to table().also { it[0] = 0 }, 5 to table().also { it[5 * 36 + 35] = 1 })) {
            assertThrows(IOException::class.java) { Gt6NativePeerInspector.decode(1, index, bytes) }
        }
    }
}
