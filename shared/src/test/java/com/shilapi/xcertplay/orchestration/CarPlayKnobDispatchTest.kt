package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayKnobDispatchTest {
    private val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480))
    private val listener = object : AirPlaySessionListener {}

    @Test fun reattachedUiUsesExistingSessionAndSendsPressThenRelease() {
        val controller = controller()
        val (session, wire) = session()
        try {
            field(controller, "activeSession", session)
            controller.attachUi(object : AirPlaySessionListener {}, {})
            assertTrue(controller.hasActiveAirPlaySession())
            // Four-byte report layout verified against ZLink's native HIDKnobFillReport.
            val gestures = listOf(
                AirPlayKnobState(wheel = -1) to byteArrayOf(0, 0, 0, -1),
                AirPlayKnobState(wheel = 1) to byteArrayOf(0, 0, 0, 1),
                AirPlayKnobState(select = true) to byteArrayOf(1, 0, 0, 0),
                AirPlayKnobState(x = -127) to byteArrayOf(0, -127, 0, 0),
                AirPlayKnobState(x = 127) to byteArrayOf(0, 127, 0, 0),
                AirPlayKnobState(y = 127) to byteArrayOf(0, 0, 127, 0),
            )
            val completed = CountDownLatch(gestures.size)
            val results = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
            for ((state, _) in gestures) {
                assertTrue(controller.sendKnob(state) { results += it; completed.countDown() })
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertTrue(results.all { it })
            val plain = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(wire.toByteArray()).data
            val commands = commands(plain)
            assertEquals(gestures.size * 2, commands.size)
            for (command in commands) {
                assertEquals("hidSendReport", command["type"])
                assertEquals(AirPlayHid.KNOB_HID_UID.toString(16), command["uuid"])
            }
            for ((index, gesture) in gestures.withIndex()) {
                assertArrayEquals(gesture.second, commands[index * 2]["hidReport"] as ByteArray)
                assertArrayEquals(byteArrayOf(0, 0, 0, 0), commands[index * 2 + 1]["hidReport"] as ByteArray)
            }
        } finally { controller.close(); session.close() }
    }

    @Test fun missingOrClosedSessionRejectsInput() {
        val controller = controller()
        val (session, _) = session()
        try {
            assertFalse(controller.hasActiveAirPlaySession())
            assertFalse(controller.sendKnob(AirPlayKnobState(wheel = 1)))
            field(controller, "activeSession", session)
            controller.close()
            assertFalse(controller.hasActiveAirPlaySession())
            assertFalse(controller.sendKnob(AirPlayKnobState(wheel = 1)))
        } finally { controller.close(); session.close() }
    }

    @Test fun unavailableEventChannelReportsFailureWithoutSending() {
        val controller = controller()
        val (session, wire) = session()
        try {
            field(controller, "activeSession", session)
            field(session, "eventCipher", null)
            val completed = CountDownLatch(1)
            var sent = true
            assertTrue(controller.sendKnob(AirPlayKnobState(select = true)) { sent = it; completed.countDown() })
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertFalse(sent)
            assertEquals(0, wire.size())
        } finally { controller.close(); session.close() }
    }

    @Test fun queuedInputCannotReachReplacedPhoneSession() {
        val controller = controller()
        val (old, oldWire) = session()
        val (replacement, replacementWire) = session()
        val release = CountDownLatch(1)
        try {
            val busy = CountDownLatch(1)
            val worker = CarPlayController::class.java.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            worker.execute { busy.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(busy.await(3, TimeUnit.SECONDS))
            field(controller, "activeSession", old)
            val completed = CountDownLatch(1)
            var sent = true
            assertTrue(controller.sendKnob(AirPlayKnobState(wheel = 1)) { sent = it; completed.countDown() })
            field(controller, "activeSession", replacement)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertFalse(sent)
            assertEquals(0, oldWire.size())
            assertEquals(0, replacementWire.size())
        } finally { release.countDown(); controller.close(); old.close(); replacement.close() }
    }

    private fun controller() = CarPlayController(RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 0)),
        config, AirPlayIdentity.generate(), PairingStore(), listener, object : AirPlayMediaHandler {}, {})

    private fun session(): Pair<AirPlaySession, ByteArrayOutputStream> {
        val wire = ByteArrayOutputStream()
        val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            listener, object : AirPlayMediaHandler {})
        field(session, "eventSocket", object : Socket() { override fun getOutputStream() = wire })
        field(session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
        return session to wire
    }

    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun commands(plain: ByteArray): List<Map<*, *>> {
        val commands = mutableListOf<Map<*, *>>()
        var offset = 0
        while (offset < plain.size) {
            val remaining = plain.copyOfRange(offset, plain.size).toString(Charsets.ISO_8859_1)
            val end = remaining.indexOf("\r\n\r\n")
            assertTrue(end > 0)
            val header = remaining.substring(0, end)
            val length = Regex("Content-Length: (\\d+)").find(header)!!.groupValues[1].toInt()
            val bodyStart = offset + end + 4
            commands += BplistCodec.decode(plain.copyOfRange(bodyStart, bodyStart + length)) as Map<*, *>
            offset = bodyStart + length
        }
        return commands
    }
}
