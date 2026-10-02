package com.shilapi.xcertplay.transport.gt6

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque

/**
 * Experimental Apple bootstrap stream over the OEM projection service, never its single-client
 * Android Bluetooth control socket. GT6 activation uses the CarPlay profile and a read-only,
 * firmware-specific native peer witness. No Wi-Fi, pairing, or firmware modification commands.
 */
class Gt6OemBluetoothStream internal constructor(
    private val socket: Socket,
    address: String,
    private val onTrace: (String) -> Unit,
    private val endpoint: InetSocketAddress = InetSocketAddress("127.0.0.1", Gt6OemProtocol.PORT),
    private val nativePeer: Gt6PeerInspector? = null,
) : BlockingDuplexByteStream {
    constructor(address: String, onTrace: (String) -> Unit = {}) : this(Socket(), address, onTrace,
        nativePeer = Gt6NativePeerInspector())

    private val target = Gt6OemProtocol.normalizeAddress(address)
        ?: throw IllegalArgumentException("Invalid GT6 Bluetooth target")
    private val lock = Object()
    private val writeLock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var initialized = false
    private var baselineReceived = false
    private var peerConfirmed = false
    private var requestSent = false
    private var closed = false
    private var ended = false
    private var started = false
    private var failure: IOException? = null
    private var localAddress: String? = null
    private var reader: Thread? = null
    private var ownsProfile = false

    /** Must run on a worker thread. Cancel by closing this instance. */
    fun connect(timeoutMillis: Long = 15_000L): String {
        require(timeoutMillis in 1..60_000)
        synchronized(lock) {
            check(!started) { "GT6 OEM stream can only connect once" }
            started = true
            if (closed) throw IOException("GT6 OEM stream is closed")
        }
        try {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            nativePeer?.prepare(target)
            synchronized(lock) { if (closed) throw IOException("GT6 OEM stream is closed") }
            if (System.nanoTime() >= deadline) throw IOException("GT6 native preflight timed out")
            socket.connect(endpoint, minOf(timeoutMillis, 2_000L).toInt())
            socket.tcpNoDelay = true
            reader = Thread(::readLoop, "diplay-gt6-oem-reader").apply { isDaemon = true; start() }
            await(deadline) { initialized && baselineReceived }
            if (nativePeer != null) {
                synchronized(writeLock) {
                    synchronized(lock) { if (closed) throw IOException("GT6 OEM stream is closed") }
                    ownsProfile = true
                    writeFrame(Gt6OemProtocol.requestCarPlayProfile(true))
                }
                onTrace("GT6 CarPlay Bluetooth profile requested; verifying native Apple peer")
                while (true) {
                    synchronized(lock) {
                        failure?.let { throw it }
                        if (closed || ended) throw IOException("GT6 OEM stream is closed")
                    }
                    val state = nativePeer.snapshot()
                    if (state.profileMask !in 0..1) throw IOException("GT6 CarPlay profile ownership was lost")
                    if (state.peer != null && state.peer != target) throw IOException("GT6 connected another phone")
                    if (state.profileMask == 1 && state.peer == target) {
                        synchronized(lock) { peerConfirmed = true; lock.notifyAll() }
                        onTrace("GT6 native Apple RFCOMM peer confirmed")
                        break
                    }
                    if (System.nanoTime() >= deadline) throw IOException("GT6 native Apple connection was not confirmed")
                    synchronized(lock) { if (!closed) waitLocked(100_000_000) }
                }
            } else {
                synchronized(lock) { if (!peerConfirmed) requestSent = true }
                if (synchronized(lock) { requestSent }) {
                    writeFrame(Gt6OemProtocol.requestPhone(target))
                    onTrace("GT6 OEM phone-link request sent; awaiting target confirmation")
                }
            }
            await(deadline) { peerConfirmed }
            return synchronized(lock) { checkNotNull(localAddress) }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun send(data: ByteArray): Unit = synchronized(writeLock) {
        synchronized(lock) {
            failure?.let { throw it }
            if (closed || ended || !peerConfirmed) throw IOException("GT6 OEM Apple stream is not connected")
        }
        verifyNativePeer()
        // 0x105 carries raw RFCOMM bytes in both directions, without protobuf wrapping.
        var position = 0
        while (position < data.size) {
            val end = minOf(position + SEND_CHUNK, data.size)
            writeFrame(Gt6OemProtocol.Frame(Gt6OemProtocol.RFCOMM_DATA, data.copyOfRange(position, end)))
            position = end
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis in 0..Long.MAX_VALUE / 1_000_000)
        val startedAt = System.nanoTime()
        synchronized(lock) {
            while (true) {
                val data = pending.pollFirst()
                if (data != null) {
                    val count = minOf(data.size, maxBytes)
                    pendingBytes -= count
                    if (count < data.size) pending.addFirst(data.copyOfRange(count, data.size))
                    lock.notifyAll()
                    return if (count == data.size) data else data.copyOf(count)
                }
                failure?.let { throw it }
                if (closed || ended) return ByteArray(0)
                val remaining = timeoutMillis * 1_000_000 - (System.nanoTime() - startedAt)
                if (remaining <= 0) return null
                waitLocked(remaining)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            pendingBytes = 0
            lock.notifyAll()
        }
        releaseProfile()
        runCatching { socket.close() }
    }

    private fun writeFrame(frame: Gt6OemProtocol.Frame) {
        synchronized(writeLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed || ended) throw IOException("GT6 OEM stream is closed")
            }
            try {
                Gt6OemProtocol.write(socket.getOutputStream(), frame)
            } catch (error: IOException) {
                fail(error)
                throw error
            }
        }
    }

    private fun readLoop() {
        try {
            val input = socket.getInputStream()
            while (!synchronized(lock) { closed }) {
                val frame = Gt6OemProtocol.read(input)
                when (frame.type) {
                    Gt6OemProtocol.INIT_INFO -> {
                        val fields = Gt6OemProtocol.fields(frame)
                        val mac = Gt6OemProtocol.normalizeAddress(fields.text(4))
                            ?: throw IOException("GT6 OEM service did not provide a valid local Bluetooth address")
                        if (fields.text(2).isNullOrBlank()) throw IOException("GT6 OEM service identity is missing")
                        synchronized(lock) {
                            localAddress = Gt6OemProtocol.colonAddress(mac)
                            initialized = true
                            lock.notifyAll()
                        }
                        onTrace("GT6 OEM projection service identified; local Bluetooth identity available")
                    }
                    Gt6OemProtocol.LINK_INFO_WITH_PEER -> {
                        val fields = Gt6OemProtocol.fields(frame)
                        val peer = Gt6OemProtocol.normalizeAddress(fields.text(7))
                        val connected = fields.integer(3) == 1L
                        synchronized(lock) {
                            if (connected && peer != null && peer != target) {
                                throw IOException("GT6 OEM projection service belongs to another phone")
                            }
                            if (peerConfirmed && (!connected || peer != target)) {
                                ended = true
                            }
                            // Initial status can claim connected with an empty peer. Ignore it.
                            if (initialized && connected && peer == target && fields.integer(2) == 0L) {
                                peerConfirmed = true
                            }
                            baselineReceived = true
                            lock.notifyAll()
                        }
                    }
                    Gt6OemProtocol.LINK_INFO -> {
                        val fields = Gt6OemProtocol.fields(frame)
                        synchronized(lock) {
                            if (peerConfirmed && fields.integer(3) == 0L) ended = true
                            lock.notifyAll()
                        }
                    }
                    Gt6OemProtocol.RFCOMM_DATA -> synchronized(lock) {
                        if (!peerConfirmed) throw IOException("GT6 OEM data arrived without target-phone confirmation")
                        verifyNativePeer()
                        if (frame.payload.isNotEmpty()) {
                            while (!closed && pendingBytes + frame.payload.size > MAX_PENDING) lock.wait()
                            if (closed) return
                            pending.addLast(frame.payload)
                            pendingBytes += frame.payload.size
                            lock.notifyAll()
                        }
                    }
                    else -> Unit // HFP, BLE, and other status frames are not Apple byte-stream data.
                }
                if (synchronized(lock) { ended }) return
            }
        } catch (_: EOFException) {
            synchronized(lock) { ended = true; lock.notifyAll() }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(IOException("GT6 OEM reader interrupted", error))
        } catch (error: IOException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IOException("GT6 OEM reader failed", error))
        } finally {
            releaseProfile()
            runCatching { socket.close() }
        }
    }

    private fun fail(error: IOException) {
        synchronized(lock) { if (!closed && failure == null) failure = error; lock.notifyAll() }
        releaseProfile()
        runCatching { socket.close() }
    }

    private fun verifyNativePeer() {
        val state = nativePeer?.snapshot() ?: return
        if (state.profileMask != 1 || state.peer != target) {
            throw IOException("GT6 native Apple peer is no longer the selected phone")
        }
    }

    private fun releaseProfile() = synchronized(writeLock) {
        if (!ownsProfile) return@synchronized
        ownsProfile = false
        runCatching {
            val state = nativePeer?.snapshot() ?: return@runCatching
            // Never disable another active phone or a profile changed by another client.
            if (state.profileMask == 1 && (state.peer == null || state.peer == target)) {
                Gt6OemProtocol.write(socket.getOutputStream(), Gt6OemProtocol.requestCarPlayProfile(false))
                onTrace("GT6 owned CarPlay Bluetooth profile released")
            }
        }.onFailure { onTrace("GT6 profile release could not be confirmed: ${it.message}") }
    }

    private fun await(deadline: Long, condition: () -> Boolean) {
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                if (closed || ended) throw IOException("GT6 OEM service closed during Bluetooth bootstrap")
                if (condition()) return
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException(
                    if (!initialized) "GT6 OEM service unavailable or occupied; close other projection apps"
                    else if (!baselineReceived) "GT6 OEM projection service did not provide peer status"
                    else "GT6 OEM Apple connection was not confirmed; activation/routing still needs verification",
                )
                waitLocked(remaining)
            }
        }
    }

    private fun waitLocked(nanos: Long) {
        try { lock.wait(nanos / 1_000_000, (nanos % 1_000_000).toInt()) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("GT6 OEM wait interrupted", error)
        }
    }

    private companion object {
        const val MAX_PENDING = 65_536
        const val SEND_CHUNK = 8_192
    }
}
