package com.example.ayaneooverlay

import android.content.Context
import android.util.Log
import java.io.File

/**
 * "Turbo" boost for a performance profile. It cannot raise clocks above the hardware
 * maximum; instead it stops the system from holding them back:
 *
 *  - CPU: `performance` governor on every cluster and no parked cores (core_ctl).
 *  - Foreground app: higher uclamp.min for the top-app cgroup, so it gets fast cores.
 *  - GPU: never drop below the top power level, and keep clock, bus and rail awake
 *    (no idle naps between frames).
 *
 * GPU thermal throttling and the firmware's thermal limits are left untouched. As an
 * extra safety net Turbo backs off by itself when the CPU stays above 92 °C for 20 s and
 * comes back after it has been below 80 °C for 30 s.
 *
 * Everything runs in one root shell loop (AYANEO services rewrite these values, so they
 * are re-applied every second). The original values are saved first and restored when
 * Turbo stops; a reboot also restores them.
 */
object TurboBoost {
    private const val TAG = "PocketHubTurbo"
    const val HOT_C = 92
    const val COOL_C = 80

    private fun file(context: Context, name: String) = File(context.filesDir, name)

    fun prefKey(profile: String) = "tune_turbo_${profile.lowercase(java.util.Locale.ROOT)}"

    /** Start the loop (or keep it running). Blocking; call off the main thread. */
    fun start(context: Context) {
        try {
            val enabled = file(context, "turbo_enabled")
            enabled.writeText("1"); enabled.setReadable(true, false)
            val script = file(context, "turbo.sh")
            val text = script(context)
            val changed = !script.exists() || script.readText() != text
            script.writeText(text); script.setReadable(true, false)
            val pid = file(context, "turbo.pid").absolutePath
            val stopOld = if (changed) "[ -f '$pid' ] && kill \"\$(cat '$pid')\" 2>/dev/null; rm -f '$pid'; sleep 0.3; " else ""
            run(stopOld + "if [ -f '$pid' ] && kill -0 \"\$(cat '$pid')\" 2>/dev/null; then exit 0; fi; " +
                "nohup sh '${script.absolutePath}' >/dev/null 2>&1 &")
        } catch (error: Exception) {
            Log.w(TAG, "Could not start Turbo", error)
        }
    }

    /** Ask the loop to restore the original values and exit. */
    fun stop(context: Context) {
        try {
            val enabled = file(context, "turbo_enabled")
            if (enabled.exists()) { enabled.writeText("0"); enabled.setReadable(true, false) }
        } catch (error: Exception) {
            Log.w(TAG, "Could not stop Turbo", error)
        }
    }

    fun startAsync(context: Context) {
        val app = context.applicationContext
        Thread({ start(app) }, "PocketHubTurbo-start").apply { isDaemon = true; start() }
    }

    /** "<state> <celsius>": state 1 = boosting, 2 = cooling down, 0 = off. Needs root to read. */
    fun stateFilePath(context: Context): String = file(context, "turbo_state").absolutePath

    private fun run(command: String) {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        process.waitFor()
    }

