package com.shilapi.xcertplay.transport.gt6

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class Gt6OemProtocolTest {
    @Test fun activationSelectsOnlyCarPlayAndRestoresAnIdleProfile() {
        for ((enabled, mask) in listOf(true to 1, false to 0)) {
            val output = ByteArrayOutputStream()
            val request = Gt6OemProtocol.requestCarPlayProfile(enabled)
            Gt6OemProtocol.write(output, request)
            assertArrayEquals(hex("0000ffff000001010000010100000005088102100$mask"), output.toByteArray())
            assertEquals(mask.toLong(), Gt6OemProtocol.fields(request).integer(2))
        }
    }
    @Test fun phoneRequestMatchesNativeDescriptorAndBigEndianHeader() {
        val output = ByteArrayOutputStream()
        Gt6OemProtocol.write(output, Gt6OemProtocol.requestPhone("12:34:56:78:9A:BC"))
        assertArrayEquals(hex("0000ffff00000101000002010000001308810410011a0c313233343536373839414243"), output.toByteArray())
    }

    @Test fun rawRfcommBytesSurviveFragmentationAndCoalescedFrames() {
        val payload = byteArrayOf(0, -1, 13, 10, -128, 1)
        val output = ByteArrayOutputStream()
        repeat(2) { Gt6OemProtocol.write(output, Gt6OemProtocol.Frame(0x105, payload)) }
        val fragmented = object : ByteArrayInputStream(output.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        repeat(2) {
            val frame = Gt6OemProtocol.read(fragmented)
            assertEquals(0x105, frame.type)
            assertArrayEquals(payload, frame.payload)
        }
        assertThrows(EOFException::class.java) { Gt6OemProtocol.read(fragmented) }
    }

    @Test fun malformedHeadersAndTruncatedPayloadsFailBeforeAllocation() {
        for (bytes in listOf(
            hex("0000ffff0000010100000105ffffffff"),
            hex("0000ffff000001010000010500010001"),
            hex("0000ffff000001020000010500000000"),
            hex("0000ffff00000101000001050000000201"),
            hex("0000ff"),
        )) assertThrows(IOException::class.java) { Gt6OemProtocol.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun protobufStatusUsesMatchingMessageIdAndHandlesUnknownFields() {
        val fields = Gt6OemProtocol.fields(Gt6OemProtocol.Frame(0x102, hex("088202220c3132333435363738394142432801")))
        assertEquals("123456789ABC", fields.text(4))
        assertEquals(1L, fields.integer(5))
        for (bytes in listOf(hex("088302"), hex("088202220f41"), hex("08820200"), hex("08820201"),
            hex("0882022880808080808080808002"))) {
            assertThrows(IOException::class.java) { Gt6OemProtocol.fields(Gt6OemProtocol.Frame(0x102, bytes)) }
        }
    }

    @Test fun placeholdersAndMalformedAddressesAreRejected() {
        for (value in listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "phone", "123456789AB")) {
            assertNull(Gt6OemProtocol.normalizeAddress(value))
        }
        assertEquals("123456789ABC", Gt6OemProtocol.normalizeAddress("12:34:56:78:9a:bc"))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
