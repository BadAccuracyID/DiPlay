package com.shilapi.xcertplay.gt6switch

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Execute the generated shell against synthetic Android command responses. No ADB or root. */
class StartupScriptsTest {
    @Test fun normalAwakePollingLaunchesOnlyOnce() {
        assertEquals(listOf("boot"), runWatcher(List(5) { "1 Awake 0" }))
    }

    @Test fun ignitionWakeIsHandledWhenAndroidNeverSleeps() {
        assertEquals(listOf("boot", "wake"), runWatcher(listOf("1 Awake 0", "0 Awake 0", "1 Awake 0", "1 Awake 0")))
    }

    @Test fun failedPowerReadDoesNotInventAWakeTransition() {
        assertEquals(listOf("boot"), runWatcher(listOf("1 Awake 0", "1 unknown 0", "1 Awake 0")))
    }

    @Test fun zeroExitAndroidErrorsAreRetriedUntilLaunchSucceeds() {
        assertEquals(listOf("boot", "boot", "boot"), runWatcher(List(15) { "1 Awake 0" }, launchFailures = 2))
    }

    @Test fun launchFailureRetriesAreBounded() {
        assertEquals(3, runWatcher(List(16) { "1 Awake 0" }, launchFailures = 10).size)
    }

    @Test fun cameraDefersStartupWithoutLosingThePendingLaunch() {
        assertEquals(listOf("boot"), runWatcher(listOf("1 Awake 1", "1 Awake 1", "1 Awake 0", "1 Awake 0")))
    }

    @Test fun unavailableCameraFocusDoesNotPermitAnUncheckedLaunch() {
        assertEquals(listOf("boot"), runWatcher(listOf("1 Awake 2", "1 Awake 2", "1 Awake 0")))
    }

    @Test fun coordinatorFailureAfterAmSuccessIsRetried() {
        assertEquals(listOf("boot", "boot"), runWatcher(List(10) { "1 Awake 0" }, ackFailures = 1))
    }
    @Test fun noCompletionReceiptCannotDisarmStartup() {
        assertEquals(listOf("boot", "boot"), runWatcher(List(55) { "1 Awake 0" }, missingAck = true))
    }
    @Test fun suspendGapTriggersWakeEvenIfAccAndAndroidRemainAwake() {
        assertEquals(listOf("boot", "wake"), runWatcher(List(6) { "1 Awake 0" }, gapAt = 3))
    }
    @Test fun failedStartupRetriesAfterCooldownInsteadOfGivingUpUntilNextIgnition() {
        assertEquals(4, runWatcher(List(25) { "1 Awake 0" }, launchFailures = 10).size)
    }
    @Test fun cameraBlocksRetryAfterCoordinatorFailure() {
        assertEquals(listOf("boot", "boot"), runWatcher(
            listOf("1 Awake 0") + List(10) { "1 Awake 1" } + List(6) { "1 Awake 0" }, ackFailures = 1))
    }

    private fun runWatcher(states: List<String>, launchFailures: Int = 0,
        ackFailures: Int = 0, missingAck: Boolean = false, gapAt: Int = 0): List<String> {
        val temp = Files.createTempDirectory("gt6-watcher-test").toFile()
        var process: Process? = null
        try {
            val bin = temp.resolve("bin").apply { mkdirs() }
            val base = temp.resolve("state").apply { mkdirs() }
            base.resolve("selected").writeText("diplay")
            temp.resolve("states").writeText(states.joinToString("\n") + "\n")
            temp.resolve("step").writeText("1")
            temp.resolve("launch-count").writeText("0")
            val stub = """
                #!/bin/sh
                name=${'$'}{0##*/}
                step=${'$'}(cat step)
                state=${'$'}(sed -n "${'$'}{step}p" states)
                set -- "${'$'}name" "${'$'}@"
                case "${'$'}1" in
                    cut)
                        if [ "${'$'}4" = /proc/uptime ]; then
                            extra=0; [ "$gapAt" -eq 0 ] || [ "${'$'}step" -lt $gapAt ] || extra=60
                            echo "${'$'}((step*5+extra)) 0"
                        else shift; exec /usr/bin/cut "${'$'}@"; fi ;;
                    getprop) case "${'$'}2" in sys.boot_completed) echo 1 ;; sys.acc.state) echo "${'$'}state" | cut -d' ' -f1 ;; esac ;;
                    dumpsys)
                        if [ "${'$'}2" = power ]; then
                            value=${'$'}(echo "${'$'}state" | cut -d' ' -f2)
                            [ "${'$'}value" != unknown ] || exit 1
                            echo "mWakefulness=${'$'}value"
                        else
                            focus=${'$'}(echo "${'$'}state" | cut -d' ' -f3)
                            case "${'$'}focus" in
                                1) echo 'mCurrentFocus=com.ivicar.avm' ;;
                                2) exit 1 ;;
                                *) echo 'mCurrentFocus=launcher' ;;
                            esac
                        fi ;;
                    settings) echo 1 ;;
                    pm) echo package:installed ;;
                    pidof) echo 123 ;;
                    flock) exit 0 ;;
                    timeout) shift 2; exec "${'$'}@" ;;
                    date) echo synthetic-time ;;
                    sleep)
                        step=${'$'}((step+1)); echo "${'$'}step" > step
                        [ "${'$'}step" -le ${states.size} ] || kill -TERM "${'$'}PPID" ;;
                    am)
                        count=${'$'}(cat launch-count); count=${'$'}((count+1)); echo "${'$'}count" > launch-count
                        token=
                        while [ "${'$'}#" -gt 1 ]; do
                            [ "${'$'}1" != reason ] || echo "${'$'}2" >> launches
                            [ "${'$'}1" != startup_request ] || token="${'$'}2"
                            shift
                        done
                        if [ "${'$'}count" -le $launchFailures ]; then echo 'Error: launch rejected'
                        else
                            echo 'Starting: Intent'
                            if [ "$missingAck" != true ]; then
                                outcome=success
                                [ "${'$'}count" -gt $ackFailures ] || outcome=failure
                                printf '%s %s' "${'$'}token" "${'$'}outcome" > "${base.absolutePath}/startup-result"
                            fi
                        fi ;;
                esac
                exit 0
            """.trimIndent() + "\n"
            listOf("cut", "getprop", "dumpsys", "settings", "pm", "pidof", "flock", "timeout", "date", "sleep", "am").forEach {
                bin.resolve(it).apply { writeText(stub); setExecutable(true) }
            }
            val watcher = temp.resolve("watcher.sh").apply {
                writeText(StartupScripts.watcher().replace(StartupScripts.BASE, base.absolutePath))
            }
            process = ProcessBuilder("/bin/sh", watcher.absolutePath).directory(temp)
                .redirectErrorStream(true).redirectOutput(temp.resolve("output")).apply {
                    environment()["PATH"] = bin.absolutePath + ":" + environment()["PATH"]
                }.start()
            assertTrue("Watcher must finish the synthetic sequence", process.waitFor(10, TimeUnit.SECONDS))
            return temp.resolve("launches").takeIf { it.exists() }?.readLines() ?: emptyList()
        } finally { process?.destroyForcibly(); temp.deleteRecursively() }
    }
}
