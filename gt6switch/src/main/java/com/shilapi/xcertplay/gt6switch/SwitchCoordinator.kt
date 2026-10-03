package com.shilapi.xcertplay.gt6switch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.transport.gt6.Gt6ProjectionControl
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

internal class SwitchCoordinator(
    private val context: Context,
    private val trace: (String) -> Unit,
    private val root: CommandRunner = RootCommands(),
    private val inspect: () -> Gt6ProjectionControl.State = Gt6ProjectionControl::inspect,
    private val release: (String) -> Unit = Gt6ProjectionControl::release,
    private val pauseThread: (Long) -> Unit = Thread::sleep,
    private val diPlayPackage: () -> String? = {
        listOf("com.shihab.diplay.hudtest", "com.shihab.diplay").firstOrNull { pkg ->
            runCatching {
                context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    .metaData?.getInt("com.shilapi.xcertplay.GT6_SWITCH_VERSION", 0) == 1
            }.getOrDefault(false)
        }
    },
    private val zLinkComponent: () -> String = {
        context.packageManager.getLaunchIntentForPackage("com.zjinnova.zlink")
            ?.component?.flattenToString() ?: throw IOException("ZLink is not installed")
    },
) {
    private val base = "/data/adb/gt6-carplay-switch"
    private val pause = "/data/local/tmp/skip_softap_boot"
    private val helper = "/data/adb/hotspot/helper.apk"
    private val bridge = "CLASSPATH=$helper timeout 25 app_process /system/bin com.efran.hotspot.RootBridge"

    fun switch(toDiPlay: Boolean) = locked { perform(toDiPlay) }

    fun openSelected() = locked {
        checkDevice()
        val selected = root.run("cat $base/selected 2>/dev/null || true").trim()
        if (selected !in listOf("diplay", "zlink")) throw IOException("Choose a receiver in CarPlay Switch first")
        val pkg = diPlayPackage()
        if (selected == "diplay" && pkg != null && hasDiPlayService(pkg)) {
            // An active or connecting controller owns its radios. Navi/wake only reopen its screen.
            trace("Reopening DiPlay without resetting its connection.")
            root.run("am start -n ${quote("$pkg/com.shilapi.xcertplay.CarPlayHostActivity")}")
        } else if (selected == "zlink" && nativeLinkReady() && hotspotReady()) {
            trace("Reopening ZLink without resetting its connection.")
            launchZLink()
        } else if (selected == "diplay" && pkg == null) {
            trace("Patched DiPlay is missing; restoring ZLink.")
            perform(false)
        } else {
            perform(selected == "diplay")
        }
    }

    private fun locked(action: () -> Unit) {
        RandomAccessFile(File(context.filesDir, "switch.lock"), "rw").use { file ->
            val lock = file.channel.tryLock() ?: throw IOException("Another switch is already running")
            lock.use { action() }
        }
    }

    private fun checkDevice() {
        check(Build.MODEL.trim().equals("GT6-CAR", true)) { "This switcher supports GT6-CAR only" }
        trace("Checking root access. Allow CarPlay Switch if Magisk asks.")
        check(root.run("id -u").trim() == "0") { "Root access was not granted" }
    }

    private fun perform(toDiPlay: Boolean) {
        checkDevice()
        val diPlay = diPlayPackage()
        if (toDiPlay && diPlay == null) throw IOException("Install the GT6 DiPlay build first")
        val zLink = zLinkComponent()
        // Exact firmware and actual peer checks precede all service/radio/app changes.
        val state = inspect()
        if (state.profileMask !in 0..1 || (state.peer != null && state.peer != state.lastPhone)) {
            throw IOException("Another phone or projection profile owns the connection. Disconnect it first.")
        }
        if (!toDiPlay && root.run("test -f $helper && test -x /data/adb/hotspot/hotspotctl.sh && echo yes").trim() != "yes") {
            throw IOException("The GT6 hotspot helper is missing; no apps were changed")
        }
        installBootSelection()
        trace("Closing the current CarPlay connection…")
        root.run("if [ ! -e $pause ]; then touch $pause; touch $base/owns-hotspot-pause; fi")
        try {
            root.run("am force-stop com.zjinnova.zlink; am force-stop com.shihab.diplay.hudtest; am force-stop com.shihab.diplay")
            root.run("setprop ctl.stop zlink5; i=0; while [ \"\$(getprop init.svc.zlink5)\" != stopped ] && [ \"\$i\" -lt 8 ]; do sleep 1; i=\$((i+1)); done; test \"\$(getprop init.svc.zlink5)\" = stopped")
            // Init must stop the supervisor and its child. Never kill an unverified PID.
            if (nativeLinkReady()) {
                throw IOException("ZLink's native service did not stop")
            }
            release(state.lastPhone)
            trace("Previous connection released.")
            root.run("cmd wifip2p init >/dev/null; cmd wifip2p remove-group >/dev/null; cmd wifip2p deinit >/dev/null")
            root.run("if [ -f $helper ]; then $bridge stop; fi; cmd wifi stop-softap")
            if (toDiPlay) {
                root.run("svc wifi enable")
                root.run("am start -n ${quote(diPlay!! + "/com.shilapi.xcertplay.Gt6StartWirelessActivity")} --es phone ${quote(state.lastPhone)}")
                root.run("printf %s ${quote(diPlay)} > $base/diplay-package; printf diplay > $base/selected")
                trace("DiPlay selected. Accept CarPlay on your iPhone if asked.")
            } else {
                trace("Preparing ZLink’s wireless connection…")
                // Restore country/channel/configuration as well as starting tethering.
                root.run("/system/bin/sh /data/adb/hotspot/hotspotctl.sh repair > $base/hotspot-last.txt 2>&1", 170)
                check(hotspotReady()) { "ZLink hotspot is not enabled" }
                root.run("setprop ctl.start zlink5")
                // OEM zlink5.sh greps all command lines for z-link. A shell loop containing
                // that name stops it spawning the real process. Poll using short calls instead.
                var ready = false
                repeat(35) {
                    if (!ready) {
                        ready = nativeLinkReady()
                        if (!ready) pauseThread(1_000)
                    }
                }
                if (!ready) throw IOException("ZLink native process did not become ready")
                launchZLink(zLink)
                root.run("printf zlink > $base/selected")
                restoreHotspotWatcher()
                trace("ZLink selected. Accept CarPlay on your iPhone if asked.")
            }
        } catch (failure: Exception) {
            // Preserve the previous choice on failure. Do not silently arm the other receiver.
            trace("Switch failed; saved receiver remains available for retry.")
            throw IOException("Switch did not finish: ${failure.message}. Retry in CarPlay Switch.", failure)
        }
    }

    private fun restoreHotspotWatcher() {
        root.run("if [ -f $base/owns-hotspot-pause ]; then rm -f $pause $base/owns-hotspot-pause; fi")
    }

    private fun launchZLink(component: String = zLinkComponent()) {
        // Preserve the action/category used by the OEM launcher, not just its component.
        root.run("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${quote(component)}")
    }

    private fun nativeLinkReady() = root.run("pidof 'z-'link || true").trim().isNotEmpty()
    private fun hotspotReady(): Boolean = root.run("$bridge status").trim().let {
        it.startsWith("ap=13 ") && it.endsWith("config=match")
    }
    private fun hasDiPlayService(pkg: String) = root.run(
        "dumpsys activity services ${quote(pkg)} | grep ServiceRecord | grep -F com.shilapi.xcertplay.DiPlaySessionService >/dev/null && echo yes || true",
    ).trim() == "yes"

    private fun installBootSelection() {
        val script = StartupScripts.watcher()
        root.run("umask 077; mkdir -p $base; chmod 700 $base; printf %s ${quote(script)} > $base/boot-script.tmp; chmod 700 $base/boot-script.tmp; mv $base/boot-script.tmp /data/adb/service.d/gt6-carplay-switch.sh")
        // Its lock is inherited only by the watcher, not by the short-lived root request.
        root.run("(sleep 5; /system/bin/sh /data/adb/service.d/gt6-carplay-switch.sh) </dev/null >> $base/watcher-output.txt 2>&1 &")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

internal interface CommandRunner { fun run(command: String, timeoutSeconds: Long = 35): String }

internal class RootCommands : CommandRunner {
    override fun run(command: String, timeoutSeconds: Long): String {
        val process = ProcessBuilder("/debug_ramdisk/su", "-c", command).start()
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw IOException("Root command timed out. Check Magisk access and retry.")
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (process.exitValue() != 0) throw IOException(error.trim().take(200).ifBlank { "The car rejected a switch step" })
            return output
        } finally { process.destroy(); process.inputStream.close(); process.errorStream.close() }
    }
}
