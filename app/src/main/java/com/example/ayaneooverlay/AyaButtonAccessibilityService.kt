package com.example.ayaneooverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

const val ACTION_HIDE_FROM_AYA_BUTTON = "com.example.ayaneooverlay.action.HIDE_FROM_AYA_BUTTON"

/** Handles the AYANEO shortcut button and yields it to AYANEO when takeover is disabled. */
class AyaButtonAccessibilityService : AccessibilityService() {
    // Track the state we requested through the AYANEO button. OEM windows can
    // arrive after our launch/hide request and must not decide the toggle state.
    private var pocketHubOpenFromButton = false
    private var lastRoutedAt = 0L
    private var ayaButtonDown = false
    private val keyHandler = Handler(Looper.getMainLooper())
    private var bootRecheckCount = 0
    private val accessibilitySettingsObserver = object : ContentObserver(keyHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            removeNativeAccessibilityServiceIfTakingOver()
        }
    }
    private val bootAccessibilityRecheck = object : Runnable {
        override fun run() {
            if (!isButtonTakeoverEnabled()) return
            removeNativeAccessibilityServiceIfTakingOver()
            bootRecheckCount++
            if (bootRecheckCount < BOOT_RECHECK_DELAYS_MS.size) {
                keyHandler.postDelayed(this, BOOT_RECHECK_DELAYS_MS[bootRecheckCount])
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        ButtonRemap.serviceRunning = true
        WifiSleep.register(this)
        pocketHubOpenFromButton = activePackage() == packageName || isPocketHubWindowVisible()
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilitySettingsObserver
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not watch accessibility service changes", error)
        }
        bootRecheckCount = 0
        keyHandler.removeCallbacks(bootAccessibilityRecheck)
        keyHandler.postDelayed(bootAccessibilityRecheck, BOOT_RECHECK_DELAYS_MS[0])
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Optional: hardware volume keys open the PocketHub volume panel instead of Android's.
            if (!VolumePanel.isEnabled(this)) return false
            // Only the first down event counts; holding the key is handled by the panel's own
            // fast repeat until the key goes up.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                keyHandler.post { VolumePanel.onKeyDown(this, up) }
            } else if (event.action == KeyEvent.ACTION_UP) {
                keyHandler.post { VolumePanel.onKeyUp() }
            }
            return true
        }
        if (event.keyCode != AYA_BUTTON_KEY_CODE) {
            // Extra buttons remapped in PocketHub settings (or being learned right now).
            return ButtonRemap.handle(this, event)
        }
        if (!isButtonTakeoverEnabled()) return false
        Log.i(TAG, "AYA button key action=${event.action} repeat=${event.repeatCount}")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> ayaButtonDown = true
            KeyEvent.ACTION_UP -> {
                // Every press (short or long) shows or hides PocketHub.
                if (ayaButtonDown) {
                    ayaButtonDown = false
                    routeShortPress()
                }
            }
        }
        // Consume the proprietary key before AYANEO's shortcut listener sees it.
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        val eventClass = event.className?.toString().orEmpty()
        if (eventPackage == packageName) {
            pocketHubOpenFromButton = true
            return
        }

        if (eventPackage == NATIVE_PACKAGE) {
            Log.i(TAG, "AYA window class=$eventClass pocketHubOpen=$pocketHubOpenFromButton")
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)) return
            when {
                eventClass == AYASPACE_WINDOW_CLASS -> {
                    pocketHubOpenFromButton = false
                }
                eventClass.endsWith(".ui.main.LauncherActivity") -> pocketHubOpenFromButton = false
            }
        } else if (!isSystemWindowPackage(eventPackage)) {
            // The user switched to another app outside the AYANEO shortcut flow.
            pocketHubOpenFromButton = false
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ButtonRemap.serviceRunning = false
        WifiSleep.unregister(this)
        ButtonRemap.learnListener = null
        keyHandler.removeCallbacks(bootAccessibilityRecheck)
        try {
            contentResolver.unregisterContentObserver(accessibilitySettingsObserver)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun isButtonTakeoverEnabled(): Boolean = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)

    /** AYANEO may re-add its key service after boot; keep it out while PocketHub owns the button. */
    private fun removeNativeAccessibilityServiceIfTakingOver() {
        if (isButtonTakeoverEnabled()) PocketHubButtonTakeover.requestEnsure(this)
    }

    private fun routeShortPress() {
        if (!allowRouteNow()) return
        // Window-state notifications may lag on the secondary display. Reconcile
        // our toggle state with visible windows before deciding to launch or hide.
        pocketHubOpenFromButton = isPocketHubWindowVisible() || activePackage() == packageName
        // If AYANEO is in a single-screen mode, the bottom display (where
        // PocketHub normally lives) may be disabled. The hardware key is still
        // handled by this accessibility service, so use it as the recovery
        // path: restore the OEM dual-display mode before deciding whether to
        // show or hide PocketHub. In the normal dual-display mode this is a
        // read-only check and the usual toggle behavior is preserved.
        Thread {
            val restored = restoreDualDisplayModeIfNeeded()
            keyHandler.post {
                if (restored) {
                    pocketHubOpenFromButton = true
                    launchPocketHub()
                } else if (pocketHubOpenFromButton) {
                    pocketHubOpenFromButton = false
                    hidePocketHub()
                } else {
                    pocketHubOpenFromButton = true
                    launchPocketHub()
                }
            }
        }.apply { isDaemon = true; name = "PocketHub-display-recovery"; start() }
    }

    /** Returns true only when a single-screen mode was changed back to dual display. */
    private fun restoreDualDisplayModeIfNeeded(): Boolean {
        return try {
            val read = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "settings get global dual_display_mode")
            )
            val current = read.inputStream.bufferedReader().readText().trim()
            val readExit = read.waitFor()
            if (readExit != 0 || current.isEmpty() || current == DUAL_DISPLAY_MODE) {
                Log.i(TAG, "Dual-display recovery not needed (mode=$current, exit=$readExit)")
                false
            } else {
                val write = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "settings put global dual_display_mode $DUAL_DISPLAY_MODE; settings get global dual_display_mode")
                )
                val result = write.inputStream.bufferedReader().readText().trim()
                val writeExit = write.waitFor()
                val confirmed = writeExit == 0 && result == DUAL_DISPLAY_MODE
                Log.i(TAG, "Dual-display recovery mode=$current result=$result exit=$writeExit confirmed=$confirmed")
                // Even if the setting could not be confirmed, open PocketHub
                // on the remaining active display so recovery is still possible.
                true
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not restore AYANEO dual-display mode", error)
            false
        }
    }

    private fun hidePocketHub() {
        try {
            // PocketHub was launched on top of the previous app. Move only our
            // task behind it so Android resumes that app instead of Home.
            val intent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_HIDE_FROM_AYA_BUTTON
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            Log.i(TAG, "AYANEO short press returned to the previous app")
        } catch (error: Exception) {
            Log.e(TAG, "Could not hide PocketHub", error)
        }
    }

    private fun openPocketHub() {
        launchPocketHub()
    }

    /** Remapped-button action: same as pressing the AYANEO button. */
    fun togglePocketHub() = routeShortPress()

    private fun launchPocketHub() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            pocketHubOpenFromButton = true
            Log.i(TAG, "Routed AYA action to PocketHub")
        } catch (error: Exception) {
            Log.e(TAG, "Could not open PocketHub from AYA window", error)
        }
    }

    private fun allowRouteNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastRoutedAt < ROUTE_DEBOUNCE_MS) return false
        lastRoutedAt = now
        return true
    }

    private fun activePackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun isPocketHubWindowVisible(): Boolean = try {
        windows.any { it.root?.packageName?.toString() == packageName }
    } catch (_: Exception) {
        false
    }

    private fun isSystemWindowPackage(packageName: String): Boolean =
        packageName == "com.android.systemui" ||
            packageName == "com.android.launcher3" ||
            packageName == "com.android.inputmethod.latin" ||
            packageName == "com.google.android.inputmethod.latin" ||
            packageName == "com.google.android.gms"

    companion object {
        const val PREFS = "pockethub_button_prefs"
        const val KEY_ENABLED = "aya_button_enabled"
        private const val NATIVE_PACKAGE = "com.ayaneo.home"
        private const val AYASPACE_WINDOW_CLASS = "com.ayaneo.space.ui.global.SpaceActivity"
        private const val AYA_BUTTON_KEY_CODE = 193
        // Hardware emits a down/up pair per tap. Keep a short guard against duplicate
        // OEM callbacks while still allowing normal consecutive toggle presses.
        private const val ROUTE_DEBOUNCE_MS = 240L
        private const val DUAL_DISPLAY_MODE = "1"
        private val BOOT_RECHECK_DELAYS_MS = longArrayOf(1_200L, 2_500L, 5_000L, 10_000L, 20_000L)
        private const val TAG = "PocketHubAyaButton"
    }
}
