package com.example.ayaneooverlay

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

data class ButtonTakeoverResult(val ok: Boolean, val output: String)

/** Keeps the PocketHub accessibility service as the sole owner of the AYANEO shortcut. */
object PocketHubButtonTakeover {
    private const val TAG = "PocketHubButtonTakeover"
    private const val ROOT_CLIENT = "/product/bin/xsu"
    private const val PREF_NATIVE_SNAPSHOT = "aya_native_accessibility_snapshot"
    private const val PREF_SNAPSHOT_CAPTURED = "aya_native_accessibility_snapshot_captured"
    private const val PREF_SNAPSHOT_VERSION = "aya_native_accessibility_snapshot_version"
    private const val CURRENT_SNAPSHOT_VERSION = 3
    private val ensureRunning = AtomicBoolean(false)

    private val nativePackages = setOf("com.ayaneo.home", "com.ayaneo.gamewindow")
    private const val NATIVE_SERVICE_CLASS = "com.ayaneo.gamewindow.service.WindowKeyEventService"

    /**
     * Always true now (the AYANEO button is core functionality). Older installs that had
     * it switched off are switched on.
     */
    fun initialize(context: Context): Boolean {
        // The AYANEO button always belongs to PocketHub: its accessibility service is core
        // functionality (button, remapping, Wi-Fi sleep), so there is no longer a switch for it.
        val prefs = context.getSharedPreferences(AyaButtonAccessibilityService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AyaButtonAccessibilityService.KEY_ENABLED, false)) {
            prefs.edit().putBoolean(AyaButtonAccessibilityService.KEY_ENABLED, true).apply()
        }
        return true
    }

    /** No first-launch question any more: the takeover is always on. */
    fun needsFirstLaunchChoice(context: Context): Boolean = false

    fun requestEnsure(context: Context) {
        if (!initialize(context) || !ensureRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val result = apply(context, true)
                if (!result.ok) Log.e(TAG, "Could not restore PocketHub accessibility takeover: ${result.output}")
            } catch (error: Exception) {
                Log.e(TAG, "Could not restore PocketHub accessibility takeover", error)
            } finally {
                ensureRunning.set(false)
            }
        }.apply { isDaemon = true; start() }
    }

    /** Apply the requested list through AYANEO's privileged root daemon and verify it. */
    fun apply(context: Context, takeover: Boolean): ButtonTakeoverResult {
        val prefs = context.getSharedPreferences(AyaButtonAccessibilityService.PREFS, Context.MODE_PRIVATE)
        val currentResult = rootCommand("settings get secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}")
        if (!currentResult.first) return ButtonTakeoverResult(false, currentResult.second)
        val current = splitServices(currentResult.second)
        val pocketHubComponent = ComponentName(context, AyaButtonAccessibilityService::class.java).flattenToString()

        val currentNative = current.filter(::isNativeAyaButtonService)
        val originalNative = if (
            !prefs.getBoolean(PREF_SNAPSHOT_CAPTURED, false) ||
            prefs.getInt(PREF_SNAPSHOT_VERSION, 0) < CURRENT_SNAPSHOT_VERSION
        ) {
            currentNative.also { snapshot ->
                prefs.edit()
                    .putString(PREF_NATIVE_SNAPSHOT, snapshot.joinToString(":"))
                    .putBoolean(PREF_SNAPSHOT_CAPTURED, true)
                    .putInt(PREF_SNAPSHOT_VERSION, CURRENT_SNAPSHOT_VERSION)
                    .commit()
            }
        } else {
            splitServices(prefs.getString(PREF_NATIVE_SNAPSHOT, "").orEmpty())
        }

        val otherServices = current.filterNot(::isNativeAyaButtonService).filterNot { isPocketHubService(it, context) }
        val desired = if (takeover) {
            otherServices + pocketHubComponent
        } else {
            (otherServices + originalNative).distinctBy(::canonicalComponent)
        }
        val desiredValue = desired.joinToString(":")
        val currentAccessibilityEnabled = rootCommand("settings get secure ${Settings.Secure.ACCESSIBILITY_ENABLED}")
        val shouldEnableAccessibility = takeover || desired.isNotEmpty() || currentAccessibilityEnabled.second.trim() == "1"
        if (desiredValue != current.joinToString(":") || (shouldEnableAccessibility && currentAccessibilityEnabled.second.trim() != "1")) {
            val command = "settings put secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES} ${shellQuote(desiredValue)}; " +
                "settings put secure ${Settings.Secure.ACCESSIBILITY_ENABLED} ${if (shouldEnableAccessibility) 1 else 0}"
            val write = rootCommand(command)
            if (!write.first) return ButtonTakeoverResult(false, write.second)
        }

        val observedResult = rootCommand("settings get secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}")
        val observed = splitServices(observedResult.second).map(::canonicalComponent).toSet()
        val expected = desired.map(::canonicalComponent).toSet()
        val enabledResult = rootCommand("settings get secure ${Settings.Secure.ACCESSIBILITY_ENABLED}")
        val enabled = enabledResult.second.trim() == "1"
        val servicePresent = observed.contains(canonicalComponent(pocketHubComponent))
        val nativeServicesAbsent = observed.none(::isNativeCanonical)
        val applied = observedResult.first && observed == expected && enabled &&
            (if (takeover) servicePresent && nativeServicesAbsent else !servicePresent)
        return ButtonTakeoverResult(applied, if (applied) "OK" else "Expected $expected; observed $observed")
    }

    fun isPocketHubEnabled(context: Context): Boolean {
        val result = rootCommand("settings get secure ${Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}")
        if (!result.first) return false
        val target = canonicalComponent(ComponentName(context, AyaButtonAccessibilityService::class.java).flattenToString())
        return splitServices(result.second).any { canonicalComponent(it) == target }
    }

    private fun rootCommand(command: String): Pair<Boolean, String> = try {
        val process = ProcessBuilder(ROOT_CLIENT, command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exit = process.waitFor()
        (exit == 0) to output
    } catch (error: Exception) {
        false to (error.message ?: "Root command failed")
    }

    private fun splitServices(value: String): List<String> = value
        .split(':')
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun canonicalComponent(value: String): String {
        val parts = value.trim().split('/', limit = 2)
        if (parts.size != 2) return value.trim().lowercase()
        val packageName = parts[0]
        val classPart = parts[1]
        val fullClass = when {
            classPart.startsWith('.') -> packageName + classPart
            '.' !in classPart -> "$packageName.$classPart"
            else -> classPart
        }
        return "$packageName/$fullClass".lowercase()
    }

    private fun isNativeAyaButtonService(value: String): Boolean = isNativeCanonical(canonicalComponent(value))

    private fun isNativeCanonical(value: String): Boolean {
        val canonical = canonicalComponent(value)
        val packageName = canonical.substringBefore('/')
        val className = canonical.substringAfter('/', "")
        return packageName in nativePackages && className == NATIVE_SERVICE_CLASS.lowercase()
    }

    private fun isPocketHubService(value: String, context: Context): Boolean {
        val target = canonicalComponent(ComponentName(context, AyaButtonAccessibilityService::class.java).flattenToString())
        return canonicalComponent(value) == target
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
