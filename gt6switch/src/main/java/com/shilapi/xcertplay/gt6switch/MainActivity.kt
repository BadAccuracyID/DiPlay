package com.shilapi.xcertplay.gt6switch

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val buttons = mutableListOf<Button>()
    private val report = StringBuilder()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(12, 20, 31)
        window.navigationBarColor = Color.rgb(12, 20, 31)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(12, 20, 31))
        }
        content.addView(TextView(this).apply {
            text = "CarPlay Switch"; textSize = 32f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Choose your CarPlay app. Switching disconnects the current session."
            textSize = 18f; setTextColor(Color.rgb(174, 191, 211)); setPadding(0, dp(10), 0, dp(24))
        })
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, diPlay) in listOf("Use DiPlay" to true, "Use ZLink" to false)) {
            val button = Button(this).apply {
                text = name; textSize = 27f; isAllCaps = false
                setTextColor(Color.rgb(12, 20, 31))
                background = GradientDrawable().apply {
                    setColor(if (diPlay) Color.rgb(147, 198, 255) else Color.rgb(99, 215, 172))
                    cornerRadius = dp(18).toFloat()
                }
                setOnClickListener { startSwitch(diPlay) }
            }
            buttons += button
            choices.addView(button, LinearLayout.LayoutParams(0, dp(125), 1f).apply { if (!diPlay) marginStart = dp(24) })
        }
        content.addView(choices)
        val export = Button(this).apply {
            text = "Save connection report"; textSize = 20f; isAllCaps = false
            setOnClickListener { saveReport() }
        }
        buttons += export
        content.addView(export, LinearLayout.LayoutParams(-1, dp(65)).apply { topMargin = dp(16) })
        status = TextView(this).apply {
            text = "Ready. Requires Magisk root access on GT6-CAR."
            textSize = 19f; setTextColor(Color.rgb(207, 222, 240)); setPadding(0, dp(24), 0, 0)
        }
        content.addView(status)
        setContentView(content)
    }

    private fun saveReport() {
        if (!busy.compareAndSet(false, true)) return
        buttons.forEach { it.isEnabled = false }
        status.text = "Saving the last ZLink connection report…"
        Thread {
            var pending: android.net.Uri? = null
            try {
                val report = RootCommands().run("cat ${StartupScripts.BASE}/zlink-diagnostics.txt 2>/dev/null || true")
                check(report.startsWith("CarPlay Switch")) { "No report yet. Choose Use ZLink first, then return here to save its report." }
                val name = "carplay-switch-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CarPlaySwitch")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Couldn’t create the report file")
                pending = uri
                contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    ?: error("Couldn’t write the report file")
                check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                    "Couldn’t finish saving the report"
                }
                pending = null
                runOnUiThread { if (!isDestroyed) status.text = "Saved to Downloads/CarPlaySwitch/$name" }
            } catch (failure: Exception) {
                pending?.let { runCatching { contentResolver.delete(it, null, null) } }
                runOnUiThread { if (!isDestroyed) status.text = "Couldn’t save the report: ${failure.message}" }
            } finally {
                busy.set(false)
                runOnUiThread { if (!isDestroyed) buttons.forEach { it.isEnabled = true } }
            }
        }.start()
    }

    private fun startSwitch(diPlay: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        buttons.forEach { it.isEnabled = false }
        report.clear()
        Thread {
            fun trace(line: String) {
                synchronized(report) { report.appendLine(line) }
                android.util.Log.i("Gt6Switch", line)
                runOnUiThread { if (!isDestroyed) status.text = synchronized(report) { report.toString() } }
            }
            try { SwitchCoordinator(this, ::trace).switch(diPlay) }
            catch (failure: Exception) { trace("Couldn’t switch: ${failure.message}") }
            finally {
                java.io.File(filesDir, "last-switch.txt").writeText(synchronized(report) { report.toString() })
                busy.set(false)
                runOnUiThread { if (!isDestroyed) buttons.forEach { it.isEnabled = true } }
            }
        }.start()
    }

    companion object { private val busy = AtomicBoolean() }
}
