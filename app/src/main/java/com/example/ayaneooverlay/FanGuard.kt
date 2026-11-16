package com.example.ayaneooverlay

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Overheat protection for when the fan is switched off (by a profile or manually).
 *
 * A small root shell loop runs independently of the PocketHub process (it survives the
 * app being closed and is restarted at boot). While the selected fan mode is OFF it
 * watches the hottest CPU thermal zone and drives the fan PWM directly. Every step needs
 * the temperature to stay past its threshold for a while, so short spikes are ignored:
 *
 *   off  → above 70 °C for 1 min → low          low  → below 65 °C for 1 min → off
 *   low  → above 82 °C for 1 min → 50 %         50 % → below 80 °C for 1 min → low
 *   any  → above 90 °C for 2 min → 100 %        100 % → below 90 °C for 1 min → 50 %
 *
 * On this fan the PWM is inverted: 255 = stopped, 0 = full speed. Low speed uses 179,
 * the value AYANEO's own Low mode wrote (≈3 100–3 300 RPM); 50 % duty is 128.
 * When the user picks any other fan mode the loop stops touching the fan, so AYANEO's
 * own regulation is left alone; it also only takes over a fan that is actually stopped.
 */
object FanGuard {
    private const val TAG = "PocketHubFanGuard"
    const val PREF_ENABLED = "fan_guard_enabled"
    private const val PREFS = "MainActivity"

    const val LOW_ON_C = 70
    const val LOW_OFF_C = 65
    const val MID_ON_C = 82
    const val MID_OFF_C = 80
    const val MAX_ON_C = 90
    const val MAX_OFF_C = 90
    const val HOLD_S = 60
    const val MAX_HOLD_S = 120
    const val PWM_OFF = 255
    const val PWM_LOW = 179
    const val PWM_MID = 128
    const val PWM_MAX = 0

    private fun dir(context: Context): File = context.filesDir
    private fun modeFile(context: Context) = File(dir(context), "fan_guard_mode")
    private fun enabledFile(context: Context) = File(dir(context), "fan_guard_enabled")
    private fun scriptFile(context: Context) = File(dir(context), "fan_guard.sh")
    private fun stateFile(context: Context) = File(dir(context), "fan_guard_state")
    private fun pidFile(context: Context) = File(dir(context), "fan_guard.pid")

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

