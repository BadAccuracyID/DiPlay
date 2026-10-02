package com.shilapi.xcertplay.transport.gt6

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Independently implemented wire format observed on the GT6 OEM projection service. */
internal object Gt6OemProtocol {
    const val PORT = 3152
    const val INIT_INFO = 0x102
    const val REQUEST_INIT = 0x101
    const val LINK_INFO = 0x104
    const val RFCOMM_DATA = 0x105
    const val LINK_INFO_WITH_PEER = 0x10c
    const val REQUEST_LINK_PHONE = 0x201
    const val MAX_PAYLOAD = 65_536
    private const val MAGIC = 0xffff
    private const val VERSION = 0x101

    data class Frame(val type: Int, val payload: ByteArray)

    /** Bit 0 selects CarPlay only. Zero restores an idle projection profile. */
    fun requestCarPlayProfile(enable: Boolean): Frame = Frame(REQUEST_INIT,
        byteArrayOf(8, 0x81.toByte(), 2, 16, if (enable) 1 else 0))

    fun read(input: InputStream): Frame {
        val first = input.read()
        if (first < 0) throw EOFException()
        try {
            val header = ByteArray(16)
            header[0] = first.toByte()
            DataInputStream(input).readFully(header, 1, 15)
            val data = DataInputStream(ByteArrayInputStream(header))
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                throw IOException("Unrecognized GT6 OEM frame header")
            }
            val type = data.readInt()
            val length = data.readInt()
            if (length !in 0..MAX_PAYLOAD) throw IOException("Invalid GT6 OEM payload length: $length")
            return Frame(type, ByteArray(length).also { DataInputStream(input).readFully(it) })
        } catch (error: EOFException) {
            throw IOException("Truncated GT6 OEM frame", error)
        }
    }

    fun write(output: OutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD)
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeInt(frame.type)
            writeInt(frame.payload.size)
            write(frame.payload)
            flush()
        }
    }

    fun requestPhone(address: String): Frame {
        val mac = normalizeAddress(address) ?: throw IOException("Invalid GT6 Bluetooth target address")
        val output = ByteArrayOutputStream()
        fun varint(value: Int) {
            var remaining = value
            while (remaining >= 128) {
                output.write((remaining and 127) or 128)
                remaining = remaining ushr 7
            }
            output.write(remaining)
        }
        varint(8); varint(REQUEST_LINK_PHONE)
        varint(16); varint(1)
        varint(26); varint(mac.length); output.write(mac.toByteArray(Charsets.US_ASCII))
        return Frame(REQUEST_LINK_PHONE, output.toByteArray())
    }

    /** Accept only real MAC addresses; Android placeholders must not enter identification. */
    fun normalizeAddress(value: String?): String? {
        val compact = value?.replace(":", "")?.uppercase() ?: return null
        return compact.takeIf {
            Regex("[0-9A-F]{12}").matches(it) &&
                it != "000000000000" && it != "020000000000" && it != "FFFFFFFFFFFF"
        }
    }

    fun colonAddress(value: String): String = value.chunked(2).joinToString(":")

    class Fields(private val values: Map<Int, Any>) {
        fun integer(field: Int): Long? = values[field] as? Long
        fun text(field: Int): String? = (values[field] as? ByteArray)?.toString(Charsets.UTF_8)
    }

    /** Only the scalar wire types used by OEM status messages; unknown fields are skipped. */
    fun fields(frame: Frame): Fields {
        val bytes = frame.payload
        var position = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (position >= bytes.size) throw IOException("Truncated GT6 OEM protobuf varint")
                val next = bytes[position++].toInt() and 255
                if (shift == 63 && next > 1) throw IOException("GT6 OEM protobuf varint overflow")
                value = value or ((next and 127).toLong() shl shift)
                if (next and 128 == 0) return value
            }
            throw IOException("Invalid GT6 OEM protobuf varint")
        }
        fun take(length: Long): ByteArray {
            if (length < 0 || length > bytes.size - position) throw IOException("Truncated GT6 OEM protobuf field")
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }
        val result = mutableMapOf<Int, Any>()
        while (position < bytes.size) {
            val tag = varint()
            if (tag ushr 3 == 0L || tag < 0 || tag ushr 3 > 0x1fffffff) throw IOException("Invalid GT6 OEM protobuf field number")
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> result[field] = varint()
                1 -> take(8)
                2 -> result[field] = take(varint())
                5 -> take(4)
                else -> throw IOException("Unsupported GT6 OEM protobuf wire type")
            }
        }
        if (result[1] != frame.type.toLong()) throw IOException("GT6 OEM message ID does not match its frame")
        return Fields(result)
    }
}