    private fun script(context: Context): String {
        val enabled = file(context, "turbo_enabled").absolutePath
        val saved = file(context, "turbo_saved").absolutePath
        val state = file(context, "turbo_state").absolutePath
        val pid = file(context, "turbo.pid").absolutePath
        return """
            #!/system/bin/sh
            # PocketHub Turbo (see TurboBoost.kt).
            echo $$ > '$pid'
            gpu=/sys/class/kgsl/kgsl-3d0
            cpus=/sys/devices/system/cpu

            # Remember every value before touching it, once per Turbo session.
            save() {
              : > '$saved'
              for p in ${'$'}cpus/cpufreq/policy*; do echo "${'$'}p/scaling_governor ${'$'}(cat ${'$'}p/scaling_governor 2>/dev/null)" >> '$saved'; done
              for c in ${'$'}cpus/cpu*/core_ctl; do [ -d "${'$'}c" ] && echo "${'$'}c/min_cpus ${'$'}(cat ${'$'}c/min_cpus 2>/dev/null)" >> '$saved'; done
              for n in min_pwrlevel idle_timer force_clk_on force_bus_on force_rail_on force_no_nap; do
                [ -f ${'$'}gpu/${'$'}n ] && echo "${'$'}gpu/${'$'}n ${'$'}(cat ${'$'}gpu/${'$'}n 2>/dev/null)" >> '$saved'
              done
              [ -f /dev/cpuctl/top-app/cpu.uclamp.min ] && echo "/dev/cpuctl/top-app/cpu.uclamp.min ${'$'}(cat /dev/cpuctl/top-app/cpu.uclamp.min)" >> '$saved'
            }

            boost() {
              for p in ${'$'}cpus/cpufreq/policy*; do echo performance > ${'$'}p/scaling_governor 2>/dev/null; done
              for c in ${'$'}cpus/cpu*/core_ctl; do [ -d "${'$'}c" ] && cat ${'$'}c/max_cpus > ${'$'}c/min_cpus 2>/dev/null; done
              echo 0 > ${'$'}gpu/min_pwrlevel 2>/dev/null
              echo 10000 > ${'$'}gpu/idle_timer 2>/dev/null
              for n in force_clk_on force_bus_on force_rail_on force_no_nap; do echo 1 > ${'$'}gpu/${'$'}n 2>/dev/null; done
              echo 50 > /dev/cpuctl/top-app/cpu.uclamp.min 2>/dev/null
            }

            restore() {
              [ -f '$saved' ] || return
              while read -r node value; do
                [ -n "${'$'}node" ] && [ -n "${'$'}value" ] && echo "${'$'}value" > "${'$'}node" 2>/dev/null
              done < '$saved'
            }

            cpu_temp() {
              max=0
              for z in /sys/class/thermal/thermal_zone*; do
                IFS= read -r t < "${'$'}z/type" 2>/dev/null || continue
                case "${'$'}t" in
                  *cpu*|*cluster*|*silver*|*gold*|*prime*)
                    IFS= read -r v < "${'$'}z/temp" 2>/dev/null || continue
                    [ "${'$'}v" -gt 1000 ] 2>/dev/null && v=${'$'}((v / 1000))
                    [ "${'$'}v" -gt "${'$'}max" ] 2>/dev/null && max=${'$'}v
                    ;;
                esac
              done
              echo ${'$'}max
            }
            now() { cut -d. -f1 /proc/uptime; }

            save
            # Killed (e.g. replaced after an app update): still put everything back.
            trap 'restore; echo "0 0" > '"'"'$state'"'"'; rm -f '"'"'$pid'"'"'; exit 0' TERM INT
            mode=1; hot_since=0; cool_since=0
            while [ "${'$'}(cat '$enabled' 2>/dev/null)" = 1 ]; do
              t=${'$'}(cpu_temp)
              if [ "${'$'}t" -ge $HOT_C ]; then [ ${'$'}hot_since -gt 0 ] || hot_since=${'$'}(now); else hot_since=0; fi
              if [ "${'$'}t" -lt $COOL_C ]; then [ ${'$'}cool_since -gt 0 ] || cool_since=${'$'}(now); else cool_since=0; fi
              if [ ${'$'}mode = 1 ] && [ ${'$'}hot_since -gt 0 ] && [ ${'$'}(( ${'$'}(now) - hot_since )) -ge 20 ]; then
                mode=2; restore
              elif [ ${'$'}mode = 2 ] && [ ${'$'}cool_since -gt 0 ] && [ ${'$'}(( ${'$'}(now) - cool_since )) -ge 30 ]; then
                mode=1
              fi
              # Re-apply every second: AYANEO's performance service rewrites these values.
              [ ${'$'}mode = 1 ] && boost
              echo "${'$'}mode ${'$'}t" > '$state'; chmod 644 '$state' 2>/dev/null
              sleep 1
            done
            restore
            echo "0 0" > '$state'
            rm -f '$pid'
        """.trimIndent() + "\n"
    }
}
