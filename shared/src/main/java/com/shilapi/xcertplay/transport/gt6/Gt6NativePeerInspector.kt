package com.shilapi.xcertplay.transport.gt6

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Read-only witness for the one GT6 firmware whose TCP status omits the remote address. */
internal interface Gt6PeerInspector {
    fun prepare(target: String)
    fun snapshot(): Gt6NativePeerState
}

internal data class Gt6NativePeerState(val profileMask: Int, val peer: String?)

internal class Gt6NativePeerInspector : Gt6PeerInspector {
    private var pid = 0
    private var base = 0L

    override fun prepare(target: String) {
        discover()
        if (lastPhone() != target) {
            throw IOException("GT6 will reconnect its last phone; connect the selected phone in the car Bluetooth app first")
        }
        val state = snapshot()
        if (state.profileMask != 0 || state.peer != null) {
            throw IOException("GT6 projection Bluetooth is already in use; close other projection apps")
        }
    }

    fun discover() {
        pid = 0
        val digest = command("sha256sum /system/bin/blink").toString(Charsets.US_ASCII).substringBefore(' ')
        if (digest != FIRMWARE_SHA256) throw IOException("GT6 native peer inspection does not support this firmware")
        val candidates = command("pidof blink").toString(Charsets.US_ASCII).trim().split(Regex("\\s+"))
        for (candidate in candidates) {
            val candidatePid = candidate.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val map = command("toybox grep '/system/bin/blink$' /proc/$candidatePid/maps | toybox head -n 1")
                .toString(Charsets.US_ASCII).trim().split(Regex("\\s+"))
            if (map.size < 3) continue
            val address = map[0].substringBefore('-').toLongOrNull(16) ?: continue
            val offset = map[2].toLongOrNull(16) ?: continue
            pid = candidatePid
            base = address - offset
            if (pointer(memory(base + SPP_TABLE, 8)) != 0L) break
            pid = 0
        }
        if (pid == 0) throw IOException("GT6 projection process could not be identified")
    }

    fun lastPhone(): String {
        val last = command("toybox grep '^lastaddr=' /data/blink/bt_conf.ini").toString(Charsets.US_ASCII)
            .trim().substringAfter('=', "")
        return Gt6OemProtocol.normalizeAddress(last)
            ?: throw IOException("Pair and connect your iPhone in the car Bluetooth app first")
    }

    @Synchronized override fun snapshot(): Gt6NativePeerState {
        check(pid > 0)
        // Fixed read-only offsets are permitted only after matching the exact firmware above.
        val metadata = command(read(base + PROFILE_MASK, 4) + ";" + read(base + CARPLAY_INDEX, 4) + ";" + read(base + SPP_TABLE, 8))
        if (metadata.size != 16) throw IOException("GT6 native state is unavailable")
        val buffer = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN)
        val mask = buffer.int
        val index = buffer.int
        val table = buffer.long and POINTER_MASK
        if (table == 0L) throw IOException("GT6 native connection table is unavailable")
        return decode(mask, index, memory(table, TABLE_BYTES))
    }

    private fun memory(address: Long, size: Int): ByteArray = command(read(address, size)).also {
        if (it.size != size) throw IOException("GT6 native peer read was incomplete")
    }

    private fun read(address: Long, size: Int): String {
        require(address > 0 && size in 1..TABLE_BYTES)
        return "dd if=/proc/$pid/mem bs=1 skip=$address count=$size 2>/dev/null"
    }

    private fun command(script: String): ByteArray {
        val process = ProcessBuilder("/debug_ramdisk/su", "-c", script).start()
        try {
            process.outputStream.close()
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw IOException("GT6 read-only root inspection timed out")
            val bytes = process.inputStream.readBytes()
            if (process.exitValue() != 0) throw IOException("GT6 native peer inspection requires root access")
            return bytes
        } finally {
            process.destroy()
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    internal companion object {
        const val FIRMWARE_SHA256 = "e13e75e5b88724dd7f350801b66249002082bf45572c91f979cca8270a6382f6"
        const val PROFILE_MASK = 0x1aff80L
        const val CARPLAY_INDEX = 0x1aa7f0L
        const val SPP_TABLE = 0x1bfbf0L
        const val TABLE_BYTES = 0x188
        const val POINTER_MASK = 0x00ffffffffffffffL

        private fun pointer(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long and POINTER_MASK

        /** The address extends into the next record's reserved word in this OEM layout. */
        fun decode(mask: Int, index: Int, bytes: ByteArray): Gt6NativePeerState {
            if (bytes.size != TABLE_BYTES) throw IOException("Invalid GT6 native connection table size")
            val count = bytes[0].toInt() and 255
            if (count !in 1..10) throw IOException("Invalid GT6 native connection table")
            if (index == 255) return Gt6NativePeerState(mask, null)
            if (index !in 0 until count) throw IOException("Invalid GT6 Apple connection index")
            val entry = index * 36
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val state = buffer.getInt(entry + 4)
            val name = bytes.copyOfRange(entry + 18, entry + 28).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            if (state != 2) return Gt6NativePeerState(mask, null)
            if (name != "iphone") throw IOException("GT6 active connection is not the Apple transport")
            val lap = buffer.getInt(entry + 32)
            if (lap ushr 24 != 0) throw IOException("Invalid GT6 native peer address")
            val uap = bytes[entry + 36].toInt() and 255
            val nap = buffer.getShort(entry + 38).toInt() and 65535
            val mac = "%04X%02X%06X".format(nap, uap, lap)
            return Gt6NativePeerState(mask, Gt6OemProtocol.normalizeAddress(mac)
                ?: throw IOException("Invalid GT6 native peer address"))
        }
    }
}
