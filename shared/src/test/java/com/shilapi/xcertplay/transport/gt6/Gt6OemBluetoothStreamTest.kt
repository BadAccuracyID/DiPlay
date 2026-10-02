package com.shilapi.xcertplay.transport.gt6

import com.shilapi.xcertplay.iap2.session.Iap2Session
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Gt6OemBluetoothStreamTest {
    private val phone = "123456789ABC"
    private val local = "AABBCCDDEEFF"

    @Test fun nativeWitnessEnablesAppleProfileAndConfirmsThePeerBeforeData() {
        val inspector = FakeInspector()
        withService(inspector) { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init()); write(peer, status("", false))
                    val activation = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0x101, activation.type)
                    assertEquals(1L, Gt6OemProtocol.fields(activation).integer(2))
                    // Status never supplies a remote address on the tested firmware.
                    inspector.state = Gt6NativePeerState(1, phone)
                    val data = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0x105, data.type)
                    assertArrayEquals(byteArrayOf(7), data.payload)
                    val release = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0x101, release.type)
                    assertEquals(0L, Gt6OemProtocol.fields(release).integer(2))
                }
            }
            assertEquals("AA:BB:CC:DD:EE:FF", stream.connect(2_000))
            assertEquals(phone, inspector.preparedTarget)
            stream.send(byteArrayOf(7))
            stream.close()
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun nativeWrongPeerIsRejectedWithoutDataOrDisconnectingThatPhone() {
        val inspector = FakeInspector()
        withService(inspector) { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init()); write(peer, status("", false))
                    assertEquals(0x101, Gt6OemProtocol.read(peer.getInputStream()).type)
                    inspector.state = Gt6NativePeerState(1, "ABCDEF123456")
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            assertTrue(assertThrows(IOException::class.java) { stream.connect(2_000) }.message!!.contains("another phone"))
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun activatedProfileWithoutNativePeerStillTimesOutAndIsReleased() {
        val inspector = FakeInspector()
        withService(inspector) { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init()); write(peer, status("", true))
                    assertEquals(0x101, Gt6OemProtocol.read(peer.getInputStream()).type)
                    inspector.state = Gt6NativePeerState(1, null)
                    // An unqualified connected flag is never sufficient.
                    val release = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0L, Gt6OemProtocol.fields(release).integer(2))
                }
            }
            assertThrows(IOException::class.java) { stream.connect(300) }
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun peerChangeAfterConnectPreventsSendingToAnotherPhone() {
        val inspector = FakeInspector()
        withService(inspector) { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init()); write(peer, status("", false))
                    assertEquals(0x101, Gt6OemProtocol.read(peer.getInputStream()).type)
                    inspector.state = Gt6NativePeerState(1, phone)
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            stream.connect(2_000)
            inspector.state = Gt6NativePeerState(1, "ABCDEF123456")
            assertThrows(IOException::class.java) { stream.send(byteArrayOf(7)) }
            stream.close()
            service.get(3, TimeUnit.SECONDS)
        }
    }

    private class FakeInspector : Gt6PeerInspector {
        @Volatile var state = Gt6NativePeerState(0, null)
        var preparedTarget: String? = null
        override fun prepare(target: String) { preparedTarget = target }
        override fun snapshot(): Gt6NativePeerState = state
    }

    @Test fun bridgeRequiresTargetConfirmationAndPreservesRawBinaryData() {
        val payload = byteArrayOf(0, -1, 13, 10, -128, 1)
        withService { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init())
                    write(peer, status("", true)) // Observed stale OEM baseline is not a connected phone.
                    val request = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0x201, request.type)
                    assertEquals(phone, Gt6OemProtocol.fields(request).text(3))
                    write(peer, status(phone, true))
                    write(peer, Gt6OemProtocol.Frame(0x105, payload))
                    val data = Gt6OemProtocol.read(peer.getInputStream())
                    assertEquals(0x105, data.type)
                    assertArrayEquals(payload, data.payload)
                }
            }
            assertEquals("AA:BB:CC:DD:EE:FF", stream.connect(2_000))
            assertArrayEquals(payload.copyOf(2), stream.recv(2, 1_000))
            assertArrayEquals(payload.copyOfRange(2, payload.size), stream.recv(100, 1_000))
            stream.send(payload)
            service.get(3, TimeUnit.SECONDS)
            assertArrayEquals(ByteArray(0), stream.recv(100, 1_000))
        }
    }

    @Test fun anotherPhonesSessionIsRejectedWithoutSendingAConnectionRequest() {
        withService { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init())
                    write(peer, status("ABCDEF123456", true))
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            val failure = assertThrows(IOException::class.java) { stream.connect(2_000) }
            assertTrue(failure.message!!.contains("another phone"))
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun emptyPeerStatusDoesNotCountAsConnectedAndTimesOut() {
        withService { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init())
                    write(peer, status("", true))
                    assertEquals(0x201, Gt6OemProtocol.read(peer.getInputStream()).type)
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            val failure = assertThrows(IOException::class.java) { stream.connect(300) }
            assertTrue(failure.message!!.contains("not confirmed"))
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun closeCancelsAWaitingConnectionAndClosesOnlyItsTcpClient() {
        withService { server, stream ->
            val requested = CountDownLatch(1)
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init())
                    write(peer, status("", true))
                    assertEquals(0x201, Gt6OemProtocol.read(peer.getInputStream()).type)
                    requested.countDown()
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            val connection = CompletableFuture.supplyAsync { runCatching { stream.connect(15_000) }.exceptionOrNull() }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            stream.close()
            assertTrue(connection.get(2, TimeUnit.SECONDS) is IOException)
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun unattributedRfcommDataFailsInsteadOfReachingIap2() {
        withService { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    write(peer, init())
                    write(peer, Gt6OemProtocol.Frame(0x105, byteArrayOf(1)))
                }
            }
            assertThrows(IOException::class.java) { stream.connect(2_000) }
            service.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun connectedOemStatusCannotReplaceAnIap2LinkHandshake() {
        withService { server, stream ->
            val service = CompletableFuture.runAsync {
                server.accept().use { peer ->
                    peer.soTimeout = 2_000
                    write(peer, init())
                    write(peer, status("", true))
                    assertEquals(0x201, Gt6OemProtocol.read(peer.getInputStream()).type)
                    write(peer, status(phone, true))
                    // A vendor connected flag alone provides no actual Apple negotiation bytes.
                    try {
                        while (true) {
                            assertEquals(0x105, Gt6OemProtocol.read(peer.getInputStream()).type)
                        }
                    } catch (_: EOFException) {
                        // Drain the accessory's detection probes but never answer as an iPhone.
                    }
                }
            }
            stream.connect(2_000)
            Iap2Session.openWireless(stream).use { session ->
                assertFalse(session.awaitReady(300))
            }
            service.get(3, TimeUnit.SECONDS)
        }
    }

    private fun init() = message(0x102, mapOf(2 to "test-vendor", 3 to "test-headunit", 4 to local))
    private fun status(address: String, connected: Boolean) = message(0x10c,
        mapOf(2 to 0, 3 to if (connected) 1 else 0, 4 to 1, 5 to local, 6 to "test-headunit", 7 to address, 8 to "test-phone"))

    private fun message(type: Int, fields: Map<Int, Any>): Gt6OemProtocol.Frame {
        val output = ByteArrayOutputStream()
        fun varint(value: Int) {
            var n = value
            while (n >= 128) { output.write((n and 127) or 128); n = n ushr 7 }
            output.write(n)
        }
        for ((field, value) in mapOf(1 to type) + fields) {
            if (value is Int) { varint(field shl 3); varint(value) }
            else { val bytes = (value as String).toByteArray(); varint((field shl 3) or 2); varint(bytes.size); output.write(bytes) }
        }
        return Gt6OemProtocol.Frame(type, output.toByteArray())
    }

    private fun write(socket: Socket, frame: Gt6OemProtocol.Frame) = Gt6OemProtocol.write(socket.getOutputStream(), frame)

    private fun withService(inspector: Gt6PeerInspector? = null, test: (ServerSocket, Gt6OemBluetoothStream) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3_000
            Gt6OemBluetoothStream(Socket(), phone, {}, InetSocketAddress("127.0.0.1", server.localPort), inspector).use { stream ->
                test(server, stream)
            }
        }
    }
}
