package com.shilapi.xcertplay.gt6switch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Debug-only shell entry point for on-device tests that must survive a Wi-Fi ADB handoff. */
class SwitchTestActivity : Activity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val mode = intent.getStringExtra("mode")?.takeIf { it in listOf("diplay", "zlink", "open", "inspect") }
            ?: run { finish(); return }
        val status = TextView(this).apply { textSize = 22f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        Thread {
            val report = StringBuilder("Normal app UID=${android.os.Process.myUid()}\n")
            fun trace(line: String) {
                report.appendLine(line)
                android.util.Log.i("Gt6SwitchTest", line)
                runOnUiThread { if (!isDestroyed) status.text = report.toString() }
            }
            try {
                when (mode) {
                    "inspect" -> com.shilapi.xcertplay.transport.gt6.Gt6ProjectionControl.inspect().let {
                        trace("STATE profileMask=${it.profileMask} peerPresent=${it.peer != null} peerMatchesLast=${it.peer == it.lastPhone}")
                    }
                    "open" -> SwitchCoordinator(this, ::trace).openSelected()
                    else -> SwitchCoordinator(this, ::trace).switch(mode == "diplay")
                }
                trace("PASS selected $mode")
            }
            catch (failure: Exception) { trace("FAIL ${failure.message}") }
            File(filesDir, "switch-test-$mode.txt").writeText(report.toString())
        }.start()
    }
}
