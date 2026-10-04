package com.shilapi.xcertplay.gt6switch

import com.shilapi.xcertplay.transport.gt6.Gt6ProjectionControl
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SwitchCoordinatorTest {
    private val root = FakeRoot()
    private val phone = "123456789ABC"
    private var peer = Gt6ProjectionControl.State(0, null, phone)
    private val released = mutableListOf<String>()
    private var installed = true
    private var inspections = 0
    private val coordinator by lazy {
        SwitchCoordinator(RuntimeEnvironment.getApplication(), {}, root,
            inspect = { inspections++; peer }, release = { released += it }, pauseThread = {},
            diPlayPackage = { if (installed) "com.shihab.diplay.hudtest" else null },
            zLinkComponent = { "com.zjinnova.zlink/.MainActivity" })
    }

    @Before fun model() { ShadowBuild.setModel("GT6-CAR") }

    @Test fun activeDiPlayIsReopenedWithoutRadioResetOrPeerRelease() {
        root.service = true
        coordinator.openSelected()
        assertEquals(0, inspections)
        assertTrue(released.isEmpty())
        assertTrue(root.commands.any { it.contains("CarPlayHostActivity") })
        assertFalse(root.commands.any { it.contains("force-stop") || it.contains("svc wifi") })
    }

    @Test fun readyZLinkIsReopenedWithoutResettingItsConnection() {
        root.selected = "zlink"; root.native = true; root.ap = true
        coordinator.openSelected()
        assertTrue(root.commands.any { it.startsWith("am start") && it.contains("com.zjinnova.zlink/") })
        assertTrue(root.commands.any { it.startsWith("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER") })
        assertEquals(0, inspections)
        assertTrue(released.isEmpty())
        assertFalse(root.commands.any { it.contains("force-stop") })
    }

    @Test fun anotherPhoneIsRejectedBeforeReceiverOrRadioChanges() {
        peer = Gt6ProjectionControl.State(1, "AABBCCDDEEFF", phone)
        assertThrows(IOException::class.java) { coordinator.switch(true) }
        assertTrue(released.isEmpty())
        assertFalse(root.commands.any { it.contains("force-stop") || it.contains("ctl.stop") || it.contains("boot-script.tmp") })
    }

    @Test fun unknownSelectionCannotSilentlySwitchReceivers() {
        root.selected = "invalid"
        assertThrows(IOException::class.java) { coordinator.openSelected() }
        assertEquals(0, inspections)
        assertFalse(root.commands.any { it.contains("force-stop") })
    }

    @Test fun switchingToZLinkRestoresRadioBeforeStartingNativeAndSavingChoice() {
        coordinator.switch(false)
        assertEquals(listOf(phone), released)
        assertEquals("zlink", root.selected)
        val repair = root.commands.indexOfFirst { it.contains("hotspotctl.sh repair") }
        val start = root.commands.indexOfFirst { it == "setprop ctl.start zlink5" }
        val launch = root.commands.indexOfFirst { it.startsWith("am start") }
        val save = root.commands.indexOfFirst { it == "printf zlink > /data/adb/gt6-carplay-switch/selected" }
        assertTrue(repair >= 0 && repair < start && start < launch && launch < save)
        assertFalse(root.commands.any { it.contains("while") && it.contains("z-link") })
    }

    @Test fun failedHotspotRepairKeepsDiPlaySelectionAndDoesNotStartZLink() {
        root.failRepair = true
        assertThrows(IOException::class.java) { coordinator.switch(false) }
        assertEquals("diplay", root.selected)
        assertFalse(root.commands.any { it == "setprop ctl.start zlink5" })
        assertFalse(root.commands.any { it.startsWith("am start") })
    }

    @Test fun failedDiPlayLaunchKeepsZLinkSelection() {
        root.selected = "zlink"; root.failLaunch = true
        assertThrows(IOException::class.java) { coordinator.switch(true) }
        assertEquals("zlink", root.selected)
    }

    @Test fun androidLaunchErrorInSuccessfulShellDoesNotSaveNewReceiver() {
        root.launchOutput = "Error: Activity class does not exist."
        assertThrows(IOException::class.java) { coordinator.switch(false) }
        assertEquals("diplay", root.selected)
    }

    @Test fun rejectedReopenDoesNotResetAnActiveConnection() {
        root.service = true
        root.launchOutput = "Security exception: Permission Denial"
        assertThrows(IOException::class.java) { coordinator.openSelected() }
        assertTrue(released.isEmpty())
        assertFalse(root.commands.any { it.contains("force-stop") })
    }

    @Test fun missingPatchedPackageRestoresZLinkThroughSameValidatedSwitch() {
        installed = false
        coordinator.openSelected()
        assertEquals(listOf(phone), released)
        assertEquals("zlink", root.selected)
        assertTrue(root.commands.any { it.contains("hotspotctl.sh repair") })
    }

    private class FakeRoot : CommandRunner {
        val commands = mutableListOf<String>()
        var selected = "diplay"
        var service = false
        var native = false
        var ap = false
        var failRepair = false
        var failLaunch = false
        var launchOutput = "Starting: Intent"
        override fun run(command: String, timeoutSeconds: Long): String {
            commands += command
            when {
                command == "id -u" -> return "0"
                command.startsWith("cat ") -> return selected
                command.startsWith("test -f ") -> return "yes"
                command.startsWith("dumpsys activity services") -> return if (service) "yes" else ""
                command.startsWith("pidof ") -> return if (native) "1234" else ""
                command.endsWith("RootBridge status") -> return if (ap) "ap=13 wifi=1 config=match" else "ap=11 wifi=3 config=match"
                command.startsWith("setprop ctl.stop") -> native = false
                command == "setprop ctl.start zlink5" -> native = true
                command.contains("hotspotctl.sh repair") -> {
                    if (failRepair) throw IOException("Hotspot repair failed")
                    ap = true
                }
                command.startsWith("am start") && failLaunch -> throw IOException("Launch failed")
                command.startsWith("am start") -> return launchOutput
                command.contains("printf diplay >") -> selected = "diplay"
                command.startsWith("printf zlink >") -> selected = "zlink"
            }
            return ""
        }
    }
}
