package com.shilapi.xcertplay

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.TextView
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.transport.gt6.Gt6OemBluetoothStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.Locale

/** Debug-only, shell-protected check. Never calls the wireless controller or Wi-Fi APIs. */
class Gt6BluetoothTestActivity : Activity() {
    private val stopped = AtomicBoolean()
    private val activeStream = AtomicReference<Gt6OemBluetoothStream?>()
    private var worker: Thread? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !Build.MODEL.trim().equals("GT6-CAR", ignoreCase = true)) {
            finish(); return
        }
        val phone = intent.getStringExtra("phone")?.replace(":", "")?.uppercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9A-F]{12}")) } ?: run { finish(); return }
        val status = TextView(this).apply { textSize = 20f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        val report = StringBuilder()
        fun trace(line: String) {
            synchronized(report) { report.appendLine(line) }
            Log.i("Gt6AppTest", line)
            runOnUiThread { if (!isFinishing) status.text = synchronized(report) { report.toString() } }
        }
        worker = Thread({
            try {
                trace("DiPlay GT6 Bluetooth check; app UID=${Process.myUid()}")
                trace("Wi-Fi mode is unchanged by this check.")
                DiPlayBootstrap.ensure(this)
                trace("Local CarPlay authentication assets loaded and key consistency verified.")
                DiPlayPreferences.saveAutoConnect(this, false)
                DiPlayPreferences.savePhone(this, phone.chunked(2).joinToString(":"), "Your iPhone")
                AirPlayPersistence.saveGt6OemBluetoothEnabled(this, true)
                AirPlayPersistence.saveWirelessEnabled(this, false)
                if (stopped.get()) throw IOException("Check cancelled")
                trace("Checking root access; allow DiPlay GT6 Test if Magisk prompts.")
                verifyRoot()
                Gt6OemBluetoothStream(phone, ::trace).use { stream ->
                    activeStream.set(stream)
                    if (stopped.get()) throw IOException("Check cancelled")
                    stream.connect(15_000)
                    trace("Selected iPhone RFCOMM peer verified from the app.")
                    Iap2Session.openWireless(stream, "gt6-app-bt-only", ::trace).use { session ->
                        check(session.awaitReady(15_000)) { "iAP2 link negotiation timed out" }
                        trace("PASS: app root access, Bluetooth and iAP2 link negotiation.")
                    }
                }
                trace("Full wireless CarPlay and Wi-Fi handoff have not been tested.")
            } catch (failure: Throwable) {
                trace("FAIL: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                activeStream.set(null)
                File(filesDir, "gt6-bt-check.txt").writeText(synchronized(report) { report.toString() })
            }
        }, "diplay-gt6-app-check").apply { start() }
    }

    override fun onDestroy() {
        stopped.set(true)
        worker?.interrupt()
        Thread { activeStream.getAndSet(null)?.close() }.start()
        super.onDestroy()
    }

    /** Allow time for the first permission prompt before the short read-only inspections. */
    private fun verifyRoot() {
        val process = ProcessBuilder("/debug_ramdisk/su", "-c", "id -u").start()
        try {
            process.outputStream.close()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw IOException("Root permission timed out; grant this app root in Magisk and retry")
            }
            val uid = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() != 0 || uid != "0") {
                val reason = process.errorStream.bufferedReader().readText().trim().take(300)
                throw IOException("Root access failed: ${reason.ifBlank { "not granted" }}")
            }
        } finally {
            process.destroy()
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}
