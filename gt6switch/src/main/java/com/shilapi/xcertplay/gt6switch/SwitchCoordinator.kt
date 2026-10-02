package com.shilapi.xcertplay.gt6switch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shilapi.xcertplay.transport.gt6.Gt6ProjectionControl
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

internal class SwitchCoordinator(private val context: Context, private val trace: (String) -> Unit) {
    private val root = RootCommands()
    private val base = "/data/adb/gt6-carplay-switch"
    private val pause = "/data/local/tmp/skip_softap_boot"
    private val helper = "/data/adb/hotspot/helper.apk"
    private val bridge = "CLASSPATH=$helper timeout 25 app_process /system/bin com.efran.hotspot.RootBridge"

    fun switch(toDiPlay: Boolean) {
        RandomAccessFile(File(context.filesDir, "switch.lock"), "rw").use { file ->
            val lock = file.channel.tryLock() ?: throw IOException("Another switch is already running")
            lock.use { perform(toDiPlay) }
        }
    }

    private fun perform(toDiPlay: Boolean) {
        check(Build.MODEL.trim().equals("GT6-CAR", true)) { "This switcher supports GT6-CAR only" }
        trace("Checking root access. Allow CarPlay Switch if Magisk asks.")
        check(root.run("id -u").trim() == "0") { "Root access was not granted" }
        val diPlay = listOf("com.shihab.diplay.hudtest", "com.shihab.diplay").firstOrNull { pkg ->
            runCatching {
                context.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    .metaData?.getInt("com.shilapi.xcertplay.GT6_SWITCH_VERSION", 0) == 1
            }.getOrDefault(false)
        }
        if (toDiPlay && diPlay == null) throw IOException("Install the GT6 DiPlay build first")
        val zLink = context.packageManager.getLaunchIntentForPackage("com.zjinnova.zlink")
            ?.component?.flattenToString() ?: throw IOException("ZLink is not installed")
        // Exact firmware and actual peer checks precede all service/radio/app changes.
        val state = Gt6ProjectionControl.inspect()
        if (state.profileMask !in 0..1 || (state.peer != null && state.peer != state.lastPhone)) {
            throw IOException("Another phone or projection profile owns the connection. Disconnect it first.")
        }
        if (!toDiPlay && root.run("test -f $helper && echo yes").trim() != "yes") {
            throw IOException("The GT6 hotspot helper is missing; no apps were changed")
        }
        installBootSelection()
        trace("Closing the current CarPlay connection…")
        root.run("if [ ! -e $pause ]; then touch $pause; touch $base/owns-hotspot-pause; fi")
        try {
            root.run("am force-stop com.zjinnova.zlink; am force-stop com.shihab.diplay.hudtest; am force-stop com.shihab.diplay")
            root.run("setprop ctl.stop zlink5; i=0; while [ \"\$(getprop init.svc.zlink5)\" != stopped ] && [ \"\$i\" -lt 8 ]; do sleep 1; i=\$((i+1)); done; test \"\$(getprop init.svc.zlink5)\" = stopped")
            // Init must stop the supervisor and its child. Never kill an unverified PID.
            if (root.run("pidof z-link || true").trim().isNotEmpty()) {
                throw IOException("ZLink's native service did not stop")
            }
            Gt6ProjectionControl.release(state.lastPhone)
            trace("Previous connection released.")
            root.run("cmd wifip2p init >/dev/null; cmd wifip2p remove-group >/dev/null; cmd wifip2p deinit >/dev/null")
            root.run("if [ -f $helper ]; then $bridge stop; fi; cmd wifi stop-softap")
            if (toDiPlay) {
                root.run("svc wifi enable; printf %s ${quote(diPlay!!)} > $base/diplay-package; printf diplay > $base/selected")
                root.run("am start -n ${quote(diPlay!! + "/com.shilapi.xcertplay.Gt6StartWirelessActivity")} --es phone ${quote(state.lastPhone)}")
                trace("DiPlay selected. Accept CarPlay on your iPhone if asked.")
            } else {
                trace("Preparing ZLink’s wireless connection…")
                root.run("svc wifi disable; $bridge start")
                restoreHotspotWatcher()
                root.run("printf zlink > $base/selected; setprop ctl.start zlink5; i=0; while ! pidof z-link >/dev/null && [ \"\$i\" -lt 22 ]; do sleep 1; i=\$((i+1)); done; pidof z-link >/dev/null")
                root.run("am start -n ${quote(zLink)}")
                trace("ZLink selected. Accept CarPlay on your iPhone if asked.")
            }
        } catch (failure: Exception) {
            runCatching { restoreHotspotWatcher(); root.run("printf zlink > $base/selected; setprop ctl.start zlink5") }
            throw IOException("Switch did not finish: ${failure.message}. Use ZLink to reconnect.", failure)
        }
    }

    private fun restoreHotspotWatcher() {
        root.run("if [ -f $base/owns-hotspot-pause ]; then rm -f $pause $base/owns-hotspot-pause; fi")
    }

    private fun installBootSelection() {
        // Native ZLink starts again at boot. Preserve DiPlay selection without changing Wi-Fi.
        val script = """
            #!/system/bin/sh
            sleep 30
            [ "${'$'}(cat $base/selected 2>/dev/null)" = diplay ] || exit 0
            pkg="${'$'}(cat $base/diplay-package 2>/dev/null)"
            case "${'$'}pkg" in com.shihab.diplay.hudtest|com.shihab.diplay) ;; *) exit 0 ;; esac
            if ! pm path "${'$'}pkg" >/dev/null 2>&1; then
                printf zlink > $base/selected
                if [ -f $base/owns-hotspot-pause ]; then rm -f $pause $base/owns-hotspot-pause; fi
                exit 0
            fi
            if [ ! -e $pause ]; then touch $pause; touch $base/owns-hotspot-pause; fi
            am force-stop com.zjinnova.zlink
            setprop ctl.stop zlink5
        """.trimIndent() + "\n"
        root.run("umask 077; mkdir -p $base; chmod 700 $base; printf %s ${quote(script)} > /data/adb/service.d/gt6-carplay-switch.sh; chmod 700 /data/adb/service.d/gt6-carplay-switch.sh")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

internal class RootCommands {
    fun run(command: String): String {
        val process = ProcessBuilder("/debug_ramdisk/su", "-c", command).start()
        try {
            process.outputStream.close()
            if (!process.waitFor(35, TimeUnit.SECONDS)) throw IOException("Root command timed out. Check Magisk access and retry.")
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (process.exitValue() != 0) throw IOException(error.trim().take(200).ifBlank { "The car rejected a switch step" })
            return output
        } finally { process.destroy(); process.inputStream.close(); process.errorStream.close() }
    }
}
