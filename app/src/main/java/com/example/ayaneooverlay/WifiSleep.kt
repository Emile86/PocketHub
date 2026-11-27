package com.example.ayaneooverlay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * "Turn off Wi-Fi when the screen is off": N minutes after the screen goes off PocketHub
 * switches Wi-Fi off (root), and switches it back on when the screen comes on — but only
 * if PocketHub was the one that turned it off.
 * Screen on/off is watched from the always-running accessibility service.
 */
object WifiSleep {
    private const val TAG = "PocketHubWifiSleep"
    private const val PREFS = "MainActivity"
    /** Minutes after screen off; -1 = never (feature off), 0 = immediately. */
    const val KEY_MINUTES = "wifi_sleep_minutes"
    private const val KEY_OFF_BY_US = "wifi_sleep_off_by_pockethub"
    private const val ACTION_TIMEOUT = "com.example.ayaneooverlay.action.WIFI_SLEEP_TIMEOUT"
    val CHOICES = listOf(-1, 0, 1, 2, 5, 10, 20, 30)

    private var receiver: BroadcastReceiver? = null

    fun minutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MINUTES, -1)

    fun setMinutes(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MINUTES, value).apply()
        if (value < 0) cancel(context)
    }

    /** Called by the accessibility service when it starts. */
    fun register(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(app)
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn(app)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try { app.registerReceiver(receiver, filter) } catch (error: Exception) { Log.w(TAG, "Could not watch the screen", error) }
    }

    fun unregister(context: Context) {
        val r = receiver ?: return
        receiver = null
        try { context.applicationContext.unregisterReceiver(r) } catch (_: Exception) { }
    }

    private fun onScreenOff(context: Context) {
        val minutes = minutes(context)
        if (minutes < 0) return
        if (minutes == 0) { turnOff(context); return }
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + minutes * 60_000L, timeoutIntent(context))
    }

    private fun onScreenOn(context: Context) {
        cancel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OFF_BY_US, false)) return
        prefs.edit().putBoolean(KEY_OFF_BY_US, false).apply()
        runRoot("svc wifi enable")
    }

    private fun cancel(context: Context) {
        try { context.getSystemService(AlarmManager::class.java).cancel(timeoutIntent(context)) } catch (_: Exception) { }
    }

    /** The timeout alarm fired: switch Wi-Fi off if the screen is still off and Wi-Fi is on. */
    internal fun turnOff(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        if (!wifi.isWifiEnabled) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFF_BY_US, true).commit()
        runRoot("svc wifi disable")
    }

    private fun timeoutIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 7301,
            Intent(context, WifiSleepReceiver::class.java).setAction(ACTION_TIMEOUT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun runRoot(command: String) {
        Thread {
            try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start().waitFor() }
            catch (error: Exception) { Log.w(TAG, "Root command failed: $command", error) }
        }.apply { isDaemon = true; start() }
    }
}

/** Receives the "screen has been off for N minutes" alarm. */
class WifiSleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WifiSleep.turnOff(context)
    }
}
