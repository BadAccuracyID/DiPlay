package com.shilapi.xcertplay.gt6switch

/** The root watcher only launches the coordinator; receiver changes share its app-level lock. */
internal object StartupScripts {
    const val BASE = "/data/adb/gt6-carplay-switch"
    fun watcher(): String = """
        #!/system/bin/sh
        base=$BASE
        umask 077
        mkdir -p "${'$'}base"
        exec 9> "${'$'}base/startup.lock"
        flock -n 9 9>&9 || exit 0
        log() { printf '%s %s\n' "${'$'}(date '+%Y-%m-%dT%H:%M:%S')" "${'$'}*" >> "${'$'}base/startup.log"; }
        i=0
        while [ "${'$'}(getprop sys.boot_completed)" != 1 ] && [ "${'$'}i" -lt 120 ]; do
            sleep 2; i=${'$'}((i+1))
        done
        [ "${'$'}(getprop sys.boot_completed)" = 1 ] || { log 'Boot readiness timed out'; exit 1; }
        # Disarm this boot's launch while waiting for Bluetooth; never create a second radio owner.
        awake=no
        attempts=0
        reason=boot
        while true; do
            selected="${'$'}(cat "${'$'}base/selected" 2>/dev/null)"
            case "${'$'}selected" in diplay|zlink) ;; *) sleep 5; continue ;; esac
            # Treat failed state reads as unknown, not as a sleep/wake edge.
            power="${'$'}(timeout 5 dumpsys power 2>/dev/null)" || { sleep 5; continue; }
            case "${'$'}power" in
                *mWakefulness=Awake*) current=yes ;;
                *mWakefulness=Asleep*|*mWakefulness=Dozing*|*mWakefulness=Dreaming*) current=no ;;
                *) sleep 5; continue ;;
            esac
            acc="${'$'}(getprop sys.acc.state)"
            case "${'$'}acc" in 1) ;; 0) current=no ;; *) sleep 5; continue ;; esac
            # GT6 can toggle ACC while Android remains Awake.
            if [ "${'$'}current" = no ]; then attempts=0; fi
            if [ "${'$'}current" = yes ] && [ "${'$'}awake" != yes ]; then
                if ! pm path com.efran.carplayswitch >/dev/null 2>&1; then
                    log 'Switcher missing; restoring ZLink supervisor'
                    printf zlink > "${'$'}base/selected"
                    if [ -f "${'$'}base/owns-hotspot-pause" ]; then
                        rm -f /data/local/tmp/skip_softap_boot "${'$'}base/owns-hotspot-pause"
                    fi
                    setprop ctl.start zlink5
                    exit 0
                fi
                if ! pidof blink >/dev/null || [ "${'$'}(settings get global bluetooth_on)" != 1 ]; then
                    sleep 5; continue
                fi
                # Do not cover the reversing camera when the display wakes in reverse.
                window="${'$'}(timeout 5 dumpsys window 2>/dev/null)" || { sleep 5; continue; }
                case "${'$'}window" in *mCurrentFocus=*) ;; *) sleep 5; continue ;; esac
                if printf '%s\n' "${'$'}window" | grep 'mCurrentFocus=' | grep -q com.ivicar.avm; then
                    sleep 5; continue
                fi
                log "Opening selected receiver (${'$'}reason)"
                # Android am can print Error: while still returning status zero.
                if ! timeout 10 am start -n com.efran.carplayswitch/com.shilapi.xcertplay.gt6switch.OpenSelectedActivity \
                    --es reason "${'$'}reason" > "${'$'}base/startup-launch.txt" 2>&1 ||
                    grep -Eq '^(Error:|Exception|Security exception:)' "${'$'}base/startup-launch.txt"; then
                    attempts=${'$'}((attempts+1))
                    log "Activity launch failed (${'$'}attempts/3)"
                    if [ "${'$'}attempts" -lt 3 ]; then sleep 10; continue; fi
                    log 'Launch retries exhausted; use CarPlay Switch to retry'
                else
                    attempts=0
                fi
                reason=wake
            fi
            awake="${'$'}current"
            sleep 5
        done
    """.trimIndent() + "\n"
}
