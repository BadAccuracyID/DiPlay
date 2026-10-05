package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

/** Read-only integration with the OEM illumination state. Never writes car or Android settings. */
internal object Gt6NightMode {
    enum class Mode(val label: String) {
        HEADLIGHTS("Follow headlights"), SYSTEM("Follow Android"), DAY("Day"), NIGHT("Night")
    }
    private const val PREFS = "gt6_appearance"
    private const val KEY = "night_mode"
    val URI: Uri = Uri.parse("content://com.szchoiceway.eventcenter.SysVarProvider/SysVar")
    const val LIGHT_KEY = "KSW_DATA_SMALL_LIGHT_ON"
    @Volatile private var lights: Boolean? = null

    fun available() = Build.MODEL.trim().equals("GT6-CAR", true)
    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "HEADLIGHTS")
    }.getOrDefault(Mode.HEADLIGHTS)
    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
    fun parse(value: String?): Boolean? = when (value?.trim()) { "1" -> true; "0" -> false; else -> null }
    fun resolve(mode: Mode, headlights: Boolean?, systemNight: Boolean): Boolean = when (mode) {
        Mode.HEADLIGHTS -> headlights ?: systemNight
        Mode.SYSTEM -> systemNight
        Mode.DAY -> false
        Mode.NIGHT -> true
    }
    fun current(context: Context): Boolean = resolve(mode(context), lights,
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

    class Monitor(context: Context, private val update: (Boolean) -> Unit) {
        private val app = context.applicationContext
        private val thread = HandlerThread("gt6-illumination")
        private val main = Handler(Looper.getMainLooper())
        private lateinit var worker: Handler
        @Volatile private var stopped = false
        private var registered = false
        private var lastSource: String? = null
        private val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { if (!stopped) worker.post { sample() } }
        }
        private val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                sample()
                if (!stopped) worker.postDelayed(this, 2_000)
            }
        }
        fun start() {
            thread.start(); worker = Handler(thread.looper)
            worker.post {
                if (stopped) return@post
                registered = runCatching { app.contentResolver.registerContentObserver(URI, true, observer); true }.getOrDefault(false)
                if (stopped) {
                    if (registered) runCatching { app.contentResolver.unregisterContentObserver(observer) }
                } else poll.run()
            }
        }
        fun stop() {
            stopped = true
            if (registered) runCatching { app.contentResolver.unregisterContentObserver(observer) }
            if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
            thread.quitSafely()
            lights = null
        }
        private fun sample() {
            if (stopped) return
            var source = "provider"
            var value = readProvider()
            if (value == null) { source = "OEM property"; value = readProperty() }
            if (value == null) source = "Android fallback"
            if (stopped) return
            lights = value // An unavailable read must not preserve an old headlight state.
            if (lastSource != source) {
                Log.i("Gt6NightMode", "Illumination source: $source")
                lastSource = source
            }
            update(current(app))
        }
        private fun readProvider(): Boolean? {
            val cancellation = CancellationSignal()
            val cancel = Runnable { cancellation.cancel() }
            main.postDelayed(cancel, 1_500)
            return try {
                app.contentResolver.query(URI, arrayOf("keyvalue"), "keyname=?", arrayOf(LIGHT_KEY), null, cancellation)?.use {
                    if (it.moveToFirst()) parse(it.getString(it.getColumnIndexOrThrow("keyvalue"))) else null
                }
            } catch (_: Exception) { null }
            finally { main.removeCallbacks(cancel) }
        }
        private fun readProperty(): Boolean? = runCatching {
            val process = ProcessBuilder("/system/bin/getprop", "rw.out.dark").start()
            try {
                process.outputStream.close()
                if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) null
                else parse(process.inputStream.bufferedReader().readText())
            } finally { process.destroyForcibly(); process.inputStream.close(); process.errorStream.close() }
        }.getOrNull()
    }
}
