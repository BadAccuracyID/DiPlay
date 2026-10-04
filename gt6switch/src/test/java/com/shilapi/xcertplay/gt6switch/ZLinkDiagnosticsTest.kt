package com.shilapi.xcertplay.gt6switch

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ZLinkDiagnosticsTest {
    @Test fun captureSummarizesTheHandoffWithoutCopyingCredentialsOrVendorPayloads() {
        val temp = Files.createTempDirectory("gt6-diagnostics-test").toFile()
        var process: Process? = null
        try {
            val base = temp.resolve("state").apply { mkdirs() }
            base.resolve("selected").writeText("zlink")
            val native = temp.resolve("native").apply { mkdirs() }
            native.resolve("zlink_log-1.txt").writeText("""
                certificate=PRIVATE-CERTIFICATE-CONTENT
                wifi_passwd=PRIVATE-PASSWORD-VALUE
                Authentication_Process: AuthenticationSucceeded
                HU ap is disconnect...
                zlink_stop reason: BT iap_watch_dog timeout
                ip_port is NULL
            """.trimIndent())
            val bin = temp.resolve("bin").apply { mkdirs() }
            fun command(name: String, body: String) {
                bin.resolve(name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
            }
            command("flock", "exit 0")
            command("pidof", "echo 123")
            command("timeout", "shift; exec \"\$@\"")
            command("app_process", "printf 'ap=13 wifi=1 config=match\\npassword=PRIVATE-PASSWORD-VALUE\\n'")
            command("dumpsys", "printf 'ServiceRecord features.service.DaemonService\\nServiceRecord features.wireless.BluetoothService\\n'")
            command("ip", "printf '1: wlan0 inet 192.168.43.1/24\\n2: wlan1 inet PRIVATE-PASSWORD-VALUE\\n'")
            command("ss", "printf 'LISTEN 0 10 0.0.0.0:7000 *:* users:((process,pid=123,fd=9))\\nLISTEN 0 10 0.0.0.0:4444 *:* users:((PRIVATE-PASSWORD-VALUE,pid=999,fd=9))\\n'")
            command("logcat", """
                printf '[apopt]wirelessCmd stopHotspot\n[InitData]isAllowConn  = true\nupdate init info 1920 720 2063 true\n'
                printf 'password=PRIVATE-PASSWORD-VALUE certificate=PRIVATE-CERTIFICATE-CONTENT\n'
            """.trimIndent())
            command("sleep", "printf diplay > '${base.absolutePath}/selected'")
            val script = temp.resolve("diagnostics.sh").apply {
                writeText(ZLinkDiagnostics.script().replace(StartupScripts.BASE, base.absolutePath)
                    .replace("/sdcard/zlinklog", native.absolutePath))
            }
            process = ProcessBuilder("/bin/sh", script.absolutePath).directory(temp)
                .redirectErrorStream(true).redirectOutput(temp.resolve("worker-output")).apply {
                    environment()["PATH"] = bin.absolutePath + ":" + environment()["PATH"]
                }.start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            val report = base.resolve("zlink-diagnostics.txt").readText()
            assertTrue(report.contains("hotspot ap=13 wifi=1 config=match"))
            assertTrue(report.contains("native_tcp_listener_port=7000"))
            assertFalse(report.contains("native_tcp_listener_port=4444"))
            assertTrue(report.contains("daemon_service=1 bluetooth_service=1"))
            assertTrue(report.contains("recent_zlink_hotspot_stop=1"))
            assertTrue(report.contains("native_mode=2063"))
            assertTrue(report.contains("latest_native_auth_success=1 latest_native_iap_timeout=1 latest_native_ap_disconnect=1 latest_native_missing_endpoint=1"))
            assertTrue(report.contains("Capture complete"))
            assertFalse(report.contains("PRIVATE-"))
            assertEquals("diplay", base.resolve("selected").readText())
        } finally { process?.destroyForcibly(); temp.deleteRecursively() }
    }
}
