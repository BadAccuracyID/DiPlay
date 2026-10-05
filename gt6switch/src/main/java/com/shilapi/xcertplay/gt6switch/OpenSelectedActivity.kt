package com.shilapi.xcertplay.gt6switch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Boot and wake receive completion feedback; a successful am start alone is insufficient. */
class OpenSelectedActivity : Activity() {
    @Volatile private var running = false
    private var queued: Intent? = null
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { text = "Opening CarPlay…"; textSize = 24f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (running) queued = intent else open(intent)
    }

    private fun open(requestIntent: Intent) {
        val reason = requestIntent.getStringExtra("reason")?.takeIf { it in listOf("boot", "wake") } ?: "shortcut"
        val request = requestIntent.getStringExtra("startup_request")?.takeIf { it.matches(Regex("[0-9]+-[0-9]+-[0-9]+")) }
        val expected = requestIntent.getStringExtra("expected_receiver")?.takeIf { it in listOf("diplay", "zlink") }
        running = true
        Thread {
            val report = StringBuilder("Open selected receiver: $reason\n")
            fun trace(line: String) {
                report.appendLine(line)
                android.util.Log.i("Gt6Startup", line)
                runOnUiThread { if (!isDestroyed) status.text = report.toString() }
            }
            val coordinator = SwitchCoordinator(this, ::trace)
            var success = false
            try {
                coordinator.openSelected(expected, verifyStartup = request != null)
                success = true
                trace("PASS opened selected receiver; phone connection not checked")
            } catch (failure: Exception) {
                trace("Couldn’t open CarPlay: ${failure.message}. Startup will retry when ready.")
            } finally {
                runCatching { File(filesDir, "startup-$reason.txt").writeText(report.toString()) }
                if (request != null) runCatching { coordinator.reportStartup(request, success) }
                runOnUiThread {
                    running = false
                    val next = queued; queued = null
                    if (!isDestroyed) {
                        if (next != null) open(next) else if (success || request != null) finish()
                    }
                }
            }
        }.start()
    }
}
