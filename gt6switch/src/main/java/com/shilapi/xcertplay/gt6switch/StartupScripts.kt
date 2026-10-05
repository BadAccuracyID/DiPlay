package com.shilapi.xcertplay.gt6switch

/** Root survives app death; automatic starts require a matching coordinator completion receipt. */
internal object StartupScripts {
    const val BASE = "/data/adb/gt6-carplay-switch"
    const val SCRIPT = "/data/adb/service.d/gt6-carplay-switch.sh"
    fun watcher(): String = """
        #!/system/bin/sh
        base=$BASE
        umask 077
        mkdir -p "${'$'}base"
        exec 9> "${'$'}base/startup.lock"
        lock_tries=0
        until flock -n 9 9>&9; do
            lock_tries=${'$'}((lock_tries+1))
            [ "${'$'}lock_tries" -lt 6 ] || exit 0
            sleep 2 9>&-
        done
        log() { printf '%s %s\n' "${'$'}(date '+%Y-%m-%dT%H:%M:%S' 9>&-)" "${'$'}*" >> "${'$'}base/startup.log"; }
        printf '%s\n' "${'$'}${'$'}" > "${'$'}base/watcher.pid"
        printf '5\n' > "${'$'}base/watcher-version"
        trap 'rm -f "${'$'}base/watcher.pid"' EXIT
        awake=no
        attempts=0
        reason=boot
        pending=
        request_receiver=
        last_tick=0
        sequence=0
        retry_at=0
        boot_wait_logged=no
        while true; do
            tick="${'$'}(cut -d. -f1 /proc/uptime 9>&-)"
            tick="${'$'}{tick%% *}"
            case "${'$'}tick" in ''|*[!0-9]*) sleep 5 9>&-; continue ;; esac
            if [ "${'$'}last_tick" -gt 0 ] && [ "${'$'}((tick-last_tick))" -ge 20 ]; then
                log 'Resume gap detected; checking saved receiver'
                awake=no; attempts=0; pending=; retry_at=0; reason=wake
            fi
            last_tick="${'$'}tick"
            if [ "${'$'}(getprop sys.boot_completed 9>&-)" != 1 ]; then
                if [ "${'$'}boot_wait_logged" != yes ]; then log 'Waiting for Android boot'; boot_wait_logged=yes; fi
                sleep 5 9>&-; continue
            fi
            selected="${'$'}(cat "${'$'}base/selected" 2>/dev/null 9>&-)"
            case "${'$'}selected" in diplay|zlink) ;; *) sleep 5 9>&-; continue ;; esac
            power="${'$'}(timeout 5 dumpsys power 2>/dev/null 9>&-)" || { sleep 5 9>&-; continue; }
            case "${'$'}power" in
                *mWakefulness=Awake*) current=yes ;;
                *mWakefulness=Asleep*|*mWakefulness=Dozing*|*mWakefulness=Dreaming*) current=no ;;
                *) sleep 5 9>&-; continue ;;
            esac
            acc="${'$'}(getprop sys.acc.state 9>&-)"
            case "${'$'}acc" in 1) ;; 0) current=no ;; *) sleep 5 9>&-; continue ;; esac
            if [ "${'$'}current" = no ]; then
                if [ "${'$'}sequence" -gt 0 ]; then reason=wake; fi
                awake=no; attempts=0; pending=; retry_at=0
                sleep 5 9>&-; continue
            fi
            if [ -n "${'$'}pending" ]; then
                # A manual switch invalidates a pending request. Never reopen the previous receiver.
                if [ "${'$'}selected" != "${'$'}request_receiver" ]; then
                    pending=; awake=yes; attempts=0
                else
                    result="${'$'}(cat "${'$'}base/startup-result" 2>/dev/null 9>&-)"
                    if [ "${'$'}result" = "${'$'}pending success" ]; then
                        if [ "${'$'}request_receiver" = diplay ]; then
                            log 'Receiver preparation confirmed (radio access ready; DiPlay not opened)'
                        else log 'Receiver startup confirmed (service ready; phone connection not checked)'; fi
                        pending=; awake=yes; attempts=0; reason=wake
                    elif [ "${'$'}result" = "${'$'}pending failure" ] || [ "${'$'}tick" -ge "${'$'}deadline" ]; then
                        log "Receiver startup failed or timed out (${'$'}attempts/3)"
                        pending=; retry_at=${'$'}((tick+15))
                        if [ "${'$'}attempts" -ge 3 ]; then
                            # Cooldown avoids a loop of radio resets; retries remain available this ignition.
                            log 'Startup retry cooldown (60 seconds)'
                            attempts=0; retry_at=${'$'}((tick+60))
                        fi
                    fi
                fi
            fi
            if [ "${'$'}awake" != yes ] && [ -z "${'$'}pending" ] && [ "${'$'}tick" -ge "${'$'}retry_at" ]; then
                if ! pm path com.efran.carplayswitch 2>/dev/null 9>&- | grep -q '^package:'; then
                    log 'Switcher missing; restoring ZLink supervisor'
                    printf zlink > "${'$'}base/selected"
                    if [ -f "${'$'}base/owns-hotspot-pause" ]; then
                        rm -f /data/local/tmp/skip_softap_boot "${'$'}base/owns-hotspot-pause"
                    fi
                    setprop ctl.start zlink5 9>&-
                    exit 0
                fi
                if ! pidof blink >/dev/null 9>&- || [ "${'$'}(settings get global bluetooth_on 9>&-)" != 1 ]; then
                    sleep 5 9>&-; continue
                fi
                window="${'$'}(timeout 5 dumpsys window 2>/dev/null 9>&-)" || { sleep 5 9>&-; continue; }
                case "${'$'}window" in *mCurrentFocus=*) ;; *) sleep 5 9>&-; continue ;; esac
                focus="${'$'}(printf '%s\n' "${'$'}window" | grep 'mCurrentFocus=' 9>&-)"
                case "${'$'}focus" in *com.ivicar.avm*|*mCurrentFocus=null*) sleep 5 9>&-; continue ;; esac
                # After an actual failure, defer retries while the driver is using another app.
                if [ "${'$'}retry_at" -gt 0 ]; then
                    case "${'$'}focus" in *launcher*|*Launcher*|*com.efran.carplayswitch*|*com.shihab.diplay*|*com.zjinnova.zlink*) ;;
                        *) sleep 5 9>&-; continue ;; esac
                fi
                sequence=${'$'}((sequence+1))
                pending="${'$'}${'$'}-${'$'}tick-${'$'}sequence"
                request_receiver="${'$'}selected"
                deadline=${'$'}((tick+240))
                attempts=${'$'}((attempts+1))
                if [ "${'$'}selected" = diplay ]; then
                    launch_mode=start-foreground-service
                    component=com.efran.carplayswitch/com.shilapi.xcertplay.gt6switch.PrepareSelectedService
                    log "Preparing selected receiver (${'$'}reason, attempt ${'$'}attempts/3)"
                else
                    launch_mode=start
                    component=com.efran.carplayswitch/com.shilapi.xcertplay.gt6switch.OpenSelectedActivity
                    log "Opening selected receiver (${'$'}reason, attempt ${'$'}attempts/3)"
                fi
                if ! timeout 10 am "${'$'}launch_mode" -n "${'$'}component" \
                    --es reason "${'$'}reason" --es startup_request "${'$'}pending" --es expected_receiver "${'$'}selected" \
                    > "${'$'}base/startup-launch.txt" 2>&1 9>&- ||
                    grep -Eq '^[[:space:]]*(Error:|Exception|Security exception:)|SecurityException' "${'$'}base/startup-launch.txt"; then
                    printf '%s failure' "${'$'}pending" > "${'$'}base/startup-result"
                fi
            fi
            sleep 5 9>&-
        done
    """.trimIndent() + "\n"

