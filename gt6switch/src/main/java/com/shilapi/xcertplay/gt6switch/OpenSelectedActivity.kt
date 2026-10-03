package com.shilapi.xcertplay.gt6switch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Navi opens the saved receiver. Boot and wake use this same serialized, non-resetting route. */
class OpenSelectedActivity : Activity() {
    @Volatile private var running = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!running) recreate()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val reason = intent.getStringExtra("reason")?.takeIf { it in listOf("boot", "wake") } ?: "shortcut"
        val status = TextView(this).apply {
            text = "Opening CarPlay…"; textSize = 24f; setPadding(32, 32, 32, 32)
        }
        setContentView(status)
        running = true
        Thread {
            val report = StringBuilder("Open selected receiver: $reason\n")
            fun trace(line: String) {
                report.appendLine(line)
                android.util.Log.i("Gt6Startup", line)
                runOnUiThread { if (!isDestroyed) status.text = report.toString() }
            }
            try {
                SwitchCoordinator(this, ::trace).openSelected()
                trace("PASS opened selected receiver")
                runOnUiThread { if (!isDestroyed) finish() }
            } catch (failure: Exception) {
                trace("Couldn’t open CarPlay: ${failure.message}. Open CarPlay Switch to retry.")
            } finally {
                File(filesDir, "startup-$reason.txt").writeText(report.toString())
                running = false
            }
        }.start()
    }
}
