package com.shilapi.xcertplay.gt6switch

/** Read-only summaries survive the loss of Wi-Fi ADB during a handoff. No raw vendor logs. */
internal object ZLinkDiagnostics {
    private const val BASE = StartupScripts.BASE

    fun start(root: CommandRunner) {
        val script = script()
        val quoted = "'" + script.replace("'", "'\\''") + "'"
        root.run("umask 077; printf %s $quoted > $BASE/zlink-diagnostics.tmp; chmod 700 $BASE/zlink-diagnostics.tmp; mv $BASE/zlink-diagnostics.tmp $BASE/zlink-diagnostics.sh")
        root.run("(timeout 180 /system/bin/sh $BASE/zlink-diagnostics.sh) </dev/null > $BASE/diagnostics-worker.txt 2>&1 &")
    }

    fun script(): String = """
        #!/system/bin/sh
        base=$BASE
        umask 077
        exec 8> "${'$'}base/diagnostics.lock"
        flock -n 8 8>&8 || exit 0
        report="${'$'}base/zlink-diagnostics.txt"
        printf 'CarPlay Switch 0.3 ZLink handoff diagnostics\n' > "${'$'}report"
        sample=0
        while [ "${'$'}sample" -lt 30 ]; do
            [ "${'$'}(cat "${'$'}base/selected" 2>/dev/null)" = zlink ] || break
            {
                printf '\nSample %s at %s\n' "${'$'}sample" "${'$'}(date '+%Y-%m-%dT%H:%M:%S')"
                pids="${'$'}(pidof 'z-'link)"
                if [ -n "${'$'}pids" ]; then echo native_running=yes; else echo native_running=no; fi
                # Listener ports help distinguish Wi-Fi join from receiver endpoint readiness.
                # ss is optional on vendor Android builds; no raw socket/process lines are copied.
                timeout 5 ss -ltnp 2>/dev/null | awk -v pids="${'$'}pids" '
                    BEGIN { count=split(pids, ids, " ") }
                    { for (i=1; i<=count; i++) {
                        if (ids[i]!="" && (index(${'$'}0, "pid=" ids[i] ",") || index(${'$'}0, "pid=" ids[i] ")"))) {
                            port=${'$'}4; sub(/^.*:/, "", port)
                            if (port ~ /^[0-9]+${'$'}/) print "native_tcp_listener_port=" port
                            break
                        }
                    } }'
                CLASSPATH=/data/adb/hotspot/helper.apk timeout 10 app_process /system/bin com.efran.hotspot.RootBridge status 2>/dev/null |
                    awk '/^ap=[0-9]+ wifi=[0-9]+ config=(match|drift)$/ { print "hotspot " ${'$'}0 }'
                timeout 5 dumpsys activity services com.zjinnova.zlink 2>/dev/null | awk '
                    /ServiceRecord.*features.service.DaemonService/ { daemon=1 }
                    /ServiceRecord.*features.wireless.(Bluetooth|ZBT|BluetoothHCT)Service/ { bt=1 }
                    END { printf "daemon_service=%d bluetooth_service=%d\n", daemon, bt }'
                ip -o -4 addr show 2>/dev/null | awk '
                    ${'$'}2 ~ /^(wlan[0-9]+|ap[0-9]+|p2p[0-9]+)${'$'}/ && ${'$'}4 ~ /^[0-9.]+\/[0-9]+${'$'}/ { print "interface=" ${'$'}2 " address=" ${'$'}4 }'
                # Emit only fixed labels and numeric modes, never the matching log line.
                timeout 5 logcat -d -t 500 -v brief -s zj 2>/dev/null | awk '
                    /wirelessCmd stopHotspot/ { stops++ }
                    /wirelessCmd startHotspot/ { starts++ }
                    /isAllowConn  = false/ { allowed="false" }
                    /isAllowConn  = true/ { allowed="true" }
                    /update init info/ && ${'$'}(NF-1) ~ /^[0-9]+${'$'}/ { mode=${'$'}(NF-1) }
                    END { printf "recent_zlink_hotspot_start=%d recent_zlink_hotspot_stop=%d\n", starts, stops
                        if (allowed!="") print "allow_connection=" allowed
                        if (mode!="") print "native_mode=" mode }'
                # Counts refer to the latest rotating native log, not to the whole session.
                latest="${'$'}(ls -t /sdcard/zlinklog/zlink_log-*.txt 2>/dev/null | head -n 1)"
                if [ -n "${'$'}latest" ]; then
                    tail -n 500 "${'$'}latest" 2>/dev/null | awk '
                        /Authentication_Process: AuthenticationSucceeded/ { auth++ }
                        /BT iap_watch_dog timeout/ { watchdog++ }
                        /HU ap is disconnect/ { ap++ }
                        /ip_port is NULL/ { endpoint++ }
                        END { printf "latest_native_auth_success=%d latest_native_iap_timeout=%d latest_native_ap_disconnect=%d latest_native_missing_endpoint=%d\n", auth, watchdog, ap, endpoint }'
                fi
            } >> "${'$'}report"
            sample=${'$'}((sample+1))
            sleep 5
        done
        printf '\nCapture complete\n' >> "${'$'}report"
    """.trimIndent() + "\n"
}
