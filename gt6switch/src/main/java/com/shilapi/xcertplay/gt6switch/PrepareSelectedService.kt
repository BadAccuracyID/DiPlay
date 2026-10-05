package com.shilapi.xcertplay.gt6switch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.File
import java.util.concurrent.Executors

/** Shell/root-only boot preparation. Never starts a DiPlay activity or connection. */
class PrepareSelectedService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay startup", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.shilapi.xcertplay.gt6switch.R.drawable.ic_switch)
            .setContentTitle("CarPlay Switch").setContentText("Preparing DiPlay radio access")
            .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val reason = intent?.getStringExtra("reason")?.takeIf { it in listOf("boot", "wake") }
        val request = intent?.getStringExtra("startup_request")?.takeIf { it.matches(Regex("[0-9]+-[0-9]+-[0-9]+")) }
        val expected = intent?.getStringExtra("expected_receiver")?.takeIf { it == "diplay" }
        worker.execute {
            val report = StringBuilder("Prepare selected receiver: ${reason ?: "invalid"}\n")
            val coordinator = SwitchCoordinator(this, {
                report.appendLine(it); android.util.Log.i("Gt6Startup", it)
            })
            var success = false
            try {
                require(reason != null && request != null && expected != null) { "Invalid startup preparation request" }
                coordinator.prepareSelected(expected)
                success = true
                report.appendLine("PASS prepared DiPlay; app not opened and phone connection not requested")
            } catch (failure: Exception) {
                report.appendLine("Couldn’t prepare DiPlay: ${failure.message}. Startup will retry when ready.")
            } finally {
                if (reason != null) runCatching { File(filesDir, "startup-$reason.txt").writeText(report.toString()) }
                if (request != null) runCatching { coordinator.reportStartup(request, success) }
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private companion object { const val CHANNEL = "gt6_startup" }
}
