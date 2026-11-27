package com.example.ayaneooverlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Remaps the Pocket DS extra buttons. Keys are caught by AyaButtonAccessibilityService
 * (accessibility key filtering), so no root is needed. Each button can have its own action
 * for a short press, a long press and a double press.
 */
object ButtonRemap {
    private const val PREFS = "pockethub_button_remap"
    private const val KEY_MAP = "map"
    private const val LONG_PRESS_MS = 500L
    private const val DOUBLE_PRESS_MS = 300L

    const val PRESS_SHORT = "short"
    const val PRESS_LONG = "long"
    const val PRESS_DOUBLE = "double"
    val PRESS_TYPES = listOf(PRESS_SHORT, PRESS_LONG, PRESS_DOUBLE)

    /** Codes PocketHub already uses for something else and never remaps. */
    private val RESERVED = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER)

    /** Set by AyaButtonAccessibilityService while it runs. */
    @Volatile var serviceRunning = false

    /** While set, the next button press is reported here instead of being handled. */
    @Volatile var learnListener: ((Int) -> Unit)? = null

    /** Stored action for "launch this app": APP_PREFIX + package name. */
    const val APP_PREFIX = "app:"

    val ACTIONS = listOf("back", "home", "recents", "last_app", "notifications", "quick_settings", "screenshot", "lock",
        "pockethub", "profile_next", "profiles", "autotune", "screens", "launch_app", "none")

    /**
     * Game controls are never remapped or learned: face buttons, shoulders, triggers,
     * stick clicks, Start/Select and the D-pad. The extra buttons (BUTTON_7, and the
     * Mode / Guide button BUTTON_MODE) are not in this list, so they can be remapped.
     */
    private val GAME_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_C,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Z,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER
    )

    class Binding(val short: String?, val long: String?, val double: String?) {
        fun get(type: String) = when (type) { PRESS_LONG -> long; PRESS_DOUBLE -> double; else -> short }
        fun isEmpty() = short == null && long == null && double == null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downCode = -1
    private var longFired = false
    private var longRunnable: Runnable? = null
    private var pendingSingle: Runnable? = null
    private var pendingSingleCode = -1

    /**
     * Factory layout for a new install (nothing saved yet). Can be changed or removed any time.
     *  - Mode (BUTTON_MODE): screenshot of both screens
     *  - BUTTON_3: Dual screen controls
     *  - BUTTON_4: Back
     *  - BUTTON_5: screenshot of both screens
     *  - BUTTON_7: Back, long press Home
     *  - BUTTON_8: Quick profile switch
     */
    private val DEFAULTS: Map<Int, Binding> = mapOf(
        KeyEvent.KEYCODE_BUTTON_MODE to Binding("screenshot", null, null),
        KeyEvent.KEYCODE_BUTTON_3 to Binding("screens", null, null),
        KeyEvent.KEYCODE_BUTTON_4 to Binding("back", null, null),
        KeyEvent.KEYCODE_BUTTON_5 to Binding("screenshot", null, null),
        KeyEvent.KEYCODE_BUTTON_7 to Binding("back", "home", null),
        KeyEvent.KEYCODE_BUTTON_8 to Binding("profiles", null, null)
    )

    fun load(context: Context): Map<Int, Binding> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAP, null)
            ?: return DEFAULTS.also { save(context, it) }
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val code = key.toIntOrNull() ?: return@mapNotNull null
                val value = json.get(key)
                // Older versions stored a single action string = short press.
                val binding = if (value is JSONObject) Binding(
                    value.optString(PRESS_SHORT).ifEmpty { null },
                    value.optString(PRESS_LONG).ifEmpty { null },
                    value.optString(PRESS_DOUBLE).ifEmpty { null }
                ) else Binding(value.toString().ifEmpty { null }, null, null)
                code to binding
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** action == null clears that press type; a button with nothing left is removed. */
    fun set(context: Context, keyCode: Int, type: String, action: String?) {
        val map = load(context).toMutableMap()
        val old = map[keyCode] ?: Binding(null, null, null)
        val updated = Binding(
            if (type == PRESS_SHORT) action else old.short,
            if (type == PRESS_LONG) action else old.long,
            if (type == PRESS_DOUBLE) action else old.double
        )
        if (updated.isEmpty()) map.remove(keyCode) else map[keyCode] = updated
        save(context, map)
    }

    fun remove(context: Context, keyCode: Int) {
        save(context, load(context).toMutableMap().apply { remove(keyCode) })
    }

    private fun save(context: Context, map: Map<Int, Binding>) {
        val json = JSONObject()
        map.forEach { (code, binding) ->
            json.put(code.toString(), JSONObject().apply {
                binding.short?.let { put(PRESS_SHORT, it) }
                binding.long?.let { put(PRESS_LONG, it) }
                binding.double?.let { put(PRESS_DOUBLE, it) }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAP, json.toString()).apply()
    }

    fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    /**
     * Called from the accessibility service for every key except the AYANEO button.
     * Returns true when the event was consumed (learning, or a remapped button).
     */
    fun handle(service: AccessibilityService, event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in RESERVED || code in GAME_KEYS) return false
        learnListener?.let { listener ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                learnListener = null
                handler.post { listener(code) }
            }
            return true
        }
        val binding = load(service)[code] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                downCode = code
                longFired = false
                longRunnable?.let { handler.removeCallbacks(it) }
                val longAction = binding.long
                if (longAction != null) {
                    val runnable = Runnable {
                        if (downCode == code) {
                            longFired = true
                            cancelPendingSingle()
                            perform(service, longAction)
                        }
                    }
                    longRunnable = runnable
                    handler.postDelayed(runnable, LONG_PRESS_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                longRunnable?.let { handler.removeCallbacks(it) }
                longRunnable = null
                downCode = -1
                if (longFired) { longFired = false; return true }
                val doubleAction = binding.double
                if (doubleAction == null) {
                    binding.short?.let { perform(service, it) }
                } else if (pendingSingle != null && pendingSingleCode == code) {
                    // Second press in time: double press.
                    cancelPendingSingle()
                    perform(service, doubleAction)
                } else {
                    // Wait briefly to see whether a second press follows.
                    cancelPendingSingle()
                    val single = Runnable {
                        pendingSingle = null
                        pendingSingleCode = -1
                        binding.short?.let { perform(service, it) }
                    }
                    pendingSingle = single
                    pendingSingleCode = code
                    handler.postDelayed(single, DOUBLE_PRESS_MS)
                }
            }
        }
        return true
    }

    private fun launchApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            android.util.Log.w("PocketHubButtonRemap", "Could not launch $packageName", error)
        }
    }

    private fun cancelPendingSingle() {
        pendingSingle?.let { handler.removeCallbacks(it) }
        pendingSingle = null
        pendingSingleCode = -1
    }

    private fun perform(service: AccessibilityService, action: String) {
        if (action.startsWith(APP_PREFIX)) { launchApp(service, action.removePrefix(APP_PREFIX)); return }
        when (action) {
            "pockethub" -> { (service as? AyaButtonAccessibilityService)?.togglePocketHub(); return }
            "profile_next", "profiles", "autotune", "screens" -> { MainActivity.remoteCommand(service, action); return }
            // PocketHub's own capture of both screens (with the save picker), not Android's.
            "screenshot" -> { MainActivity.remoteCommand(service, "capture"); return }
            "last_app" -> {
                // Android switches to the previous app on a quick double tap of Recents.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                handler.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 280L)
                return
            }
        }
        val global = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return
            else -> return // "none": the button is simply blocked
        }
        service.performGlobalAction(global)
    }
}