    /** Migration from v0.2/0.3: stop only an interpreter running our exact installed script. */
    fun stopPreviousWatcher(): String = """
        for entry in /proc/[0-9]*/cmdline; do
            [ -r "${'$'}entry" ] || continue
            # Shell builtins skip hundreds of unrelated processes before spawning any parser.
            process_dir="${'$'}{entry%/cmdline}"
            IFS= read -r process_name < "${'$'}process_dir/comm" 2>/dev/null || continue
            case "${'$'}process_name" in sh|busybox) ;; *) continue ;; esac
            args="${'$'}(tr '\000' '\n' < "${'$'}entry" 2>/dev/null)"
            executable="${'$'}(printf '%s\n' "${'$'}args" | sed -n '1p')"
            script="${'$'}(printf '%s\n' "${'$'}args" | sed -n '2p')"
            case "${'$'}executable" in
                /system/bin/sh|sh) ;;
                /debug_ramdisk/.magisk/busybox/busybox|busybox)
                    [ "${'$'}script" = sh ] || continue
                    script="${'$'}(printf '%s\n' "${'$'}args" | sed -n '3p')" ;;
                *) continue ;;
            esac
            [ "${'$'}script" = "$SCRIPT" ] || continue
            pid="${'$'}{entry#/proc/}"; pid="${'$'}{pid%/cmdline}"
            kill -TERM "${'$'}pid" 2>/dev/null || true
        done
    """.trimIndent()
}
