package com.example.ayaneooverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PocketHubBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val takeoverEnabled = PocketHubButtonTakeover.initialize(context)
        // Global equalizer: start straight away (foreground service start is allowed from boot).
        if (PocketHubEqualizer.isEnabled(context)) {
            try { EqualizerService.start(context) } catch (_: Exception) { }
        }
        val prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_pockethub", false)
        val swapSizeGb = prefs.getInt(PocketHubSwap.PREFERENCE_KEY, 0)
        val fanGuard = FanGuard.isEnabled(context)
        if (!takeoverEnabled && !autoStart && swapSizeGb <= 0 && !fanGuard) return

        val pending = goAsync()
        Thread {
            try {
                if (takeoverEnabled) {
                    val result = PocketHubButtonTakeover.apply(context, true)
                    if (!result.ok) android.util.Log.e("PocketHubBootReceiver", "Button takeover recheck failed: ${result.output}")
                }
                // PocketDS's AYANEO root daemon (xsu) has CAP_SYS_ADMIN; ordinary `su`
                // returns uid 0 without the capability needed by swapon on this firmware.
                if (swapSizeGb > 0) PocketHubSwap.run(context, "boot", swapSizeGb)
                // Overheat protection must work even if PocketHub itself is never opened.
                if (fanGuard) FanGuard.ensureRunning(context)
                if (autoStart) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; start() }
    }
}
