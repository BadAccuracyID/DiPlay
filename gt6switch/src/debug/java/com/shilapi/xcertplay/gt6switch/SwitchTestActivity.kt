package com.shilapi.xcertplay.gt6switch

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Debug-only shell entry point for on-device tests that must survive a Wi-Fi ADB handoff. */
class SwitchTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val mode = intent.getStringExtra("mode")?.takeIf { it == "diplay" || it == "zlink" }
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
            try { SwitchCoordinator(this, ::trace).switch(mode == "diplay"); trace("PASS selected $mode") }
            catch (failure: Exception) { trace("FAIL ${failure.message}") }
            File(filesDir, "switch-test-$mode.txt").writeText(report.toString())
        }.start()
    }
}
