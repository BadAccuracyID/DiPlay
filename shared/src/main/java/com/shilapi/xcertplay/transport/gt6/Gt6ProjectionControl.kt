package com.shilapi.xcertplay.transport.gt6

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Explicit, user-requested release for switching receivers. Never used by automatic startup. */
object Gt6ProjectionControl {
    data class State(val profileMask: Int, val peer: String?, val lastPhone: String)

    fun inspect(): State {
        val inspector = Gt6NativePeerInspector().apply { discover() }
        val state = inspector.snapshot()
        return State(state.profileMask, state.peer, inspector.lastPhone())
    }

    /** Only the owner's selected phone may be disconnected, including a stale mask-zero session. */
    fun release(expectedPhone: String) {
        val target = Gt6OemProtocol.normalizeAddress(expectedPhone)
            ?: throw IOException("Invalid selected phone")
        val inspector = Gt6NativePeerInspector().apply { discover() }
        val state = inspector.snapshot()
        requireReleasable(state, target)
        if (state.profileMask == 0 && state.peer == null) return
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", Gt6OemProtocol.PORT), 2_000)
            socket.soTimeout = 2_000
            // Verify the dedicated projection service identity before sending a command.
            var identified = false
            repeat(8) {
                if (!identified) {
                    val frame = Gt6OemProtocol.read(socket.getInputStream())
                    if (frame.type == Gt6OemProtocol.INIT_INFO) {
                        val fields = Gt6OemProtocol.fields(frame)
                        if (fields.text(2).isNullOrBlank() || Gt6OemProtocol.normalizeAddress(fields.text(4)) == null) {
                            throw IOException("Unexpected OEM projection service")
                        }
                        identified = true
                    }
                }
            }
            if (!identified) throw IOException("OEM projection service did not identify itself")
            requireReleasable(inspector.snapshot(), target)
            Gt6OemProtocol.write(socket.getOutputStream(), Gt6OemProtocol.requestCarPlayProfile(false))
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                val current = inspector.snapshot()
                requireReleasable(current, target)
                if (current.profileMask == 0 && current.peer == null) return
                Thread.sleep(100)
            } while (System.nanoTime() < deadline)
            throw IOException("The car has not released the previous phone session")
        }
    }

    internal fun requireReleasable(state: Gt6NativePeerState, expectedPhone: String) {
        if (state.profileMask !in 0..1) throw IOException("Another projection profile is active")
        if (state.peer != null && state.peer != expectedPhone) {
            throw IOException("Another phone owns CarPlay; disconnect it first")
        }
    }
}