    /** Tell the guard which fan mode the user selected; it only acts while this is FAN_MODE_OFF. */
    fun syncMode(context: Context, mode: String) {
        try {
            modeFile(context).writeText(mode)
            modeFile(context).setReadable(true, false)
        } catch (error: Exception) { Log.w(TAG, "Could not record fan mode", error) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply()
        writeEnabledFlag(context, enabled)
        if (enabled) ensureRunningAsync(context)
    }

    private fun writeEnabledFlag(context: Context, enabled: Boolean) {
        try {
            enabledFile(context).writeText(if (enabled) "1" else "0")
            enabledFile(context).setReadable(true, false)
        } catch (error: Exception) { Log.w(TAG, "Could not record guard state", error) }
    }

    /** Start the root loop if it is enabled and not already running. Blocking; call off the main thread. */
    fun ensureRunning(context: Context) {
        val enabled = isEnabled(context)
        writeEnabledFlag(context, enabled)
        if (!enabled) return
        if (!modeFile(context).exists()) {
            val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("fan_mode", "FAN_MODE_OFF") ?: "FAN_MODE_OFF"
            syncMode(context, mode)
        }
        try {
            val script = scriptFile(context)
            script.writeText(script(context))
            script.setReadable(true, false)
            val pid = pidFile(context).absolutePath
            val command = "if [ -f '$pid' ] && kill -0 \"\$(cat '$pid')\" 2>/dev/null; then exit 0; fi; " +
                "nohup sh '${script.absolutePath}' >/dev/null 2>&1 &"
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        } catch (error: Exception) {
            Log.w(TAG, "Could not start the fan guard", error)
        }
    }

    fun ensureRunningAsync(context: Context) {
        val app = context.applicationContext
        Thread({ ensureRunning(app) }, "PocketHubFanGuard-start").apply { isDaemon = true; start() }
    }

    /** "<level> <celsius>" written by the loop: level 0 = idle, 1 = low, 2 = 50 %, 3 = 100 %. Needs root to read. */
    fun stateFilePath(context: Context): String = stateFile(context).absolutePath

    private fun script(context: Context): String {
        val mode = modeFile(context).absolutePath
        val enabled = enabledFile(context).absolutePath
        val state = stateFile(context).absolutePath
        val pid = pidFile(context).absolutePath
        return """
            #!/system/bin/sh
            # PocketHub overheat guard (see FanGuard.kt).
            echo $$ > '$pid'
            fan=/sys/devices/platform/soc/soc:pwm-fan
            pwm=
            for p in ${'$'}fan/hwmon/hwmon*/pwm1 ${'$'}fan/pwm1 /sys/class/hwmon/hwmon*/pwm1; do
              [ -w "${'$'}p" ] && { pwm=${'$'}p; break; }
            done
            [ -n "${'$'}pwm" ] || { echo "-1 0" > '$state'; exit 1; }

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

            fan_rpm() {
              r=${'$'}(cat ${'$'}fan/fan_rpm_state 2>/dev/null | grep -o '[0-9]*' | head -n 1)
              echo ${'$'}{r:-0}
            }

            set_fan() {
              echo 1 > ${'$'}fan/fan_power_state 2>/dev/null
              echo "${'$'}1" > "${'$'}pwm" 2>/dev/null
            }

            # Seconds since boot; each threshold remembers when the temperature crossed it.
            now() { cut -d. -f1 /proc/uptime; }
            held() { [ "${'$'}1" -gt 0 ] && [ ${'$'}(( ${'$'}(now) - ${'$'}1 )) -ge "${'$'}2" ]; }
            since() { if [ "${'$'}1" = yes ]; then [ "${'$'}2" -gt 0 ] && echo "${'$'}2" || now; else echo 0; fi; }
            cond() { [ "${'$'}@" ] && echo yes || echo no; }

            level=0
            up_low=0; up_mid=0; up_max=0; down_low=0; down_mid=0; down_max=0
            while true; do
              [ "${'$'}(cat '$enabled' 2>/dev/null)" = 1 ] || break
              t=${'$'}(cpu_temp)
              if [ "${'$'}(cat '$mode' 2>/dev/null)" != FAN_MODE_OFF ]; then
                # Another fan mode is active: AYANEO regulates the fan, stay out of the way.
                level=0
                up_low=0; up_mid=0; up_max=0; down_low=0; down_mid=0; down_max=0
              else
                up_low=${'$'}(since ${'$'}(cond "${'$'}t" -gt $LOW_ON_C) ${'$'}up_low)
                up_mid=${'$'}(since ${'$'}(cond "${'$'}t" -gt $MID_ON_C) ${'$'}up_mid)
                up_max=${'$'}(since ${'$'}(cond "${'$'}t" -gt $MAX_ON_C) ${'$'}up_max)
                down_low=${'$'}(since ${'$'}(cond "${'$'}t" -lt $LOW_OFF_C) ${'$'}down_low)
                down_mid=${'$'}(since ${'$'}(cond "${'$'}t" -lt $MID_OFF_C) ${'$'}down_mid)
                down_max=${'$'}(since ${'$'}(cond "${'$'}t" -lt $MAX_OFF_C) ${'$'}down_max)
                previous=${'$'}level
                if [ ${'$'}level -lt 3 ] && held ${'$'}up_max $MAX_HOLD_S; then level=3
                elif [ ${'$'}level -lt 2 ] && held ${'$'}up_mid $HOLD_S; then level=2
                elif [ ${'$'}level -eq 0 ] && held ${'$'}up_low $HOLD_S && [ "${'$'}(fan_rpm)" -eq 0 ]; then
                  # Only take over a fan that is really stopped, so a mode chosen in AYANEO's
                  # own app (which PocketHub does not see) is never overridden.
                  level=1
                elif [ ${'$'}level -eq 3 ] && held ${'$'}down_max $HOLD_S; then level=2
                elif [ ${'$'}level -eq 2 ] && held ${'$'}down_mid $HOLD_S; then level=1
                elif [ ${'$'}level -eq 1 ] && held ${'$'}down_low $HOLD_S; then level=0
                fi
                if [ ${'$'}level -ne ${'$'}previous ]; then
                  # Each step down must hold its own minute before the next one.
                  [ "${'$'}down_low" -gt 0 ] && down_low=${'$'}(now)
                  [ "${'$'}down_mid" -gt 0 ] && down_mid=${'$'}(now)
                  [ "${'$'}down_max" -gt 0 ] && down_max=${'$'}(now)
                fi
                case ${'$'}level in
                  # Re-assert every cycle: AYANEO services may rewrite the PWM.
                  1) set_fan $PWM_LOW ;;
                  2) set_fan $PWM_MID ;;
                  3) set_fan $PWM_MAX ;;
                  0) [ "${'$'}previous" -gt 0 ] && echo $PWM_OFF > "${'$'}pwm" 2>/dev/null ;;
                esac
              fi
              echo "${'$'}level ${'$'}t" > '$state'
              chmod 644 '$state' 2>/dev/null
              sleep 2
            done
            # Guard switched off while it was cooling: hand the fan back to "off".
            [ "${'$'}level" -gt 0 ] && echo $PWM_OFF > "${'$'}pwm" 2>/dev/null
            echo "0 0" > '$state'
            rm -f '$pid'
        """.trimIndent() + "\n"
    }
}
