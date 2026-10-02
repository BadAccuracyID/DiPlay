package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Shell/root-only entry point used after CarPlay Switch has released the previous receiver. */
class Gt6StartWirelessActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val phone = intent.getStringExtra("phone")?.replace(":", "")?.uppercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9A-F]{12}")) }
        if (phone == null || !Build.MODEL.trim().equals("GT6-CAR", true)) { finish(); return }
        val status = TextView(this).apply { text = "Preparing DiPlay…"; textSize = 28f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        Thread {
            try {
                DiPlayBootstrap.ensure(this)
                ProcessBuilder("/debug_ramdisk/su", "-c", "id -u").start().let { root ->
                    try {
                        root.outputStream.close()
                        check(root.waitFor(30, TimeUnit.SECONDS) && root.exitValue() == 0 &&
                            root.inputStream.bufferedReader().readText().trim() == "0") {
                            "Allow DiPlay root access in Magisk, then use CarPlay Switch again."
                        }
                    } finally { root.destroy(); root.inputStream.close(); root.errorStream.close() }
                }
                DiPlayPreferences.savePhone(this, phone.chunked(2).joinToString(":"), "Your iPhone")
                DiPlayPreferences.saveAutoConnect(this, false)
                AirPlayPersistence.saveGt6OemBluetoothEnabled(this, true)
                AirPlayPersistence.saveWirelessEnabled(this, true)
                AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.WIFI_P2P)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this, CarPlayHostActivity::class.java)); finish()
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread { if (!isDestroyed) status.text = "Couldn’t start DiPlay.\n${failure.message}" }
            }
        }.start()
    }
}
