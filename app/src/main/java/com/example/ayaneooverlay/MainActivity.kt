package com.example.ayaneooverlay

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.media.AudioManager
import android.net.Uri
import android.content.Context
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.IInterface
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.PopupWindow
import android.widget.Switch
import android.view.inputmethod.InputMethodManager
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

open class MainActivity : Activity() {
    /**
     * True for DualScreenPopupActivity: only the Dual screen controls window is shown, over
     * whatever app is open, and the activity closes with it. PocketHub itself is not opened.
     */
    protected open val popupMode: Boolean = false

    /** All PocketHub screens share one preferences file, whatever the activity class is. */
    override fun getPreferences(mode: Int): android.content.SharedPreferences =
        getSharedPreferences("MainActivity", mode)

    // AYANEO-inspired dark glass palette: near-black canvas, soft teal borders,
    // and luminous green accents similar to the stock Pocket DS control center.
    private var bg = Color.rgb(7, 13, 18)
    private var cardBg = Color.rgb(17, 29, 39)
    private var cardBg2 = Color.rgb(24, 41, 53)
    private var mint = Color.rgb(44, 255, 177)
    private var cyan = Color.rgb(75, 220, 245)
    private var white = Color.rgb(241, 248, 250)
    private var muted = Color.rgb(155, 181, 194)
    private var border = Color.rgb(63, 100, 111)
    private var uiLanguage = "en" // English is the default UI language.
    private var accentScheme = "blue" // High-contrast graphite and electric-blue default.
    private var layoutTheme = "glass" // glass, compact, neon, arcade, solstice, synthwave, volcanic, eightbit
    private var selectedProfile = "Balanced"
    private data class FanCurvePoint(var temperature: Int, var speedPercent: Int)
    private data class FanCurveConfig(val path: String, val raw: String)
    private val defaultFanCurve = listOf(
        FanCurvePoint(40, 0), FanCurvePoint(55, 10), FanCurvePoint(65, 45),
        FanCurvePoint(75, 50), FanCurvePoint(85, 100), FanCurvePoint(100, 100)
    )
    private var profileStatus = "Профили переключаются через штатный сервис AYANEO"
    private var profileDiagnostics = "Профиль ещё не применялся в этом запуске."
    private data class ProfileButtonViews(val root: LinearLayout, val title: TextView)
    private val profileButtons = linkedMapOf<String, ProfileButtonViews>()
    private var profileStatusValueView: TextView? = null
    private var retroProfileTitleView: TextView? = null
    private var rootAccessBadge: TextView? = null
    @Volatile private var rootAccessGranted: Boolean? = null
    @Volatile private var rootProbeRunning = false
    /** "checking", "granted", "denied" (refused), "no_su" (no su binary) or "timeout" (no answer). */
    @Volatile private var rootState = "checking"
    private var rootWarningDialogShown = false
    @Volatile private var startupProfileRestored = false
    private var advanced = false
    private var controllerPage = false
    /** Sub-page of controllerPage: device settings (buttons, charging, Wi-Fi, monitor, memory). */
    private var devicePage = false
    private var profileEditorPage = false
    /** Package whose own game profile is being edited (from the favorites page), or null. */
    private var gameProfilePackage: String? = null
    /** Game profile that was active when PocketHub last closed; kept only if that game still runs. */
    private var pendingStartupGameProfile: String? = null
    private var gameWatchRunnable: Runnable? = null
    private var gameWatchMisses = 0
    private val gameWatchHandler = Handler(Looper.getMainLooper())
    private var controlPage = false
    /** Third home page (swipe left from the tools page): favorite apps. */
    private var favoritesPage = false
    /** Fourth home page: quick toggles (Wi-Fi, Bluetooth, rotation and user-added tiles). */
    private var quickPage = false
    /** Home page 4: Game tuning (tuned profiles, auto-tune). */
    private var tuningPage = false
    /** Tuned profile open in the profile editor (null = built-in / game profile). */
    private var tunedEditorId: String? = null
    private val quickTileStates = HashMap<String, Boolean>()
    private var helpPage = false
    private var editorProfile = "Balanced"
    private var editorFanMode = "FAN_MODE_OFF"
    private var editorUpdating = false
    private val editorFanButtons = linkedMapOf<String, TextView>()
    private val editorCpuSeeks = linkedMapOf<String, SeekBar>()
    private val editorCpuValues = linkedMapOf<String, TextView>()
    private val cpuPolicySteps = linkedMapOf(
        "policy0" to mutableListOf(307200, 441600, 556800, 672000, 787200, 902400, 1017600, 1113600, 1228800, 1344000, 1459200, 1555200, 1670400, 1785600, 1900800, 2016000),
        "policy3" to mutableListOf(499200, 614400, 729600, 844800, 940800, 1056000, 1171200, 1286400, 1401600, 1536000, 1651200, 1785600, 1920000, 2054400, 2188800, 2323200, 2457600, 2592000, 2707200, 2803200),
        "policy7" to mutableListOf(595200, 729600, 864000, 998400, 1132800, 1248000, 1363200, 1478400, 1593600, 1708800, 1843200, 1977600, 2092800, 2227200, 2342400, 2476800, 2592000, 2726400, 2841600, 2956800)
    )
    @Volatile private var cpuLimitEnforcerProcess: Process? = null
    @Volatile private var cpuLimitEnforcerMarker: File? = null
    @Volatile private var gpuLimitEnforcerMarker: File? = null
    private var editorGpuSeek: SeekBar? = null
    private var editorGpuValue: TextView? = null
    private var editorGpuLock: Switch? = null
    private var editorTurbo: Switch? = null
    /** Game profile controller overrides: "" / -1 = leave the current setting alone. */
    private var editorAbxy = ""
    private var editorVib = -1
    private var homeSwipeStartX = 0f
    private var homeSwipeStartY = 0f
    private var controllerVibrationLevel = -1
    private var controllerAbxyMode = "STANDARD"
    private var controllerStatusValueView: TextView? = null
    private var controllerVibrationStatusView: TextView? = null
    private val controllerChoiceButtons = linkedMapOf<String, TextView>()
    @Volatile private var pendingControllerCommand: String? = null
    private var lastOverlayPermissionState: Boolean? = null
    private lateinit var content: LinearLayout
    private lateinit var rootStatus: TextView
    private lateinit var screenScroll: ScrollView
    // Live values are updated in-place; the view hierarchy is never rebuilt by the timer.
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var cpuValueView: TextView? = null
    private var gpuValueView: TextView? = null
    private var displayValueView: TextView? = null
    private var memoryValueView: TextView? = null
    private var swapValueView: TextView? = null
    private var storageValueView: TextView? = null
    private var batteryRingView: MetricRingView? = null
    private var storageRingView: MetricRingView? = null
    private var ramRingView: MetricRingView? = null
    private var batteryDetailValueView: TextView? = null
    private var batteryPowerValueView: TextView? = null
    private var batteryRuntimeValueView: TextView? = null
    private var fpsValueView: TextView? = null
    /** Aurora Live: fan icon that spins only while the fan does, and the FPS history chart. */
    private var auroraFanIcon: LivingIconView? = null
    private var fpsGraphView: FpsGraphView? = null
    private val fpsHistory = ArrayDeque<Int>()
    private var activeCustomDialog: android.app.Dialog? = null
    // Live battery state is sampled with telemetry; only runtime estimation is throttled.
    private var lastBatteryRuntimeText: String = "—"
    private var lastBatteryRuntimeSampleElapsedMs: Long = 0L
    private var lastBatteryRuntimeStatus: Int? = null
    private var lastBatteryRuntimePlugged: Int? = null
    private var lastBatteryRuntimeLevel: Int? = null
    private var lastBatteryRuntimeLevelElapsedMs: Long = 0L
    /** Smoothed power draw (W) for the runtime estimate; reset when charging state changes. */
    private var batteryPowerAvgW: Double? = null
    private var batteryStateSinceMs: Long = 0L
    @Volatile private var latestBatteryIntent: Intent? = null
    private var batteryReceiverRegistered = false
    private val batteryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val previous = latestBatteryIntent
            val fresh = Intent(intent)
            latestBatteryIntent = fresh
            val stateChanged = previous == null ||
                previous.getIntExtra("status", -1) != fresh.getIntExtra("status", -1) ||
                previous.getIntExtra("plugged", 0) != fresh.getIntExtra("plugged", 0)
            if (stateChanged) {
                lastBatteryRuntimeStatus = null
                lastBatteryRuntimePlugged = null
                lastBatteryRuntimeLevel = null
                lastBatteryRuntimeLevelElapsedMs = 0L
            }
            refreshLiveValues()
        }
    }
    private var lastFpsSampleElapsedMs: Long = 0L
    private var cachedMainDisplayFps: Int? = null
    private var fpsRunnable: Runnable? = null
    @Volatile private var fpsSampling = false
    @Volatile private var fpsLayer: String? = null
    private var fanValueView: TextView? = null
    private var fanSubtitleView: TextView? = null
    private val fanModeButtons = linkedMapOf<String, TextView>()
    private var selectedFanMode: String = "FAN_MODE_BALANCE"
    @Volatile private var pendingFanMode: String? = null
    @Volatile private var pendingCpuSchedulerMode: String? = null

    // Per-app volume uses a root-owned app_process helper; Shizuku is not required.
    @Volatile private var rootAudioBridge: RootAudioBridgeClient? = null
    @Volatile private var rootAudioBridgeStarting = false
    private var perAppOpenRequested = false
    private var cpuUsageValueView: TextView? = null
    private var gpuUsageValueView: TextView? = null
    private var cpuUsageMeterView: SegmentedUsageView? = null
    private var gpuUsageMeterView: SegmentedUsageView? = null
    private var ramUsageMeterView: SegmentedUsageView? = null
    private var storageUsageMeterView: SegmentedUsageView? = null
    private var cpuTempValueView: TextView? = null
    private var gpuTempValueView: TextView? = null
    private var cpuChartView: MiniLineChartView? = null
    private var gpuChartView: MiniLineChartView? = null
    private var fanStatusValueView: TextView? = null
    private var brightnessSlidersContainer: LinearLayout? = null
    private var lastCpuTotal: Long? = null
    private var lastCpuIdle: Long? = null
    @Volatile private var telemetryRunning = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val brightnessWriteRunnables = mutableMapOf<String, Runnable>()
    private var oemAidlConnection: ServiceConnection? = null
    @Volatile private var oemAidlBound = false
    @Volatile private var oemServiceBinder: IBinder? = null
    @Volatile private var oemBinderDescriptor: String? = null
    @Volatile private var oemClientId: String? = null
    @Volatile private var oemCallbackRegistered = false
    @Volatile private var oemRegistrationCheckActive = false
    @Volatile private var oemLastCallbackMessage: String? = null
    @Volatile private var oemLastCallbackDiagnostic: String? = null
    private val oemCallbackTransactions = AtomicInteger(0)

    // Mirror the OEM generated AIDL callback Stub: expose the callback interface descriptor,
    // accept string callbacks, and retain diagnostics even if the vendor callback shape differs.
    private val oemCallbackBinder: IBinder = object : Binder(), IInterface {
        init { attachInterface(this, OEM_CALLBACK_DESCRIPTOR) }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(OEM_CALLBACK_DESCRIPTOR)
                return true
            }
            if (code >= IBinder.FIRST_CALL_TRANSACTION) {
                val seen = oemCallbackTransactions.incrementAndGet()
                val startPosition = data.dataPosition()
                return try {
                    data.enforceInterface(OEM_CALLBACK_DESCRIPTOR)
                    val message = data.readString().orEmpty()
                    oemLastCallbackMessage = message
                    oemLastCallbackDiagnostic = "callback #$seen · code=$code · flags=$flags"
                    Log.i(OEM_LOG_TAG, "Callback #$seen code=$code flags=$flags message=${message.take(240)}")
                    handleOemCallback(message)
                    reply?.writeNoException()
                    true
                } catch (e: Exception) {
                    val diagnostic = "callback #$seen · code=$code · flags=$flags · ${e.javaClass.simpleName}: ${e.message ?: "no detail"} · parcel=${data.dataSize()}B pos=${data.dataPosition()} (start=$startPosition)"
                    oemLastCallbackDiagnostic = diagnostic
                    Log.e(OEM_LOG_TAG, diagnostic, e)
                    false
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (popupMode) return super.dispatchKeyEvent(event)
        // RB / LB flip between the home pages, like swiping right / left.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            val onHome = !advanced && !controllerPage && !profileEditorPage && !helpPage && activeCustomDialog?.isShowing != true
            if (onHome) {
                if (event.action == KeyEvent.ACTION_UP) {
                    val current = if (quickPage) 4 else if (tuningPage) 3 else if (favoritesPage) 2 else if (controlPage) 1 else 0
                    val step = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
                    val next = (current + step).coerceIn(0, HOME_PAGE_COUNT - 1)
                    if (next != current) showHomePage(next, step)
                }
                return true
            }
        }
        val isBackKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (isBackKey) {
            if (event.action == KeyEvent.ACTION_DOWN) return true
            if (event.action == KeyEvent.ACTION_UP) {
                val dialog = activeCustomDialog
                if (dialog?.isShowing == true) {
                    dialog.dismiss()
                    return true
                }
                if (advanced) {
                    advanced = false
                    buildScreen(homeTransitionDirection = -1)
                    return true
                }
                if (profileEditorPage) {
                    profileEditorPage = false
                    gameProfilePackage = null; tunedEditorId = null
                    buildScreen(homeTransitionDirection = -1)
                    return true
                }
                if (controllerPage) {
                    controllerPage = false
                    buildScreen(homeTransitionDirection = -1)
                    return true
                }
                if (helpPage) {
                    helpPage = false
                    buildScreen(homeTransitionDirection = -1)
                    return true
                }
                // On the home dashboard, the controller's B/Back key should not kill
                // the overlay while the user is playing. Home/launcher can still leave it.
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (activeCustomDialog?.isShowing == true) {
            activeCustomDialog?.dismiss()
            return
        }
        if (advanced) {
            advanced = false
            buildScreen(homeTransitionDirection = -1)
        } else if (profileEditorPage) {
            profileEditorPage = false
            gameProfilePackage = null; tunedEditorId = null
            buildScreen(homeTransitionDirection = -1)
        } else if (controllerPage) {
            controllerPage = false
            buildScreen(homeTransitionDirection = -1)
        } else if (helpPage) {
            helpPage = false
            buildScreen(homeTransitionDirection = -1)
        }
        // Intentionally remain on the dashboard rather than finishing the app.
    }

    /**
     * Collect focusable descendants through the public Android View API.
     * View/ViewGroup do not expose a Kotlin `focusables()` method in all SDK stubs;
     * addFocusables(ArrayList, direction) is the compatible Java API.
     */
    private fun firstFocusableInTree(root: View): View? {
        val candidates = ArrayList<View>()
        root.addFocusables(candidates, View.FOCUS_FORWARD)
        return candidates.firstOrNull { it.isFocusable && it.isShown && it.isEnabled }
    }

    private var lastGuardedDialog: android.app.Dialog? = null
    private var lastGuardedKey = ""

    /**
     * Dialog that opens only once: a second show() from the same place while the first copy is
     * still open (for example a quick double tap on a tile) is ignored.
     */
    private inner class GuardedDialog : android.app.Dialog(this@MainActivity) {
        override fun show() {
            val key = Throwable().stackTrace.asSequence()
                .filter { it.className.startsWith("com.example.ayaneooverlay.MainActivity") && !it.className.contains("GuardedDialog") }
                .take(2).joinToString("|") { it.methodName + ":" + it.lineNumber }
            val last = lastGuardedDialog
            if (last != null && last !== this && last.isShowing && key == lastGuardedKey) {
                if (activeCustomDialog === this) activeCustomDialog = last
                return
            }
            lastGuardedDialog = this
            lastGuardedKey = key
            super.show()
        }
    }

    private var keyboardLanguagePopup: PopupWindow? = null

    private fun prepareDialogNavigation(dialog: android.app.Dialog) {
        val previousDialog = activeCustomDialog
        activeCustomDialog = dialog
        dialog.setOnKeyListener { _, keyCode, event ->
            val shouldClose = keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
            if (shouldClose && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else shouldClose && event.action == KeyEvent.ACTION_DOWN
        }
        dialog.setOnShowListener {
            val decor = dialog.window?.decorView
            if (decor != null) firstFocusableInTree(decor)?.requestFocus()
        }
        dialog.setOnDismissListener {
            if (activeCustomDialog === dialog) {
                activeCustomDialog = previousDialog?.takeIf { it.isShowing }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (popupMode) { startPopup(); return }
        // New installs take over the hardware shortcut by default; on every launch
        // reassert the secure accessibility-service list if firmware changed it.
        PocketHubButtonTakeover.initialize(this)
        PocketHubButtonTakeover.requestEnsure(this)
        // The physical key routes a hide action through this activity. The task may
        // recreate MainActivity instead of delivering onNewIntent on some firmware.
        if (intent?.action == ACTION_HIDE_FROM_AYA_BUTTON) {
            moveTaskToBack(true)
            return
        }
        val appPrefs = getPreferences(Context.MODE_PRIVATE)
        uiLanguage = appPrefs.getString("ui_language", "en") ?: "en"
        accentScheme = appPrefs.getString("accent_scheme", "blue") ?: "blue"
        layoutTheme = loadLayoutTheme(appPrefs)
        selectedFanMode = appPrefs.getString("fan_mode", "FAN_MODE_OFF") ?: "FAN_MODE_OFF"
        applyAppearancePreferences()
        // Prefer the smaller built-in panel. If the firmware forbids cross-display launches,
        // stay on the launch display instead of repeatedly relaunching the activity.
        if (redirectToSmallestDisplayIfPossible()) return
        // Restore the last profile here for the UI only; it is reapplied once root access
        // is confirmed (see checkRootAccessAsync), keeping the saved fan mode.
        // A game profile left over from last time is not restored blindly: start from the
        // built-in profile that was active before it (the game check happens with root below).
        val builtIn = setOf("Eco", "Balanced", "Streaming", "Gaming", "Max")
        val savedProfile = appPrefs.getString("profile", "Balanced") ?: "Balanced"
        selectedProfile = when {
            savedProfile in builtIn -> savedProfile
            isGameProfile(savedProfile) -> appPrefs.getString(KEY_PROFILE_BEFORE_GAME, "Balanced")?.takeIf { it in builtIn } ?: "Balanced"
            else -> "Balanced"
        }
        pendingStartupGameProfile = savedProfile.takeIf { isGameProfile(it) }
        profileStatus = tr(
            "Showing the last Overlay profile; device settings were left unchanged on launch.",
            "Показан последний профиль Overlay; при запуске настройки устройства не менялись.",
            "Laatste Overlay-profiel wordt getoond; instellingen zijn bij het starten niet gewijzigd."
        )
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        buildScreen()
        // Overheat guard: runs as its own root loop and only acts while the fan mode is OFF.
        FanGuard.syncMode(this, selectedFanMode)
        FanGuard.ensureRunningAsync(this)
        // Establish the vendor AIDL session automatically; users should never need a manual connect button.
        testOemAidl(showToast = false)
        liveInstance = java.lang.ref.WeakReference(this)
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            handleRemoteCommand(intent)
            maybeShowSetupWizard()
        }, 600L)
        checkRootAccessAsync()
        grantSystemPermissionsAsync()
        startLiveRefresh()
    }

    /**
     * With root, PocketHub gives itself what it needs instead of sending the user to Android's
     * settings: "Display over other apps" (touchpad, PC keyboard, volume panel, popups) and
     * notifications (its background services).
     */
    private fun grantSystemPermissionsAsync() {
        val needOverlay = !Settings.canDrawOverlays(this)
        val needNotifications = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!needOverlay && !needNotifications) return
        Thread {
            val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow; " +
                "pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null; true"
            if (!runAyaRootCommand(command).first) runRootCommand(command)
            Log.i("PocketHubSetup", "permissions overlay=${Settings.canDrawOverlays(this)}")
        }.apply { isDaemon = true; start() }
    }

    override fun onResume() {
        super.onResume()
        if (popupMode) return
        applyRecentsVisibility()
        PocketHubButtonTakeover.requestEnsure(this)
        if (getPreferences(Context.MODE_PRIVATE).getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)) {
            try { ScreenAudioVolumeService.start(this) } catch (e: Exception) { Log.w("PocketHubScreenAudio", "Could not restore screen audio service", e) }
        }
        val overlayPermissionState = Settings.canDrawOverlays(this)
        if (lastOverlayPermissionState != null && lastOverlayPermissionState != overlayPermissionState && ::content.isInitialized) {
            buildScreen()
        }
        lastOverlayPermissionState = overlayPermissionState
        registerBatteryReceiverIfNeeded()
        if (rootAccessGranted != true) checkRootAccessAsync()
        startLiveRefresh()
        if (!oemAidlBound && oemAidlConnection == null) {
            uiHandler.postDelayed({
                if (!isFinishing && !isDestroyed && !oemAidlBound && oemAidlConnection == null) testOemAidl(showToast = false)
            }, 700L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (popupMode) return
        setIntent(intent)
        if (intent.action == ACTION_HIDE_FROM_AYA_BUTTON) {
            moveTaskToBack(true)
        }
        handleRemoteCommand(intent)
    }

    /** Popup mode: theme + root check, then the Dual screen controls window; closing it closes the activity. */
    private fun startPopup() {
        if (intent?.getStringExtra(EXTRA_POPUP_COMMAND) != "capture") popupInstance = java.lang.ref.WeakReference(this)
        val appPrefs = getPreferences(Context.MODE_PRIVATE)
        uiLanguage = appPrefs.getString("ui_language", "en") ?: "en"
        accentScheme = appPrefs.getString("accent_scheme", "blue") ?: "blue"
        layoutTheme = loadLayoutTheme(appPrefs)
        applyAppearancePreferences()
        Thread {
            val granted = checkRoot()
            rootAccessGranted = granted
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (intent?.getStringExtra(EXTRA_POPUP_COMMAND)) {
                    // PocketHub's own two-screen capture; the popup is invisible until the picker shows.
                    "capture" -> uiHandler.postDelayed({ captureBothScreens { finish() } }, 250L)
                    "profiles" -> showProfileQuickPicker { finish() }
                    "autotune" -> showAutoTuneDialog { finish() }
                    else -> showScreenControlDialog { finish() }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Commands sent by remapped buttons (see ButtonRemap / remoteCommand). */
    private fun handleRemoteCommand(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_REMOTE_COMMAND) ?: return
        intent.removeExtra(EXTRA_REMOTE_COMMAND)
        runRemoteCommand(command)
    }

    internal fun runRemoteCommand(command: String) {
        when (command) {
            "profile_next" -> {
                val order = listOf("Eco", "Balanced", "Streaming", "Gaming", "Max")
                val current = if (isGameProfile(selectedProfile))
                    getPreferences(Context.MODE_PRIVATE).getString(KEY_PROFILE_BEFORE_GAME, "Balanced") ?: "Balanced"
                else selectedProfile
                val next = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
                applyPerformanceProfile(next)
                Toast.makeText(this, tr("Profile", "Профиль", "Profiel", "Perfil") + ": " + profileDisplayName(next), Toast.LENGTH_SHORT).show()
                if (!advanced && !profileEditorPage && !helpPage && !controllerPage) buildScreen()
            }
            "screens" -> showScreenControlDialog()
        }
    }

    // ---- First-run setup wizard ---------------------------------------------------------

    /** Shows the setup wizard once on a new install (existing installs skip it). */
    private fun maybeShowSetupWizard() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) return
        // Installs that were already in use before the wizard existed are not asked again.
        if (prefs.contains("profile") || prefs.contains("layout_theme")) {
            prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
            return
        }
        showSetupWizard()
    }

    /**
     * Step-by-step first setup: language, welcome, AYANEO components and start after boot,
     * sound, keyboard. Each step is applied when you go to the next one. It can be opened again
     * from Settings → About.
     */
    private fun showSetupWizard() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val imePrefs = getSharedPreferences(DualScreenImeService.PREFS, Context.MODE_PRIVATE)
        val dialog = GuardedDialog()
        dialog.setCancelable(false)
        val pageCount = 6
        var page = 0

        // Choices (applied step by step).
        // Every question starts on the recommended answer.
        var disableHome = true
        var disableSpace = true
        var autoStart = true
        var screenAudio = true
        var volumeKeys = true
        var panelDisplay = prefs.getString(ScreenAudioVolumeService.KEY_VOLUME_PANEL_DISPLAY, "upper") ?: "upper"
        var useKeyboard = true
        var placement = DualScreenImeService.PLACEMENT_BOTTOM

        // New installs start with the Dusk design (changeable in Settings → Interface).
        if (!prefs.contains("layout_theme")) {
            prefs.edit().putString("layout_theme", "dusk").apply()
            layoutTheme = "dusk"
            applyAppearancePreferences()
            buildScreen()
        }
        val kbLanguages = listOf(
            DualScreenImeService.LANGUAGE_EN to "English",
            DualScreenImeService.LANGUAGE_RU to "Русский",
            DualScreenImeService.LANGUAGE_DE to "Deutsch",
            DualScreenImeService.LANGUAGE_FR to "Français",
            DualScreenImeService.LANGUAGE_ES to "Español",
            DualScreenImeService.LANGUAGE_ZH to "中文 · 拼音"
        )
        val chosenLanguages = (imePrefs.getStringSet(DualScreenImeService.KEY_ENABLED_LANGUAGES, null)?.toMutableSet()
            ?: mutableSetOf(DualScreenImeService.LANGUAGE_EN).apply { if (kbLanguages.any { it.first == uiLanguage }) add(uiLanguage) })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cardBg2, bg))
        }
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, margin(bottom = 10))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, margin(top = 10))

        fun wTitle(value: String) = body.addView(label(value, 22f, white, true), margin(bottom = 8))
        fun wText(value: String, size: Float = 13.5f, color: Int = muted) = body.addView(label(value, size, color), margin(bottom = 9))
        fun heading(value: String) = body.addView(label(value, 12.5f, mint, true), margin(top = 8, bottom = 5))
        fun recommendedTag(value: String = tr("★ Recommended", "★ Рекомендуется", "★ Aanbevolen", "★ Recomendado")) =
            body.addView(label(value, 11.5f, mint, true), margin(bottom = 4))

        lateinit var render: () -> Unit
        /** Two choices (Yes / No by default). */
        fun yesNo(question: String, hint: String?, value: Boolean, recommended: String? = null, yes: String = tr("Yes", "Да", "Ja", "Sí"), no: String = tr("No", "Нет", "Nee", "No"), set: (Boolean) -> Unit) {
            body.addView(label(question, 14.5f, white, true), margin(top = 6, bottom = 3))
            if (recommended != null) recommendedTag(recommended)
            if (hint != null) body.addView(label(hint, 11.5f, muted), margin(bottom = 6))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(settingsChoice(yes, value) { set(true); render() }.apply { textSize = 13f }, weight(1f, end = 3))
            row.addView(settingsChoice(no, !value) { set(false); render() }.apply { textSize = 13f }, weight(1f, start = 3))
            body.addView(row, margin(bottom = 10))
        }

        fun applySystemStep() {
            prefs.edit().putBoolean("auto_start_pockethub", autoStart).apply()
            val home = "com.ayaneo.home/com.ayaneo.home.ui.main.LauncherActivity"
            val space = "com.ayaneo.home/com.ayaneo.space.ui.global.SpaceActivity"
            val wasHomeOn = prefs.getBoolean("aya_home_enabled", true)
            val wasSpaceOn = prefs.getBoolean("aya_space_enabled", true)
            Thread {
                fun set(component: String, enabled: Boolean, key: String) {
                    val command = if (enabled) "pm enable --user 0 'com.ayaneo.home' >/dev/null && pm enable --user 0 '$component'"
                        else "pm disable --user 0 '$component'"
                    val ok = runAyaRootCommand(command).first
                    if (ok) prefs.edit().putBoolean(key, enabled).apply()
                }
                if (disableHome == wasHomeOn) set(home, !disableHome, "aya_home_enabled")
                if (disableSpace == wasSpaceOn) set(space, !disableSpace, "aya_space_enabled")
            }.apply { isDaemon = true; start() }
        }

        fun grantOverlayPermission() = grantSystemPermissionsAsync()

        fun applySoundStep() {
            try {
                if (screenAudio) ScreenAudioVolumeService.enable(this) else if (prefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)) ScreenAudioVolumeService.disable(this)
            } catch (error: Exception) { Log.w("PocketHubSetup", "Screen audio", error) }
            prefs.edit()
                .putBoolean(ScreenAudioVolumeService.KEY_VOLUME_KEYS, volumeKeys)
                .putString(ScreenAudioVolumeService.KEY_VOLUME_PANEL_DISPLAY, panelDisplay)
                .putString(ScreenAudioVolumeService.KEY_VOLUME_KEYS_TARGET, "master")
                .apply()
            if (volumeKeys) grantOverlayPermission()
        }

        fun applyKeyboardStep() {
            // Defaults for a new install: small keys and the Terminal theme.
            val edit = imePrefs.edit()
                .putString(DualScreenImeService.KEY_SIZE, DualScreenImeService.SIZE_SMALL)
                .putString(DualScreenImeService.KEY_PLACEMENT, placement)
            if (!imePrefs.contains(DualScreenImeService.KEY_THEME)) edit.putString(DualScreenImeService.KEY_THEME, DualScreenImeService.THEME_CARTRIDGE)
            val ordered = kbLanguages.map { it.first }.filter { it in chosenLanguages }.ifEmpty { listOf(DualScreenImeService.LANGUAGE_EN) }
            edit.putStringSet(DualScreenImeService.KEY_ENABLED_LANGUAGES, ordered.toSet())
            val active = imePrefs.getString(DualScreenImeService.KEY_LANGUAGE, null)
            edit.putString(DualScreenImeService.KEY_LANGUAGE, active?.takeIf { it in ordered } ?: ordered.first())
            edit.apply()
            if (useKeyboard) {
                // Turn PocketHub Keyboard on in Android (it still has to be chosen as the keyboard).
                enablePocketHubKeyboard { }
                grantOverlayPermission()
            }
        }

        fun finishWizard() {
            prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
            if (!imePrefs.contains(DualScreenImeService.KEY_THEME)) imePrefs.edit().putString(DualScreenImeService.KEY_THEME, DualScreenImeService.THEME_CARTRIDGE).apply()
            // The AYANEO button is always PocketHub's: make sure the takeover is in place now.
            setNativeAccessibilityServiceForTakeover(true)
            dialog.dismiss()
            buildScreen()
        }

        var renderedPage = -1
        val recommended = tr("★ Recommended", "★ Рекомендуется", "★ Aanbevolen", "★ Recomendado")
        render = {
            // Same page re-rendered after a choice: keep the scroll position (no jump to the top).
            val samePage = renderedPage == page
            val keepY = if (samePage) scroll.scrollY else 0
            renderedPage = page
            body.removeAllViews()
            dots.removeAllViews()
            for (i in 0 until pageCount) dots.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (i == page) mint else border) }
            }, LinearLayout.LayoutParams(dp(if (i == page) 22 else 8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) })

            when (page) {
                0 -> {
                    wTitle("Language · Язык · Taal · Idioma")
                    wText("Choose the app language. · Выбери язык программы.", 15f)
                    val codes = listOf("en", "ru", "nl", "es", "de", "fr", "it", "zh")
                    val names = listOf("English", "Русский", "Nederlands", "Español", "Deutsch", "Français", "Italiano", "中文")
                    codes.chunked(2).forEachIndexed { r, pair ->
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        pair.forEachIndexed { c, code ->
                            row.addView(settingsChoice(names[r * 2 + c], uiLanguage == code) {
                                uiLanguage = code
                                prefs.edit().putString("ui_language", code).apply()
                                render()
                            }.apply { textSize = 16f; minHeight = dp(52) }, weight(1f, start = if (c == 0) 0 else 3, end = if (c == 0) 3 else 0))
                        }
                        body.addView(row, margin(bottom = 6))
                    }
                }
                1 -> {
                    wTitle(tr("Welcome to PocketHub", "Добро пожаловать в PocketHub", "Welkom bij PocketHub", "Bienvenido a PocketHub"))
                    wText(tr(
                        "PocketHub is the control center for your AYANEO Pocket DS. It replaces AYANEO's own Game Window app: performance profiles and auto-tune, the fan, the AYANEO button, the lower screen as keyboard and touchpad, volume per screen and more.",
                        "PocketHub — центр управления для AYANEO Pocket DS. Он заменяет родное приложение AYANEO Game Window: профили производительности и автоподбор, вентилятор, кнопка AYANEO, нижний экран как клавиатура и тачпад, громкость по экранам и многое другое.",
                        "PocketHub is het bedieningscentrum voor je AYANEO Pocket DS. Het vervangt AYANEO's eigen Game Window-app: prestatieprofielen en automatisch afstemmen, de ventilator, de AYANEO-knop, het onderste scherm als toetsenbord en touchpad, volume per scherm en meer.",
                        "PocketHub es el centro de control de tu AYANEO Pocket DS. Sustituye a la app Game Window de AYANEO: perfiles de rendimiento y ajuste automático, el ventilador, el botón AYANEO, la pantalla inferior como teclado y panel táctil, volumen por pantalla y más."
                    ), 17.5f, white)
                    wText(tr(
                        "PocketHub needs root (Magisk). The AYANEO button always opens and hides PocketHub.\n\nThe next few steps set up the basics. Everything can be changed later in Settings.",
                        "PocketHub нужен root (Magisk). Кнопка AYANEO всегда открывает и скрывает PocketHub.\n\nДальше несколько шагов с основными настройками. Всё можно поменять потом в Настройках.",
                        "PocketHub heeft root (Magisk) nodig. De AYANEO-knop opent en verbergt PocketHub altijd.\n\nDe volgende stappen stellen de basis in. Alles is later te wijzigen in Instellingen.",
                        "PocketHub necesita root (Magisk). El botón AYANEO siempre abre y oculta PocketHub.\n\nLos siguientes pasos configuran lo básico. Todo se puede cambiar luego en Ajustes."
                    ), 16f)
                }
                2 -> {
                    wTitle(tr("System", "Система", "Systeem", "Sistema"))
                    // Launcher recommendation first: turning AYANEO Home off goes together with it.
                    val launcherBox = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = roundedBackground(blendColor(cardBg, cyan, 0.12f), cyan, 14)
                    }
                    launcherBox.addView(label(tr("★ Recommended: PocketHub Launcher", "★ Рекомендуем: PocketHub Launcher", "★ Aanbevolen: PocketHub Launcher", "★ Recomendado: PocketHub Launcher"), 14.5f, cyan, true), margin(bottom = 4))
                    launcherBox.addView(label(tr(
                        "Our own home screen for both screens. It replaces AYANEO Home and Android's basic launcher, has far more settings (separate desktops and wallpapers, launch on the upper or lower screen) and uses much less memory.",
                        "Наш рабочий стол для обоих экранов. Он заменяет AYANEO Home и простой лаунчер Android, у него гораздо больше настроек (свои рабочие столы и обои на каждом экране, запуск на верхнем или нижнем экране) и он занимает намного меньше памяти.",
                        "Ons eigen startscherm voor beide schermen. Het vervangt AYANEO Home en de eenvoudige launcher van Android, heeft veel meer instellingen (eigen bureaubladen en achtergronden per scherm, starten op het boven- of onderscherm) en gebruikt veel minder geheugen.",
                        "Nuestra pantalla de inicio para ambas pantallas. Sustituye a AYANEO Home y al launcher básico de Android, tiene muchos más ajustes (escritorios y fondos propios por pantalla, abrir en la pantalla superior o inferior) y usa mucha menos memoria."
                    ), 12f, white), margin(bottom = 8))
                    launcherBox.addView(pill("⬇  " + tr("Download PocketHub Launcher (GitHub)", "Скачать PocketHub Launcher (GitHub)", "PocketHub Launcher downloaden (GitHub)", "Descargar PocketHub Launcher (GitHub)"), cyan, bg) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Emile86/PocketHub-Launcher/releases"))) }
                        catch (_: Exception) { Toast.makeText(this, "github.com/Emile86/PocketHub-Launcher", Toast.LENGTH_LONG).show() }
                    }.apply { minHeight = dp(44); textSize = 13f })
                    body.addView(launcherBox, margin(top = 2, bottom = 10))
                    yesNo(tr("Turn off AYANEO Home?", "Выключить AYANEO Home?", "AYANEO Home uitzetten?", "¿Desactivar AYANEO Home?"),
                        tr("AYANEO's home screen uses about 300 MB of memory all the time. Without it, Android's own launcher takes over — or PocketHub Launcher, if you install it.",
                            "Рабочий стол AYANEO постоянно занимает около 300 МБ памяти. Без него главным станет родной лаунчер Android — или PocketHub Launcher, если ты его установишь.",
                            "AYANEO's startscherm gebruikt continu ongeveer 300 MB geheugen. Zonder komt de launcher van Android ervoor in de plaats — of PocketHub Launcher, als je die installeert.",
                            "La pantalla de inicio de AYANEO usa unos 300 MB de memoria todo el tiempo. Sin ella se usa el launcher de Android, o PocketHub Launcher si lo instalas."),
                        disableHome,
                        recommended = tr("★ Recommended together with PocketHub Launcher", "★ Рекомендуется вместе с PocketHub Launcher", "★ Aanbevolen samen met PocketHub Launcher", "★ Recomendado junto con PocketHub Launcher")) { disableHome = it }
                    yesNo(tr("Turn off AYASpace?", "Выключить AYASpace?", "AYASpace uitzetten?", "¿Desactivar AYASpace?"),
                        tr("AYANEO's game library. If you don't use it, it is switched off at system level: it no longer runs or takes memory and background work, so those resources are freed.",
                            "Игровая библиотека AYANEO. Если ты ей не пользуешься, она выключится на системном уровне: перестанет работать, занимать память и нагружать фон — эти ресурсы освободятся.",
                            "AYANEO's gamebibliotheek. Gebruik je die niet, dan wordt ze op systeemniveau uitgezet: ze draait niet meer en neemt geen geheugen of achtergrondwerk in, zodat die middelen vrijkomen.",
                            "La biblioteca de juegos de AYANEO. Si no la usas, se desactiva a nivel de sistema: deja de ejecutarse y de ocupar memoria y trabajo en segundo plano, y esos recursos quedan libres."),
                        disableSpace, recommended = recommended) { disableSpace = it }
                    yesNo(tr("Start PocketHub after boot?", "Запускать PocketHub при загрузке?", "PocketHub starten na het opstarten?", "¿Iniciar PocketHub al arrancar?"),
                        tr("PocketHub opens by itself after a restart, so its panel and live data are ready right away.",
                            "PocketHub сам откроется после перезагрузки, и его панель с данными будет сразу готова.",
                            "PocketHub opent vanzelf na een herstart, zodat het paneel en de live-gegevens meteen klaar zijn.",
                            "PocketHub se abre solo tras reiniciar, así su panel y los datos en vivo están listos enseguida."),
                        autoStart, recommended = recommended) { autoStart = it }
                }
                3 -> {
                    wTitle(tr("Sound", "Звук", "Geluid", "Sonido"))
                    yesNo(tr("Let PocketHub control the sound?", "Управлять звуком через PocketHub?", "Geluid laten regelen door PocketHub?", "¿Dejar que PocketHub controle el sonido?"),
                        tr("Independent volume for the upper and lower screen, plus an overall volume.",
                            "Отдельная громкость для верхнего и нижнего экрана плюс общая громкость.",
                            "Apart volume voor het boven- en onderscherm, plus een totaalvolume.",
                            "Volumen independiente para la pantalla superior e inferior, más un volumen general."),
                        screenAudio, recommended = recommended) { screenAudio = it }
                    yesNo(tr("Use the volume buttons with PocketHub's panel?", "Кнопки громкости — с панелью PocketHub?", "Volumeknoppen met het PocketHub-paneel?", "¿Usar los botones de volumen con el panel de PocketHub?"),
                        tr("The physical buttons change the overall volume of both screens by default.",
                            "Физические кнопки по умолчанию меняют общую громкость обоих экранов.",
                            "De fysieke knoppen regelen standaard het totaalvolume van beide schermen.",
                            "Los botones físicos cambian por defecto el volumen general de ambas pantallas."),
                        volumeKeys, recommended = tr("★ Recommended: use the physical buttons", "★ Рекомендуется: назначить на физические кнопки", "★ Aanbevolen: gebruik de fysieke knoppen", "★ Recomendado: usar los botones físicos")) { volumeKeys = it }
                    if (volumeKeys) {
                        heading(tr("SHOW THE VOLUME PANEL ON", "ГДЕ ПОКАЗЫВАТЬ ПАНЕЛЬ ГРОМКОСТИ", "VOLUMEPANEEL TONEN OP", "MOSTRAR EL PANEL DE VOLUMEN EN"))
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        listOf(
                            "upper" to tr("Upper", "Верхний", "Boven", "Superior"),
                            "lower" to tr("Lower", "Нижний", "Onder", "Inferior"),
                            "both" to tr("Both", "Оба", "Beide", "Ambas")
                        ).forEachIndexed { i, (code, name) ->
                            val on = panelDisplay == code
                            val cell = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_HORIZONTAL
                                setPadding(dp(6), dp(8), dp(6), dp(8))
                                isClickable = true; isFocusable = true
                                background = roundedBackground(if (on) blendColor(cardBg, mint, 0.18f) else cardBg, if (on) mint else border, 14)
                                addView(VolumeSpotView(this@MainActivity, code, mint, blendColor(cardBg2, Color.WHITE, 0.08f), Color.rgb(12, 14, 18)), LinearLayout.LayoutParams(-1, -2))
                                addView(label(name, 13.5f, if (on) white else muted, true).apply { gravity = Gravity.CENTER }, margin(top = 6))
                                setOnClickListener { panelDisplay = code; render() }
                                enableControllerFocus(this, 14)
                            }
                            row.addView(cell, weight(1f, start = if (i == 0) 0 else 3, end = if (i == 2) 0 else 3))
                        }
                        body.addView(row, margin(bottom = 8))
                    }
                }
                4 -> {
                    wTitle(tr("Keyboard", "Клавиатура", "Toetsenbord", "Teclado"))
                    yesNo(tr("Use PocketHub Keyboard?", "Использовать клавиатуру PocketHub?", "PocketHub Keyboard gebruiken?", "¿Usar PocketHub Keyboard?"),
                        tr("A keyboard made for two screens: it can open on the lower screen while you type into the upper one.",
                            "Клавиатура для двух экранов: может открываться на нижнем экране, пока ты печатаешь на верхнем.",
                            "Een toetsenbord voor twee schermen: het kan op het onderste scherm openen terwijl je op het bovenste typt.",
                            "Un teclado para dos pantallas: puede abrirse en la inferior mientras escribes en la superior."),
                        useKeyboard, recommended = recommended) { useKeyboard = it }
                    if (useKeyboard) {
                        heading(tr("WHERE SHOULD IT OPEN", "ГДЕ ОТКРЫВАТЬ", "WAAR OPENEN", "DÓNDE ABRIRLO"))
                        recommendedTag(tr("★ Recommended: always on the lower screen", "★ Рекомендуется: всегда на нижнем экране", "★ Aanbevolen: altijd op het onderscherm", "★ Recomendado: siempre en la pantalla inferior"))
                        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        listOf(
                            DualScreenImeService.PLACEMENT_BOTTOM to tr("Always lower screen", "Всегда на нижнем", "Altijd onderscherm", "Siempre abajo"),
                            DualScreenImeService.PLACEMENT_ACTIVE to tr("Screen you tap", "Где нажмёшь", "Scherm waarop je tikt", "Donde toques")
                        ).forEachIndexed { i, (code, name) ->
                            val on = placement == code
                            val cell = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_HORIZONTAL
                                setPadding(dp(8), dp(8), dp(8), dp(8))
                                isClickable = true; isFocusable = true
                                background = roundedBackground(if (on) blendColor(cardBg, mint, 0.18f) else cardBg, if (on) mint else border, 14)
                                addView(VolumeSpotView(this@MainActivity, code, mint, blendColor(cardBg2, Color.WHITE, 0.08f), Color.rgb(12, 14, 18), overlay = "keyboard"), LinearLayout.LayoutParams(-1, -2))
                                addView(label(name, 13.5f, if (on) white else muted, true).apply { gravity = Gravity.CENTER }, margin(top = 6))
                                setOnClickListener { placement = code; render() }
                                enableControllerFocus(this, 14)
                            }
                            prow.addView(cell, weight(1f, start = if (i == 0) 0 else 4, end = if (i == 0) 4 else 0))
                        }
                        body.addView(prow, margin(bottom = 10))
                        heading(tr("KEYBOARD LANGUAGES", "ЯЗЫКИ КЛАВИАТУРЫ", "TOETSENBORDTALEN", "IDIOMAS DEL TECLADO"))
                        body.addView(label(tr("Pick one or more. With several, a key on the keyboard switches between them.",
                            "Выбери один или несколько. Если их несколько, они переключаются клавишей на клавиатуре.",
                            "Kies er een of meer. Bij meerdere wissel je met een toets op het toetsenbord.",
                            "Elige uno o varios. Con varios, una tecla del teclado cambia entre ellos."), 11.5f, muted), margin(bottom = 5))
                        kbLanguages.chunked(2).forEach { pair ->
                            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                            pair.forEachIndexed { c, (code, name) ->
                                val on = code in chosenLanguages
                                row.addView(settingsChoice((if (on) "✓ " else "") + name, on) {
                                    if (on && chosenLanguages.size > 1) chosenLanguages.remove(code) else chosenLanguages.add(code)
                                    render()
                                }.apply { textSize = 13f }, weight(1f, start = if (c == 0) 0 else 3, end = if (c == 0) 3 else 0))
                            }
                            body.addView(row, margin(bottom = 6))
                        }
                        val required = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(10), dp(12), dp(12))
                            background = roundedBackground(blendColor(cardBg, mint, 0.14f), mint, 14)
                        }
                        required.addView(label(tr("⚠ REQUIRED", "⚠ ОБЯЗАТЕЛЬНО", "⚠ VERPLICHT", "⚠ OBLIGATORIO"), 13f, mint, true), margin(bottom = 3))
                        required.addView(label(tr(
                            "Choose PocketHub Keyboard as your keyboard — otherwise the keyboard will not work. PocketHub turns it on in Android and sets small keys; then pick “PocketHub Keyboard” in the list.",
                            "Выбери PocketHub Keyboard своей клавиатурой — иначе клавиатура работать не будет. PocketHub сам включит её в Android и поставит маленькие клавиши, тебе останется выбрать «PocketHub Keyboard» в списке.",
                            "Kies PocketHub Keyboard als je toetsenbord — anders werkt het toetsenbord niet. PocketHub zet het aan in Android en kiest kleine toetsen; kies daarna “PocketHub Keyboard” in de lijst.",
                            "Elige PocketHub Keyboard como tu teclado; si no, el teclado no funcionará. PocketHub lo activa en Android y pone teclas pequeñas; luego elige “PocketHub Keyboard” en la lista."
                        ), 12.5f, white), margin(bottom = 8))
                        val chosen = (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.let {
                            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith("$packageName/")
                        } == true
                        required.addView(pill(if (chosen) "✓  " + tr("PocketHub Keyboard is selected", "PocketHub Keyboard выбрана", "PocketHub Keyboard is gekozen", "PocketHub Keyboard está elegido")
                            else "⌨  " + tr("CHOOSE KEYBOARD", "ВЫБРАТЬ КЛАВИАТУРУ", "TOETSENBORD KIEZEN", "ELEGIR TECLADO"), mint, bg) {
                            applyKeyboardStep()
                            enablePocketHubKeyboard { enabled ->
                                if (enabled) {
                                    try { (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker() } catch (_: Exception) { }
                                    // Refresh the button once a keyboard has been picked.
                                    uiHandler.postDelayed({ if (dialog.isShowing && page == 4) render() }, 4000L)
                                } else {
                                    Toast.makeText(this, tr(
                                        "Turn on “PocketHub Keyboard” here, then press the button again.",
                                        "Включи здесь «PocketHub Keyboard», затем нажми кнопку ещё раз.",
                                        "Zet hier “PocketHub Keyboard” aan en druk daarna opnieuw op de knop.",
                                        "Activa aquí “PocketHub Keyboard” y vuelve a pulsar el botón."
                                    ), Toast.LENGTH_LONG).show()
                                    try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { }
                                }
                            }
                        }.apply { minHeight = dp(58); textSize = 16f })
                        body.addView(required, margin(top = 8, bottom = 10))
                        wText(tr("More options, including the keyboard design, are in Settings → Dual-screen keyboard.",
                            "Дополнительные настройки, в том числе дизайн клавиатуры, — в Настройках → «Двойная клавиатура».",
                            "Meer opties, ook het ontwerp van het toetsenbord, vind je in Instellingen → Toetsenbord voor twee schermen.",
                            "Más opciones, incluido el diseño del teclado, en Ajustes → Teclado de doble pantalla."), 12f)
                    }
                }
                else -> {
                    wTitle(tr("All set", "Всё готово", "Klaar", "Todo listo"))
                    wText(tr(
                        "Press the AYANEO button any time to open or hide PocketHub. Swipe between the home pages or use LB / RB. Help (?) explains every feature.",
                        "Нажимай кнопку AYANEO, чтобы открыть или скрыть PocketHub. Между главными страницами — свайпом или LB / RB. В справке (?) описана каждая функция.",
                        "Druk op de AYANEO-knop om PocketHub te openen of te verbergen. Wissel van pagina met een veeg of LB / RB. Help (?) legt elke functie uit.",
                        "Pulsa el botón AYANEO para abrir u ocultar PocketHub. Cambia de página deslizando o con LB / RB. La ayuda (?) explica cada función."
                    ), 15f, white)
                    wText(tr(
                        "In Settings → Interface you can pick from many designs (themes) for PocketHub, and in Settings → Dual-screen keyboard a design for the keyboard.",
                        "В Настройках → «Интерфейс» можно выбрать один из многих дизайнов (тем) PocketHub, а в Настройках → «Двойная клавиатура» — дизайн клавиатуры.",
                        "In Instellingen → Interface kies je uit veel ontwerpen (thema's) voor PocketHub, en in Instellingen → Toetsenbord voor twee schermen een ontwerp voor het toetsenbord.",
                        "En Ajustes → Interfaz puedes elegir entre muchos diseños (temas) para PocketHub, y en Ajustes → Teclado de doble pantalla un diseño para el teclado."
                    ), 14f, white)
                    wText(tr("You can run this setup again from Settings → About.",
                        "Эту настройку можно пройти ещё раз: Настройки → «О программе».",
                        "Je kunt deze installatie opnieuw doen via Instellingen → Over.",
                        "Puedes repetir esta configuración en Ajustes → Acerca de."))
                }
            }

            nav.removeAllViews()
            if (page > 0) nav.addView(pill(tr("BACK", "НАЗАД", "TERUG", "ATRÁS"), cardBg2, white) { page--; render() }.apply { minHeight = dp(46) }, weight(1f, end = 4))
            val last = page == pageCount - 1
            nav.addView(pill(if (last) tr("START", "НАЧАТЬ", "STARTEN", "EMPEZAR") else tr("NEXT", "ДАЛЕЕ", "VOLGENDE", "SIGUIENTE"), mint, bg) {
                when (page) {
                    2 -> applySystemStep()
                    3 -> applySoundStep()
                    4 -> applyKeyboardStep()
                }
                if (last) finishWizard() else { page++; render() }
            }.apply { minHeight = dp(46) }, weight(1f, start = if (page > 0) 4 else 0))
            if (samePage) scroll.post { scroll.scrollTo(0, keepY) }
            else {
                scroll.scrollTo(0, 0)
                nav.getChildAt(nav.childCount - 1)?.requestFocus()
            }
        }

        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, event ->
            val back = keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
            if (back && event.action == KeyEvent.ACTION_UP) { if (page > 0) { page--; render() }; true } else back
        }
        render()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(bg))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    /**
     * Turns PocketHub Keyboard on in Android's keyboard list with root, so it shows up in the
     * keyboard picker. Tries `ime enable` and also writes the enabled list directly, through
     * AYANEO's xsu first (plain su may report success without changing system state on this
     * firmware), then waits until Android reports the keyboard as enabled. [then] runs on the
     * UI thread with true when it is enabled.
     */
    private fun enablePocketHubKeyboard(then: (Boolean) -> Unit) {
        val id = ComponentName(this, DualScreenImeService::class.java).flattenToShortString()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        fun enabled() = try { imm?.enabledInputMethodList?.any { it.packageName == packageName } == true } catch (_: Exception) { false }
        if (enabled()) { then(true); return }
        Thread {
            val script = "ime enable '$id' >/dev/null 2>&1; " +
                "cur=\$(settings get secure enabled_input_methods); " +
                "case \":\$cur:\" in *':$id:'*|*':$id;'*) ;; *) " +
                "if [ -z \"\$cur\" ] || [ \"\$cur\" = null ]; then settings put secure enabled_input_methods '$id'; " +
                "else settings put secure enabled_input_methods \"\$cur:$id\"; fi ;; esac"
            runAyaRootCommand(script)
            var ok = false
            repeat(10) { if (!ok) { ok = enabled(); if (!ok) Thread.sleep(200) } }
            if (!ok) {
                runRootCommand(script)
                repeat(10) { if (!ok) { ok = enabled(); if (!ok) Thread.sleep(200) } }
            }
            Log.i("PocketHubSetup", "keyboard enabled=$ok id=$id")
            runOnUiThread { then(ok) }
        }.apply { isDaemon = true; start() }
    }

    /** First launch: ask whether the AYANEO button should open PocketHub. */
    private fun maybeAskAyaButtonTakeover() {
        if (!PocketHubButtonTakeover.needsFirstLaunchChoice(this)) return
        val prefs = getSharedPreferences(AyaButtonAccessibilityService.PREFS, Context.MODE_PRIVATE)
        showSimpleActionDialog(tr(
            "Open PocketHub with the AYANEO button?\n\nPocketHub turns on its accessibility service and turns off AYANEO Home and AYANEO Window button services. You can change this later in Settings → Button & components.",
            "Открывать PocketHub кнопкой AYANEO?\n\nPocketHub включит свою службу специальных возможностей и отключит службы кнопки AYANEO Home и AYANEO Window. Это можно изменить позже в Настройках → Кнопка и компоненты.",
            "PocketHub openen met de AYANEO-knop?\n\nPocketHub zet zijn toegankelijkheidsservice aan en de knopservices van AYANEO Home en AYANEO Window uit. Later te wijzigen in Instellingen → Knop en onderdelen.",
            "¿Abrir PocketHub con el botón AYANEO?\n\nPocketHub activa su servicio de accesibilidad y desactiva los servicios de botón de AYANEO Home y AYANEO Window. Puedes cambiarlo en Ajustes → Botón y componentes."
        ), listOf(
            tr("Yes, use the AYANEO button", "Да, использовать кнопку AYANEO", "Ja, AYANEO-knop gebruiken", "Sí, usar el botón AYANEO") to {
                prefs.edit().putBoolean(AyaButtonAccessibilityService.KEY_ENABLED, true).apply()
                setNativeAccessibilityServiceForTakeover(true)
                if (advanced) buildScreen()
            },
            tr("No, keep AYANEO's button", "Нет, оставить кнопку AYANEO как есть", "Nee, AYANEO-knop houden", "No, mantener el botón AYANEO") to {
                prefs.edit().putBoolean(AyaButtonAccessibilityService.KEY_ENABLED, false).apply()
            }
        ))
    }

    override fun onPause() {
        if (popupMode) { super.onPause(); return }
        stopLiveRefresh()
        unregisterBatteryReceiverIfNeeded()
        super.onPause()
    }

    override fun onDestroy() {
        if (popupMode) {
            if (popupInstance?.get() === this) popupInstance = null
            super.onDestroy()
            return
        }
        if (liveInstance?.get() === this) liveInstance = null
        stopGameWatch()
        stopCpuLimitEnforcer()
        stopGpuLimitEnforcer()
        unregisterBatteryReceiverIfNeeded()
        val audioBridge = rootAudioBridge
        rootAudioBridge = null
        if (audioBridge != null) Thread({
            try { audioBridge.close() } catch (_: Exception) { }
        }, "AyaneoRootAudio-close").apply { isDaemon = true; start() }
        releaseOemAidl()
        super.onDestroy()
    }

    private fun registerBatteryReceiverIfNeeded() {
        if (batteryReceiverRegistered) return
        try {
            val sticky = registerReceiver(batteryChangedReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null && latestBatteryIntent == null) latestBatteryIntent = Intent(sticky)
            batteryReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("AyaneoOverlay", "Battery change receiver registration failed", e)
        }
    }

    private fun unregisterBatteryReceiverIfNeeded() {
        if (!batteryReceiverRegistered) return
        try { unregisterReceiver(batteryChangedReceiver) } catch (_: Exception) { }
        batteryReceiverRegistered = false
    }

    private fun startLiveRefresh() {
        stopLiveRefresh()
        val task = object : Runnable {
            override fun run() {
                refreshLiveValues()
                refreshHandler.postDelayed(this, 2000L)
            }
        }
        refreshRunnable = task
        refreshHandler.postDelayed(task, 2000L)
        startFpsPoller()
    }

    /**
     * FPS is sampled once a second. Finding the game's SurfaceFlinger layer is expensive,
     * so the layer is resolved at most every 5 s and only its frame timestamps are read
     * on each tick (one root call per second).
     */
    private fun startFpsPoller() {
        stopFpsPoller()
        val task = object : Runnable {
            override fun run() {
                val onDashboard = !advanced && !controlPage && !favoritesPage && !quickPage && !tuningPage && !controllerPage && !profileEditorPage && !helpPage
                if (onDashboard && fpsValueView != null && !fpsSampling) {
                    fpsSampling = true
                    Thread {
                        val fps = try { readMainDisplayFpsFast() } catch (_: Exception) { null }
                        runOnUiThread {
                            fpsSampling = false
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            cachedMainDisplayFps = fps
                            fpsValueView?.text = fps?.let { "$it FPS" } ?: "— FPS"
                            fpsHistory.addLast(fps ?: 0)
                            while (fpsHistory.size > FpsGraphView.SLOTS) fpsHistory.removeFirst()
                            fpsGraphView?.setValues(fpsHistory)
                        }
                    }.apply { isDaemon = true; start() }
                }
                refreshHandler.postDelayed(this, FPS_POLL_MS)
            }
        }
        fpsRunnable = task
        refreshHandler.post(task)
    }

    private fun stopFpsPoller() {
        fpsRunnable?.let { refreshHandler.removeCallbacks(it) }
        fpsRunnable = null
    }

    private fun readMainDisplayFpsFast(): Int? {
        val now = SystemClock.elapsedRealtime()
        var layer = fpsLayer
        if (layer == null || now - lastFpsSampleElapsedMs > 5_000L) {
            lastFpsSampleElapsedMs = now
            layer = resolveFpsLayer()
            fpsLayer = layer
        }
        if (layer == null) return null
        val fps = fpsFromLayer(layer, windowNs = 1_000_000_000L)
        if (fps == null) fpsLayer = null
        return fps
    }

    /** The focused app's busiest SurfaceFlinger layer on the main display. */
    private fun resolveFpsLayer(): String? {
        val windowDump = rootRead("dumpsys window displays 2>/dev/null") ?: return null
        val displayBlock = Regex("""(?s)Display: mDisplayId=0(.*?)(?=\n\s*Display: mDisplayId=|\z)""")
            .find(windowDump)?.groupValues?.getOrNull(1) ?: windowDump
        val packageName = (displayBlock.lineSequence() + windowDump.lineSequence())
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            .mapNotNull { line ->
                Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""").find(line)?.groupValues?.getOrNull(1)
            }.firstOrNull { it != packageNameSelf() && it.contains('.') } ?: return null
        val layers = rootRead("dumpsys SurfaceFlinger --list 2>/dev/null")?.lineSequence()
            ?.filter { it.contains(packageName) && !it.contains("com.example.ayaneooverlay") }
            ?.toList().orEmpty()
        if (layers.isEmpty()) return null
        val preferred = layers.filter { it.contains("SurfaceView", true) || it.contains("BLAST", true) || it.contains("BufferQueue", true) }
            .ifEmpty { layers }
        return preferred.take(5).maxByOrNull { fpsFromLayer(it, windowNs = 2_000_000_000L) ?: -1 }
    }

    /** Frames presented in the last [windowNs] on one layer, as frames per second. */
    private fun fpsFromLayer(layer: String, windowNs: Long): Int? {
        val escaped = layer.replace("'", "'\\''")
        val dump = rootRead("dumpsys SurfaceFlinger --latency '$escaped' 2>/dev/null") ?: return null
        val timestamps = dump.lineSequence().drop(1).mapNotNull { line ->
            val ts = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (ts <= 0L || ts >= Long.MAX_VALUE / 2) null else ts
        }.distinct().sorted().toList()
        if (timestamps.size < 2) return 0
        val newest = timestamps.last()
        // A game that stopped drawing has no recent frames: report 0 instead of a stale rate.
        val nowNs = System.nanoTime()
        if (nowNs - newest > windowNs) return 0
        val recent = timestamps.filter { it >= newest - windowNs }
        if (recent.size < 2) return 0
        val span = recent.last() - recent.first()
        if (span <= 0L) return null
        val fps = (recent.size - 1) * 1_000_000_000.0 / span
        return fps.takeIf { it in 0.0..240.0 }?.let { kotlin.math.round(it).toInt() }
    }

    private fun stopLiveRefresh() {
        stopFpsPoller()
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        refreshRunnable = null
    }

    /** Refresh live telemetry off the UI thread; chart history is kept in-memory for ~60 seconds. */
    private fun refreshLiveValues() {
        if (isFinishing || isDestroyed || telemetryRunning) return
        telemetryRunning = true
        Thread {
            try {
                // One root shell gathers the device-specific metrics, avoiding a flood of su
                // processes while the dashboard is left open.
                val metrics = readRootTelemetry()
                val cpuUsage = readCpuUsagePercent()
                val gpuUsage = readGpuUsagePercent(metrics["gpu_busy"])
                val display = displayHz()
                val memory = memoryInfo()
                val swap = swapInfo()
                val storage = storageInfo()
                val nowElapsed = SystemClock.elapsedRealtime()
                val battery = batterySummary()
                // FPS has its own faster poller (startFpsPoller).
                val cpuFreq = metrics["cpu_freq_khz"]?.toFloatOrNull()?.div(1000f)
                val gpuFreq = metrics["gpu_freq_hz"]?.toFloatOrNull()?.div(1_000_000f)
                val cpuTemp = formatThermal(metrics["cpu_temp"])
                val gpuTemp = formatThermal(metrics["gpu_temp"])
                val fanRpm = Regex("\\d+").find(metrics["fan_rpm"].orEmpty())?.value?.toIntOrNull()
                val fan = fanRpm?.let { "$it RPM" } ?: "RPM —"
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    cpuValueView?.text = cpuFreq?.let { String.format(Locale.US, "%.0f MHz", it) } ?: "— MHz"
                    gpuValueView?.text = gpuFreq?.let { String.format(Locale.US, "%.0f MHz", it) } ?: "— MHz"
                    cpuTempValueView?.text = cpuTemp.removePrefix("Temp ")
                    gpuTempValueView?.text = gpuTemp.removePrefix("Temp ")
                    cpuTempValueView?.let { colorTemperature(it, metrics["cpu_temp"]) }
                    gpuTempValueView?.let { colorTemperature(it, metrics["gpu_temp"]) }
                    cpuUsageValueView?.text = cpuUsage?.let { "${it.toInt()}%" } ?: "—"
                    gpuUsageValueView?.text = gpuUsage?.let { "${it.toInt()}%" } ?: "—"
                    cpuUsageMeterView?.progress = cpuUsage?.toInt() ?: 0
                    gpuUsageMeterView?.progress = gpuUsage?.toInt() ?: 0
                    displayValueView?.text = display
                    memoryValueView?.text = memory
                    swapValueView?.text = swap
                    ramRingView?.setPercent(percentFromFraction(memory))
                    ramUsageMeterView?.progress = percentFromFraction(memory) ?: 0
                    storageValueView?.text = storage
                    storageRingView?.setPercent(percentFromFraction(storage))
                    storageUsageMeterView?.progress = percentFromFraction(storage) ?: 0
                    auroraFanIcon?.animated = (fanRpm ?: 0) > 0
                    val batteryParts = battery.split("||")
                    batteryRingView?.setPercent(batteryParts.getOrNull(0)?.removeSuffix("%")?.toIntOrNull())
                    batteryDetailValueView?.text = batteryParts.getOrNull(1) ?: "—"
                    batteryPowerValueView?.text = batteryParts.getOrNull(2) ?: "— W"
                    batteryRuntimeValueView?.text = batteryParts.getOrNull(3) ?: "—"
                    fanValueView?.text = fan
                }
            } finally { telemetryRunning = false }
        }.start()
    }

    /** Collect exact sysfs readings from the Pocket DS in one privileged shell. */
    private fun readRootTelemetry(): Map<String, String> {
        val script = """
            cpu_freq=; for f in /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; do [ -r "${'$'}f" ] && cpu_freq=${'$'}(cat "${'$'}f") && break; done
            gpu_freq=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null)
            if [ -z "${'$'}gpu_freq" ] || [ "${'$'}gpu_freq" = 0 ]; then gpu_freq=${'$'}(for f in /sys/class/devfreq/*/cur_freq; do case "${'$'}f" in *kgsl*|*3d00000*) cat "${'$'}f" 2>/dev/null; break;; esac; done); fi
            fan_rpm=${'$'}(cat /sys/devices/platform/soc/soc:pwm-fan/fan_rpm_state 2>/dev/null)
            gpu_busy=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)
            cpu_temp=; gpu_temp=
            for z in /sys/class/thermal/thermal_zone*; do
              [ -r "${'$'}z/type" ] && [ -r "${'$'}z/temp" ] || continue
              IFS= read -r t < "${'$'}z/type"
              case "${'$'}t" in
                *cpu*|*cluster*|*silver*|*gold*) if [ -z "${'$'}cpu_temp" ]; then IFS= read -r cpu_temp < "${'$'}z/temp"; fi ;;
              esac
              case "${'$'}t" in
                *gpu*|*gpuss*|*kgsl*|*graphics*) if [ -z "${'$'}gpu_temp" ]; then IFS= read -r gpu_temp < "${'$'}z/temp"; fi ;;
              esac
              [ -n "${'$'}cpu_temp" ] && [ -n "${'$'}gpu_temp" ] && break
            done
            echo cpu_freq_khz=${'$'}cpu_freq
            echo gpu_freq_hz=${'$'}gpu_freq
            echo fan_rpm=${'$'}fan_rpm
            echo gpu_busy=${'$'}gpu_busy
            echo cpu_temp=${'$'}cpu_temp
            echo gpu_temp=${'$'}gpu_temp
        """.trimIndent()
        val raw = rootRead(script) ?: return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }

    /** Temperature digits: normal colour, yellow from 65 °C, red from 85 °C. */
    private fun colorTemperature(view: TextView, raw: String?) {
        val value = raw?.toDoubleOrNull()
        val celsius = value?.let { if (it > 1000.0) it / 1000.0 else it }
        val normal = view.tag as? Int ?: white
        view.setTextColor(when {
            celsius == null -> normal
            celsius >= 85.0 -> Color.rgb(255, 72, 72)
            celsius >= 65.0 -> Color.rgb(255, 204, 0)
            else -> normal
        })
    }

    private fun formatThermal(raw: String?): String {
        val temp = raw?.toDoubleOrNull() ?: return "Temp —"
        val celsius = if (temp > 1000.0) temp / 1000.0 else temp
        return String.format(Locale.US, "%.1f°C", celsius)
    }

    /** CPU utilisation from the kernel aggregate counter; null until a second sample exists. */
    private fun readCpuUsagePercent(): Float? {
        return try {
            val first = java.io.File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val values = first.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.sum()
            val previousTotal = lastCpuTotal
            val previousIdle = lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idle
            if (previousTotal == null || previousIdle == null || total <= previousTotal) null
            else (((total - previousTotal) - (idle - previousIdle)).toFloat() * 100f / (total - previousTotal)).coerceIn(0f, 100f)
        } catch (_: Exception) { null }
    }

    /** KGSL reports busy and total time counters on common Qualcomm firmware. */
    private fun readGpuUsagePercent(raw: String?): Float? {
        val text = raw ?: return null
        val nums = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.toList()
        if (nums.size < 2 || nums[1] <= 0L) return null
        return (nums[0].toDouble() * 100.0 / nums[1]).toFloat().coerceIn(0f, 100f)
    }

    private fun buildScreen(homeTransitionDirection: Int = 0) {
        Toast.setColors(cardBg2, mint, white, border)
        val restoreScrollY = if ((advanced || (controllerPage && devicePage)) && ::screenScroll.isInitialized) screenScroll.scrollY else null
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = when (layoutTheme) {
                "synthwave" -> SynthwaveBackdrop()
                "brutal", "brutal_dark" -> DotGridBackdrop()
                "pop" -> AuroraBackdrop(bg, intArrayOf(Color.rgb(255, 64, 160), Color.rgb(255, 150, 60), Color.rgb(130, 90, 255), Color.rgb(40, 210, 255)), glowAlpha = 190)
                "aurora" -> AuroraBackdrop(bg, intArrayOf(Color.rgb(40, 220, 180), Color.rgb(130, 80, 255), Color.rgb(255, 80, 180), Color.rgb(60, 140, 255)))
                "dusk" -> duskBackdrop()
                "obsidian" -> ObsidianBackdrop(bg, mint, cyan)
                "blueprint" -> BlueprintGridBackdrop(bg, mint, resources.displayMetrics.density)
                "cartridge" -> CartridgeBackdrop(bg, white, resources.displayMetrics.density)
                "abyss" -> AbyssBackdrop(bg, mint, cyan, resources.displayMetrics.density)
                "hifi" -> HiFiBackdrop(bg, resources.displayMetrics.density)
                "hud" -> HudBackdrop(bg, mint, resources.displayMetrics.density)
                "bauhaus" -> BauhausBackdrop(bg, resources.displayMetrics.density)
                "jade" -> JadeBackdrop(bg, mint, resources.displayMetrics.density)
                "chrono" -> GradientDrawable().apply {
                    // Chronograph: still, almost black; a faint warm light from the top like a lit dial.
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    colors = intArrayOf(Color.rgb(22, 19, 14), bg)
                    gradientRadius = resources.displayMetrics.heightPixels * 0.95f
                    setGradientCenter(0.5f, 0f)
                }
                "bridge" -> android.graphics.drawable.ColorDrawable(bg)
                "volcanic" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(43, 16, 9), bg, Color.rgb(12, 8, 9)))
                "eightbit" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 11, 26), bg, Color.rgb(12, 20, 38)))
                "eightbit" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(7, 11, 26), bg, Color.rgb(12, 20, 38)))
                "solstice" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(222, 211, 184), bg, Color.rgb(245, 239, 218)))
                else -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(15, 24, 48), bg, Color.rgb(4, 6, 13)))
            }
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.TRANSPARENT); isFillViewport = true }
        screenScroll = scroll
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Start with the controls, not a large brand/title banner: the Pocket DS screen is short.
            val pad = if (layoutTheme == "compact") 8 else 11
            setPadding(dp(pad), dp(5), dp(pad), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.removeAllViews()
        profileButtons.clear()
        profileStatusValueView = null
        retroProfileTitleView = null
        rootAccessBadge = null
        batteryRingView = null
        storageRingView = null
        ramRingView = null
        auroraFanIcon = null
        fpsGraphView = null
        swapValueView = null
        brightnessSlidersContainer = null

        if (advanced) {
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(tr("SETTINGS & PERSONALIZATION", "НАСТРОЙКИ И ВНЕШНИЙ ВИД", "INSTELLINGEN EN WEERGAVE", "AJUSTES Y APARIENCIA"), 13f, mint, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(pill(tr("‹  HOME", "‹  ГЛАВНАЯ", "‹  START", "‹  INICIO"), cardBg2, white) { advanced = false; buildScreen(homeTransitionDirection = -1) })
            content.addView(top, margin(bottom = 10))
            buildAdvanced()
        } else if (controllerPage) {
            if (devicePage) buildDeviceSettingsPage() else buildControllerSettings()
        } else if (profileEditorPage) {
            buildProfileEditor()
        } else if (helpPage) {
            buildHelpGuide()
        } else if (tuningPage) {
            buildTuningPage()
        } else if (quickPage) {
            buildQuickPage()
        } else if (favoritesPage) {
            buildFavoritesPage()
        } else if (controlPage) {
            buildSecondaryHome()
        } else buildDashboard()
        if (!advanced && !controllerPage && !profileEditorPage && !helpPage) {
            val dock = when {
                quickPage -> quickDock()
                tuningPage -> tuningDock()
                favoritesPage -> favoritesDock()
                controlPage -> secondaryHomeDock()
                else -> dashboardControlDock()
            }
        page.addView(dock, LinearLayout.LayoutParams(-1, dp(if (layoutTheme == "compact") 82 else if (layoutTheme == "obsidian" || layoutTheme == "chrono" || layoutTheme == "blueprint" || layoutTheme == "cartridge" || layoutTheme == "abyss" || layoutTheme == "hifi" || layoutTheme == "hud" || layoutTheme == "bauhaus" || layoutTheme == "jade") 97 else 88)))
        }
        if (homeTransitionDirection != 0) {
            val host = findViewById<android.widget.FrameLayout>(android.R.id.content)
            val oldPage = host?.getChildAt(0)
            if (host != null && oldPage != null) {
                host.removeAllViews()
                val stage = android.widget.FrameLayout(this).apply {
                    clipChildren = true
                    clipToPadding = true
                    setBackgroundColor(Color.TRANSPARENT)
                }
                host.addView(stage, android.widget.FrameLayout.LayoutParams(-1, -1))
                stage.addView(oldPage, android.widget.FrameLayout.LayoutParams(-1, -1))
                stage.addView(page, android.widget.FrameLayout.LayoutParams(-1, -1))
                page.translationX = homeTransitionDirection * resources.displayMetrics.widthPixels.toFloat()
                page.elevation = dp(12).toFloat()
                page.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                stage.post {
                    page.animate()
                        .translationX(0f)
                        .setDuration(320L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                        .withEndAction {
                            page.translationX = 0f
                            page.elevation = 0f
                            page.setLayerType(View.LAYER_TYPE_NONE, null)
                            stage.removeView(oldPage)
                        }
                        .start()
                }
            } else {
                setContentView(page)
            }
        } else {
            setContentView(page)
        }
        if (restoreScrollY != null) {
            scroll.post { scroll.scrollTo(0, restoreScrollY) }
        } else if (!advanced) {
            content.post { (profileButtons[selectedProfile]?.root ?: firstFocusableInTree(content))?.requestFocus() }
        }
        refreshRootAccessBadge()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            homeSwipeStartX = ev.x
            homeSwipeStartY = ev.y
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && !advanced && !controllerPage && !profileEditorPage && !helpPage) {
            val dx = ev.x - homeSwipeStartX
            val dy = ev.y - homeSwipeStartY
            if (kotlin.math.abs(dx) > dp(72) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f) {
                // Home pages: 0 = dashboard, 1 = tools, 2 = favorites, 3 = quick toggles.
                val current = if (quickPage) 4 else if (tuningPage) 3 else if (favoritesPage) 2 else if (controlPage) 1 else 0
                val next = (current + if (dx < 0f) 1 else -1).coerceIn(0, HOME_PAGE_COUNT - 1)
                if (next != current) {
                    showHomePage(next, if (dx < 0f) 1 else -1)
                    return true
                }
            }
        }
        return handled
    }

    private fun showHomePage(index: Int, direction: Int) {
        controlPage = index == 1
        favoritesPage = index == 2
        tuningPage = index == 3
        quickPage = index == 4
        helpPage = false
        buildScreen(homeTransitionDirection = direction)
    }

    private fun pageDots(index: Int): View = label(
        (0 until HOME_PAGE_COUNT).joinToString("    ") { if (it == index) "●" else "○" }, 9f, muted, true
    ).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("monospace", Typeface.BOLD)
        contentDescription = tr("Page ${index + 1} of $HOME_PAGE_COUNT", "Страница ${index + 1} из $HOME_PAGE_COUNT", "Pagina ${index + 1} van $HOME_PAGE_COUNT", "Página ${index + 1} de $HOME_PAGE_COUNT")
    }


    // ---- Game tuning page ----------------------------------------------------------------
    // User profiles for a game or for one game inside an app (GameHub .exe, emulator ROM),
    // auto-tune of CPU / GPU limits and an adaptive mode. Engine: GameTuning.kt.

    private var tuningRefreshTask: Runnable? = null
    private var tuningDetected: DetectedGame? = null
    @Volatile private var tuningDetecting = false

    /** The running PocketHub (the popup window hands profile changes to it). */
    private fun tuningHost(): MainActivity = liveInstance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: this

    private fun noiseLevelName(level: String): String = when (level) {
        NoiseLevel.SILENT -> tr("Silent", "Бесшумно", "Stil", "Silencioso")
        NoiseLevel.QUIET -> tr("Quiet", "Тихо", "Rustig", "Bajo")
        NoiseLevel.BALANCED -> tr("Balanced", "Баланс", "Gebalanceerd", "Equilibrado")
        else -> tr("Maximum", "Максимально", "Maximaal", "Máximo")
    }

    private fun sensorName(sensor: String): String = when (sensor) {
        Thermal.SENSOR_GPU -> "GPU"
        Thermal.SENSOR_CPU -> "CPU"
        Thermal.SENSOR_SKIN -> tr("Body", "Корпус", "Behuizing", "Carcasa")
        else -> tr("Hottest", "Самый горячий", "Heetste", "El más caliente")
    }

    /** "Quiet", "GPU ≤ 75 °C" or "FPS only". */
    private fun goalDescription(goal: TuneGoal?): String {
        goal ?: return ""
        return when (goal.kind) {
            TuneGoal.KIND_NOISE -> tr("Noise: ", "Шум: ", "Geluid: ", "Ruido: ") + noiseLevelName(goal.noise)
            TuneGoal.KIND_TEMP -> sensorName(goal.sensor) + " ≤ ${goal.tempTarget.toInt()} °C"
            TuneGoal.KIND_FPS -> tr("FPS only", "Только FPS", "Alleen FPS", "Solo FPS")
            else -> ""
        }
    }

    private fun tunedSummary(p: TunedProfile): String {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val key = p.profileKey.lowercase(Locale.ROOT)
        fun ghz(policy: String): String {
            val v = prefs.getLong("tune_cpu_${policy}_$key", 0L)
            return if (v <= 0L) "max" else String.format(Locale.US, "%.1f", v / 1_000_000.0)
        }
        val gpu = prefs.getLong("tune_gpu_$key", defaultGpuTarget(p.profileKey)) / 1_000_000
        val limits = "CPU ${ghz("policy0")} · ${ghz("policy3")} · ${ghz("policy7")} GHz · GPU $gpu MHz"
        val noise = goalDescription(p.goal()).let { if (it.isEmpty()) "" else " · $it" }
        return if (p.mode == TunedProfile.MODE_ADAPTIVE)
            tr("Adaptive", "Адаптивный", "Adaptief", "Adaptativo") + " · ${adaptiveTarget(p)} FPS" + noise
        else limits + noise
    }

    private fun adaptiveTarget(p: TunedProfile): Int = when {
        p.targetFps > 0 -> p.targetFps
        p.tunedBaseline > 0 -> p.tunedBaseline
        else -> 60
    }

    private fun tunedBinding(p: TunedProfile): String = when {
        p.pkg == null -> tr("Manual only", "Только вручную", "Alleen handmatig", "Solo manual")
        p.gameKey != null -> appLabel(p.pkg!!) + " → " + (p.gameLabel ?: p.gameKey)
        else -> appLabel(p.pkg!!) + " · " + tr("whole app", "всё приложение", "hele app", "toda la app")
    }

    private fun buildTuningPage() {
        tuningHost().startTunedAutoSwitch()
        val mono = Typeface.create("monospace", Typeface.BOLD)
        content.setPadding(dp(12), dp(12), dp(12), dp(10))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 5 else 18)
        }
        header.addView(label(tr("Game tuning", "Тюнинг игр", "Game-afstemming", "Ajuste de juegos"), 20f, white, true))
        header.addView(label(tr(
            "Profiles for the game on the top screen — also for one game inside GameHub or an emulator.",
            "Профили для игры на верхнем экране — в том числе для отдельной игры внутри GameHub или эмулятора.",
            "Profielen voor de game op het bovenste scherm — ook voor één game in GameHub of een emulator.",
            "Perfiles para el juego de la pantalla superior, también para un juego dentro de GameHub o un emulador."
        ), 10f, muted), margin(top = 2))
        content.addView(header, margin(bottom = 10))

        // --- What runs right now ---
        val now = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        now.addView(label(tr("ON THE TOP SCREEN", "НА ВЕРХНЕМ ЭКРАНЕ", "OP HET BOVENSTE SCHERM", "EN LA PANTALLA SUPERIOR"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 6))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this)
        row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val appText = label(tr("Looking…", "Определяю…", "Zoeken…", "Buscando…"), 15f, white, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val gameText = label("", 11f, mint).apply { maxLines = 2 }
        val profileText = label("", 10f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(appText); texts.addView(gameText); texts.addView(profileText)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val fpsText = label("—", 22f, mint, true).apply { gravity = Gravity.END; typeface = mono }
        row.addView(fpsText, LinearLayout.LayoutParams(dp(78), -2))
        now.addView(row)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("⚡  " + tr("AUTO-TUNE", "АВТОПОДБОР", "AUTO-AFSTEMMEN", "AUTOAJUSTE"), mint, bg) { showAutoTuneDialog() }
            .apply { minHeight = dp(48) }, weight(1f, end = 4))
        actions.addView(pill("＋  " + tr("SAVE PROFILE", "СОХРАНИТЬ ПРОФИЛЬ", "PROFIEL OPSLAAN", "GUARDAR PERFIL"), cardBg2, white) { createTunedProfileManually() }
            .apply { minHeight = dp(48) }, weight(1f, start = 4))
        now.addView(actions, margin(top = 10))
        if (TunedProfiles.isTuned(selectedProfile)) {
            now.addView(pill("↩  " + tr("BACK TO SYSTEM PROFILE", "ВЕРНУТЬ СИСТЕМНЫЙ ПРОФИЛЬ", "TERUG NAAR SYSTEEMPROFIEL", "VOLVER AL PERFIL DEL SISTEMA") +
                " · " + profileDisplayName(systemProfileBeforeTuned()), cardBg2, white) { deactivateTunedProfile() }
                .apply { minHeight = dp(46) }, margin(top = 8))
        }
        content.addView(now, margin(bottom = 10))

        fun showDetected(game: DetectedGame?, fps: Int?) {
            tuningDetected = game
            if (game == null) {
                icon.setImageDrawable(null)
                appText.text = tr("No app on the top screen", "На верхнем экране нет приложения", "Geen app op het bovenste scherm", "No hay app en la pantalla superior")
                gameText.text = tr("Start a game, then come back here.", "Запусти игру и вернись сюда.", "Start een game en kom dan terug.", "Abre un juego y vuelve aquí.")
            } else {
                icon.setImageDrawable(appIcon(game.pkg))
                appText.text = appLabel(game.pkg)
                gameText.text = when (game.source) {
                    "exe" -> "▸ " + (game.gameLabel ?: "") + "  (.exe)"
                    "rom" -> "▸ " + (game.gameLabel ?: "")
                    else -> tr("No separate game recognised inside the app", "Отдельная игра внутри приложения не распознана", "Geen aparte game in de app herkend", "No se reconoce un juego dentro de la app")
                }
            }
            val active = TunedProfiles.byKey(this, selectedProfile)
            profileText.text = tr("Active profile: ", "Активный профиль: ", "Actief profiel: ", "Perfil activo: ") +
                (active?.name ?: profileDisplayName(selectedProfile)) +
                (if (AdaptiveGovernor.isRunning() && AdaptiveGovernor.status.isNotBlank()) "  ·  " + AdaptiveGovernor.status else "")
            fpsText.text = fps?.takeIf { it > 0 }?.let { "$it\nFPS" } ?: "—"
        }
        showDetected(tuningDetected, null)

        // --- Automatic switching ---
        val autoCard = card().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        autoCard.addView(Switch(this).apply {
            text = tr("Switch profiles automatically for the running game", "Автоматически включать профиль запущенной игры", "Automatisch het profiel van de lopende game kiezen", "Cambiar de perfil automáticamente según el juego")
            textSize = 11f; setTextColor(white)
            isChecked = getPreferences(Context.MODE_PRIVATE).getBoolean(TunedProfiles.KEY_AUTO_SWITCH, true)
            setOnCheckedChangeListener { _, on ->
                getPreferences(Context.MODE_PRIVATE).edit().putBoolean(TunedProfiles.KEY_AUTO_SWITCH, on).apply()
                tuningHost().startTunedAutoSwitch()
            }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        autoCard.addView(Switch(this).apply {
            text = tr("Quiet mode — fan one level lower, chip kept cooler (for any profile)", "Тихий режим — вентилятор на уровень тише, чип холоднее (для любого профиля)",
                "Stille modus — ventilator een stand lager, chip koeler (voor elk profiel)", "Modo silencioso: ventilador un nivel más bajo, chip más frío (cualquier perfil)")
            textSize = 11f; setTextColor(white)
            isChecked = quietModeOn()
            setOnCheckedChangeListener { _, on -> setQuietMode(on) }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        content.addView(autoCard, margin(bottom = 12))

        // --- Saved profiles ---
        val list = TunedProfiles.load(this)
        content.addView(label(tr("MY PROFILES", "МОИ ПРОФИЛИ", "MIJN PROFIELEN", "MIS PERFILES") + "  ·  ${list.size}", 10f, muted, true).apply { typeface = mono }, margin(bottom = 6))
        if (list.isEmpty()) {
            content.addView(label(tr(
                "No profiles yet. Start a game and press Auto-tune, or save the current settings as a profile.",
                "Профилей пока нет. Запусти игру и нажми «Автоподбор» или сохрани текущие настройки как профиль.",
                "Nog geen profielen. Start een game en druk op Auto-afstemmen, of sla de huidige instellingen op.",
                "Aún no hay perfiles. Abre un juego y pulsa Autoajuste, o guarda los ajustes actuales como perfil."
            ), 11f, muted), margin(bottom = 8))
        }
        list.forEachIndexed { index, p ->
            val active = selectedProfile == p.profileKey
            val accent = if (active) mint else cyan
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(9))
                isClickable = true; isFocusable = true
                background = roundedBackground(if (active) blendColor(cardBg2, mint, 0.18f) else cardBg2, if (active) mint else border, 16)
                setOnClickListener { if (active) deactivateTunedProfile() else applyTunedProfile(p, manual = true) }
                setOnLongClickListener { showTunedProfileActions(p); true }
                val iconView: View = if (p.pkg != null) ImageView(this@MainActivity).apply { setImageDrawable(appIcon(p.pkg!!)) }
                    else if (usesLivingIcons()) LivingIconView(this@MainActivity, "profiles", accent, animated = active)
                    else label("⚡", 22f, accent, true).apply { gravity = Gravity.CENTER }
                addView(iconView, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label((if (active) "● " else "") + p.name, 13f, white, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(label(tunedBinding(p) + if (p.pkg != null && !p.autoApply) "  ·  " + tr("manual", "вручную", "handmatig", "manual") else "", 10f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(label(tunedSummary(p), 10f, accent).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                addView(pill("⋯", cardBg, white) { showTunedProfileActions(p) }.apply { textSize = 16f }, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginStart = dp(6) })
                enableControllerFocus(this, 16)
            }
            content.addView(item, margin(bottom = if (index == list.lastIndex) 4 else 7))
        }
        content.addView(label(tr(
            "Tap a profile to switch it on; tap the active one (●) again to go back to the system profile. Hold it (or ⋯) to edit, retune or delete.",
            "Нажми на профиль, чтобы включить его; повторное нажатие на активный (●) возвращает системный профиль. Удерживай (или ⋯), чтобы изменить, подобрать заново или удалить.",
            "Tik op een profiel om het aan te zetten; tik nogmaals op het actieve (●) om terug te gaan naar het systeemprofiel. Houd vast (of ⋯) om te bewerken, opnieuw af te stemmen of te verwijderen.",
            "Toca un perfil para activarlo; toca de nuevo el activo (●) para volver al perfil del sistema. Mantén pulsado (o ⋯) para editar, reajustar o eliminar."
        ), 9f, muted), margin(top = 4))

        // Live refresh while this page is open: what runs on top and its frame rate.
        tuningRefreshTask?.let { refreshHandler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                if (!tuningPage || advanced || profileEditorPage || controllerPage || helpPage || isFinishing || isDestroyed) return
                if (rootAccessGranted == true && !tuningDetecting) {
                    tuningDetecting = true
                    Thread {
                        val game = try { GameDetector.detect() } catch (_: Exception) { null }
                        val fps = try { FpsProbe.read(1000L) } catch (_: Exception) { null }
                        try { Thermal.hasSkinSensor() } catch (_: Exception) { }
                        runOnUiThread {
                            tuningDetecting = false
                            if (tuningRefreshTask === this && tuningPage) showDetected(game, fps)
                        }
                    }.apply { isDaemon = true; start() }
                }
                refreshHandler.postDelayed(this, 3000L)
            }
        }
        tuningRefreshTask = task
        refreshHandler.post(task)
    }

    private fun tuningDock(): LinearLayout = dockContainer().apply {
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(dockAction("‹", tr("FAVORITES", "ИЗБРАННОЕ", "FAVORIETEN", "FAVORITOS"),
            tr("Back to favorites", "Назад к избранному", "Terug naar favorieten", "Volver a favoritos")) {
            showHomePage(2, -1)
        }, weight(1f, end = 3))
        actions.addView(dockAction("+", tr("PROFILE", "ПРОФИЛЬ", "PROFIEL", "PERFIL"),
            tr("Save a new profile", "Сохранить новый профиль", "Nieuw profiel opslaan", "Guardar un perfil nuevo")) {
            createTunedProfileManually()
        }, weight(1f, start = 3, end = 3))
        actions.addView(dockAction("⚙", tr("SETTINGS", "НАСТРОЙКИ", "INSTELLINGEN", "AJUSTES"),
            tr("Settings", "Настройки", "Instellingen", "Ajustes")) {
            tuningPage = false
            advanced = true
            controllerPage = false
            buildScreen(homeTransitionDirection = 1)
        }, weight(1f, start = 3))
        addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pageDots(3), LinearLayout.LayoutParams(-1, dp(11)))
    }

    /** Small text-entry window (profile names). */
    private fun askText(title: String, initial: String, onDone: (String) -> Unit) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(title, 13f, mint, true), margin(bottom = 8))
        val input = android.widget.EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setTextColor(white); setHintTextColor(muted); textSize = 15f
            isSingleLine = true
            background = roundedBackground(cardBg2, border, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        panel.addView(input, margin(bottom = 10))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(tr("CANCEL", "ОТМЕНА", "ANNULEREN", "CANCELAR"), cardBg2, white) { dialog.dismiss() }, weight(1f, end = 4))
        buttons.addView(pill(tr("OK", "ОК", "OK", "OK"), mint, bg) {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) { dialog.dismiss(); onDone(value.take(40)) }
        }, weight(1f, start = 4))
        panel.addView(buttons)
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost(dp(480)), -2)
        input.requestFocus()
    }

    /** Copies the effective limits of [from] into the keys of the tuned profile [toKey]. */
    private fun copyProfileSettings(from: String, toKey: String) {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val src = from.lowercase(Locale.ROOT)
        val dst = toKey.lowercase(Locale.ROOT)
        val edit = prefs.edit()
        cpuPolicySteps.keys.forEach { policy ->
            val k = "tune_cpu_${policy}_$src"
            edit.putLong("tune_cpu_${policy}_$dst", if (prefs.contains(k)) prefs.getLong(k, 0L) else prefs.getLong("tune_cpu_$src", defaultCpuTarget(from)))
        }
        edit.putLong("tune_gpu_$dst", prefs.getLong("tune_gpu_$src", defaultGpuTarget(from)))
        edit.putBoolean("tune_gpu_lock_$dst", prefs.getBoolean("tune_gpu_lock_$src", from == "Max"))
        edit.putString("tune_fan_$dst", prefs.getString("tune_fan_$src", fanModeForProfile(from)) ?: fanModeForProfile(from))
        edit.putBoolean(TurboBoost.prefKey(toKey), prefs.getBoolean(TurboBoost.prefKey(from), false))
        edit.apply()
    }

    /** New profile from the current settings, bound to what runs on top (can be changed later). */
    private fun createTunedProfileManually() {
        val game = tuningDetected
        val suggested = game?.gameLabel ?: game?.pkg?.let { appLabel(it) } ?: tr("My profile", "Мой профиль", "Mijn profiel", "Mi perfil")
        askText(tr("Profile name", "Название профиля", "Profielnaam", "Nombre del perfil"), suggested) { name ->
            val profile = TunedProfile(TunedProfiles.newId(), name, game?.pkg, game?.gameKey, game?.gameLabel, autoApply = game != null)
            copyProfileSettings(selectedProfile, profile.profileKey)
            TunedProfiles.upsert(this, profile)
            Toast.makeText(this, tr("Profile saved — set its limits now", "Профиль сохранён — задай его частоты", "Profiel opgeslagen — stel nu de limieten in", "Perfil guardado: ajusta sus límites"), Toast.LENGTH_SHORT).show()
            openTunedProfileEditor(profile)
        }
    }

    private fun openTunedProfileEditor(p: TunedProfile) {
        tunedEditorId = p.id
        gameProfilePackage = null
        editorProfile = p.profileKey
        profileEditorPage = true
        buildScreen(homeTransitionDirection = 1)
    }

    /** Switches to a tuned profile, remembering the profile to return to. */
    internal fun applyTunedProfile(p: TunedProfile, manual: Boolean) {
        val host = tuningHost()
        if (host !== this) { host.runOnUiThread { host.applyTunedProfile(p, manual) }; dismissPopupSoon(); return }
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (!TunedProfiles.isTuned(selectedProfile)) prefs.edit().putString(KEY_PROFILE_BEFORE_TUNED, selectedProfile).apply()
        prefs.edit().putBoolean(KEY_TUNED_AUTO_APPLIED, !manual).apply()
        applyPerformanceProfile(p.profileKey)
        Toast.makeText(this, tr("Profile", "Профиль", "Profiel", "Perfil") + ": " + p.name, Toast.LENGTH_SHORT).show()
        if (tuningPage && !advanced && !profileEditorPage) buildScreen()
    }

    /** The built-in profile a tuned profile returns to. */
    private fun systemProfileBeforeTuned(): String =
        (getPreferences(Context.MODE_PRIVATE).getString(KEY_PROFILE_BEFORE_TUNED, "Balanced") ?: "Balanced")
            .takeUnless { TunedProfiles.isTuned(it) } ?: "Balanced"

    /** Turns the active tuned profile off and goes back to the system profile used before it. */
    internal fun deactivateTunedProfile() {
        val host = tuningHost()
        if (host !== this) { host.runOnUiThread { host.deactivateTunedProfile() }; dismissPopupSoon(); return }
        if (!TunedProfiles.isTuned(selectedProfile)) return
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(KEY_TUNED_AUTO_APPLIED, false).apply()
        restoreProfileBeforeTuned()
        Toast.makeText(this, tr("Back to system profile: ", "Возврат к системному профилю: ", "Terug naar systeemprofiel: ", "Vuelta al perfil del sistema: ") +
            profileDisplayName(selectedProfile), Toast.LENGTH_SHORT).show()
        if (tuningPage && !advanced && !profileEditorPage) buildScreen()
    }

    /** Back to the profile that was active before a tuned profile took over. */
    private fun restoreProfileBeforeTuned() {
        val previous = getPreferences(Context.MODE_PRIVATE).getString(KEY_PROFILE_BEFORE_TUNED, "Balanced") ?: "Balanced"
        applyPerformanceProfile(if (TunedProfiles.isTuned(previous)) "Balanced" else previous)
    }

    private fun dismissPopupSoon() {
        if (popupMode) uiHandler.postDelayed({ activeCustomDialog?.dismiss() }, 150L)
    }

    private fun showTunedProfileActions(p: TunedProfile) {
        val game = tuningDetected
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (selectedProfile == p.profileKey) actions += (tr("Turn off — back to ", "Выключить — вернуть ", "Uitzetten — terug naar ", "Desactivar: volver a ") + profileDisplayName(systemProfileBeforeTuned())) to { deactivateTunedProfile() }
        else actions += tr("Apply now", "Включить сейчас", "Nu toepassen", "Aplicar ahora") to { applyTunedProfile(p, manual = true) }
        actions += tr("Frequencies, fan & Turbo…", "Частоты, вентилятор и турбо…", "Frequenties, ventilator en Turbo…", "Frecuencias, ventilador y Turbo…") to { openTunedProfileEditor(p) }
        actions += tr("Auto-tune again", "Подобрать заново", "Opnieuw afstemmen", "Reajustar") to { showAutoTuneDialog(saveInto = p) }
        actions += (if (p.mode == TunedProfile.MODE_ADAPTIVE) "● " else "") + tr("Mode: adaptive (hold a frame rate)", "Режим: адаптивный (держать FPS)", "Modus: adaptief (FPS vasthouden)", "Modo: adaptativo (mantener FPS)") to {
            val choices = listOf(0, 30, 40, 45, 60, 90, 120)
            showSimpleActionDialog(tr("Frame rate to hold", "Какой FPS держать", "Vast te houden FPS", "FPS a mantener"), choices.map { fps ->
                (if (p.mode == TunedProfile.MODE_ADAPTIVE && p.targetFps == fps) "● " else "") +
                    (if (fps == 0) tr("From auto-tune", "Как при автоподборе", "Uit auto-afstemming", "Del autoajuste") + (if (p.tunedBaseline > 0) " (${p.tunedBaseline})" else " (60)") else "$fps FPS") to {
                    p.mode = TunedProfile.MODE_ADAPTIVE; p.targetFps = fps
                    TunedProfiles.upsert(this, p)
                    if (selectedProfile == p.profileKey) applyPerformanceProfile(p.profileKey)
                    if (tuningPage) buildScreen()
                }
            })
        }
        actions += (if (p.mode != TunedProfile.MODE_ADAPTIVE) "● " else "") + tr("Mode: fixed limits", "Режим: фиксированные частоты", "Modus: vaste limieten", "Modo: límites fijos") to {
            p.mode = TunedProfile.MODE_FIXED
            TunedProfiles.upsert(this, p)
            if (selectedProfile == p.profileKey) applyPerformanceProfile(p.profileKey)
            if (tuningPage) buildScreen()
        }
        actions += tr("Heat / noise limit: ", "Предел тепла / шума: ", "Warmte- / geluidslimiet: ", "Límite de calor / ruido: ") +
            goalDescription(p.goal()).ifBlank { tr("none", "нет", "geen", "ninguno") } to {
            val choices = mutableListOf<Pair<String, TuneGoal?>>()
            listOf(NoiseLevel.SILENT, NoiseLevel.QUIET, NoiseLevel.BALANCED, NoiseLevel.COOLING).forEach { level ->
                val g = TuneGoal(TuneGoal.KIND_NOISE, noise = level)
                choices += goalDescription(g) to g
            }
            val tempOptions = mutableListOf(Thermal.SENSOR_MAX to listOf(70, 75, 80), Thermal.SENSOR_GPU to listOf(70, 75, 80), Thermal.SENSOR_CPU to listOf(75, 80))
            if (Thermal.skinKnown()) tempOptions += Thermal.SENSOR_SKIN to listOf(40, 42, 45)
            tempOptions.forEach { (sensor, temps) ->
                temps.forEach { t ->
                    val g = TuneGoal(TuneGoal.KIND_TEMP, tempTarget = t.toDouble(), sensor = sensor)
                    choices += goalDescription(g) to g
                }
            }
            choices += goalDescription(TuneGoal(TuneGoal.KIND_FPS)) to TuneGoal(TuneGoal.KIND_FPS)
            choices += tr("No limit — fan mode only", "Без предела — только режим вентилятора", "Geen limiet — alleen ventilatormodus", "Sin límite: solo modo de ventilador") to null
            val current = p.goal()
            showSimpleActionDialog(tr("Besides FPS, keep", "Кроме FPS, держать", "Naast FPS behouden", "Además de FPS, mantener"), choices.map { (name, g) ->
                (if (g == current) "● " else "") + name to {
                    p.setGoal(g)
                    TunedProfiles.upsert(this, p)
                    if (g != null) getPreferences(Context.MODE_PRIVATE).edit().putString("tune_fan_${p.profileKey.lowercase(Locale.ROOT)}", g.fanMode()).apply()
                    if (selectedProfile == p.profileKey) applyPerformanceProfile(p.profileKey)
                    if (tuningPage) buildScreen()
                }
            })
        }
        actions += tr("Rename", "Переименовать", "Hernoemen", "Renombrar") to {
            askText(tr("Profile name", "Название профиля", "Profielnaam", "Nombre del perfil"), p.name) { name ->
                p.name = name; TunedProfiles.upsert(this, p); if (tuningPage) buildScreen()
            }
        }
        actions += tr("Bind to…", "Привязать к…", "Koppelen aan…", "Vincular a…") to {
            val bind = mutableListOf<Pair<String, () -> Unit>>()
            if (game?.gameKey != null) bind += (tr("This game: ", "Эта игра: ", "Deze game: ", "Este juego: ") + "${appLabel(game.pkg)} → ${game.gameLabel}") to {
                p.pkg = game.pkg; p.gameKey = game.gameKey; p.gameLabel = game.gameLabel; p.autoApply = true
                TunedProfiles.upsert(this, p); if (tuningPage) buildScreen()
            }
            if (game != null) bind += (tr("Whole app: ", "Всё приложение: ", "Hele app: ", "Toda la app: ") + appLabel(game.pkg)) to {
                p.pkg = game.pkg; p.gameKey = null; p.gameLabel = null; p.autoApply = true
                TunedProfiles.upsert(this, p); if (tuningPage) buildScreen()
            }
            bind += tr("No binding — only by hand", "Без привязки — только вручную", "Niet koppelen — alleen handmatig", "Sin vínculo: solo manual") to {
                p.pkg = null; p.gameKey = null; p.gameLabel = null; p.autoApply = false
                TunedProfiles.upsert(this, p); if (tuningPage) buildScreen()
            }
            showSimpleActionDialog(tr("Bind profile to", "Привязать профиль к", "Profiel koppelen aan", "Vincular perfil a"), bind)
        }
        if (p.pkg != null) actions += (if (p.autoApply) tr("Automatic switching: on", "Автовключение: да", "Automatisch wisselen: aan", "Cambio automático: sí")
            else tr("Automatic switching: off", "Автовключение: нет", "Automatisch wisselen: uit", "Cambio automático: no")) to {
            p.autoApply = !p.autoApply; TunedProfiles.upsert(this, p); if (tuningPage) buildScreen()
        }
        actions += tr("Delete", "Удалить", "Verwijderen", "Eliminar") to {
            showSimpleActionDialog(tr("Delete “${p.name}”?", "Удалить «${p.name}»?", "“${p.name}” verwijderen?", "¿Eliminar «${p.name}»?"), listOf(
                tr("Delete", "Удалить", "Verwijderen", "Eliminar") to {
                    val wasActive = selectedProfile == p.profileKey
                    TunedProfiles.delete(this, p.id)
                    if (wasActive) restoreProfileBeforeTuned()
                    if (tuningPage) buildScreen()
                },
                tr("Keep", "Оставить", "Behouden", "Conservar") to { }
            ))
        }
        showSimpleActionDialog(p.name, actions)
    }

    // ---- Auto-tune window ----

    private fun showAutoTuneDialog(saveInto: TunedProfile? = null, onClosed: (() -> Unit)? = null) {
        if (rootAccessGranted != true) {
            Toast.makeText(this, tr("Auto-tune needs root access.", "Для автоподбора нужен root.", "Auto-afstemmen vereist root.", "El autoajuste requiere root."), Toast.LENGTH_LONG).show()
            onClosed?.invoke()
            return
        }
        val mono = Typeface.create("monospace", Typeface.BOLD)
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(label("⚡  " + tr("AUTO-TUNE CPU & GPU", "АВТОПОДБОР ЧАСТОТ CPU И GPU", "CPU & GPU AUTO-AFSTEMMEN", "AUTOAJUSTE DE CPU Y GPU"), 13f, mint, true), margin(bottom = 8))
        panel.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.setCanceledOnTouchOutside(false)
        var detected: DetectedGame? = null
        var finishedHandled = false
        // A result on screen that is neither saved nor discarded: the test limits are still set.
        var resultPending = false

        fun close() {
            AutoTuner.listener = null
            dialog.dismiss()
        }

        fun noiseName(level: String): String = noiseLevelName(level)

        fun showResult(result: AutoTuner.Result) {
            resultPending = true
            body.removeAllViews()
            val host = tuningHost()
            body.addView(label(tr("Done", "Готово", "Klaar", "Listo"), 16f, white, true), margin(bottom = 4))
            body.addView(label(
                tr("Full speed: ", "На максимуме: ", "Volle snelheid: ", "A tope: ") + "${result.baselineFps} FPS  ·  " +
                    tr("kept: ", "держим: ", "behouden: ", "mantiene: ") + "${result.targetFps} FPS" +
                    (if (result.capped) tr(" (frame cap)", " (ограничение кадров)", " (FPS-limiet)", " (límite de FPS)") else ""),
                11f, muted), margin(bottom = 2))
            body.addView(label(
                goalDescription(result.goal) +
                    (if (result.tempLimit > 0) String.format(Locale.US, "  ·  %s %.0f °C (%s %.0f °C)",
                        tr("max", "макс.", "max", "máx."), result.maxTemp, tr("limit", "предел", "limiet", "límite"), result.tempLimit) else ""),
                11f, muted), margin(bottom = 8))
            fun line(name: String, from: String, to: String) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(label(name, 11f, white), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(label("$from  →  $to", 11f, mint, true).apply { typeface = mono })
                body.addView(r, margin(bottom = 3))
            }
            line("GPU", "${result.maxGpuHz / 1_000_000}", "${result.gpuHz / 1_000_000} MHz")
            listOf("policy7" to tr("CPU prime", "CPU главное ядро", "CPU prime-kern", "CPU núcleo principal"),
                "policy3" to tr("CPU big cores", "CPU большие ядра", "CPU grote kernen", "CPU núcleos grandes"),
                "policy0" to tr("CPU little cores", "CPU малые ядра", "CPU kleine kernen", "CPU núcleos pequeños")).forEach { (policy, name) ->
                val max = result.maxCpu[policy] ?: return@forEach
                val got = result.cpu[policy] ?: return@forEach
                line(name, "${max / 1000}", "${got / 1000} MHz")
            }
            val game = detected
            val existing = saveInto ?: TunedProfiles.load(this).firstOrNull { game != null && it.pkg == game.pkg && it.gameKey == game.gameKey }
            body.addView(label(tr("Profile name", "Название профиля", "Profielnaam", "Nombre del perfil"), 10f, muted, true), margin(top = 10, bottom = 3))
            val nameInput = android.widget.EditText(this).apply {
                setText(existing?.name ?: game?.gameLabel ?: game?.pkg?.let { appLabel(it) } ?: tr("Tuned profile", "Мой профиль", "Afgestemd profiel", "Perfil ajustado"))
                setTextColor(white); textSize = 14f; isSingleLine = true
                background = roundedBackground(cardBg2, border, 12)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            body.addView(nameInput, margin(bottom = 6))
            val autoSwitch = Switch(this).apply {
                text = if (game?.gameKey != null) tr("Switch on automatically for ", "Включать автоматически для ", "Automatisch kiezen voor ", "Activar automáticamente para ") + (game.gameLabel ?: "")
                    else if (game != null) tr("Switch on automatically for ", "Включать автоматически для ", "Automatisch kiezen voor ", "Activar automáticamente para ") + appLabel(game.pkg)
                    else tr("No game recognised — profile is applied by hand", "Игра не распознана — профиль включается вручную", "Geen game herkend — handmatig toepassen", "Juego no reconocido: se aplica a mano")
                textSize = 11f; setTextColor(white)
                isChecked = game != null
                isEnabled = game != null
            }
            body.addView(autoSwitch, LinearLayout.LayoutParams(-1, dp(46)))
            val adaptive = Switch(this).apply {
                text = tr("Adaptive: keep ${result.targetFps} FPS and lower the limits further when possible",
                    "Адаптивный: держать ${result.targetFps} FPS и снижать частоты ещё, когда можно",
                    "Adaptief: ${result.targetFps} FPS vasthouden en waar mogelijk verder verlagen",
                    "Adaptativo: mantener ${result.targetFps} FPS y bajar más cuando se pueda")
                textSize = 11f; setTextColor(white)
                isChecked = existing?.mode == TunedProfile.MODE_ADAPTIVE
            }
            body.addView(adaptive, LinearLayout.LayoutParams(-1, dp(46)))

            fun save(cpu: Map<String, Int>, gpuHz: Long, goal: TuneGoal) {
                resultPending = false
                val profile = existing ?: TunedProfile(TunedProfiles.newId(), "", game?.pkg, game?.gameKey, game?.gameLabel)
                profile.name = nameInput.text.toString().trim().ifBlank { profile.name.ifBlank { "Profile" } }.take(40)
                if (existing == null && game != null) { profile.pkg = game.pkg; profile.gameKey = game.gameKey; profile.gameLabel = game.gameLabel }
                profile.autoApply = autoSwitch.isChecked && profile.pkg != null
                profile.mode = if (adaptive.isChecked) TunedProfile.MODE_ADAPTIVE else TunedProfile.MODE_FIXED
                profile.tunedBaseline = result.targetFps
                profile.setGoal(goal)
                if (existing == null) copyProfileSettings(host.selectedProfile.takeUnless { TunedProfiles.isTuned(it) } ?: "Gaming", profile.profileKey)
                val key = profile.profileKey.lowercase(Locale.ROOT)
                val edit = getPreferences(Context.MODE_PRIVATE).edit()
                cpu.forEach { (policy, value) -> edit.putLong("tune_cpu_${policy}_$key", if (value >= (result.maxCpu[policy] ?: Int.MAX_VALUE)) 0L else value.toLong()) }
                edit.putLong("tune_gpu_$key", gpuHz)
                edit.putBoolean("tune_gpu_lock_$key", false)
                edit.putString("tune_fan_$key", goal.fanMode())
                edit.apply()
                TunedProfiles.upsert(this, profile)
                host.applyTunedProfile(profile, manual = !profile.autoApply)
                close()
            }

            if (result.short) {
                val goal = result.goal
                val warn = Color.rgb(255, 196, 92)
                if (goal.kind == TuneGoal.KIND_TEMP) {
                    body.addView(label(
                        tr("Holding ${goal.tempTarget.toInt()} °C (${sensorName(goal.sensor)}) leaves this game at about ${result.achievedFps} FPS instead of ${result.targetFps}. Choose:",
                            "Чтобы держать ${goal.tempTarget.toInt()} °C (${sensorName(goal.sensor)}), игре остаётся около ${result.achievedFps} FPS вместо ${result.targetFps}. Выбери:",
                            "Om ${goal.tempTarget.toInt()} °C (${sensorName(goal.sensor)}) te houden haalt deze game ongeveer ${result.achievedFps} FPS in plaats van ${result.targetFps}. Kies:",
                            "Para mantener ${goal.tempTarget.toInt()} °C (${sensorName(goal.sensor)}) el juego queda en unos ${result.achievedFps} FPS en vez de ${result.targetFps}. Elige:"),
                        11f, warn), margin(top = 6, bottom = 6))
                    body.addView(pill(tr("Keep ${goal.tempTarget.toInt()} °C · ${result.achievedFps} FPS", "Держать ${goal.tempTarget.toInt()} °C · ${result.achievedFps} FPS",
                        "${goal.tempTarget.toInt()} °C houden · ${result.achievedFps} FPS", "Mantener ${goal.tempTarget.toInt()} °C · ${result.achievedFps} FPS"), mint, bg) {
                        save(result.cpu, result.gpuHz, goal)
                    }.apply { minHeight = dp(46) }, margin(bottom = 6))
                    body.addView(pill(tr("Keep ${result.targetFps} FPS · let it run warmer", "Держать ${result.targetFps} FPS · пусть греется сильнее",
                        "${result.targetFps} FPS houden · mag warmer worden", "Mantener ${result.targetFps} FPS · que se caliente más"), cardBg2, white) {
                        save(result.fpsCpu, result.fpsGpuHz, TuneGoal(TuneGoal.KIND_FPS))
                    }.apply { minHeight = dp(46) }, margin(bottom = 6))
                } else {
                    val louder = NoiseLevel.louder(goal.noise)
                    body.addView(label(
                        tr("At “${noiseName(goal.noise)}” this game holds about ${result.achievedFps} FPS instead of ${result.targetFps}. Choose:",
                            "При «${noiseName(goal.noise)}» игра держит около ${result.achievedFps} FPS вместо ${result.targetFps}. Выбери:",
                            "Op “${noiseName(goal.noise)}” haalt deze game ongeveer ${result.achievedFps} FPS in plaats van ${result.targetFps}. Kies:",
                            "En «${noiseName(goal.noise)}» el juego mantiene unos ${result.achievedFps} FPS en vez de ${result.targetFps}. Elige:"),
                        11f, warn), margin(top = 6, bottom = 6))
                    body.addView(pill(tr("Keep it quiet · ${result.achievedFps} FPS", "Оставить тихо · ${result.achievedFps} FPS", "Stil houden · ${result.achievedFps} FPS", "Mantener bajo · ${result.achievedFps} FPS"), mint, bg) {
                        save(result.cpu, result.gpuHz, goal)
                    }.apply { minHeight = dp(46) }, margin(bottom = 6))
                    if (louder != null) body.addView(pill(tr("Keep ${result.targetFps} FPS · fan “${noiseName(louder)}”", "Держать ${result.targetFps} FPS · вентилятор «${noiseName(louder)}»",
                        "${result.targetFps} FPS houden · ventilator “${noiseName(louder)}”", "Mantener ${result.targetFps} FPS · ventilador «${noiseName(louder)}»"), cardBg2, white) {
                        save(result.fpsCpu, result.fpsGpuHz, if (louder == NoiseLevel.COOLING) TuneGoal(TuneGoal.KIND_FPS) else TuneGoal(TuneGoal.KIND_NOISE, noise = louder))
                    }.apply { minHeight = dp(46) }, margin(bottom = 6))
                }
                body.addView(pill(tr("DISCARD", "НЕ СОХРАНЯТЬ", "WEGGOOIEN", "DESCARTAR"), cardBg2, white) {
                    resultPending = false
                    host.applyPerformanceProfile(host.selectedProfile)
                    close()
                }.apply { minHeight = dp(44) })
                return
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(pill(tr("DISCARD", "НЕ СОХРАНЯТЬ", "WEGGOOIEN", "DESCARTAR"), cardBg2, white) {
                resultPending = false
                host.applyPerformanceProfile(host.selectedProfile)
                close()
            }.apply { minHeight = dp(46) }, weight(1f, end = 4))
            buttons.addView(pill(tr("SAVE & APPLY", "СОХРАНИТЬ И ВКЛЮЧИТЬ", "OPSLAAN EN TOEPASSEN", "GUARDAR Y APLICAR"), mint, bg) {
                save(result.cpu, result.gpuHz, result.goal)
            }.apply { minHeight = dp(46) }, weight(1f, start = 4))
            body.addView(buttons, margin(top = 8))
        }

        fun showError(code: String?) {
            body.removeAllViews()
            val message = when (code) {
                "cancelled" -> tr("Stopped. Your previous profile is back.", "Остановлено. Прежний профиль восстановлен.", "Gestopt. Je vorige profiel is terug.", "Detenido. Se restauró el perfil anterior.")
                "no_frames" -> tr("No frame rate from the top screen. Keep the game running in gameplay (not a menu or pause) and try again.",
                    "Нет кадров с верхнего экрана. Держи игру в самом геймплее (не в меню и не на паузе) и попробуй снова.",
                    "Geen beeldsnelheid van het bovenste scherm. Laat de game spelen (geen menu of pauze) en probeer opnieuw.",
                    "No hay FPS en la pantalla superior. Mantén el juego en partida (no en menú ni pausa) y reinténtalo.")
                "game_changed" -> tr("Another app came to the top screen, so tuning stopped.", "На верхнем экране сменилось приложение, подбор остановлен.", "Er kwam een andere app op het bovenste scherm; afstemmen gestopt.", "Otra app pasó a la pantalla superior; se detuvo el ajuste.")
                else -> tr("Auto-tune failed: ", "Автоподбор не удался: ", "Auto-afstemmen mislukt: ", "El autoajuste falló: ") + (code ?: "?")
            }
            body.addView(label(message, 12f, white), margin(bottom = 10))
            body.addView(pill(tr("CLOSE", "ЗАКРЫТЬ", "SLUITEN", "CERRAR"), cardBg2, white) { close() }.apply { minHeight = dp(44) })
        }

        val stageView = label("", 14f, white, true)
        val detailView = label("", 11f, muted)
        val fpsView = label("—", 34f, mint, true).apply { gravity = Gravity.CENTER; typeface = mono }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(border)
        }
        fun showProgress() {
            body.removeAllViews()
            body.addView(label(tr(
                "Play normally on the top screen (a typical scene, not a menu). PocketHub lowers the limits step by step and watches the frame rate. About 2–3 minutes.",
                "Играй как обычно на верхнем экране (типичная сцена, не меню). PocketHub по шагам снижает частоты и следит за FPS. Около 2–3 минут.",
                "Speel gewoon op het bovenste scherm (een normale scène, geen menu). PocketHub verlaagt de limieten stap voor stap en kijkt naar de FPS. Ongeveer 2–3 minuten.",
                "Juega con normalidad en la pantalla superior (una escena típica, no un menú). PocketHub baja los límites paso a paso y vigila los FPS. Unos 2–3 minutos."
            ), 10f, muted), margin(bottom = 10))
            body.addView(stageView)
            body.addView(detailView, margin(bottom = 4))
            body.addView(fpsView, margin(bottom = 4))
            body.addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply { bottomMargin = dp(12) })
            body.addView(pill(tr("STOP", "СТОП", "STOPPEN", "DETENER"), cardBg2, white) { AutoTuner.cancel() }.apply { minHeight = dp(46) })
        }

        val listener = object : AutoTuner.Listener {
            override fun onProgress(stage: String, detail: String, fps: Int?, percent: Int) {
                stageView.text = stage
                detailView.text = detail
                fpsView.text = fps?.let { "$it FPS" } ?: "…"
                bar.progress = percent
            }
            override fun onFinished(result: AutoTuner.Result?, error: String?) {
                if (finishedHandled) return
                finishedHandled = true
                val host = tuningHost()
                if (result == null) {
                    host.applyPerformanceProfile(host.selectedProfile)
                    showError(error)
                } else showResult(result)
            }
        }

        fun startTuning(tolerance: Int, target: Int, goal: TuneGoal) {
            val host = tuningHost()
            host.stopCpuLimitEnforcer()
            host.stopGpuLimitEnforcer()
            AdaptiveGovernor.stop()
            // Measure at the chosen noise level, so the limits fit the fan you will use.
            host.queueFanMode(goal.fanMode())
            AutoTuner.listener = listener
            showProgress()
            AutoTuner.start(cpuPolicySteps.mapValues { it.value.toList() }, tolerance, target, mapOf(
                "baseline" to tr("Measuring full speed", "Замер на полной скорости", "Volle snelheid meten", "Midiendo a tope"),
                "gpu" to tr("Graphics (GPU)", "Графика (GPU)", "Graphics (GPU)", "Gráficos (GPU)"),
                "prime" to tr("CPU prime core", "CPU главное ядро", "CPU prime-kern", "CPU núcleo principal"),
                "big" to tr("CPU big cores", "CPU большие ядра", "CPU grote kernen", "CPU núcleos grandes"),
                "little" to tr("CPU little cores", "CPU малые ядра", "CPU kleine kernen", "CPU núcleos pequeños"),
                "verify" to tr("Checking the result", "Проверка результата", "Resultaat controleren", "Comprobando el resultado"),
                "soak" to if (goal.kind == TuneGoal.KIND_TEMP) tr("Heat check: target temperature", "Проверка нагрева: целевая температура", "Warmtetest: doeltemperatuur", "Prueba de calor: temperatura objetivo")
                    else tr("Heat check at this noise level", "Проверка нагрева при этом уровне шума", "Warmtetest op dit geluidsniveau", "Prueba de calor con este ruido")
            ), goal)
        }

        fun showSetup() {
            body.removeAllViews()
            val gameLine = label(tr("Looking for the game…", "Ищу игру…", "Game zoeken…", "Buscando el juego…"), 12f, white, true)
            body.addView(gameLine, margin(bottom = 4))
            body.addView(label(tr(
                "Finds the lowest CPU and GPU limits that keep the frame rate. Less heat, a quieter fan and longer battery life. Start it while you are in the game.",
                "Находит самые низкие частоты CPU и GPU, при которых FPS не падает. Меньше нагрев, тише вентилятор, дольше работа от батареи. Запускай, когда ты уже в игре.",
                "Zoekt de laagste CPU- en GPU-limieten die de FPS behouden. Minder warmte, stillere ventilator en langere accuduur. Start het terwijl je in de game bent.",
                "Busca los límites de CPU y GPU más bajos que mantienen los FPS. Menos calor, ventilador más silencioso y más batería. Inícialo ya dentro del juego."
            ), 10f, muted), margin(bottom = 10))
            var tolerance = 5
            var target = 0
            fun choiceRow(parent: LinearLayout, title: String, options: List<Pair<Int, String>>, get: () -> Int, set: (Int) -> Unit) {
                parent.addView(label(title, 10f, muted, true), margin(bottom = 3))
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val views = mutableMapOf<Int, TextView>()
                fun paint() = views.forEach { (v, b) ->
                    val on = v == get()
                    b.background = roundedBackground(if (on) mint else cardBg2, if (on) mint else border, 10)
                    b.setTextColor(if (on) bg else white)
                }
                options.forEachIndexed { i, (v, n) ->
                    val b = pill(n, cardBg2, white) { set(v); paint() }.apply { textSize = 11f; minHeight = dp(40); maxLines = 1 }
                    views[v] = b
                    r.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(5) })
                }
                paint()
                parent.addView(r, margin(bottom = 8))
            }
            // Frame rate to keep: Auto, or any value from 20 to 165 on a slider.
            run {
                val fpsHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fpsHead.addView(label(tr("FRAME RATE TO KEEP", "КАКОЙ FPS ДЕРЖАТЬ", "TE BEHOUDEN FPS", "FPS A MANTENER"), 10f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
                val fpsValue = label("", 13f, mint, true)
                fpsHead.addView(fpsValue)
                body.addView(fpsHead, margin(bottom = 2))
                val fpsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                lateinit var autoPill: TextView
                fun paintFps() {
                    val auto = target == 0
                    fpsValue.text = if (auto) tr("Auto", "Авто", "Auto", "Auto") else "$target FPS"
                    autoPill.background = roundedBackground(if (auto) mint else cardBg2, if (auto) mint else border, 10)
                    autoPill.setTextColor(if (auto) bg else white)
                }
                val fpsSeek = SeekBar(this).apply {
                    max = 165 - 20
                    progress = 60 - 20
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) { target = 20 + progress; paintFps() }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) { target = 20 + (seekBar?.progress ?: 40); paintFps() }
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                styleTouchSlider(fpsSeek, mint, 26, 48)
                autoPill = pill(tr("Auto", "Авто", "Auto", "Auto"), cardBg2, white) { target = 0; paintFps() }.apply { textSize = 11f; minHeight = dp(40) }
                fpsRow.addView(autoPill, LinearLayout.LayoutParams(dp(72), -2))
                fpsRow.addView(fpsSeek, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
                body.addView(fpsRow, margin(bottom = 8))
                paintFps()
            }
            choiceRow(body, tr("ALLOWED DROP (NO FRAME CAP)", "ДОПУСТИМОЕ ПАДЕНИЕ (БЕЗ ОГРАНИЧЕНИЯ FPS)", "TOEGESTANE DALING (ZONDER LIMIET)", "CAÍDA PERMITIDA (SIN LÍMITE)"),
                listOf(3 to "3 %", 5 to "5 %", 10 to "10 %"), { tolerance }) { tolerance = it }

            // Besides the frame rate, keep ONE of: fan noise or a temperature (or neither).
            val startGoal = saveInto?.goal() ?: TuneGoal(TuneGoal.KIND_NOISE, noise = NoiseLevel.BALANCED)
            var kindIdx = when (startGoal.kind) { TuneGoal.KIND_TEMP -> 1; TuneGoal.KIND_FPS -> 2; else -> 0 }
            var noiseIdx = listOf(NoiseLevel.SILENT, NoiseLevel.QUIET, NoiseLevel.BALANCED, NoiseLevel.COOLING).indexOf(startGoal.noise).let { if (it < 0) 2 else it }
            val sensors = mutableListOf(Thermal.SENSOR_MAX, Thermal.SENSOR_GPU, Thermal.SENSOR_CPU)
            var sensorIdx = 0
            var tempValue = if (startGoal.kind == TuneGoal.KIND_TEMP) Math.round(startGoal.tempTarget).toInt() else 75
            val chipTemps = listOf(65, 70, 75, 80, 85)
            val skinTemps = listOf(38, 40, 42, 45)
            val goalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun buildGoalBox() {
                goalBox.removeAllViews()
                when (kindIdx) {
                    0 -> {
                        choiceRow(goalBox, tr("FAN NOISE", "ШУМ ВЕНТИЛЯТОРА", "VENTILATORGELUID", "RUIDO DEL VENTILADOR"),
                            listOf(NoiseLevel.SILENT, NoiseLevel.QUIET, NoiseLevel.BALANCED, NoiseLevel.COOLING).mapIndexed { i, level -> i to noiseName(level) }, { noiseIdx }) { noiseIdx = it }
                        goalBox.addView(label(tr(
                            "The fan stays at this level. PocketHub keeps the chip under its safe temperature (Silent 66 °C, Quiet 78 °C, Balanced 86 °C) by lowering the limits. Maximum: the fan runs at full speed, only overheat protection applies.",
                            "Вентилятор остаётся на этом уровне. PocketHub держит чип ниже безопасной температуры уровня (Бесшумно 66 °C, Тихо 78 °C, Баланс 86 °C), снижая частоты. Максимально — вентилятор на полную, действует только защита от перегрева.",
                            "De ventilator blijft op deze stand. PocketHub houdt de chip onder de veilige temperatuur (Stil 66 °C, Rustig 78 °C, Gebalanceerd 86 °C) door de limieten te verlagen. Maximaal: de ventilator draait op volle snelheid, alleen de oververhittingsbeveiliging geldt.",
                            "El ventilador se queda en este nivel. PocketHub mantiene el chip bajo su temperatura segura (Silencioso 66 °C, Bajo 78 °C, Equilibrado 86 °C) bajando los límites. Máximo: el ventilador a toda velocidad, solo actúa la protección contra sobrecalentamiento."
                        ), 9f, muted), margin(bottom = 8))
                    }
                    1 -> {
                        choiceRow(goalBox, tr("SENSOR", "ДАТЧИК", "SENSOR", "SENSOR"),
                            sensors.mapIndexed { i, sensor -> i to sensorName(sensor) }, { sensorIdx }) {
                            val wasSkin = sensors[sensorIdx] == Thermal.SENSOR_SKIN
                            sensorIdx = it
                            val isSkin = sensors[sensorIdx] == Thermal.SENSOR_SKIN
                            if (wasSkin != isSkin) { tempValue = if (isSkin) 42 else 75; buildGoalBox() }
                        }
                        val isSkin = sensors[sensorIdx] == Thermal.SENSOR_SKIN
                        choiceRow(goalBox, tr("TARGET TEMPERATURE", "ЦЕЛЕВАЯ ТЕМПЕРАТУРА", "DOELTEMPERATUUR", "TEMPERATURA OBJETIVO"),
                            (if (isSkin) skinTemps else chipTemps).map { it to "$it °C" }, { tempValue }) { tempValue = it }
                        goalBox.addView(label(tr(
                            "The fan works as hard as it needs (up to full speed). PocketHub lowers the limits only if even that cannot hold the temperature. GPU suits most games, CPU suits Switch / PS2 emulators" + (if (sensors.contains(Thermal.SENSOR_SKIN)) ", Body is what your hands feel (it reacts slowly, so this run is longer)." else "."),
                            "Вентилятор работает, сколько нужно (до максимума). PocketHub снижает частоты, только если даже так температура не держится. GPU подходит большинству игр, CPU — эмуляторам Switch / PS2" + (if (sensors.contains(Thermal.SENSOR_SKIN)) ", «Корпус» — то, что чувствуют руки (он реагирует медленно, подбор дольше)." else "."),
                            "De ventilator werkt zo hard als nodig (tot vol). PocketHub verlaagt de limieten alleen als zelfs dat de temperatuur niet houdt. GPU past bij de meeste games, CPU bij Switch- / PS2-emulators" + (if (sensors.contains(Thermal.SENSOR_SKIN)) ", Behuizing is wat je handen voelen (reageert traag, dus duurt langer)." else "."),
                            "El ventilador trabaja lo que haga falta (hasta el máximo). PocketHub baja los límites solo si ni así se mantiene la temperatura. GPU sirve para la mayoría de juegos, CPU para emuladores de Switch / PS2" + (if (sensors.contains(Thermal.SENSOR_SKIN)) ", Carcasa es lo que notan tus manos (reacciona lento, tarda más)." else ".")
                        ), 9f, muted), margin(bottom = 8))
                    }
                    else -> goalBox.addView(label(tr(
                        "Only the frame rate counts: the fan cools as hard as it can, the temperature stays within overheat protection. Fastest run.",
                        "Важен только FPS: вентилятор охлаждает изо всех сил, температура — в рамках защиты от перегрева. Самый быстрый подбор.",
                        "Alleen de FPS telt: de ventilator koelt zo hard als hij kan, de temperatuur blijft binnen de oververhittingsbeveiliging. Snelste run.",
                        "Solo cuentan los FPS: el ventilador enfría al máximo y la temperatura queda dentro de la protección. El ajuste más rápido."
                    ), 9f, muted), margin(bottom = 8))
                }
            }
            choiceRow(body, tr("BESIDES FPS, KEEP", "КРОМЕ FPS, ДЕРЖАТЬ", "NAAST FPS BEHOUDEN", "ADEMÁS DE FPS, MANTENER"),
                listOf(0 to tr("Quiet", "Тишину", "Stilte", "Silencio"), 1 to tr("Temperature", "Температуру", "Temperatuur", "Temperatura"), 2 to tr("FPS only", "Только FPS", "Alleen FPS", "Solo FPS")),
                { kindIdx }) { kindIdx = it; buildGoalBox() }
            body.addView(goalBox)
            buildGoalBox()
            // Body sensor only on devices that report one.
            Thread {
                val hasSkin = try { Thermal.hasSkinSensor() } catch (_: Exception) { false }
                runOnUiThread {
                    if (hasSkin && !sensors.contains(Thermal.SENSOR_SKIN)) {
                        sensors.add(Thermal.SENSOR_SKIN)
                        if (startGoal.kind == TuneGoal.KIND_TEMP) sensorIdx = sensors.indexOf(startGoal.sensor).coerceAtLeast(0)
                        if (kindIdx == 1) buildGoalBox()
                    } else if (startGoal.kind == TuneGoal.KIND_TEMP && sensorIdx == 0) {
                        sensorIdx = sensors.indexOf(startGoal.sensor).coerceAtLeast(0)
                        if (kindIdx == 1) buildGoalBox()
                    }
                }
            }.apply { isDaemon = true; start() }

            fun chosenGoal(): TuneGoal = when (kindIdx) {
                0 -> TuneGoal(TuneGoal.KIND_NOISE, noise = listOf(NoiseLevel.SILENT, NoiseLevel.QUIET, NoiseLevel.BALANCED, NoiseLevel.COOLING)[noiseIdx])
                1 -> TuneGoal(TuneGoal.KIND_TEMP, tempTarget = tempValue.toDouble(), sensor = sensors[sensorIdx])
                else -> TuneGoal(TuneGoal.KIND_FPS)
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(pill(tr("CANCEL", "ОТМЕНА", "ANNULEREN", "CANCELAR"), cardBg2, white) { close() }.apply { minHeight = dp(46) }, weight(1f, end = 4))
            buttons.addView(pill("⚡  " + tr("START", "НАЧАТЬ", "STARTEN", "EMPEZAR"), mint, bg) { startTuning(tolerance, target, chosenGoal()) }.apply { minHeight = dp(46) }, weight(1f, start = 4))
            body.addView(buttons, margin(top = 4))
            Thread {
                val game = try { GameDetector.detect() } catch (_: Exception) { null }
                runOnUiThread {
                    detected = game
                    if (game != null) tuningDetected = game
                    gameLine.text = when {
                        game == null -> tr("No app on the top screen — start the game first.", "На верхнем экране нет приложения — сначала запусти игру.", "Geen app op het bovenste scherm — start eerst de game.", "No hay app arriba: abre primero el juego.")
                        game.gameLabel != null -> "${appLabel(game.pkg)} → ${game.gameLabel}"
                        else -> appLabel(game.pkg)
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        if (AutoTuner.running) {
            // Reopened while a run is going: follow it.
            AutoTuner.listener = listener
            showProgress()
        } else showSetup()

        // While tuning, B / Back must not close the window (the run would continue unseen).
        dialog.setOnKeyListener { _, keyCode, event ->
            val back = keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
            if (back && event.action == KeyEvent.ACTION_UP && !AutoTuner.running) close()
            back
        }
        dialog.setOnDismissListener {
            if (activeCustomDialog === dialog) activeCustomDialog = null
            val host = tuningHost()
            if (AutoTuner.running) {
                // Closed from outside (e.g. the button pressed again): stop and put the profile back.
                AutoTuner.listener = object : AutoTuner.Listener {
                    override fun onProgress(stage: String, detail: String, fps: Int?, percent: Int) = Unit
                    override fun onFinished(result: AutoTuner.Result?, error: String?) { host.applyPerformanceProfile(host.selectedProfile) }
                }
                AutoTuner.cancel()
            } else {
                if (AutoTuner.listener === listener) AutoTuner.listener = null
                if (resultPending) host.applyPerformanceProfile(host.selectedProfile)
            }
            if (onClosed != null) uiHandler.postDelayed({ onClosed() }, 150L)
            if (tuningPage && !popupMode) buildScreen()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(dp(720)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    // ---- Automatic switching to tuned profiles ----

    private var tunedAutoSwitchTask: Runnable? = null
    /** Tuned profile switched on automatically for the game that is running now. If the user
     *  leaves it (another profile, or "back to system profile"), it is not forced back until
     *  that game has closed. */
    private var tunedSessionKey: String? = null
    @Volatile private var tunedAutoSwitchBusy = false
    private var tunedMissCount = 0

    /** Every few seconds: if a game with an automatic tuned profile runs on top, switch to it. */
    internal fun startTunedAutoSwitch() {
        tunedAutoSwitchTask?.let { gameWatchHandler.removeCallbacks(it) }
        tunedAutoSwitchTask = null
        if (popupMode) return
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (!prefs.getBoolean(TunedProfiles.KEY_AUTO_SWITCH, true)) return
        if (TunedProfiles.load(this).none { it.autoApply && it.pkg != null }) return
        val task = object : Runnable {
            override fun run() {
                if (tunedAutoSwitchTask !== this || isFinishing || isDestroyed) return
                if (rootAccessGranted == true && !tunedAutoSwitchBusy && !AutoTuner.running) {
                    tunedAutoSwitchBusy = true
                    Thread {
                        val game = try { GameDetector.detect() } catch (_: Exception) { null }
                        val match = TunedProfiles.match(this@MainActivity, game)
                        runOnUiThread {
                            tunedAutoSwitchBusy = false
                            if (tunedAutoSwitchTask !== this) return@runOnUiThread
                            handleTunedDetection(game, match)
                        }
                    }.apply { isDaemon = true; start() }
                }
                gameWatchHandler.postDelayed(this, TUNED_SWITCH_INTERVAL_MS)
            }
        }
        tunedAutoSwitchTask = task
        gameWatchHandler.postDelayed(task, 4000L)
    }

    private fun handleTunedDetection(game: DetectedGame?, match: TunedProfile?) {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (match != null) {
            tunedMissCount = 0
            if (selectedProfile == match.profileKey) { tunedSessionKey = match.profileKey; return }
            // Already switched on for this run of the game and then left by the user: respect that.
            if (tunedSessionKey == match.profileKey) return
            // A favorite's game profile for the whole app stays unless this profile is for one game in it.
            if (isGameProfile(selectedProfile) && match.gameKey == null) return
            if (!TunedProfiles.isTuned(selectedProfile)) prefs.edit().putString(KEY_PROFILE_BEFORE_TUNED, selectedProfile).apply()
            prefs.edit().putBoolean(KEY_TUNED_AUTO_APPLIED, true).apply()
            tunedSessionKey = match.profileKey
            applyPerformanceProfile(match.profileKey)
            Toast.makeText(this, tr("Game profile: ", "Профиль игры: ", "Gameprofiel: ", "Perfil del juego: ") + match.name, Toast.LENGTH_SHORT).show()
            if (tuningPage && !advanced && !profileEditorPage) buildScreen()
            return
        }
        // Nothing matches: the game is gone, so it may switch on again next time.
        if (game == null || TunedProfiles.match(this, game) == null) tunedSessionKey = null
        // Leave an automatically applied profile after two misses in a row.
        if (TunedProfiles.isTuned(selectedProfile) && prefs.getBoolean(KEY_TUNED_AUTO_APPLIED, false)) {
            tunedMissCount++
            if (tunedMissCount >= 2) {
                tunedMissCount = 0
                prefs.edit().putBoolean(KEY_TUNED_AUTO_APPLIED, false).apply()
                restoreProfileBeforeTuned()
                Toast.makeText(this, tr("Game closed: profile restored", "Игра закрыта: профиль восстановлен", "Game gesloten: profiel hersteld", "Juego cerrado: perfil restaurado") +
                    " · " + profileDisplayName(selectedProfile), Toast.LENGTH_SHORT).show()
                if (tuningPage && !advanced && !profileEditorPage) buildScreen()
            }
        } else tunedMissCount = 0
    }

    private fun buildSecondaryHome() {
        val mono = Typeface.create("monospace", Typeface.BOLD)
        // Everything on this page fits on one screen: the rows share the free height by weight
        // (the scroll view fills its viewport), and only scroll if the screen is too short.
        content.setPadding(dp(10), dp(6), dp(10), dp(6))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 5 else 16)
        }
        header.addView(label(tr("More controls", "Дополнительное управление", "Meer bediening", "Más controles"), 15f, white, true))
        content.addView(header, margin(bottom = 6))

        fun livingKindFor(icon: String) = when (icon) {
            "⌨" -> "keyboard"; "🖱" -> "touchpad"; "🖥" -> "pc"; "🎚" -> "mixer"; "⚡" -> "profiles"
            "🎮" -> "controller"; "⚙" -> "cpu"; else -> "star"
        }
        fun iconView(icon: String, accent: Int, sizeDp: Int, animated: Boolean = true): View =
            if (usesLivingIcons()) LivingIconView(this@MainActivity, livingKindFor(icon), accent, animated = animated)
            else label(icon, sizeDp * 0.62f, accent, true).apply { gravity = Gravity.CENTER }

        /** Half-width tile: icon above, title and one-line subtitle below. */
        fun quickTile(icon: String, title: String, subtitle: String, accent: Int, description: String, iconAnimated: Boolean = true, onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = description
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = toolTileBackground(accent, .20f)
                elevation = if (flatTheme()) 0f else dp(4).toFloat()
                minimumHeight = dp(70)
                setOnClickListener { onClick() }
                addView(iconView(icon, accent, 42, iconAnimated), LinearLayout.LayoutParams(-1, dp(42)))
                addView(label(title, 14f, white, true).apply {
                    gravity = Gravity.CENTER; typeface = mono; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, margin(top = 4))
                enableControllerFocus(this, 18)
            }

        fun row(left: View, right: View, weight: Float) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(3) })
            r.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(3) })
            r.minimumHeight = dp(70)
            content.addView(r, LinearLayout.LayoutParams(-1, 0, weight).apply { bottomMargin = dp(6) })
        }
        fun full(tile: View, weight: Float, last: Boolean = false) {
            content.addView(tile, LinearLayout.LayoutParams(-1, 0, weight).apply { bottomMargin = if (last) 0 else dp(6) })
        }

        row(quickTile(
            "⌨", tr("KEYBOARD", "КЛАВИАТУРА", "TOETSENBORD", "TECLADO"),
            tr("Type into the top screen", "Ввод на верхний экран", "Typen op bovenste scherm", "Escribir en la pantalla superior"),
            mint,
            tr("Open the keyboard on the lower screen", "Открыть клавиатуру на нижнем экране", "Toetsenbord openen op onderste scherm", "Abrir el teclado en la pantalla inferior")
        ) { toggleFreeKeyboard() }, quickTile(
            "🖱", tr("TOUCHPAD", "ТАЧПАД", "TOUCHPAD", "PANEL TÁCTIL"),
            tr("Mouse for the top screen", "Мышь для верхнего экрана", "Muis voor bovenste scherm", "Ratón para la pantalla superior"),
            cyan,
            tr("Open a touchpad with mouse buttons on the lower screen", "Открыть тачпад с кнопками мыши на нижнем экране", "Touchpad met muisknoppen openen op onderste scherm", "Abrir un panel táctil con botones de ratón en la pantalla inferior")
        ) { toggleTouchpad() }, 1.3f)

        row(quickTile(
            "🖥", tr("PC KEYBOARD", "PC-КЛАВИАТУРА", "PC-TOETSENBORD", "TECLADO DE PC"),
            tr("Ctrl, Alt, F1–F12", "Ctrl, Alt, F1–F12", "Ctrl, Alt, F1–F12", "Ctrl, Alt, F1–F12"),
            cyan,
            tr("Open a full PC keyboard on the lower screen", "Открыть полную PC-клавиатуру на нижнем экране", "Volledig pc-toetsenbord openen op onderste scherm", "Abrir un teclado de PC completo en la pantalla inferior")
        ) { togglePcKeyboard() }, quickTile(
            "⚡", tr("PROFILES", "ПРОФИЛИ", "PROFIELEN", "PERFILES"),
            tr("Fan · CPU · GPU limits", "Вентилятор · CPU · GPU", "Ventilator · CPU · GPU", "Ventilador · CPU · GPU"),
            cyan,
            tr("Open performance profile settings", "Открыть настройки профилей производительности", "Prestatieprofielen instellen", "Abrir ajustes de perfiles de rendimiento")
        ) {
            profileEditorPage = true
            gameProfilePackage = null; tunedEditorId = null
            editorProfile = selectedProfile.takeUnless { isGameProfile(it) } ?: "Max"
            buildScreen(homeTransitionDirection = 1)
        }, 1.3f)

        val eqOn = PocketHubEqualizer.isEnabled(this)
        row(quickTile(
            "🎮", tr("CONTROLLER", "КОНТРОЛЛЕР", "CONTROLLER", "MANDO"),
            tr("Mode · vibration · layout", "Режим · вибрация · раскладка", "Modus · trilling · indeling", "Modo · vibración · botones"),
            mint,
            tr("Open built-in controller settings", "Открыть настройки встроенного контроллера", "Instellingen van ingebouwde controller openen", "Abrir ajustes del mando integrado")
        ) {
            advanced = false
            devicePage = false
            controllerPage = true
            buildScreen(homeTransitionDirection = 1)
        }, quickTile(
            "🎚", tr("EQUALIZER", "ЭКВАЛАЙЗЕР", "EQUALIZER", "ECUALIZADOR"),
            (if (eqOn) tr("On", "Включён", "Aan", "Activado") else tr("Off", "Выключен", "Uit", "Desactivado")) + " · " +
                tr("Device sound", "Звук устройства", "Apparaatgeluid", "Sonido del equipo"),
            mint,
            tr("Open the sound equalizer", "Открыть эквалайзер звука", "Geluidsequalizer openen", "Abrir el ecualizador"),
            iconAnimated = eqOn
        ) { showEqualizerDialog() }, 1.3f)

        // Device: full width, content centred like the tiles above.
        val deviceTile = quickTile(
            "⚙", tr("DEVICE", "УСТРОЙСТВО", "APPARAAT", "DISPOSITIVO"),
            tr("Buttons · charging · Wi-Fi · monitor · memory", "Кнопки · зарядка · Wi-Fi · монитор · память", "Knoppen · opladen · wifi · monitor · geheugen", "Botones · carga · wifi · monitor · memoria"),
            cyan,
            tr("Open device settings", "Открыть настройки устройства", "Apparaatinstellingen openen", "Abrir ajustes del dispositivo")
        ) {
            advanced = false
            devicePage = true
            controllerPage = true
            buildScreen(homeTransitionDirection = 1)
        }
        full(deviceTile, 1f, last = true)
    }

    /** Device settings, opened from the second home page (shares the controller page's navigation). */
    private fun buildDeviceSettingsPage() {
        controllerChoiceButtons.clear()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(tr("DEVICE", "УСТРОЙСТВО", "APPARAAT", "DISPOSITIVO"), 17f, mint, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill(tr("‹  BACK", "‹  НАЗАД", "‹  TERUG", "‹  ATRÁS"), cardBg2, white) {
            controllerPage = false
            devicePage = false
            buildScreen(homeTransitionDirection = -1)
        })
        content.addView(header, margin(bottom = 10))

        content.addView(settingsSection("⌘", tr("Buttons", "Кнопки", "Knoppen", "Botones"),
            tr("Remap the extra physical buttons", "Переназначение дополнительных кнопок", "Extra fysieke knoppen toewijzen", "Reasignar los botones físicos extra")), margin(bottom = 6))
        content.addView(buttonRemapCard(), margin(bottom = 16))

        content.addView(settingsSection("▣", tr("Device", "Аппарат", "Apparaat", "Equipo"),
            tr("Charging, Wi-Fi and external monitor", "Зарядка, Wi-Fi и внешний монитор", "Opladen, wifi en externe monitor", "Carga, wifi y monitor externo")), margin(bottom = 6))
        content.addView(deviceSettingsCard(), margin(bottom = 16))

        content.addView(settingsSection("▤", tr("Memory", "Память", "Geheugen", "Memoria"),
            tr("Virtual memory (swap)", "Виртуальная память (swap)", "Virtueel geheugen (swap)", "Memoria virtual (swap)")), margin(bottom = 6))
        content.addView(virtualMemorySettingsCard(), margin(bottom = 16))
    }

    /** Help topics the user has opened (reset each time help is opened). */
    private val helpExpanded = HashSet<String>()
    private var helpQuery = ""

    /**
     * Interactive help: search box plus one expandable card per feature. Content lives in
     * HelpContent (all eight UI languages); English is the fallback.
     */
    private fun buildHelpGuide() {
        val mono = Typeface.create("monospace", Typeface.BOLD)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label(tr("POCKETHUB HELP", "СПРАВКА POCKETHUB", "POCKETHUB-HULP", "AYUDA DE POCKETHUB"), 15f, mint, true).apply { typeface = mono })
        heading.addView(label(tr(
            "Tap a topic to open it",
            "Нажми на тему, чтобы раскрыть её",
            "Tik op een onderwerp om het te openen",
            "Toca un tema para abrirlo"
        ), 10f, muted).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill(tr("‹  BACK", "‹  НАЗАД", "‹  TERUG", "‹  ATRÁS"), cardBg2, white) {
            helpPage = false
            buildScreen(homeTransitionDirection = -1)
        })
        content.addView(header, margin(bottom = 8))

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = android.widget.EditText(this).apply {
            hint = "⌕  " + tr("Search help", "Поиск по справке", "Zoeken in hulp", "Buscar en la ayuda")
            setSingleLine(true)
            setText(helpQuery)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 13f
            background = roundedBackground(cardBg2, border, 12)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        tools.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toggleAll = pill(tr("OPEN ALL", "РАСКРЫТЬ ВСЁ", "ALLES OPENEN", "ABRIR TODO"), cardBg2, white) { }.apply { textSize = 10f }
        tools.addView(toggleAll, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(6) })
        content.addView(tools, margin(bottom = 8))
        // The search box is the only touch-focusable view here, so Android hands it focus on
        // its own. A focused EditText pulls the ScrollView back to itself on every relayout
        // (opening a topic), which made the list jump to the top. The counter label acts as a
        // quiet focus holder so the search box only has focus while the user is typing.
        val countView = label("", 9f, muted).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        content.addView(countView, margin(bottom = 6))
        fun releaseSearchFocus() {
            if (!search.hasFocus()) return
            countView.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(search.windowToken, 0)
        }
        search.setOnEditorActionListener { _, _, _ -> releaseSearchFocus(); true }
        content.addView(list)

        var visibleTopics: List<HelpContent.Topic> = emptyList()
        fun updateToggleLabel() {
            val allOpen = visibleTopics.isNotEmpty() && visibleTopics.all { it.id in helpExpanded }
            toggleAll.text = if (allOpen) tr("CLOSE ALL", "СВЕРНУТЬ ВСЁ", "ALLES SLUITEN", "CERRAR TODO")
                else tr("OPEN ALL", "РАСКРЫТЬ ВСЁ", "ALLES OPENEN", "ABRIR TODO")
        }
        // A card opens and closes in place (no view is removed), so the tapped card keeps
        // focus and the ScrollView does not jump to the search box at the top.
        fun addCard(topic: HelpContent.Topic, index: Int, searching: Boolean) {
            list.addView(helpTopicCard(topic, index, searching) {
                releaseSearchFocus()
                updateToggleLabel()
            }, margin(bottom = 7))
        }

        fun render() {
            list.removeAllViews()
            val query = helpQuery.trim().lowercase(Locale.ROOT)
            val visible = HelpContent.topics.filter { topic ->
                if (query.isEmpty()) return@filter true
                val e = topic.entry(uiLanguage)
                (listOf(e.title, e.summary) + e.bullets).any { it.lowercase(Locale.ROOT).contains(query) }
            }
            countView.text = tr("Topics", "Тем", "Onderwerpen", "Temas") + ": ${visible.size} / ${HelpContent.topics.size}"
            visibleTopics = visible
            updateToggleLabel()
            toggleAll.setOnClickListener {
                val allOpen = visibleTopics.isNotEmpty() && visibleTopics.all { it.id in helpExpanded }
                if (allOpen) visibleTopics.forEach { helpExpanded.remove(it.id) } else visibleTopics.forEach { helpExpanded.add(it.id) }
                releaseSearchFocus()
                val scrollY = screenScroll.scrollY
                render()
                screenScroll.post { screenScroll.scrollTo(0, scrollY) }
            }
            if (visible.isEmpty()) {
                list.addView(label(tr("Nothing found", "Ничего не найдено", "Niets gevonden", "No se encontró nada"), 11f, muted).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            visible.forEachIndexed { index, topic -> addCard(topic, index, query.isNotEmpty()) }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                helpQuery = s?.toString().orEmpty()
                render()
            }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        render()
        content.post { if (search.hasFocus() || content.findFocus() == null) countView.requestFocus() }
    }

    private fun helpTopicCard(topic: HelpContent.Topic, index: Int, searching: Boolean, onChanged: () -> Unit): View {
        val entry = topic.entry(uiLanguage)
        val accents = listOf(mint, cyan, profileAccent("Gaming"), profileAccent("Streaming"), profileAccent("Eco"))
        val accent = accents[index % accents.size]
        val radius = if (layoutTheme == "eightbit") 5 else 16
        val closedBg = roundedBackground(cardBg2, border, radius)
        val openBg = roundedBackground(blendColor(cardBg2, accent, 0.10f), blendColor(border, accent, 0.6f), radius)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(12), dp(9))
            isClickable = true
            isFocusable = true
            contentDescription = entry.title
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val icon = LivingIconView(this@MainActivity, topic.icon, accent, accent, animated = false)
            row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(entry.title, 12f, white, true))
            val summary = label(entry.summary, 9.5f, muted).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(summary)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val arrow = label("▸", 16f, accent, true)
            row.addView(arrow, LinearLayout.LayoutParams(dp(22), -2).apply { marginStart = dp(6) })
            addView(row)
            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(44), dp(6), 0, 0)
            }
            entry.bullets.forEach { bullet ->
                val line = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(label("●", 7f, accent, true).apply { setPadding(0, dp(4), dp(8), 0) })
                line.addView(label(bullet, 10.5f, white).apply { setLineSpacing(dp(2).toFloat(), 1f) }, LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(line, margin(bottom = 5))
            }
            addView(details)
            fun showState(open: Boolean) {
                background = if (open) openBg else closedBg
                summary.maxLines = if (open) 4 else 1
                arrow.text = if (open) "▾" else "▸"
                icon.animated = open && usesLivingIcons()
                details.visibility = if (open) View.VISIBLE else View.GONE
            }
            // While searching, matching topics open automatically so the hit is visible.
            showState(searching || topic.id in helpExpanded)
            setOnClickListener {
                if (topic.id in helpExpanded) helpExpanded.remove(topic.id) else helpExpanded.add(topic.id)
                onChanged()
                showState(searching || topic.id in helpExpanded)
            }
            enableControllerFocus(this, 16)
        }
    }

    private fun buildProfileEditor() {
        editorFanButtons.clear()
        loadCpuPolicyFrequencyStepsFromRoot()
        val mono = Typeface.create("monospace", Typeface.BOLD)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gamePackage = gameProfilePackage
        if (gamePackage != null) editorProfile = gameProfileKey(gamePackage)
        val tuned = if (gamePackage == null) tunedEditorId?.let { id -> TunedProfiles.load(this).firstOrNull { it.id == id } } else null
        if (tuned != null) editorProfile = tuned.profileKey
        header.addView(label(
            if (gamePackage != null) tr("GAME PROFILE", "ПРОФИЛЬ ИГРЫ", "GAMEPROFIEL", "PERFIL DEL JUEGO")
            else if (tuned != null) tr("MY PROFILE", "МОЙ ПРОФИЛЬ", "MIJN PROFIEL", "MI PERFIL")
            else tr("PROFILE TUNING", "НАСТРОЙКА ПРОФИЛЕЙ", "PROFIELINSTELLINGEN", "AJUSTE DE PERFILES"),
            14f, mint, true).apply { typeface = mono }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill(tr("‹  BACK", "‹  НАЗАД", "‹  TERUG", "‹  ATRÁS"), cardBg2, white) {
            profileEditorPage = false
            gameProfilePackage = null; tunedEditorId = null
            buildScreen(homeTransitionDirection = -1)
        })
        content.addView(header, margin(bottom = 8))
        if (gamePackage != null) {
            val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            appRow.addView(ImageView(this).apply { setImageDrawable(appIcon(gamePackage)) }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(appLabel(gamePackage), 15f, white, true))
            texts.addView(label(tr(
                "Applied when you start this app from Favorites.",
                "Включается при запуске этого приложения из Избранного.",
                "Wordt toegepast als je deze app vanuit Favorieten start.",
                "Se aplica al abrir esta app desde Favoritos."
            ), 10f, muted))
            appRow.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(appRow, margin(bottom = 8))
        } else if (tuned != null) {
            val tunedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (tuned.pkg != null) tunedRow.addView(ImageView(this).apply { setImageDrawable(appIcon(tuned.pkg!!)) }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(tuned.name, 15f, white, true))
            texts.addView(label(tunedBinding(tuned) + if (tuned.mode == TunedProfile.MODE_ADAPTIVE) "  ·  " + tr(
                "adaptive: these are the starting limits", "адаптивный: это стартовые частоты", "adaptief: dit zijn de startlimieten", "adaptativo: son los límites iniciales") else "", 10f, muted))
            tunedRow.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(tunedRow, margin(bottom = 8))
        } else {
            content.addView(label(tr("Choose a profile, then tune its fan and frequency limits.", "Выбери профиль и настрой вентилятор и пределы частот.", "Kies een profiel en stel ventilator en frequentielimieten in."), 10f, muted), margin(bottom = 6))
        }

        val profileTabs = linkedMapOf<String, TextView>()
        val profiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (gamePackage == null && tuned == null) listOf("Eco", "Balanced", "Streaming", "Gaming", "Max").forEachIndexed { index, name ->
            val button = pill(profileDisplayName(name), cardBg2, white) {
                if (editorProfile != name) {
                    saveCurrentProfileEditorDraft()
                    editorProfile = name
                    updateProfileEditorControls(profileTabs)
                }
            }.apply { textSize = 10f; minHeight = dp(38); setPadding(dp(3), dp(4), dp(3), dp(4)) }
            profileTabs[name] = button
            profiles.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
        }
        if (gamePackage == null && tuned == null) content.addView(profiles, margin(bottom = 7))

        val schedulerCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        schedulerCard.addView(label(tr("CPU SCHEDULING MODE", "РЕЖИМ ПЛАНИРОВАНИЯ CPU", "CPU-PLANNINGSMODUS", "MODO DE PLANIFICACIÓN CPU"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 5))
        val schedulerChoices = listOf(
            "POWER_SAVING" to tr("Power Saving", "Энергосбережение", "Energiebesparing", "Ahorro de energía"),
            "BALANCED" to tr("Balanced", "Баланс", "Gebalanceerd", "Equilibrado"),
            "HIGH_PERFORMANCE" to tr("Performance", "Производительность", "Prestaties", "Rendimiento")
        )
        val schedulerButtons = linkedMapOf<String, TextView>()
        val schedulerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        schedulerChoices.forEachIndexed { index, (mode, title) ->
            val button = settingsChoice(title, getPreferences(Context.MODE_PRIVATE).getString("cpu_scheduler_mode", "HIGH_PERFORMANCE") == mode) {
                applyCpuSchedulerMode(mode)
                schedulerButtons.forEach { (key, view) ->
                    val active = key == mode
                    view.background = roundedBackground(if (active) mint else cardBg2, if (active) mint else border, 11)
                    view.setTextColor(if (active) bg else white)
                }
            }
            schedulerButtons[mode] = button
            schedulerRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(5) })
        }
        schedulerCard.addView(schedulerRow)
        content.addView(schedulerCard, margin(bottom = 7))

        val fanCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        fanCard.addView(label(tr("FAN MODE", "РЕЖИМ ВЕНТИЛЯТОРА", "VENTILATORMODUS", "MODO DEL VENTILADOR"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 5))
        val fanChoices = listOf(
            "FAN_MODE_OFF" to tr("Off", "Выкл.", "Uit", "Apagado"),
            "FAN_MODE_MUTE" to tr("Low", "Низкий", "Laag", "Bajo"),
            "FAN_MODE_BALANCE" to tr("High", "Высокий", "Hoog", "Alto"),
            "FAN_MODE_TURBO" to tr("Max", "Макс.", "Max", "Máx."),
            "FAN_MODE_CUSTOM" to tr("Custom", "Кривая", "Aangepast", "Personalizado")
        )
        fun fanRow(entries: List<Pair<String, String>>, rowIndex: Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            entries.forEachIndexed { index, (mode, title) ->
                val choice = pill(title, cardBg2, white) {
                    editorFanMode = mode
                    refreshProfileEditorFanButtons()
                }.apply { textSize = 10f; minHeight = dp(38); setPadding(dp(3), dp(4), dp(3), dp(4)) }
                editorFanButtons[mode] = choice
                row.addView(choice, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
            }
            if (entries.size < 3) row.addView(View(this).apply { alpha = 0f }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
            fanCard.addView(row, if (rowIndex == 0) LinearLayout.LayoutParams(-1, -2) else margin(top = 4))
        }
        fanChoices.chunked(3).forEachIndexed { rowIndex, row -> fanRow(row, rowIndex) }
        content.addView(fanCard, margin(bottom = 7))

        editorCpuSeeks.clear()
        editorCpuValues.clear()
        val cpuCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(7)) }
        cpuCard.addView(label(tr("CPU CLUSTER LIMITS", "ЛИМИТЫ КЛАСТЕРОВ CPU", "CPU-CLUSTERLIMIETEN", "LÍMITES DE CLÚSTERES CPU"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 4))
        val cpuClusters = listOf(
            Triple("policy0", "CPU 0–2", cpuPolicySteps.getValue("policy0")),
            Triple("policy3", "CPU 3–6", cpuPolicySteps.getValue("policy3")),
            Triple("policy7", "CPU 7", cpuPolicySteps.getValue("policy7"))
        )
        cpuClusters.forEach { (key, clusterName, frequencies) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(clusterName, 10f, white, true).apply { typeface = mono }, LinearLayout.LayoutParams(dp(67), -2))
            val value = label("— MHz", 11f, mint, true).apply { typeface = mono; gravity = Gravity.END }
            editorCpuValues[key] = value
            row.addView(value, LinearLayout.LayoutParams(dp(66), -2))
            val seek = SeekBar(this).apply {
                max = frequencies.lastIndex
                progressTintList = ColorStateList.valueOf(mint)
                thumbTintList = ColorStateList.valueOf(mint)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        value.text = if (progress == frequencies.lastIndex) tr("MAX", "МАКС.", "MAX", "MÁX.") else "${frequencies[progress.coerceIn(frequencies.indices)] / 1000} MHz"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            editorCpuSeeks[key] = seek
            row.addView(seek, LinearLayout.LayoutParams(0, dp(42), 1f))
            cpuCard.addView(row)
        }
        cpuCard.addView(label(tr("Each slider uses this cluster's supported hardware steps.", "Каждый ползунок использует доступные аппаратные шаги своего кластера.", "Elke schuifregelaar gebruikt de beschikbare hardwarestappen van de cluster."), 9f, muted))
        content.addView(cpuCard, margin(bottom = 7))

        val gpuCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(7)) }
        val gpuHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gpuHeader.addView(label(tr("GPU MAX FREQUENCY", "МАКС. ЧАСТОТА GPU", "MAX. GPU-FREQUENTIE", "FRECUENCIA MÁX. GPU"), 10f, muted, true).apply { typeface = mono }, LinearLayout.LayoutParams(0, -2, 1f))
        editorGpuValue = label("— MHz", 13f, cyan, true).apply { typeface = mono }
        gpuHeader.addView(editorGpuValue)
        gpuCard.addView(gpuHeader)
        val gpuFrequenciesMHz = listOf(124, 220, 295, 348, 401, 475, 550, 615, 680, 719, 746, 794, 827, 860, 1000)
        editorGpuSeek = SeekBar(this).apply {
            max = gpuFrequenciesMHz.lastIndex
            progressTintList = ColorStateList.valueOf(cyan)
            thumbTintList = ColorStateList.valueOf(cyan)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    editorGpuValue?.text = "${gpuFrequenciesMHz[progress.coerceIn(gpuFrequenciesMHz.indices)]} MHz"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        gpuCard.addView(editorGpuSeek, LinearLayout.LayoutParams(-1, dp(40)))
        editorGpuLock = Switch(this).apply {
            text = tr("Lock GPU at selected frequency", "Зафиксировать GPU на выбранной частоте", "GPU vastzetten op gekozen frequentie", "Fijar GPU en frecuencia seleccionada")
            textSize = 10f
            setTextColor(white)
            thumbTintList = ColorStateList.valueOf(mint)
            trackTintList = ColorStateList.valueOf(border)
        }
        gpuCard.addView(editorGpuLock)
        content.addView(gpuCard, margin(bottom = 8))

        val turboCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        turboCard.addView(label(tr("TURBO", "ТУРБО", "TURBO", "TURBO"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 3))
        editorTurbo = Switch(this).apply {
            text = tr("Turbo boost for this profile", "Турбо-буст для этого профиля", "Turbo-boost voor dit profiel", "Turbo para este perfil")
            textSize = 11f
            setTextColor(white)
            thumbTintList = ColorStateList.valueOf(mint)
            trackTintList = ColorStateList.valueOf(border)
            setOnCheckedChangeListener { _, enabled ->
                if (editorUpdating) return@setOnCheckedChangeListener
                getPreferences(Context.MODE_PRIVATE).edit().putBoolean(TurboBoost.prefKey(editorProfile), enabled).apply()
                if (editorProfile == selectedProfile) {
                    if (enabled) TurboBoost.startAsync(this@MainActivity) else TurboBoost.stop(this@MainActivity)
                }
            }
        }
        turboCard.addView(editorTurbo)
        turboCard.addView(label(tr(
            "Keeps CPU and GPU at the top of this profile's limits: performance governor, no parked cores, priority for the app on screen, GPU never naps. Not an overclock. Backs off above ${TurboBoost.HOT_C}°C and returns below ${TurboBoost.COOL_C}°C. Uses more power and heat.",
            "Держит CPU и GPU на верхней границе лимитов профиля: регулятор performance, без парковки ядер, приоритет приложению на экране, GPU не засыпает. Это не разгон. Отключается выше ${TurboBoost.HOT_C}°C и возвращается ниже ${TurboBoost.COOL_C}°C. Больше расход и нагрев.",
            "Houdt CPU en GPU op de bovengrens van dit profiel: performance-governor, geen geparkeerde kernen, voorrang voor de app op het scherm, GPU slaapt niet. Geen overklokken. Schakelt terug boven ${TurboBoost.HOT_C}°C en weer aan onder ${TurboBoost.COOL_C}°C. Meer verbruik en warmte.",
            "Mantiene CPU y GPU en el máximo de este perfil: gobernador performance, sin núcleos aparcados, prioridad a la app en pantalla y la GPU no reposa. No es overclock. Se retira por encima de ${TurboBoost.HOT_C}°C y vuelve por debajo de ${TurboBoost.COOL_C}°C. Más consumo y calor."
        ), 9f, muted), margin(top = 2))
        content.addView(turboCard, margin(bottom = 8))

        if (gamePackage != null) {
            val gameKey = gameProfileKey(gamePackage).lowercase(Locale.ROOT)
            val gamePrefs = getPreferences(Context.MODE_PRIVATE)
            editorAbxy = gamePrefs.getString("tune_abxy_$gameKey", "") ?: ""
            editorVib = gamePrefs.getInt("tune_vib_$gameKey", -1)
            val controllerCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
            controllerCard.addView(label(tr("CONTROLLER", "КОНТРОЛЛЕР", "CONTROLLER", "MANDO"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 4))
            fun choiceRow(title: String, options: List<Pair<String, String>>, selected: () -> String, onPick: (String) -> Unit) {
                controllerCard.addView(label(title, 9f, muted), margin(top = 2, bottom = 3))
                val buttons = linkedMapOf<String, TextView>()
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                fun paint() = buttons.forEach { (value, button) ->
                    val active = value == selected()
                    button.background = roundedBackground(if (active) mint else cardBg2, if (active) mint else border, 10)
                    button.setTextColor(if (active) bg else white)
                }
                options.forEachIndexed { index, (value, name) ->
                    val button = pill(name, cardBg2, white) { onPick(value); paint() }.apply {
                        textSize = 10f; minHeight = dp(36); setPadding(dp(3), dp(4), dp(3), dp(4))
                    }
                    buttons[value] = button
                    row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
                }
                paint()
                controllerCard.addView(row)
            }
            val keep = tr("Keep", "Не менять", "Behouden", "Mantener")
            choiceRow(tr("Button layout", "Раскладка кнопок", "Knopindeling", "Distribución de botones"),
                listOf("" to keep, "STANDARD" to "Xbox", "JAPAN" to tr("Japanese", "Японская", "Japans", "Japonesa")),
                { editorAbxy }) { editorAbxy = it }
            choiceRow(tr("Vibration", "Вибрация", "Trilling", "Vibración"),
                listOf("-1" to keep, "0" to tr("Off", "Выкл.", "Uit", "No"), "1" to tr("Low", "Слабая", "Laag", "Baja"),
                    "2" to tr("Medium", "Средняя", "Gemiddeld", "Media"), "3" to tr("High", "Сильная", "Hoog", "Alta")),
                { editorVib.toString() }) { editorVib = it.toInt() }
            content.addView(controllerCard, margin(bottom = 8))

            val eqCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
            eqCard.addView(label(tr("EQUALIZER", "ЭКВАЛАЙЗЕР", "EQUALIZER", "ECUALIZADOR"), 10f, muted, true).apply { typeface = mono }, margin(bottom = 3))
            val eqSummary = label("", 10f, white)
            fun showEqSummary() {
                eqSummary.text = when (gamePrefs.getString("tune_eq_mode_$gameKey", "keep") ?: "keep") {
                    "on" -> tr("Own equalizer for this game", "Свой эквалайзер для этой игры", "Eigen equalizer voor deze game", "Ecualizador propio para este juego")
                    "off" -> tr("Equalizer off in this game", "В этой игре эквалайзер выключен", "Equalizer uit in deze game", "Ecualizador apagado en este juego")
                    else -> tr("Not changed (your usual equalizer)", "Не меняется (обычный эквалайзер)", "Ongewijzigd (je gewone equalizer)", "Sin cambios (tu ecualizador habitual)")
                }
            }
            showEqSummary()
            eqCard.addView(eqSummary, margin(bottom = 6))
            eqCard.addView(pill("🎚  " + tr("SET UP EQUALIZER", "НАСТРОИТЬ ЭКВАЛАЙЗЕР", "EQUALIZER INSTELLEN", "AJUSTAR ECUALIZADOR"), cardBg2, white) {
                showEqualizerDialog(gamePackage) { showEqSummary() }
            }.apply { minHeight = dp(42) })
            content.addView(eqCard, margin(bottom = 8))

            content.addView(pill(tr("SAVE GAME PROFILE", "СОХРАНИТЬ ПРОФИЛЬ ИГРЫ", "GAMEPROFIEL OPSLAAN", "GUARDAR PERFIL DEL JUEGO"), mint, bg) {
                if (!saveGameProfile(gamePackage)) return@pill
                saveCurrentProfileEditorDraft()
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putString("tune_abxy_$gameKey", editorAbxy)
                    .putInt("tune_vib_$gameKey", editorVib)
                    .apply()
                Toast.makeText(this, tr("Game profile saved", "Профиль игры сохранён", "Gameprofiel opgeslagen", "Perfil del juego guardado"), Toast.LENGTH_SHORT).show()
                profileEditorPage = false
                gameProfilePackage = null; tunedEditorId = null
                buildScreen(homeTransitionDirection = -1)
            }.apply { minHeight = dp(44) }, margin(bottom = 7))
            if (hasGameProfile(gamePackage)) content.addView(pill(tr("DELETE GAME PROFILE", "УДАЛИТЬ ПРОФИЛЬ ИГРЫ", "GAMEPROFIEL VERWIJDEREN", "ELIMINAR PERFIL DEL JUEGO"), cardBg2, white) {
                deleteGameProfile(gamePackage)
                profileEditorPage = false
                gameProfilePackage = null; tunedEditorId = null
                buildScreen(homeTransitionDirection = -1)
            }.apply { minHeight = dp(42) }, margin(bottom = 8))
            editorUpdating = false
            updateProfileEditorControls(profileTabs)
            profileStatusValueView = null
            return
        }
        content.addView(pill(tr("RESET THIS PROFILE TO DEFAULTS", "СБРОСИТЬ ПРОФИЛЬ", "DIT PROFIEL HERSTELLEN", "RESTABLECER PERFIL"), cardBg2, white) {
            resetProfileTuning(editorProfile)
        }.apply { minHeight = dp(42) }, margin(bottom = 7))
        content.addView(pill(tr("SAVE & APPLY PROFILE", "СОХРАНИТЬ И ПРИМЕНИТЬ", "PROFIEL OPSLAAN EN TOEPASSEN", "GUARDAR Y APLICAR PERFIL"), mint, bg) {
            saveCurrentProfileEditorDraft()
            if (tuned != null) applyTunedProfile(tuned, manual = true) else applyPerformanceProfile(editorProfile)
            Toast.makeText(this, tr("Applying saved profile…", "Применяю сохранённый профиль…", "Opgeslagen profiel toepassen…"), Toast.LENGTH_SHORT).show()
        }.apply { minHeight = dp(44) }, margin(bottom = 8))
        editorUpdating = false
        updateProfileEditorControls(profileTabs)
        profileStatusValueView = null
    }

    private fun loadCpuPolicyFrequencyStepsFromRoot() {
        if (rootAccessGranted != true) return
        cpuPolicySteps.keys.toList().forEach { policy ->
            val directory = "/sys/devices/system/cpu/cpufreq/$policy"
            val values = rootRead("cat $directory/scaling_available_frequencies 2>/dev/null")
                ?.split(Regex("\\s+"))
                ?.mapNotNull { it.toIntOrNull() }
                ?.distinct()
                ?.sorted()
                ?.toMutableList()
            // Only expose frequencies accepted by scaling_max_freq. cpuinfo_max_freq
            // can be a silicon ceiling that the cpufreq driver does not allow as a cap.
            val validValues = values?.takeIf { it.size >= 3 }
            if (validValues != null) {
                cpuPolicySteps[policy]?.clear()
                cpuPolicySteps[policy]?.addAll(validValues)
            }
        }
    }

    private fun currentProfileCpuTarget(cluster: String): Long {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val profileKey = editorProfile.lowercase(Locale.ROOT)
        val clusterKey = "tune_cpu_${cluster}_${profileKey}"
        if (prefs.contains(clusterKey)) return prefs.getLong(clusterKey, 0L)
        val previousCluster = when (cluster) {
            "policy0" -> "little"
            "policy3" -> "big"
            else -> null
        }
        if (previousCluster != null) {
            val previousKey = "tune_cpu_${previousCluster}_${profileKey}"
            if (prefs.contains(previousKey)) return prefs.getLong(previousKey, 0L)
        }
        return prefs.getLong("tune_cpu_$profileKey", defaultCpuTarget(editorProfile))
    }

    private fun currentProfileGpuTarget(): Long = getPreferences(Context.MODE_PRIVATE).getLong(
        "tune_gpu_${editorProfile.lowercase(Locale.ROOT)}", defaultGpuTarget(editorProfile)
    )

    private fun updateProfileEditorControls(profileTabs: Map<String, TextView>) {
        editorUpdating = true
        profileTabs.forEach { (name, button) ->
            val active = name == editorProfile
            button.background = roundedBackground(if (active) profileAccent(name) else cardBg2, if (active) profileAccent(name) else border, 11)
            button.setTextColor(if (active) bg else white)
        }
        editorFanMode = getPreferences(Context.MODE_PRIVATE).getString(
            "tune_fan_${editorProfile.lowercase(Locale.ROOT)}", fanModeForProfile(editorProfile)
        ) ?: fanModeForProfile(editorProfile)
        val cpuChoices = cpuPolicySteps
        cpuChoices.forEach { (cluster, frequencies) ->
            val target = currentProfileCpuTarget(cluster)
            val index = if (target <= 0L) frequencies.lastIndex else frequencies.indexOfLast { it.toLong() <= target }.coerceAtLeast(0)
            editorCpuSeeks[cluster]?.progress = index
            editorCpuValues[cluster]?.text = if (index == frequencies.lastIndex) tr("MAX", "МАКС.", "MAX", "MÁX.") else "${frequencies[index] / 1000} MHz"
        }
        val gpuChoices = listOf(124800000L, 220000000L, 295000000L, 348000000L, 401000000L, 475000000L, 550000000L, 615000000L, 680000000L, 719000000L, 746000000L, 794000000L, 827000000L, 860000000L, 1000000000L)
        val target = currentProfileGpuTarget()
        val gpuIndex = gpuChoices.indices.minByOrNull { kotlin.math.abs(gpuChoices[it] - target) } ?: 0
        editorGpuSeek?.progress = gpuIndex
        editorGpuValue?.text = "${gpuChoices[gpuIndex] / 1_000_000L} MHz"
        editorGpuLock?.isChecked = getPreferences(Context.MODE_PRIVATE).getBoolean(
            "tune_gpu_lock_${editorProfile.lowercase(Locale.ROOT)}", editorProfile == "Max"
        )
        editorTurbo?.isChecked = getPreferences(Context.MODE_PRIVATE).getBoolean(TurboBoost.prefKey(editorProfile), false)
        refreshProfileEditorFanButtons()
        editorUpdating = false
    }

    private fun refreshProfileEditorFanButtons() {
        editorFanButtons.forEach { (mode, button) ->
            val active = mode == editorFanMode
            button.background = roundedBackground(if (active) mint else cardBg2, if (active) mint else border, 10)
            button.setTextColor(if (active) bg else white)
        }
    }

    private fun saveCurrentProfileEditorDraft() {
        if (editorUpdating) return
        val cpuChoices = cpuPolicySteps
        val gpuChoices = listOf(124800000L, 220000000L, 295000000L, 348000000L, 401000000L, 475000000L, 550000000L, 615000000L, 680000000L, 719000000L, 746000000L, 794000000L, 827000000L, 860000000L, 1000000000L)
        val gpuHz = gpuChoices[(editorGpuSeek?.progress ?: gpuChoices.lastIndex).coerceIn(gpuChoices.indices)]
        val edit = getPreferences(Context.MODE_PRIVATE).edit()
            .putString("tune_fan_${editorProfile.lowercase(Locale.ROOT)}", editorFanMode)
            .putLong("tune_gpu_${editorProfile.lowercase(Locale.ROOT)}", gpuHz)
            .putBoolean("tune_gpu_lock_${editorProfile.lowercase(Locale.ROOT)}", editorGpuLock?.isChecked == true)
        cpuChoices.forEach { (cluster, frequencies) ->
            val index = (editorCpuSeeks[cluster]?.progress ?: frequencies.lastIndex).coerceIn(frequencies.indices)
            edit.putLong("tune_cpu_${cluster}_${editorProfile.lowercase(Locale.ROOT)}", if (index == frequencies.lastIndex) 0L else frequencies[index].toLong())
        }
        edit.apply()
    }

    private fun resetProfileTuning(profile: String) {
        val key = profile.lowercase(Locale.ROOT)
        getPreferences(Context.MODE_PRIVATE).edit()
            .remove("tune_fan_$key")
            .remove("tune_cpu_$key")
            .remove("tune_cpu_little_$key")
            .remove("tune_cpu_big_$key")
            .remove("tune_cpu_policy0_$key")
            .remove("tune_cpu_policy3_$key")
            .remove("tune_cpu_policy7_$key")
            .remove("tune_gpu_$key")
            .remove("tune_gpu_lock_$key")
            .remove("tune_turbo_$key")
            .apply()
        editorFanMode = fanModeForProfile(profile)
        updateProfileEditorControls(emptyMap())
        applyPerformanceProfile(profile)
        Toast.makeText(this, tr("Profile restored to defaults", "Настройки профиля сброшены", "Profielinstellingen hersteld"), Toast.LENGTH_SHORT).show()
    }

    private fun buildDashboard() {
        rootWarningBanner()?.let { content.addView(it, margin(bottom = 10)) }
        if (layoutTheme == "eightbit") {
            buildEightBitDashboard()
            return
        }
        val profiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Starship Console: keep the buttons below the top bar.
            setPadding(dp(5), dp(if (isBridge()) 13 else 5), dp(5), dp(5))
            background = if (isBrutal()) brutalBox(cardBg2) else if (glassTheme()) auroraGlass(30) else if (isPop()) popCard(26) else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, cardBg)).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), border)
            }
            elevation = if (flatTheme()) 0f else dp(4).toFloat()
        }
        listOf("Eco", "Balanced", "Streaming", "Gaming", "Max").forEach { p ->
            val active = p == selectedProfile
            val accent = profileAccent(p)
            val ink = if (active) onAccentInk() else white
            val title = label(profileDisplayName(p), if (layoutTheme == "compact") 10f else 12f, ink, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(1), dp(1), dp(1), 0)
            }
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(if (layoutTheme == "compact") 58 else 68)
                setPadding(dp(3), dp(5), dp(3), dp(5))
                background = profileButtonBackground(p, active)
                elevation = if (flatTheme()) 0f else dp(if (active) 6 else 2).toFloat()
                contentDescription = tr("${profileDisplayName(p)} performance profile", "Профиль производительности: ${profileDisplayName(p)}")
                isClickable = true
                isFocusable = true
                setOnClickListener { applyPerformanceProfile(p) }
                if (usesLivingIcons()) {
                    // Only the selected profile's icon moves.
                    addView(LivingIconView(this@MainActivity, p.lowercase(Locale.ROOT), if (active) Color.WHITE else accent, accent, animated = active),
                        LinearLayout.LayoutParams(dp(36), dp(36)))
                } else {
                    addView(ProfileGlyphView(this@MainActivity, p).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            if (isBrutal()) {
                                setColor(if (active) Color.WHITE else blendColor(cardBg, accent, if (layoutTheme == "brutal_dark") 0.55f else 0.35f))
                                setStroke(dp(2), brutalLine())
                            } else {
                                setColor(if (active) Color.argb(54, 255, 255, 255) else blendColor(cardBg2, accent, 0.25f))
                                setStroke(dp(1), Color.argb(if (active) 175 else 110, Color.red(accent), Color.green(accent), Color.blue(accent)))
                            }
                        }
                        elevation = if (flatTheme()) 0f else dp(2).toFloat()
                    }, LinearLayout.LayoutParams(dp(34), dp(34)))
                }
                addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            enableControllerFocus(button, if (layoutTheme == "compact") 12 else 16)
            profileButtons[p] = ProfileButtonViews(button, title)
            profiles.addView(button, weight(1f, start = 3, end = 3))
        }
        // Chronograph has its own arrangement: processors on top, the FPS "dial" in the middle of
        // the quick row, and the profiles at the bottom, right above the dock.
        // Hologram uses it too: the readouts first, the profile selector as a console at the bottom.
        val chronoLayout = layoutTheme == "chrono" || layoutTheme == "hud"
        if (!chronoLayout) content.addView(profiles, margin(bottom = 9))

        // Put the clickable quick controls directly below the profile strip, as in the stock panel.
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        val fpsCard = metricCard("FPS", "— FPS", tr("Main display game FPS", "FPS игры на основном экране", "Game-FPS op hoofdscherm", "FPS del juego en pantalla principal"), Color.rgb(255, 194, 80), "fps")
        val fanCard = metricCard("FAN", "RPM —", currentFanSubtitle(), mint, "fan", true) { showFanDialog() }
        val displayCard = metricCard("DISPLAY", displayHz(), tr("Tap to choose", "Нажми для выбора", "Tik om te kiezen", "Toca para elegir"), cyan, "display", true) { showRefreshRateDialog() }
        if (chronoLayout) {
            quick.addView(fanCard, matchHeightWeight(1f, end = 4))
            quick.addView(fpsCard, matchHeightWeight(1.15f, start = 2, end = 2))
            quick.addView(displayCard, matchHeightWeight(1f, start = 4))
        } else {
            quick.addView(fpsCard, matchHeightWeight(1f, end = 4))
            quick.addView(fanCard, matchHeightWeight(1f, start = 2, end = 2))
            quick.addView(displayCard, matchHeightWeight(1f, start = 4))
        }

        val processors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        processors.addView(processorCard("CPU", "cpu", mint), matchHeightWeight(1f, end = 4))
        processors.addView(processorCard("GPU", "gpu", profileAccent("Streaming")), matchHeightWeight(1f, start = 2))
        if (chronoLayout) {
            content.addView(processors, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(7) })
            content.addView(quick, LinearLayout.LayoutParams(-1, 0, 0.8f).apply { bottomMargin = dp(7) })
        } else {
            content.addView(quick, LinearLayout.LayoutParams(-1, 0, 0.8f).apply { bottomMargin = dp(7) })
            content.addView(processors, LinearLayout.LayoutParams(-1, 0, 1.2f).apply { bottomMargin = dp(7) })
        }

        // Battery, RAM and storage share the utility row.
        val utilities = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        utilities.addView(batteryCard().apply { minimumHeight = dp(70) }, matchHeightWeight(1.3f, end = 3))
        utilities.addView(ramFooterCard().apply { minimumHeight = dp(70) }, matchHeightWeight(1f, start = 1, end = 2))
        utilities.addView(storageFooterCard().apply { minimumHeight = dp(70) }, matchHeightWeight(1f, start = 2))
        content.addView(utilities, LinearLayout.LayoutParams(-1, 0, 0.68f).apply { bottomMargin = dp(6) })
        if (chronoLayout) content.addView(profiles, margin(top = 2, bottom = 4))
    }

    private fun buildEightBitDashboard() {
        // This layout deliberately behaves like a game HUD rather than another card dashboard:
        // a cartridge title strip, a selected-mode marquee, command keys and pixel meters.
        val mono = Typeface.create("monospace", Typeface.BOLD)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = RetroFrameDrawable(Color.rgb(13, 20, 34), mint, 4)
        }
        header.addView(PixelHandheldMark(this), LinearLayout.LayoutParams(dp(35), dp(30)).apply { marginEnd = dp(7) })
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        titleBlock.addView(label("POCKET // HUB", 13f, white, true).apply { typeface = mono })
        titleBlock.addView(label("SYSTEM CARTRIDGE  •  01", 8f, mint, true).apply { typeface = mono })
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("●  READY", 9f, mint, true).apply { typeface = mono })
        content.addView(header, margin(bottom = 6))

        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(8), dp(7))
            background = RetroFrameDrawable(Color.rgb(17, 28, 43), Color.rgb(98, 135, 163), 5)
        }
        hero.addView(PixelHeroSprite(this), LinearLayout.LayoutParams(dp(90), dp(82)).apply { marginEnd = dp(9) })
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        heroText.addView(label("PLAYER 1  /  PROFILE", 8f, muted, true).apply { typeface = mono }, margin(bottom = 1))
        val selected = label(profileDisplayName(selectedProfile).uppercase(Locale.ROOT), 19f, profileAccent(selectedProfile), true).apply {
            typeface = mono; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        retroProfileTitleView = selected
        heroText.addView(selected)
        heroText.addView(label("POWER LEVEL: ACTIVE", 8f, mint, true).apply { typeface = mono }, margin(top = 2))
        hero.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
        heroRow.addView(hero, LinearLayout.LayoutParams(0, dp(103), 1.25f).apply { marginEnd = dp(5) })

        val fanTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = RetroFrameDrawable(Color.rgb(12, 25, 31), mint, 5)
            isClickable = true; isFocusable = true
            contentDescription = tr("Open fan mode controls", "Открыть управление режимом вентилятора")
            setOnClickListener { showFanDialog() }
        }
        fanTile.addView(label("THERMAL CORE", 8f, mint, true).apply { typeface = mono })
        val fanReadout = label("RPM —", 18f, white, true).apply { typeface = mono; setPadding(0, dp(2), 0, dp(1)) }
        fanValueView = fanReadout
        fanTile.addView(fanReadout)
        val fanSub = label(currentFanSubtitle().uppercase(Locale.ROOT), 8f, muted, true).apply { typeface = mono; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        fanSubtitleView = fanSub
        fanTile.addView(fanSub)
        fanTile.addView(label("[ TAP TO CONFIGURE ]", 7f, cyan, true).apply { typeface = mono; setPadding(0, dp(2), 0, 0) })
        enableControllerFocus(fanTile, 10)
        heroRow.addView(fanTile, LinearLayout.LayoutParams(0, dp(103), 0.88f))
        content.addView(heroRow, margin(bottom = 6))

        val modeTray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(5))
            background = RetroFrameDrawable(Color.rgb(13, 20, 34), Color.rgb(54, 78, 100), 4)
        }
        modeTray.addView(label("SELECT POWER MODE  //  A BUTTON TO APPLY", 8f, muted, true).apply { typeface = mono }, margin(bottom = 3))
        val commandKeys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("Eco", "Balanced", "Streaming", "Gaming", "Max").forEachIndexed { index, profile ->
            val active = profile == selectedProfile
            val short = when (profile) { "Eco" -> "ECO"; "Balanced" -> "BAL"; "Streaming" -> "CAST"; "Gaming" -> "GAME"; else -> "MAX" }
            val title = label(short, 9f, if (active) bg else white, true).apply {
                typeface = mono; gravity = Gravity.CENTER; maxLines = 1
            }
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(3), dp(2), dp(3))
                background = profileButtonBackground(profile, active)
                isClickable = true; isFocusable = true
                contentDescription = tr("${profileDisplayName(profile)} performance profile", "Профиль производительности: ${profileDisplayName(profile)}")
                setOnClickListener { applyPerformanceProfile(profile) }
                addView(label("0${index + 1}", 7f, if (active) bg else muted, true).apply { typeface = mono; gravity = Gravity.CENTER })
                addView(title)
            }
            profileButtons[profile] = ProfileButtonViews(button, title)
            enableControllerFocus(button, 7)
            commandKeys.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        modeTray.addView(commandKeys)
        content.addView(modeTray, margin(bottom = 6))

        val telemetryLabel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        telemetryLabel.addView(label("LIVE HARDWARE BUS", 8f, mint, true).apply { typeface = mono }, LinearLayout.LayoutParams(0, -2, 1f))
        telemetryLabel.addView(label("CPU  /  GPU", 8f, muted, true).apply { typeface = mono })
        content.addView(telemetryLabel, margin(bottom = 3))

        val processorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = RetroFrameDrawable(Color.rgb(9, 16, 28), Color.rgb(54, 78, 100), 4)
        }
        processorPanel.addView(retroProcessorLine("CPU", mint, true), LinearLayout.LayoutParams(-1, dp(61)))
        val divider = View(this).apply { setBackgroundColor(Color.rgb(43, 64, 82)) }
        processorPanel.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        processorPanel.addView(retroProcessorLine("GPU", cyan, false), LinearLayout.LayoutParams(-1, dp(61)))
        content.addView(processorPanel, margin(bottom = 6))

        val utilityStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        val displayTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(4), dp(7), dp(4)); background = RetroFrameDrawable(Color.rgb(14, 25, 38), cyan, 4)
            isClickable = true; isFocusable = true; setOnClickListener { showRefreshRateDialog() }
            contentDescription = tr("Choose refresh rate", "Выбрать частоту экрана")
        }
        displayTile.addView(label("DISPLAY / FPS", 8f, muted, true).apply { typeface = mono })
        val displayValue = label(displayHz(), 15f, cyan, true).apply { typeface = mono; setPadding(0, dp(2), 0, 0) }
        displayValueView = displayValue
        displayTile.addView(displayValue)
        val fpsValue = label("— FPS", 9f, white, true).apply { typeface = mono }
        fpsValueView = fpsValue
        displayTile.addView(fpsValue)
        enableControllerFocus(displayTile, 9)
        utilityStrip.addView(displayTile, LinearLayout.LayoutParams(0, dp(74), 0.82f).apply { marginEnd = dp(5) })

        val battery = batteryCard().apply {
            minimumHeight = dp(74); setPadding(dp(7), dp(5), dp(7), dp(5))
            background = RetroFrameDrawable(Color.rgb(14, 25, 38), Color.rgb(113, 143, 101), 4)
        }
        utilityStrip.addView(battery, LinearLayout.LayoutParams(0, dp(74), 1.3f).apply { marginEnd = dp(5) })
        val memory = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun memoryRow(title: String, valueText: String, accent: Int, isRam: Boolean): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(3), dp(6), dp(3))
                background = RetroFrameDrawable(Color.rgb(14, 25, 38), accent, 4)
            }
            val textRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            textRow.addView(label(title, 8f, muted, true).apply { typeface = mono }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = label(valueText, 10f, white, true).apply { typeface = mono; gravity = Gravity.END; maxLines = 1 }
            if (isRam) memoryValueView = value else storageValueView = value
            textRow.addView(value)
            row.addView(textRow)
            val meter = SegmentedUsageView(this, accent).apply { progress = percentFromFraction(valueText) ?: 0 }
            if (isRam) ramUsageMeterView = meter else storageUsageMeterView = meter
            row.addView(meter, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(2) })
            return row
        }
        memory.addView(memoryRow(tr("RAM", "ОЗУ", "RAM", "RAM"), memoryInfo(), cyan, true),
            LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(4) })
        memory.addView(memoryRow(tr("STORAGE", "НАКОПИТЕЛЬ", "OPSLAG", "ALMACENAMIENTO"), storageInfo(), Color.rgb(191, 163, 255), false),
            LinearLayout.LayoutParams(-1, 0, 1f))
        utilityStrip.addView(memory, LinearLayout.LayoutParams(0, dp(74), 1f))
        content.addView(utilityStrip)
    }

    private fun retroProcessorLine(name: String, accent: Int, cpu: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val nameView = label(name, 12f, accent, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }
        addView(nameView, LinearLayout.LayoutParams(dp(37), -2))
        val frequency = label("— MHz", 13f, white, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.CENTER }
        if (cpu) cpuValueView = frequency else gpuValueView = frequency
        addView(frequency, LinearLayout.LayoutParams(dp(82), -2))
        val meter = SegmentedUsageView(this@MainActivity, accent)
        if (cpu) cpuUsageMeterView = meter else gpuUsageMeterView = meter
        addView(meter, LinearLayout.LayoutParams(0, dp(19), 1f).apply { marginStart = dp(4); marginEnd = dp(6) })
        val usage = label("—%", 10f, accent, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        if (cpu) cpuUsageValueView = usage else gpuUsageValueView = usage
        addView(usage, LinearLayout.LayoutParams(dp(38), -2))
        val temp = label("—°C", 10f, muted, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD); gravity = Gravity.END }
        if (cpu) cpuTempValueView = temp else gpuTempValueView = temp
        temp.tag = temp.currentTextColor
        addView(temp, LinearLayout.LayoutParams(dp(48), -2))
    }

    private fun buildControllerSettings() {
        controllerChoiceButtons.clear()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = label(tr("CONTROLLER", "КОНТРОЛЛЕР", "CONTROLLER", "MANDO"), 17f, mint, true)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill(tr("‹  HOME", "‹  ГЛАВНАЯ", "‹  START", "‹  INICIO"), cardBg2, white) {
            controllerPage = false
            buildScreen(homeTransitionDirection = -1)
        })
        content.addView(header, margin(bottom = 6))
        content.addView(label(
            tr("BUILT-IN GAMEPAD", "ВСТРОЕННЫЙ ГЕЙМПАД", "INGEBOUWDE GAMEPAD", "MANDO INTEGRADO"),
            9f, muted, true
        ), margin(bottom = 7))

        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.FILL_VERTICAL }
        val vibrationCard = card().apply { minimumHeight = dp(160) }
        vibrationCard.addView(label(tr("VIBRATION", "ВИБРАЦИЯ", "TRILLING", "VIBRACIÓN"), 15f, white, true), margin(bottom = 3))
        vibrationCard.addView(label(
            tr("Button haptics", "Отклик кнопок", "Haptische feedback", "Respuesta háptica"), 10f, muted
        ), margin(bottom = 10))
        val vibrationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            0 to tr("Off", "Выкл.", "Uit", "Desactivada"),
            1 to tr("Low", "Слабая", "Laag", "Baja"),
            2 to tr("Medium", "Средняя", "Gemiddeld", "Media"),
            3 to tr("High", "Сильная", "Hoog", "Alta")
        ).forEachIndexed { index, (level, name) ->
            val option = pill(name, cardBg2, white) { setControllerVibration(level) }.apply {
                textSize = 10f
                setPadding(dp(7), dp(11), dp(7), dp(11))
                controllerChoiceButtons["vib_$level"] = this
            }
            vibrationRow.addView(option, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        vibrationCard.addView(vibrationRow)
        val vibState = controllerVibrationLevel.takeIf { it in 0..3 }?.let { vibrationLabel(it) }
            ?: tr("Reading device setting…", "Читаю настройку устройства…", "Instelling lezen…", "Leyendo ajuste…")
        controllerVibrationStatusView = label(vibState, 10f, muted).apply { setPadding(0, dp(8), 0, 0) }
        vibrationCard.addView(controllerVibrationStatusView)
        settingsRow.addView(vibrationCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })

        val layoutCard = card().apply { minimumHeight = dp(160) }
        layoutCard.addView(label(tr("BUTTON LAYOUT", "РАСКЛАДКА КНОПОК", "KNOPINDELING", "DISTRIBUCIÓN"), 15f, white, true), margin(bottom = 3))
        layoutCard.addView(label(
            tr("ABXY positions", "Расположение ABXY", "Posities van ABXY", "Posiciones ABXY"), 10f, muted
        ), margin(bottom = 10))
        val layoutRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("STANDARD" to tr("Xbox", "Xbox", "Xbox", "Xbox"), "JAPAN" to tr("Japanese", "Японская", "Japans", "Japonesa"))
            .forEachIndexed { index, (mode, name) ->
                val option = pill(name, cardBg2, white) { setControllerAbxyMode(mode) }.apply {
                    textSize = 12f
                    setPadding(dp(12), dp(13), dp(12), dp(13))
                    controllerChoiceButtons["abxy_$mode"] = this
                }
                layoutRow.addView(option, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(7)
                })
            }
        layoutCard.addView(layoutRow)
        settingsRow.addView(layoutCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
        content.addView(settingsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        content.addView(controllerModeCard(), margin(bottom = 8))

        controllerStatusValueView = null
        updateControllerChoiceUi()
        refreshControllerSettings()
    }

    private fun vibrationLabel(level: Int): String = when (level) {
        0 -> tr("Vibration is off", "Вибрация выключена", "Trilling is uit", "Vibración desactivada")
        1 -> tr("Vibration: low", "Вибрация: слабая", "Trilling: laag", "Vibración: baja")
        2 -> tr("Vibration: medium", "Вибрация: средняя", "Trilling: gemiddeld", "Vibración: media")
        else -> tr("Vibration: high", "Вибрация: сильная", "Trilling: hoog", "Vibración: alta")
    }

    private fun updateControllerChoiceUi() {
        controllerChoiceButtons.forEach { (key, button) ->
            val selected = when {
                key.startsWith("vib_") -> key.substringAfter("vib_").toIntOrNull() == controllerVibrationLevel
                key.startsWith("abxy_") -> key.substringAfter("abxy_") == controllerAbxyMode
                else -> false
            }
            button.setTextColor(if (selected) bg else white)
            button.background = roundedBackground(if (selected) mint else cardBg2, if (selected) mint else border,
                if (layoutTheme == "eightbit") 4 else 12)
        }
    }

    private fun refreshControllerSettings() {
        val rootGranted = rootAccessGranted
        if (rootGranted != true) checkRootAccessAsync()
        Thread {
            val raw = rootRead("cat /sdcard/.aya/aya_vibrator_state.conf 2>/dev/null")
            val intensity = raw?.takeIf { it.matches(Regex("(?i)[0-9a-f]{16}")) }
                ?.substring(10, 12)?.toIntOrNull(16)
            val level = when {
                intensity == 0 -> 0
                intensity != null && intensity in 1..3 -> 1
                intensity != null && intensity in 4..7 -> 2
                intensity != null && intensity in 8..11 -> 3
                else -> -1
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || !controllerPage) return@runOnUiThread
                if (level >= 0) controllerVibrationLevel = level
                updateControllerChoiceUi()
                controllerVibrationStatusView?.text = if (level >= 0) vibrationLabel(level) else tr(
                    "Custom vibration level", "Свой уровень вибрации", "Aangepast trillingsniveau", "Nivel de vibración personalizado"
                )
            }
        }.start()
        if (oemAidlBound && oemServiceBinder != null && !oemClientId.isNullOrBlank()) {
            sendOemControllerMessage("com_get_abxy_mode:$controllerAbxyMode")
        } else {
            pendingControllerCommand = "com_get_abxy_mode:$controllerAbxyMode"
            if (oemAidlConnection == null) testOemAidl(showToast = false)
        }
    }

    private fun setControllerVibration(level: Int) {
        if (level !in 0..3) return
        val intensityHex = listOf("00", "03", "07", "0A")[level]
        val defaultConfig = "5501012C00B20000"
        Thread {
            val path = "/sdcard/.aya/aya_vibrator_state.conf"
            val read = runRootCommand("if [ -e '$path' ]; then cat '$path'; else echo __POCKETHUB_MISSING__; fi")
            val current = read.second.trim()
            val validCurrent = current.matches(Regex("(?i)[0-9a-f]{16}"))
            val canUseDefault = read.first && current == "__POCKETHUB_MISSING__"
            val config = when {
                validCurrent -> current.replaceRange(10, 12, intensityHex)
                canUseDefault -> defaultConfig.replaceRange(10, 12, intensityHex)
                else -> ""
            }
            val result = if (config.isNotEmpty()) runRootCommand("printf '%s' '$config' > '$path' && cat '$path'")
                else Triple(false, "Could not read the existing controller vibration config", -1)
            runOnUiThread {
                if (result.first && result.second.equals(config, ignoreCase = true)) {
                    controllerVibrationLevel = level
                    updateControllerChoiceUi()
                    controllerVibrationStatusView?.text = vibrationLabel(level)
                    Toast.makeText(this, tr("Vibration updated", "Вибрация изменена", "Trilling bijgewerkt", "Vibración actualizada"), Toast.LENGTH_SHORT).show()
                } else {
                    controllerStatusValueView?.text = tr(
                        "Could not update vibration. Check PocketHub root access.",
                        "Не удалось изменить вибрацию. Проверь root-доступ PocketHub.",
                        "Trilling wijzigen mislukt. Controleer roottoegang.",
                        "No se pudo cambiar la vibración. Comprueba root."
                    )
                    Toast.makeText(this, controllerStatusValueView?.text ?: "Root error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setControllerAbxyMode(mode: String) {
        if (mode != "STANDARD" && mode != "JAPAN") return
        controllerAbxyMode = mode
        updateControllerChoiceUi()
        pendingControllerCommand = "com_set_abxy_mode:$mode"
        controllerStatusValueView?.text = tr("Applying button layout…", "Применяю раскладку кнопок…", "Knopindeling toepassen…", "Aplicando distribución…")
        if (oemAidlBound && oemServiceBinder != null && !oemClientId.isNullOrBlank()) dispatchPendingControllerCommandIfReady()
        else if (oemAidlConnection == null) testOemAidl(showToast = false)
    }

    private fun dispatchPendingControllerCommandIfReady() {
        val command = pendingControllerCommand ?: return
        if (!oemAidlBound || oemServiceBinder == null || oemBinderDescriptor != OEM_AIDL_DESCRIPTOR || oemClientId.isNullOrBlank()) return
        pendingControllerCommand = null
        sendOemControllerMessage(command)
    }

    private fun sendOemControllerMessage(command: String) {
        val binder = oemServiceBinder ?: return
        val clientId = oemClientId ?: return
        Thread {
            val result = try {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(OEM_AIDL_DESCRIPTOR)
                    data.writeString("$clientId:msg_type_controller:$command")
                    if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw IllegalStateException("OEM Binder did not handle controller command")
                    reply.readException()
                    null
                } finally { data.recycle(); reply.recycle() }
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                if (result == null) {
                    if (command.startsWith("com_set_abxy_mode:")) {
                        controllerAbxyMode = command.substringAfter(':').uppercase(Locale.ROOT)
                        updateControllerChoiceUi()
                        controllerStatusValueView?.text = tr("Button layout applied", "Раскладка кнопок применена", "Knopindeling toegepast", "Distribución aplicada")
                    }
                } else {
                    controllerStatusValueView?.text = tr("OEM controller command failed", "Команда OEM контроллера не выполнена", "OEM-controlleropdracht mislukt", "Falló el comando OEM") + ": $result"
                }
            }
        }.start()
    }

    private fun dockContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(5), dp(10), dp(4))
        background = if (isBrutal()) brutalBox(cardBg) else if (glassTheme()) auroraGlass(30) else if (isPop()) popCard(30) else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg, cardBg2)).apply {
            cornerRadius = dp(if (layoutTheme == "eightbit") 4 else 18).toFloat()
            setStroke(dp(if (layoutTheme == "eightbit") 2 else 1), border)
        }
    }

    private fun dashboardControlDock(): LinearLayout = dockContainer().apply {
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(dockAction("☼", tr("BRIGHTNESS", "ЯРКОСТЬ", "HELDERHEID", "BRILLO"),
            tr("Display brightness settings", "Настройка яркости экранов", "Schermhelderheid instellen", "Ajustes de brillo de pantalla")) { showBrightnessDialog() }, weight(1f, end = 3))
        actions.addView(dockAction("♫", tr("SOUND", "ЗВУК", "GELUID", "SONIDO"),
            tr("Sound settings", "Настройки звука", "Geluidsinstellingen", "Ajustes de sonido")) { showSoundDialog() }, weight(1f, start = 3, end = 3))
        actions.addView(dockAction("▤", tr("DUAL SCREEN", "ДВА ЭКРАНА", "2 SCHERMEN", "2 PANTALLAS"),
            tr("Screen and controller focus", "Экраны и фокус контроллера", "Schermen en controllerfocus", "Pantallas y enfoque del mando")) { showScreenControlDialog() }, weight(1f, start = 3, end = 3))
        actions.addView(dockAction("▣", tr("CAPTURE", "СНИМОК", "FOTO", "FOTO"),
            tr("Capture both screens", "Снимок обоих экранов", "Beide schermen vastleggen", "Capturar ambas pantallas")) { captureBothScreens() }, weight(1f, start = 3, end = 3))
        addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pageDots(0), LinearLayout.LayoutParams(-1, dp(11)))
        val rootBadge = label("ROOT OFF", 8f, Color.rgb(255, 190, 112), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(cardBg2, Color.rgb(175, 111, 58), 10)
            contentDescription = tr("Root access is unavailable", "Нет доступа root", "Geen roottoegang", "No hay acceso root")
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(this@MainActivity, tr(
                    "Allow root access for PocketHub in Magisk, then return to the app.",
                    "Разреши root-доступ для PocketHub в Magisk и вернись в приложение.",
                    "Sta roottoegang voor PocketHub toe in Magisk en keer terug naar de app."
                ), Toast.LENGTH_LONG).show()
                checkRootAccessAsync()
            }
            visibility = View.GONE
        }
        rootAccessBadge = rootBadge
        // Keep the root warning available without taking a permanent slot from the wider controls.
        actions.addView(rootBadge, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(4) })
    }

    private fun secondaryHomeDock(): LinearLayout = dockContainer().apply {
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(dockAction("‹", tr("MAIN", "ГЛАВНАЯ", "START", "INICIO"),
            tr("Return to the main dashboard", "Вернуться на главный экран", "Terug naar het hoofddashboard", "Volver al panel principal")) {
            showHomePage(0, -1)
        }, weight(1f, end = 3))
        actions.addView(dockAction("?", tr("HELP", "ПОМОЩЬ", "HELP", "AYUDA"),
            tr("Open the PocketHub help guide", "Открыть справочник PocketHub", "Open de PocketHub-hulpgids", "Abrir la guía de ayuda de PocketHub")) {
            // Every visit starts clean: all topics folded, no search text.
            helpExpanded.clear()
            helpQuery = ""
            helpPage = true
            buildScreen(homeTransitionDirection = 1)
        }, weight(0.6f, start = 3, end = 3))
        actions.addView(dockAction("⚙", tr("SETTINGS", "НАСТРОЙКИ", "INSTELLINGEN", "AJUSTES"),
            tr("Settings", "Настройки", "Instellingen", "Ajustes")) {
            helpPage = false
            advanced = true
            controllerPage = false
            buildScreen(homeTransitionDirection = 1)
        }, weight(1f, start = 3))
        addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pageDots(1), LinearLayout.LayoutParams(-1, dp(11)))
    }

    private fun favoritesDock(): LinearLayout = dockContainer().apply {
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(dockAction("‹", tr("TOOLS", "ИНСТРУМЕНТЫ", "TOOLS", "HERRAMIENTAS"),
            tr("Back to device tools", "Назад к инструментам", "Terug naar apparaattools", "Volver a las herramientas")) {
            showHomePage(1, -1)
        }, weight(1f, end = 3))
        actions.addView(dockAction("+", tr("ADD", "ДОБАВИТЬ", "TOEVOEGEN", "AÑADIR"),
            tr("Add an app to favorites", "Добавить приложение в избранное", "App aan favorieten toevoegen", "Añadir una app a favoritos")) {
            showFavoriteAppPicker()
        }, weight(1f, start = 3, end = 3))
        actions.addView(dockAction("⚙", tr("SETTINGS", "НАСТРОЙКИ", "INSTELLINGEN", "AJUSTES"),
            tr("Settings", "Настройки", "Instellingen", "Ajustes")) {
            favoritesPage = false
            advanced = true
            controllerPage = false
            buildScreen(homeTransitionDirection = 1)
        }, weight(1f, start = 3))
        addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pageDots(2), LinearLayout.LayoutParams(-1, dp(11)))
    }

    private fun quickDock(): LinearLayout = dockContainer().apply {
        val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(dockAction("‹", tr("TUNING", "ТЮНИНГ", "AFSTEMMEN", "AJUSTE"),
            tr("Back to game tuning", "Назад к тюнингу игр", "Terug naar game-afstemming", "Volver al ajuste de juegos")) {
            showHomePage(3, -1)
        }, weight(1f, end = 3))
        actions.addView(dockAction("+", tr("ADD", "ДОБАВИТЬ", "TOEVOEGEN", "AÑADIR"),
            tr("Add a quick tile", "Добавить быструю кнопку", "Snelle tegel toevoegen", "Añadir un acceso rápido")) {
            showQuickTilePicker()
        }, weight(1f, start = 3, end = 3))
        actions.addView(dockAction("⚙", tr("SETTINGS", "НАСТРОЙКИ", "INSTELLINGEN", "AJUSTES"),
            tr("Settings", "Настройки", "Instellingen", "Ajustes")) {
            quickPage = false
            advanced = true
            controllerPage = false
            buildScreen(homeTransitionDirection = 1)
        }, weight(1f, start = 3))
        addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(pageDots(4), LinearLayout.LayoutParams(-1, dp(11)))
    }

    // ---- Quick toggles page ------------------------------------------------------------

    /** One quick toggle. Root shell commands read ("1" = on) and switch the state. */
    private data class QuickTileSpec(
        val id: String,
        val icon: String,
        val title: () -> String,
        val stateCommand: String?,
        val onCommand: String?,
        val offCommand: String?,
        val settingsAction: String?
    )

    private val quickTileSpecs: List<QuickTileSpec> by lazy {
        listOf(
            QuickTileSpec("wifi", "wifi", { "Wi-Fi" },
                "[ \"$(settings get global wifi_on)\" != 0 ] && echo 1 || echo 0", "svc wifi enable", "svc wifi disable", Settings.ACTION_WIFI_SETTINGS),
            QuickTileSpec("bluetooth", "bluetooth", { "Bluetooth" },
                "settings get global bluetooth_on", "cmd bluetooth_manager enable || svc bluetooth enable",
                "cmd bluetooth_manager disable || svc bluetooth disable", Settings.ACTION_BLUETOOTH_SETTINGS),
            QuickTileSpec("rotate", "rotate", { tr("Auto-rotate", "Автоповорот", "Automatisch draaien", "Girar automáticamente") },
                "settings get system accelerometer_rotation", "settings put system accelerometer_rotation 1",
                "settings put system accelerometer_rotation 0", Settings.ACTION_DISPLAY_SETTINGS),
            QuickTileSpec("airplane", "airplane", { tr("Airplane mode", "Режим полёта", "Vliegtuigmodus", "Modo avión") },
                "settings get global airplane_mode_on", "cmd connectivity airplane-mode enable",
                "cmd connectivity airplane-mode disable", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            QuickTileSpec("location", "location", { tr("Location", "Местоположение", "Locatie", "Ubicación") },
                "[ \"$(settings get secure location_mode)\" != 0 ] && echo 1 || echo 0", "cmd location set-location-enabled true",
                "cmd location set-location-enabled false", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            QuickTileSpec("dnd", "dnd", { tr("Do not disturb", "Не беспокоить", "Niet storen", "No molestar") },
                "[ \"$(settings get global zen_mode)\" != 0 ] && echo 1 || echo 0", "cmd notification set_dnd on",
                "cmd notification set_dnd off", "android.settings.ZEN_MODE_SETTINGS"),
            QuickTileSpec("saver", "saver", { tr("Battery saver", "Энергосбережение", "Batterijbesparing", "Ahorro de batería") },
                "settings get global low_power", "cmd power set-mode 1", "cmd power set-mode 0", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            QuickTileSpec("dark", "dark", { tr("Dark theme", "Тёмная тема", "Donker thema", "Tema oscuro") },
                "cmd uimode night | grep -qi yes && echo 1 || echo 0", "cmd uimode night yes", "cmd uimode night no", Settings.ACTION_DISPLAY_SETTINGS),
            // Force landscape: lock the main display to its landscape rotation and make every
            // app follow it, even apps that ask for portrait. The state lives in our own setting.
            QuickTileSpec("landscape", "landscape", { tr("Force landscape", "Альбомная всегда", "Liggend forceren", "Forzar horizontal") },
                "[ \"$(settings get global pockethub_force_landscape)\" = 1 ] && echo 1 || echo 0",
                "s=$(wm size -d 0 | grep -o '[0-9]*x[0-9]*' | tail -n 1); w=${'$'}{s%x*}; h=${'$'}{s#*x}; " +
                    // Keep the current landscape rotation (90° or 270°) so the image is not flipped.
                    "cur=$(settings get system user_rotation); " +
                    "if [ \"${'$'}w\" -gt \"${'$'}h\" ]; then case \"${'$'}cur\" in 0|2) r=${'$'}cur ;; *) r=0 ;; esac; " +
                    "else case \"${'$'}cur\" in 1|3) r=${'$'}cur ;; *) r=1 ;; esac; fi; " +
                    "settings put system accelerometer_rotation 0; settings put system user_rotation ${'$'}r; " +
                    "cmd window user-rotation -d 0 lock ${'$'}r; cmd window fixed-to-user-rotation -d 0 enabled; " +
                    "settings put global pockethub_force_landscape 1",
                "cmd window fixed-to-user-rotation -d 0 default; settings put global pockethub_force_landscape 0",
                Settings.ACTION_DISPLAY_SETTINGS)
        )
    }

    private fun quickTileIds(): MutableList<String> {
        val raw = getPreferences(Context.MODE_PRIVATE).getString(KEY_QUICK_TILES, null)
            ?: return mutableListOf("wifi", "bluetooth", "rotate")
        return try {
            val json = org.json.JSONArray(raw)
            (0 until json.length()).mapNotNull { json.optString(it).takeIf { id -> quickTileSpecs.any { s -> s.id == id } } }.toMutableList()
        } catch (_: Exception) { mutableListOf("wifi", "bluetooth", "rotate") }
    }

    private fun saveQuickTileIds(ids: List<String>) {
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_QUICK_TILES, org.json.JSONArray(ids.distinct()).toString()).apply()
    }

    private fun buildQuickPage() {
        val mono = Typeface.create("monospace", Typeface.BOLD)
        content.setPadding(dp(12), dp(12), dp(12), dp(10))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 5 else 18)
        }
        header.addView(label(tr("QUICK SETTINGS", "БЫСТРЫЕ НАСТРОЙКИ", "SNELLE INSTELLINGEN", "AJUSTES RÁPIDOS"), 13f, mint, true).apply { typeface = mono })
        header.addView(label(tr(
            "Tap to switch · hold for settings and more",
            "Нажми — переключить · удерживай — настройки и ещё",
            "Tik om te schakelen · houd vast voor instellingen",
            "Toca para cambiar · mantén para ajustes y más"
        ), 9f, muted), margin(top = 1))
        content.addView(header, margin(bottom = 8))

        val ids = quickTileIds()
        val tiles = ids.mapNotNull { id -> quickTileSpecs.firstOrNull { it.id == id } }.map { quickTileView(it, ids) } + addQuickTileView()
        val columns = 3
        tiles.chunked(columns).forEach { rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEachIndexed { index, tile ->
                row.addView(tile, LinearLayout.LayoutParams(0, dp(112), 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            repeat(columns - rowTiles.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(8) }) }
            content.addView(row, margin(bottom = 8))
        }
        refreshQuickTileStates()
    }

    private fun quickTileView(spec: QuickTileSpec, ids: List<String>): View {
        val on = quickTileStates[spec.id] == true
        val accent = mint
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isLongClickable = true
            contentDescription = spec.title()
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = if (on) {
                if (glassTheme()) auroraGlass(22, accent, strong = true)
                else if (isPop()) popCard(24, accent, strong = true)
                else roundedBackground(blendColor(cardBg, accent, 0.55f), accent, if (layoutTheme == "eightbit") 6 else 20)
            } else roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 6 else 20)
            val iconTint = if (on) (if (isBrutal()) BRUTAL_INK else Color.WHITE) else accent
            addView(LivingIconView(this@MainActivity, spec.icon, iconTint, accent, animated = on && usesLivingIcons()),
                LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(label(spec.title(), 11f, if (on) onAccentInk().takeIf { isBrutal() } ?: white else white, true).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(label(if (on) tr("On", "Вкл.", "Aan", "Activado") else tr("Off", "Выкл.", "Uit", "Desactivado"), 9f, if (on) white else muted).apply {
                gravity = Gravity.CENTER
            })
            setOnClickListener { toggleQuickTile(spec) }
            setOnLongClickListener { showQuickTileActions(spec, ids); true }
            enableControllerFocus(this, 20)
        }
    }

    private fun addQuickTileView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = tr("Add a quick tile", "Добавить быструю кнопку", "Snelle tegel toevoegen", "Añadir un acceso rápido")
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(if (layoutTheme == "eightbit") 6 else 20).toFloat()
            setStroke(dp(2), blendColor(border, mint, .6f), dp(8).toFloat(), dp(5).toFloat())
        }
        addView(label("+", 34f, mint, true).apply { gravity = Gravity.CENTER })
        addView(label(tr("Add tile", "Добавить", "Tegel toevoegen", "Añadir"), 11f, muted, true).apply { gravity = Gravity.CENTER })
        setOnClickListener { showQuickTilePicker() }
        enableControllerFocus(this, 20)
    }

    /** Read every visible tile's state in one root call, then redraw the page. */
    private fun refreshQuickTileStates() {
        val specs = quickTileIds().mapNotNull { id -> quickTileSpecs.firstOrNull { it.id == id } }
        val script = specs.filter { it.stateCommand != null }.joinToString("; ") { "echo \"${it.id}=$(${it.stateCommand})\"" }
        Thread {
            val output = if (script.isNotEmpty()) rootRead(script).orEmpty() else ""
            val states = output.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to (line.substring(i + 1).trim() == "1")
            }.toMap()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                var changed = false
                states.forEach { (id, on) -> if (quickTileStates[id] != on) { quickTileStates[id] = on; changed = true } }
                if (changed && quickPage && !advanced && !profileEditorPage) buildScreen()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun toggleQuickTile(spec: QuickTileSpec) {
        val turnOn = quickTileStates[spec.id] != true
        val command = (if (turnOn) spec.onCommand else spec.offCommand) ?: return
        quickTileStates[spec.id] = turnOn
        buildScreen()
        Thread {
            val (ok, output, _) = runRootCommand(command)
            if (!ok) Log.w("PocketHubQuick", "${spec.id}: $output")
            // Radios take a moment to settle; read the real state back afterwards.
            Thread.sleep(900)
            runOnUiThread { refreshQuickTileStates() }
        }.apply { isDaemon = true; start() }
    }

    private fun showQuickTileActions(spec: QuickTileSpec, ids: List<String>) {
        val index = ids.indexOf(spec.id)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        spec.settingsAction?.let { action ->
            actions += tr("Open settings", "Открыть настройки", "Instellingen openen", "Abrir ajustes") to {
                try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
                    Toast.makeText(this, tr("Settings page not available", "Страница настроек недоступна", "Instellingenpagina niet beschikbaar", "Página de ajustes no disponible"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (index > 0) actions += tr("Move left", "Сдвинуть влево", "Naar links", "Mover a la izquierda") to {
            saveQuickTileIds(ids.toMutableList().apply { add(index - 1, removeAt(index)) }); buildScreen()
        }
        if (index in 0 until ids.lastIndex) actions += tr("Move right", "Сдвинуть вправо", "Naar rechts", "Mover a la derecha") to {
            saveQuickTileIds(ids.toMutableList().apply { add(index + 1, removeAt(index)) }); buildScreen()
        }
        actions += tr("Remove tile", "Убрать кнопку", "Tegel verwijderen", "Quitar acceso") to {
            saveQuickTileIds(ids.filterNot { it == spec.id }); buildScreen()
        }
        showSimpleActionDialog(spec.title(), actions)
    }

    private fun showQuickTilePicker() {
        val existing = quickTileIds()
        val available = quickTileSpecs.filter { it.id !in existing }
        if (available.isEmpty()) {
            Toast.makeText(this, tr("All tiles are already added", "Все кнопки уже добавлены", "Alle tegels zijn al toegevoegd", "Ya están todos los accesos"), Toast.LENGTH_SHORT).show()
            return
        }
        showSimpleActionDialog(tr("ADD QUICK TILE", "ДОБАВИТЬ БЫСТРУЮ КНОПКУ", "SNELLE TEGEL TOEVOEGEN", "AÑADIR ACCESO RÁPIDO"),
            available.map { spec -> spec.title() to { saveQuickTileIds(existing + spec.id); buildScreen() } })
    }

    private fun showSimpleActionDialog(title: String, actions: List<Pair<String, () -> Unit>>) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(title, 13f, mint, true), margin(bottom = 8))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { (name, action) -> list.addView(pill(name, cardBg2, white) { dialog.dismiss(); action() }, margin(bottom = 6)) }
        panel.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.8f).toInt().coerceAtMost(dp(460)), -2)
    }

    // ---- Favorites page --------------------------------------------------------------

    private fun favoritePackages(): MutableList<String> {
        val raw = getPreferences(Context.MODE_PRIVATE).getString(KEY_FAVORITE_APPS, "[]") ?: "[]"
        return try {
            val json = org.json.JSONArray(raw)
            (0 until json.length()).mapNotNull { json.optString(it).takeIf { name -> name.isNotBlank() } }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveFavoritePackages(packages: List<String>) {
        val json = org.json.JSONArray().apply { packages.distinct().forEach { put(it) } }
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_FAVORITE_APPS, json.toString()).apply()
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) { packageName }

    private fun appIcon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) { null }

    private fun buildFavoritesPage() {
        val mono = Typeface.create("monospace", Typeface.BOLD)
        content.setPadding(dp(12), dp(12), dp(12), dp(10))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 5 else 18)
        }
        header.addView(label(tr("FAVORITES", "ИЗБРАННОЕ", "FAVORIETEN", "FAVORITOS"), 13f, mint, true).apply { typeface = mono })
        header.addView(label(tr(
            "Tap to open on the top screen · hold for more",
            "Нажми — откроется на верхнем экране · удерживай — ещё действия",
            "Tik om op het bovenste scherm te openen · houd vast voor meer",
            "Toca para abrir en la pantalla superior · mantén para más"
        ), 9f, muted), margin(top = 1))
        content.addView(header, margin(bottom = 8))

        // Drop apps that were uninstalled since they were added.
        val installed = favoritePackages().filter { packageManager.getLaunchIntentForPackage(it) != null }
        val columns = 3
        val tiles = installed.map { favoriteTile(it, installed) } + addFavoriteTile()
        tiles.chunked(columns).forEach { rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEachIndexed { index, tile ->
                row.addView(tile, LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
            repeat(columns - rowTiles.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginStart = dp(8) }) }
            content.addView(row, margin(bottom = 8))
        }
    }

    private fun favoriteTile(packageName: String, all: List<String>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        isLongClickable = true
        val name = appLabel(packageName)
        contentDescription = name
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 6 else 20)
        addView(ImageView(this@MainActivity).apply {
            setImageDrawable(appIcon(packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(label(name, 11f, white, true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        if (hasGameProfile(packageName)) {
            val active = selectedProfile == gameProfileKey(packageName)
            addView(label(if (active) "⚡ " + tr("ACTIVE", "АКТИВЕН", "ACTIEF", "ACTIVO") else "⚙ " + tr("PROFILE", "ПРОФИЛЬ", "PROFIEL", "PERFIL"),
                8f, if (active) bg else mint, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = roundedBackground(if (active) mint else cardBg, mint, 8)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        }
        setOnClickListener { launchFavorite(packageName, android.view.Display.DEFAULT_DISPLAY) }
        setOnLongClickListener { showFavoriteActions(packageName, all); true }
        enableControllerFocus(this, 24)
    }

    private fun addFavoriteTile(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = tr("Add an app to favorites", "Добавить приложение в избранное", "App aan favorieten toevoegen", "Añadir una app a favoritos")
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(if (layoutTheme == "eightbit") 6 else 20).toFloat()
            setStroke(dp(2), blendColor(border, mint, .6f), dp(8).toFloat(), dp(5).toFloat())
        }
        addView(label("+", 34f, mint, true).apply { gravity = Gravity.CENTER })
        addView(label(tr("Add app", "Добавить", "App toevoegen", "Añadir app"), 11f, muted, true).apply { gravity = Gravity.CENTER })
        setOnClickListener { showFavoriteAppPicker() }
        enableControllerFocus(this, 24)
    }

    // ---- Game profiles (per favorite) ------------------------------------------------

    private fun gameProfileKey(packageName: String) = GAME_PROFILE_PREFIX + packageName
    private fun isGameProfile(profile: String) = profile.startsWith(GAME_PROFILE_PREFIX)

    private fun gameProfiles(): MutableList<String> {
        val raw = getPreferences(Context.MODE_PRIVATE).getString(KEY_GAME_PROFILES, "[]") ?: "[]"
        return try {
            val json = org.json.JSONArray(raw)
            (0 until json.length()).mapNotNull { json.optString(it).takeIf { name -> name.isNotBlank() } }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun hasGameProfile(packageName: String) = packageName in gameProfiles()

    /** Registers the profile; refuses a 21st one. */
    private fun saveGameProfile(packageName: String): Boolean {
        val list = gameProfiles()
        if (packageName in list) return true
        if (list.size >= MAX_GAME_PROFILES) {
            Toast.makeText(this, tr(
                "Up to $MAX_GAME_PROFILES game profiles. Delete one first.",
                "Можно не больше $MAX_GAME_PROFILES профилей игр. Сначала удали один.",
                "Maximaal $MAX_GAME_PROFILES gameprofielen. Verwijder er eerst een.",
                "Máximo $MAX_GAME_PROFILES perfiles de juego. Elimina uno primero."
            ), Toast.LENGTH_LONG).show()
            return false
        }
        list.add(packageName)
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_GAME_PROFILES, org.json.JSONArray(list).toString()).apply()
        return true
    }

    private fun deleteGameProfile(packageName: String) {
        val key = gameProfileKey(packageName).lowercase(Locale.ROOT)
        val list = gameProfiles().filterNot { it == packageName }
        val edit = getPreferences(Context.MODE_PRIVATE).edit()
            .putString(KEY_GAME_PROFILES, org.json.JSONArray(list).toString())
            .remove("tune_fan_$key").remove("tune_gpu_$key").remove("tune_gpu_lock_$key").remove("tune_turbo_$key")
            .remove("tune_abxy_$key").remove("tune_vib_$key")
            .remove("tune_eq_mode_$key").remove("tune_eq_levels_$key").remove("tune_eq_preamp_$key")
            .remove("tune_eq_bass_$key").remove("tune_eq_preset_$key")
        listOf("policy0", "policy3", "policy7").forEach { edit.remove("tune_cpu_${it}_$key") }
        edit.apply()
        if (selectedProfile == gameProfileKey(packageName)) restoreProfileAfterGame()
    }

    private fun openGameProfileEditor(packageName: String) {
        gameProfilePackage = packageName
        profileEditorPage = true
        buildScreen(homeTransitionDirection = 1)
    }

    /** True while the app has a live process (checked through root, works for any app). */
    private fun isPackageRunning(packageName: String): Boolean =
        runRootCommand("pidof '${packageName.replace("'", "")}'").second.trim().isNotEmpty()

    /**
     * While a game profile is active, check every few seconds whether the game is still
     * running. Once its process is gone (two checks in a row), switch back to the built-in
     * profile that was active before. Leaving the game with Home keeps the profile.
     */
    private fun startGameWatch(packageName: String, initialDelayMs: Long = GAME_WATCH_START_DELAY_MS) {
        stopGameWatch()
        gameWatchMisses = 0
        val key = gameProfileKey(packageName)
        val task = object : Runnable {
            override fun run() {
                if (selectedProfile != key) { stopGameWatch(); return }
                Thread {
                    val running = try { isPackageRunning(packageName) } catch (_: Exception) { true }
                    runOnUiThread {
                        if (gameWatchRunnable !== this || selectedProfile != key) return@runOnUiThread
                        gameWatchMisses = if (running) 0 else gameWatchMisses + 1
                        if (gameWatchMisses >= 2) {
                            stopGameWatch()
                            restoreProfileAfterGame()
                            Toast.makeText(this@MainActivity, tr(
                                "Game closed: profile restored",
                                "Игра закрыта: профиль восстановлен",
                                "Game gesloten: profiel hersteld",
                                "Juego cerrado: perfil restaurado"
                            ) + " · ${profileDisplayName(selectedProfile)}", Toast.LENGTH_SHORT).show()
                            if (favoritesPage && !profileEditorPage) buildScreen()
                        } else {
                            gameWatchHandler.postDelayed(this, GAME_WATCH_INTERVAL_MS)
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }
        gameWatchRunnable = task
        gameWatchHandler.postDelayed(task, initialDelayMs)
    }

    private fun stopGameWatch() {
        gameWatchRunnable?.let { gameWatchHandler.removeCallbacks(it) }
        gameWatchRunnable = null
    }

    /** Back to the built-in profile that was active before a game profile took over. */
    /**
     * Applies the game's controller layout / vibration overrides, remembering the current
     * values first so they come back when the game closes.
     */
    private fun applyGameControllerSettings(packageName: String) {
        val key = gameProfileKey(packageName).lowercase(Locale.ROOT)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val abxy = prefs.getString("tune_abxy_$key", "") ?: ""
        val vib = prefs.getInt("tune_vib_$key", -1)
        if (abxy.isEmpty() && vib < 0) return
        val alreadyInGame = prefs.contains(KEY_CONTROLLER_BEFORE_GAME_ABXY) || prefs.contains(KEY_CONTROLLER_BEFORE_GAME_VIB)
        Thread {
            // Only snapshot when coming from normal use, not when switching between games.
            if (!alreadyInGame) {
                val edit = prefs.edit()
                if (abxy.isNotEmpty()) edit.putString(KEY_CONTROLLER_BEFORE_GAME_ABXY, controllerAbxyMode)
                if (vib >= 0) edit.putInt(KEY_CONTROLLER_BEFORE_GAME_VIB, readControllerVibrationLevel())
                edit.apply()
            }
            runOnUiThread {
                if (abxy.isNotEmpty()) setControllerAbxyMode(abxy)
                if (vib >= 0) setControllerVibration(vib)
            }
        }.apply { isDaemon = true; start() }
    }

    /** Current vibration level 0–3 from AYANEO's config, or -1 if unknown. */
    private fun readControllerVibrationLevel(): Int {
        val raw = rootRead("cat /sdcard/.aya/aya_vibrator_state.conf 2>/dev/null")
        val intensity = raw?.takeIf { it.matches(Regex("(?i)[0-9a-f]{16}")) }?.substring(10, 12)?.toIntOrNull(16)
        return when {
            intensity == 0 -> 0
            intensity != null && intensity in 1..3 -> 1
            intensity != null && intensity in 4..7 -> 2
            intensity != null && intensity in 8..11 -> 3
            else -> -1
        }
    }

    private fun restoreControllerAfterGame() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val abxy = prefs.getString(KEY_CONTROLLER_BEFORE_GAME_ABXY, null)
        val vib = prefs.getInt(KEY_CONTROLLER_BEFORE_GAME_VIB, -1)
        prefs.edit().remove(KEY_CONTROLLER_BEFORE_GAME_ABXY).remove(KEY_CONTROLLER_BEFORE_GAME_VIB).apply()
        if (abxy != null) setControllerAbxyMode(abxy)
        if (vib >= 0) setControllerVibration(vib)
    }

    /**
     * Applies a game's own equalizer (or switches it off), first saving the current global
     * equalizer so it comes back when the game closes.
     */
    private fun applyGameEqualizer(packageName: String) {
        val key = gameProfileKey(packageName).lowercase(Locale.ROOT)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val mode = prefs.getString("tune_eq_mode_$key", "keep") ?: "keep"
        if (mode == "keep") { restoreEqualizerAfterGame(); return }
        if (!prefs.getBoolean(KEY_EQ_BEFORE_GAME, false)) {
            prefs.edit().putBoolean(KEY_EQ_BEFORE_GAME, true)
                .putBoolean("eq_before_game_enabled", PocketHubEqualizer.isEnabled(this))
                .putString("eq_before_game_levels", prefs.getString(PocketHubEqualizer.KEY_LEVELS, null))
                .putInt("eq_before_game_preamp", PocketHubEqualizer.preamp(this))
                .putInt("eq_before_game_bass", PocketHubEqualizer.bass(this))
                .commit()
        }
        try {
            if (mode == "off") {
                PocketHubEqualizer.setEnabled(this, false)
            } else {
                prefs.edit()
                    .putString(PocketHubEqualizer.KEY_LEVELS, prefs.getString("tune_eq_levels_$key", null) ?: prefs.getString(PocketHubEqualizer.KEY_LEVELS, null))
                    .putInt(PocketHubEqualizer.KEY_PREAMP, prefs.getInt("tune_eq_preamp_$key", PocketHubEqualizer.preamp(this)))
                    .putInt(PocketHubEqualizer.KEY_BASS, prefs.getInt("tune_eq_bass_$key", PocketHubEqualizer.bass(this)))
                    .commit()
                if (PocketHubEqualizer.isEnabled(this)) PocketHubEqualizer.applySaved(this) else PocketHubEqualizer.setEnabled(this, true)
            }
        } catch (error: Exception) {
            Log.w("PocketHub", "Could not apply game equalizer", error)
        }
    }

    /** Puts back the global equalizer saved by applyGameEqualizer. */
    private fun restoreEqualizerAfterGame() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_EQ_BEFORE_GAME, false)) return
        val wasEnabled = prefs.getBoolean("eq_before_game_enabled", false)
        val edit = prefs.edit()
        val levels = prefs.getString("eq_before_game_levels", null)
        if (levels != null) edit.putString(PocketHubEqualizer.KEY_LEVELS, levels) else edit.remove(PocketHubEqualizer.KEY_LEVELS)
        edit.putInt(PocketHubEqualizer.KEY_PREAMP, prefs.getInt("eq_before_game_preamp", 0))
            .putInt(PocketHubEqualizer.KEY_BASS, prefs.getInt("eq_before_game_bass", 0))
            .remove(KEY_EQ_BEFORE_GAME).remove("eq_before_game_enabled").remove("eq_before_game_levels")
            .remove("eq_before_game_preamp").remove("eq_before_game_bass")
            .commit()
        try {
            if (wasEnabled) {
                if (PocketHubEqualizer.isEnabled(this)) PocketHubEqualizer.applySaved(this) else PocketHubEqualizer.setEnabled(this, true)
            } else if (PocketHubEqualizer.isEnabled(this)) {
                PocketHubEqualizer.setEnabled(this, false)
            }
        } catch (error: Exception) {
            Log.w("PocketHub", "Could not restore equalizer", error)
        }
    }

    private fun restoreProfileAfterGame() {
        restoreControllerAfterGame()
        restoreEqualizerAfterGame()
        val previous = getPreferences(Context.MODE_PRIVATE).getString(KEY_PROFILE_BEFORE_GAME, "Balanced") ?: "Balanced"
        applyPerformanceProfile(previous.takeIf { it in setOf("Eco", "Balanced", "Streaming", "Gaming", "Max") } ?: "Balanced")
    }

    private fun launchFavorite(packageName: String, displayId: Int) {
        if (hasGameProfile(packageName)) {
            // Remember the built-in profile so it can be restored later.
            if (!isGameProfile(selectedProfile)) {
                getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_PROFILE_BEFORE_GAME, selectedProfile).apply()
            }
            applyPerformanceProfile(gameProfileKey(packageName))
            applyGameControllerSettings(packageName)
            applyGameEqualizer(packageName)
            startGameWatch(packageName)
            Toast.makeText(this, tr("Game profile applied", "Применён профиль игры", "Gameprofiel toegepast", "Perfil del juego aplicado") +
                ": ${appLabel(packageName)}", Toast.LENGTH_SHORT).show()
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            Toast.makeText(this, tr("App not found", "Приложение не найдено", "App niet gevonden", "App no encontrada"), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
        } catch (error: Exception) {
            Log.w("PocketHubFavorites", "Launch on display $displayId failed; using default display", error)
            try { startActivity(intent) } catch (_: Exception) { }
        }
    }

    private fun showFavoriteActions(packageName: String, all: List<String>) {
        val bottomDisplay = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays
            .firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY }?.displayId
        val index = all.indexOf(packageName)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += tr("Open on top screen", "Открыть на верхнем экране", "Openen op bovenste scherm", "Abrir en la pantalla superior") to { launchFavorite(packageName, android.view.Display.DEFAULT_DISPLAY) }
        if (bottomDisplay != null) actions += tr("Open on bottom screen", "Открыть на нижнем экране", "Openen op onderste scherm", "Abrir en la pantalla inferior") to { launchFavorite(packageName, bottomDisplay) }
        if (index > 0) actions += tr("Move left", "Сдвинуть влево", "Naar links", "Mover a la izquierda") to {
            saveFavoritePackages(all.toMutableList().apply { add(index - 1, removeAt(index)) }); buildScreen()
        }
        if (index in 0 until all.lastIndex) actions += tr("Move right", "Сдвинуть вправо", "Naar rechts", "Mover a la derecha") to {
            saveFavoritePackages(all.toMutableList().apply { add(index + 1, removeAt(index)) }); buildScreen()
        }
        actions += (if (hasGameProfile(packageName)) tr("Edit game profile", "Изменить профиль игры", "Gameprofiel bewerken", "Editar perfil del juego")
            else tr("Set up game profile", "Настроить профиль игры", "Gameprofiel instellen", "Configurar perfil del juego")) to { openGameProfileEditor(packageName) }
        if (isGameProfile(selectedProfile) && selectedProfile == gameProfileKey(packageName)) {
            actions += tr("Back to normal profile", "Вернуть обычный профиль", "Terug naar normaal profiel", "Volver al perfil normal") to { restoreProfileAfterGame() }
        }
        actions += tr("Remove from favorites", "Убрать из избранного", "Verwijderen uit favorieten", "Quitar de favoritos") to {
            if (hasGameProfile(packageName)) deleteGameProfile(packageName)
            saveFavoritePackages(all.filterNot { it == packageName }); buildScreen()
        }
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(appLabel(packageName), 13f, mint, true), margin(bottom = 8))
        actions.forEach { (title, action) ->
            panel.addView(pill(title, cardBg2, white) { dialog.dismiss(); action() }, margin(bottom = 6))
        }
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.8f).toInt().coerceAtMost(dp(460)), -2)
    }

    /** App list with search. Without onPick it adds a favorite; with onPick it just returns the choice. */
    private fun showFavoriteAppPicker(title: String? = null, onPick: ((String) -> Unit)? = null) {
        val existing = if (onPick == null) favoritePackages().toSet() else emptySet()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in existing }
            .map { it to appLabel(it) }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(title ?: tr("ADD TO FAVORITES", "ДОБАВИТЬ В ИЗБРАННОЕ", "TOEVOEGEN AAN FAVORIETEN", "AÑADIR A FAVORITOS"), 12f, mint, true), margin(bottom = 8))
        val search = android.widget.EditText(this).apply {
            hint = tr("Search", "Поиск", "Zoeken", "Buscar")
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 13f
            background = roundedBackground(cardBg2, border, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        fun fill(filter: String) {
            list.removeAllViews()
            val query = filter.trim().lowercase(Locale.ROOT)
            apps.filter { query.isEmpty() || it.second.lowercase(Locale.ROOT).contains(query) || it.first.contains(query) }
                .forEach { (pkg, name) ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        isFocusable = true
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        background = roundedBackground(cardBg2, border, 12)
                        addView(ImageView(this@MainActivity).apply { setImageDrawable(appIcon(pkg)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                        addView(label(name, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                        setOnClickListener {
                            dialog.dismiss()
                            if (onPick != null) onPick(pkg) else {
                                saveFavoritePackages(favoritePackages() + pkg)
                                buildScreen()
                            }
                        }
                        enableControllerFocus(this, 12)
                    }
                    list.addView(row, margin(bottom = 6))
                }
        }
        fill("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { fill(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        panel.addView(pill(tr("CLOSE", "ЗАКРЫТЬ", "SLUITEN", "CERRAR"), cardBg2, white) { dialog.dismiss() }, margin(top = 8))
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(700)), -2)
    }

    /** PC keyboard tile: full computer layout on the lower screen, typing into the upper screen. */
    /**
     * Equalizer dialog: presets, one slider per band, gain and bass.
     * gamePackage == null edits the global equalizer (with an on/off switch). With a game,
     * it edits that game profile's own equalizer (mode Keep / Own / Off) instead.
     */
    private fun showEqualizerDialog(gamePackage: String? = null, onClosed: (() -> Unit)? = null) {
        val bands = PocketHubEqualizer.bands()
        if (bands == null || bands.count == 0) {
            Toast.makeText(this, tr("This firmware has no system equalizer.", "В этой прошивке нет системного эквалайзера.",
                "Deze firmware heeft geen systeemequalizer.", "Este firmware no tiene ecualizador del sistema."), Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val gameKey = gamePackage?.let { gameProfileKey(it).lowercase(Locale.ROOT) }
        // Settings are read from and written to these keys (global or this game's).
        val levelsKey = gameKey?.let { "tune_eq_levels_$it" } ?: PocketHubEqualizer.KEY_LEVELS
        val preampKey = gameKey?.let { "tune_eq_preamp_$it" } ?: PocketHubEqualizer.KEY_PREAMP
        val bassKey = gameKey?.let { "tune_eq_bass_$it" } ?: PocketHubEqualizer.KEY_BASS
        val presetKey = gameKey?.let { "tune_eq_preset_$it" } ?: PocketHubEqualizer.KEY_PRESET
        // A game's equalizer is heard live only while that game's profile is active.
        fun gameActive() = gamePackage != null && selectedProfile == gameProfileKey(gamePackage) &&
            prefs.getString("tune_eq_mode_$gameKey", "keep") == "on"
        fun storedLevels(): IntArray {
            val raw = prefs.getString(levelsKey, null) ?: prefs.getString(PocketHubEqualizer.KEY_LEVELS, null)
            val saved = raw?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            return IntArray(bands.count) { (saved.getOrNull(it) ?: 0).coerceIn(bands.minLevel, bands.maxLevel) }
        }
        fun storedPreamp() = prefs.getInt(preampKey, if (gameKey != null) PocketHubEqualizer.preamp(this) else 0).coerceIn(0, PocketHubEqualizer.MAX_PREAMP_DB)
        fun storedBass() = prefs.getInt(bassKey, if (gameKey != null) PocketHubEqualizer.bass(this) else 0).coerceIn(0, 1000)
        /** Hear the change now: global edits, or a game's edits while that game runs. */
        fun applyNow() {
            if (gameKey != null) {
                if (!gameActive()) return
                prefs.edit()
                    .putString(PocketHubEqualizer.KEY_LEVELS, prefs.getString(levelsKey, null))
                    .putInt(PocketHubEqualizer.KEY_PREAMP, storedPreamp())
                    .putInt(PocketHubEqualizer.KEY_BASS, storedBass())
                    .commit()
            }
            if (PocketHubEqualizer.isEnabled(this)) PocketHubEqualizer.applySaved(this)
        }
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, bg)).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), border)
            }
        }
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(label("🎚  " + tr("EQUALIZER", "ЭКВАЛАЙЗЕР", "EQUALIZER", "ECUALIZADOR") +
            (gamePackage?.let { " · " + appLabel(it) } ?: ""), 13f, mint, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        val enableSwitch = Switch(this).apply {
            text = tr("On", "Вкл.", "Aan", "Activo"); textSize = 11f; setTextColor(white)
            isChecked = PocketHubEqualizer.isEnabled(this@MainActivity)
        }
        if (gamePackage == null) headRow.addView(enableSwitch)
        panel.addView(headRow, margin(bottom = 2))
        panel.addView(label(if (gamePackage == null) tr("Shapes the sound of everything that plays on the device.",
            "Меняет звучание всего, что играет на устройстве.",
            "Past het geluid aan van alles wat op het apparaat speelt.",
            "Ajusta el sonido de todo lo que suena en el dispositivo.")
        else tr("Used while this game's profile is active. When the game closes, your usual equalizer comes back.",
            "Действует, пока активен профиль этой игры. Когда игра закрывается, возвращается обычный эквалайзер.",
            "Actief zolang het profiel van deze game actief is. Na het sluiten komt je gewone equalizer terug.",
            "Se usa mientras el perfil de este juego está activo. Al cerrarlo vuelve tu ecualizador habitual."), 9.5f, muted), margin(bottom = 8))

        val bandArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (gameKey != null) {
            // Keep = don't touch the equalizer for this game; Own = these settings; Off = no equalizer.
            val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val modeButtons = linkedMapOf<String, TextView>()
            fun paintModes() {
                val mode = prefs.getString("tune_eq_mode_$gameKey", "keep") ?: "keep"
                modeButtons.forEach { (code, view) ->
                    val on = code == mode
                    view.background = roundedBackground(if (on) mint else cardBg2, if (on) mint else border, 12)
                    view.setTextColor(if (on) onAccentInk() else white)
                }
                bandArea.alpha = if (mode == "on") 1f else 0.45f
            }
            listOf("keep" to tr("Keep", "Не менять", "Behouden", "Mantener"),
                "on" to tr("Own settings", "Свой", "Eigen", "Propio"),
                "off" to tr("Off", "Выключить", "Uit", "Apagado")).forEachIndexed { index, (code, name) ->
                val button = pill(name, cardBg2, white) {
                    prefs.edit().putString("tune_eq_mode_$gameKey", code).apply()
                    // First time "Own": start from the current global settings.
                    if (code == "on" && !prefs.contains(levelsKey)) {
                        prefs.edit().putString(levelsKey, prefs.getString(PocketHubEqualizer.KEY_LEVELS, "") ?: "")
                            .putInt(preampKey, PocketHubEqualizer.preamp(this)).putInt(bassKey, PocketHubEqualizer.bass(this)).apply()
                    }
                    paintModes()
                    val pkg = gamePackage!!
                    if (selectedProfile == gameProfileKey(pkg)) applyGameEqualizer(pkg)
                }.apply { textSize = 10f; minHeight = dp(38) }
                modeButtons[code] = button
                modeRow.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
            }
            panel.addView(modeRow, margin(bottom = 8))
            panel.post { paintModes() }
        }
        panel.addView(bandArea)

        val levels = storedLevels()
        val sliders = mutableListOf<SeekBar>()
        val presetButtons = mutableListOf<Pair<String, TextView>>()
        fun markPreset(code: String) {
            prefs.edit().putString(presetKey, code).apply()
            presetButtons.forEach { (c, view) ->
                val on = c == code
                view.background = roundedBackground(if (on) mint else cardBg2, if (on) mint else border, 12)
                view.setTextColor(if (on) onAccentInk() else white)
            }
        }
        fun pushLive() {
            prefs.edit().putString(levelsKey, levels.joinToString(",")).apply()
            applyNow()
        }
        fun showLevels() {
            sliders.forEachIndexed { index, bar -> bar.progress = (levels[index] - bands.minLevel) / 100 }
        }

        // Presets: flat plus the device's own presets.
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presetCodes = listOf("flat" to tr("Flat", "Ровно", "Vlak", "Plano")) +
            bands.presets.mapIndexed { index, name -> index.toString() to name }
        presetCodes.forEach { (code, name) ->
            val button = label(name, 11f, white, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener {
                    val target = if (code == "flat") IntArray(bands.count) else code.toIntOrNull()?.let { PocketHubEqualizer.presetLevels(it, bands) }
                    if (target != null) {
                        target.forEachIndexed { i, v -> levels[i] = v.coerceIn(bands.minLevel, bands.maxLevel) }
                        showLevels()
                        pushLive()
                        markPreset(code)
                    }
                }
                enableControllerFocus(this, 12)
            }
            presetButtons += code to button
            presetRow.addView(button, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) })
        }
        bandArea.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        }, margin(bottom = 8))

        // One row per band: frequency, slider, dB.
        for (band in 0 until bands.count) {
            val hz = bands.centerHz[band]
            val freq = if (hz >= 1000) "${"%.1f".format(Locale.ROOT, hz / 1000f).removeSuffix(".0")} kHz" else "$hz Hz"
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(freq, 11f, white, true), LinearLayout.LayoutParams(dp(64), -2))
            val value = label("", 11f, mint, true).apply { gravity = Gravity.END }
            fun showDb(level: Int) {
                val db = level / 100
                value.text = (if (db > 0) "+" else "") + "$db dB"
            }
            val bar = SeekBar(this).apply {
                max = (bands.maxLevel - bands.minLevel) / 100
                progress = (levels[band] - bands.minLevel) / 100
                progressTintList = ColorStateList.valueOf(mint)
                styleTouchSlider(this, mint, thumbDp = 24, minHeightDp = 46)
                enableControllerFocus(this, 7)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) { }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { }
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val level = bands.minLevel + progress * 100
                        showDb(level)
                        if (fromUser) {
                            levels[band] = level
                            pushLive()
                            markPreset(PocketHubEqualizer.PRESET_CUSTOM)
                        }
                    }
                })
            }
            showDb(levels[band])
            sliders += bar
            row.addView(bar, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(value, LinearLayout.LayoutParams(dp(58), -2))
            bandArea.addView(row)
        }

        // Pre-amp: the system equalizer lowers loudness, this brings it back (a limiter keeps it clean).
        val gainRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gainRow.addView(label(tr("Gain", "Усиление", "Versterking", "Ganancia"), 11f, white, true), LinearLayout.LayoutParams(dp(64), -2))
        val gainValue = label("+${storedPreamp()} dB", 11f, mint, true).apply { gravity = Gravity.END }
        gainRow.addView(SeekBar(this).apply {
            max = PocketHubEqualizer.MAX_PREAMP_DB
            progress = storedPreamp()
            progressTintList = ColorStateList.valueOf(cyan)
            styleTouchSlider(this, cyan, thumbDp = 24, minHeightDp = 46)
            enableControllerFocus(this, 7)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) { }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    gainValue.text = "+$progress dB"
                    if (fromUser) {
                        prefs.edit().putInt(preampKey, progress).apply()
                        applyNow()
                    }
                }
            })
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        gainRow.addView(gainValue, LinearLayout.LayoutParams(dp(58), -2))
        bandArea.addView(gainRow)

        // Bass boost.
        val bassRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bassRow.addView(label(tr("Bass", "Басы", "Bas", "Graves"), 11f, white, true), LinearLayout.LayoutParams(dp(64), -2))
        val bassValue = label("${storedBass() / 10}%", 11f, mint, true).apply { gravity = Gravity.END }
        bassRow.addView(SeekBar(this).apply {
            max = 100
            progress = storedBass() / 10
            progressTintList = ColorStateList.valueOf(mint)
            styleTouchSlider(this, mint, thumbDp = 24, minHeightDp = 46)
            enableControllerFocus(this, 7)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) { }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    bassValue.text = "$progress%"
                    if (fromUser) {
                        prefs.edit().putInt(bassKey, progress * 10).apply()
                        applyNow()
                    }
                }
            })
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        bassRow.addView(bassValue, LinearLayout.LayoutParams(dp(58), -2))
        bandArea.addView(bassRow, margin(bottom = 8))

        markPreset(prefs.getString(presetKey, "flat") ?: "flat")
        val engineView = label("", 9f, muted).apply { setPadding(0, 0, 0, dp(6)) }
        fun showEngine() {
            engineView.text = when (PocketHubEqualizer.engine) {
                "dp" -> tr("Engine: Dynamics (EQ + gain + limiter)", "Режим: Dynamics (эквалайзер + усиление + лимитер)", "Engine: Dynamics (EQ + versterking + limiter)", "Motor: Dynamics (EQ + ganancia + limitador)")
                "eq" -> tr("Engine: classic equalizer", "Режим: классический эквалайзер", "Engine: klassieke equalizer", "Motor: ecualizador clásico")
                else -> ""
            }
        }
        showEngine()
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            PocketHubEqualizer.setEnabled(this, checked)
            uiHandler.postDelayed({ showEngine() }, 700L)
        }
        if (gamePackage == null) panel.addView(engineView)
        panel.addView(pill(tr("DONE", "ГОТОВО", "KLAAR", "LISTO"), mint, bg) { dialog.dismiss() })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel); setBackgroundColor(Color.TRANSPARENT) }
        dialog.setContentView(scroll)
        prepareDialogNavigation(dialog)
        dialog.setOnDismissListener {
            if (activeCustomDialog === dialog) activeCustomDialog = null
            if (controlPage && gamePackage == null) buildScreen()
            onClosed?.invoke()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(640)), (resources.displayMetrics.heightPixels * 0.9f).toInt())
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun togglePcKeyboard() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, tr(
                "Allow PocketHub to display over other apps",
                "Разреши PocketHub показ поверх других окон",
                "Sta PocketHub toe boven andere apps te tonen",
                "Permite que PocketHub se muestre sobre otras apps"
            ), Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        try { PcKeyboardOverlayService.toggle(this) } catch (error: Exception) {
            Log.w("PocketHubPcKeyboard", "Could not start the PC keyboard", error)
        }
    }

    private fun dockAction(icon: String, title: String, description: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            background = roundedBackground(cardBg2, border, if (layoutTheme == "eightbit") 4 else 16)
            // Obsidian: about 10 % taller buttons and a larger icon.
            val obsidianDock = layoutTheme == "obsidian" || layoutTheme == "chrono" || layoutTheme == "blueprint" || layoutTheme == "cartridge" || layoutTheme == "abyss" || layoutTheme == "hifi" || layoutTheme == "hud" || layoutTheme == "bauhaus" || layoutTheme == "jade"
            setPadding(dp(8), dp(if (obsidianDock) 6 else 5), dp(8), dp(if (obsidianDock) 6 else 5))
            elevation = if (flatTheme()) 0f else dp(3).toFloat()
            val neoKind = if (obsidianDock) when (icon) {
                "+" -> "plus"; "?" -> "help"; "‹" -> "back"; "▤" -> "screens"; "▣" -> "display"
                "☼" -> "brightness"; "♫" -> "sound"; "⚙" -> "gear"; else -> null
            } else null
            val disc: View = if (neoKind != null) LivingIconView(this@MainActivity, neoKind, mint)
            else label(icon, 20f, mint, true).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(cardBg2, border, 20)
            }
            addView(disc, LinearLayout.LayoutParams(dp(if (obsidianDock) 42 else 38), dp(if (obsidianDock) 42 else 38)))
            addView(label(title, 10f, white, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            enableControllerFocus(this, 18)
        }

    private fun captureBothScreens(onFinished: (() -> Unit)? = null) {
        // No "capturing" toast: it would end up in the screenshot itself.
        Thread {
            try {
                val captureDir = File(cacheDir, "screen-captures").apply { mkdirs() }
                captureDir.listFiles()?.forEach { it.delete() }
                val displayIds = resolvePhysicalCaptureDisplayIds()
                val topFile = File(captureDir, "top.png")
                val bottomFile = File(captureDir, "bottom.png")
                val command = """
                    set -e
                    screencap -p -d ${displayIds.first} '${topFile.absolutePath}'
                    screencap -p -d ${displayIds.second} '${bottomFile.absolutePath}'
                    chmod 644 '${topFile.absolutePath}' '${bottomFile.absolutePath}'
                    test -s '${topFile.absolutePath}'
                    test -s '${bottomFile.absolutePath}'
                    echo CAPTURE_OK
                """.trimIndent()
                val result = runRootCommand(command)
                if (!result.first || !result.second.contains("CAPTURE_OK") || topFile.length() < 8 || bottomFile.length() < 8) {
                    throw IllegalStateException(result.second.ifBlank { "Screen capture failed" })
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) showScreenshotPicker(topFile, bottomFile, onFinished) else onFinished?.invoke() }
            } catch (error: Exception) {
                Log.e("PocketHubScreenshot", "Could not capture both displays", error)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(
                        this,
                        tr("Could not capture both screens", "Не удалось снять оба экрана", "Beide schermen konden niet worden vastgelegd", "No se pudieron capturar ambas pantallas"),
                        Toast.LENGTH_LONG
                    ).show()
                    onFinished?.invoke()
                }
            }
        }.apply { isDaemon = true; name = "PocketHub-screen-capture"; start() }
    }

    private fun resolvePhysicalCaptureDisplayIds(): Pair<Long, Long> {
        val surfaceResult = runRootCommand("dumpsys SurfaceFlinger --display-id")
        check(surfaceResult.first) { surfaceResult.second.ifBlank { "Cannot list physical displays" } }
        val validPhysicalIds = Regex("""Display\s+(\d+)\s+\(HWC display[^)]*\):""")
            .findAll(surfaceResult.second).mapNotNull { it.groupValues[1].toLongOrNull() }.toSet()
        val displayResult = runRootCommand("dumpsys display")
        check(displayResult.first) { displayResult.second.ifBlank { "Cannot map logical displays" } }
        val logicalToPhysical = displayResult.second.split("Display Power Controller:").mapNotNull { section ->
            val logicalId = Regex("""mDisplayId=(\d+)""").find(section)?.groupValues?.get(1)?.toIntOrNull()
            val physicalId = Regex("""mUniqueDisplayId=local:(\d+)""").find(section)?.groupValues?.get(1)?.toLongOrNull()
            if (logicalId != null && physicalId != null) logicalId to physicalId else null
        }.toMap()
        val displays = getSystemService(DisplayManager::class.java).displays
            .filter { it.state == android.view.Display.STATE_ON && it.flags and android.view.Display.FLAG_PRESENTATION != 0 }
            .sortedByDescending { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        check(displays.size >= 2) { "Two active displays were not found" }
        val topId = logicalToPhysical[displays.first().displayId]
        val bottomId = logicalToPhysical[displays.last().displayId]
        check(topId != null && bottomId != null && topId != bottomId) { "Cannot map upper and lower displays" }
        check(topId in validPhysicalIds && bottomId in validPhysicalIds) { "Display IDs are not accepted by screencap" }
        return topId to bottomId
    }

    private fun showScreenshotPicker(topFile: File, bottomFile: File, onClosed: (() -> Unit)? = null) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(tr("Choose a screen to save", "Выбери экран для сохранения", "Kies welk scherm je wilt opslaan", "Elige qué pantalla guardar"), 17f, white, true), margin(bottom = 9))
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        options.addView(screenshotPreviewOption(
            tr("Top screen", "Верхний экран", "Bovenste scherm", "Pantalla superior"), topFile
        ) {
            dialog.dismiss()
            saveScreenshot(topFile, bottomFile, "top")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        options.addView(screenshotPreviewOption(
            tr("Bottom screen", "Нижний экран", "Onderste scherm", "Pantalla inferior"), bottomFile
        ) {
            dialog.dismiss()
            saveScreenshot(bottomFile, topFile, "bottom")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        panel.addView(options)
        panel.addView(pill(
            tr("SAVE BOTH", "СОХРАНИТЬ ОБА", "BEIDE OPSLAAN", "GUARDAR AMBAS"), mint, bg
        ) {
            dialog.dismiss()
            saveBothScreens(topFile, bottomFile)
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(8); gravity = Gravity.CENTER })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        if (onClosed != null) dialog.setOnDismissListener { uiHandler.postDelayed({ onClosed() }, 150L) }
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(dp(720)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun screenshotPreviewOption(title: String, file: File, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = title
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(cardBg2, border, 15)
            elevation = if (flatTheme()) 0f else dp(3).toFloat()
            setOnClickListener { action() }
            addView(label(title, 13f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(30)))
            val preview = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = title
                setImageBitmap(decodeScreenshotPreview(file))
            }
            addView(preview, LinearLayout.LayoutParams(-1, dp(148)))
            enableControllerFocus(this, 15)
        }

    private fun decodeScreenshotPreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 700 || bounds.outHeight / sample > 500) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun saveScreenshot(selectedFile: File, otherFile: File, screen: String) {
        val outputName = "PocketHub_${System.currentTimeMillis()}_$screen.png"
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveScreenshotToGallery(selectedFile, outputName)
                } else {
                    val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PocketHub").apply { mkdirs() }
                    selectedFile.copyTo(File(directory, outputName), overwrite = true)
                }
                selectedFile.delete()
                otherFile.delete()
                runOnUiThread {
                    Toast.makeText(this, tr("Screenshot saved", "Скриншот сохранён", "Schermafbeelding opgeslagen", "Captura guardada"), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Log.e("PocketHubScreenshot", "Could not save screenshot", error)
                runOnUiThread {
                    Toast.makeText(this, tr("Could not save screenshot", "Не удалось сохранить скриншот", "Schermafbeelding opslaan mislukt", "No se pudo guardar la captura"), Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; this.name = "PocketHub-save-screenshot"; start() }
    }

    private fun saveBothScreens(topFile: File, bottomFile: File) {
        val timestamp = System.currentTimeMillis()
        Thread {
            val savedUris = mutableListOf<Uri>()
            val legacyFiles = mutableListOf<File>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    savedUris += saveScreenshotToGallery(topFile, "PocketHub_${timestamp}_top.png")
                    savedUris += saveScreenshotToGallery(bottomFile, "PocketHub_${timestamp}_bottom.png")
                } else {
                    val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PocketHub").apply { mkdirs() }
                    val topDestination = File(directory, "PocketHub_${timestamp}_top.png")
                    val bottomDestination = File(directory, "PocketHub_${timestamp}_bottom.png")
                    topFile.copyTo(topDestination, overwrite = true)
                    legacyFiles += topDestination
                    bottomFile.copyTo(bottomDestination, overwrite = true)
                    legacyFiles += bottomDestination
                    android.media.MediaScannerConnection.scanFile(
                        this, legacyFiles.map { it.absolutePath }.toTypedArray(), arrayOf("image/png", "image/png"), null
                    )
                }
                topFile.delete()
                bottomFile.delete()
                runOnUiThread {
                    Toast.makeText(this, tr("Both screenshots saved", "Оба скриншота сохранены", "Beide schermafbeeldingen opgeslagen", "Ambas capturas guardadas"), Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                savedUris.forEach { contentResolver.delete(it, null, null) }
                legacyFiles.forEach { it.delete() }
                Log.e("PocketHubScreenshot", "Could not save both screenshots", error)
                runOnUiThread {
                    Toast.makeText(this, tr("Could not save both screenshots", "Не удалось сохранить оба скриншота", "Beide schermafbeeldingen opslaan mislukt", "No se pudieron guardar ambas capturas"), Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; name = "PocketHub-save-both-screenshots"; start() }
    }

    private fun saveScreenshotToGallery(file: File, outputName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PocketHub")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery image")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("Could not write gallery image")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun storageFooterCard(): LinearLayout = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        val ring = MetricRingView(this@MainActivity, Color.rgb(191, 163, 255)).apply {
            setPercent(percentFromFraction(storageInfo()))
        }
        storageRingView = ring
        addView(ring, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) })
        val values = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        values.addView(label(tr("STORAGE", "НАКОПИТЕЛЬ", "OPSLAG", "ALMACENAMIENTO"), 9f, muted, true))
        val value = label("—", 12f, white, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
        storageValueView = value
        values.addView(value)
        addView(values, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun ramFooterCard(): LinearLayout = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        val ring = MetricRingView(this@MainActivity, Color.rgb(90, 201, 255)).apply {
            setPercent(percentFromFraction(memoryInfo()))
        }
        ramRingView = ring
        addView(ring, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) })
        val values = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        values.addView(label(tr("RAM USED", "ЗАНЯТО ОЗУ", "RAM GEBRUIKT", "RAM USADA"), 9f, muted, true))
        val value = label(memoryInfo(), 12f, white, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }
        memoryValueView = value
        values.addView(value)
        values.addView(label(tr("SWAP FILE · USED / FREE", "SWAP-ФАЙЛ · ЗАНЯТО / СВОБОДНО", "SWAPBESTAND · GEBRUIKT / VRIJ", "ARCHIVO SWAP · USADO / LIBRE"), 8f, muted, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        val swapValue = label(swapInfo(), 10f, white, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        swapValueView = swapValue
        values.addView(swapValue)
        addView(values, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun showBrightnessDialog() {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label("☼  ${tr("DISPLAY BRIGHTNESS", "ЯРКОСТЬ ЭКРАНОВ", "SCHERMHELDERHEID")}", 12f, mint, true), margin(bottom = 7))
        panel.addView(label(tr("Adjust both screens independently", "Настройка верхнего и нижнего экранов", "Stel beide schermen afzonderlijk in"), 18f, white, true), margin(bottom = 10))
        panel.addView(buildBrightnessControls(), margin(bottom = 8))
        panel.addView(pill(tr("DONE", "ГОТОВО", "KLAAR"), mint, bg) { dialog.dismiss() })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt().coerceAtMost(dp(620)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun buildBrightnessControls(): LinearLayout {
        val outer = card().apply { setPadding(dp(10), dp(9), dp(10), dp(7)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(tr("BRIGHTNESS", "ЯРКОСТЬ", "HELDERHEID"), 10f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label(tr("TOP / BOTTOM DISPLAY", "ВЕРХНИЙ / НИЖНИЙ ЭКРАН", "BOVEN / ONDERSTE SCHERM"), 10f, muted, true))
        outer.addView(header, margin(bottom = 3))
        if (rootAccessGranted != true) {
            val message = when (rootAccessGranted) {
                false -> tr(
                    "Magisk has not granted root access to PocketHub. Allow it in Magisk → Superuser, then reopen this panel.",
                    "Magisk не разрешил root-доступ для PocketHub. Разреши его в Magisk → Superuser и открой эту панель снова.",
                    "Magisk heeft PocketHub geen roottoegang gegeven. Sta dit toe via Magisk → Superuser en open dit paneel opnieuw."
                )
                else -> tr("Checking root access…", "Проверяю root-доступ…", "Roottoegang controleren…")
            }
            outer.addView(label(message, 11f, white).apply { setPadding(0, dp(7), 0, dp(8)) })
            outer.addView(pill(tr("CHECK ROOT", "ПРОВЕРИТЬ ROOT", "ROOT CONTROLEREN"), cardBg2, mint) {
                checkRootAccessAsync()
            }.apply { minHeight = dp(42) })
            return outer
        }
        // One full-width row per panel gives each brightness control a longer,
        // more precise travel distance than the previous side-by-side layout.
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brightnessSlidersContainer = sliders
        val loading = label(tr("Finding backlight controls…", "Поиск регуляторов подсветки…", "Achterlichtregelaars zoeken…"), 11f, muted).apply { setPadding(0, dp(3), 0, dp(3)) }
        outer.addView(loading)
        outer.addView(sliders)
        Thread {
            val nodes = backlightNodes()
            runOnUiThread {
                if (isFinishing || isDestroyed || brightnessSlidersContainer !== sliders) return@runOnUiThread
                outer.removeView(loading)
                sliders.removeAllViews()
                when {
                    nodes.size >= 2 -> {
                        addInlineBrightnessSlider(sliders, tr("Top display", "Верхний экран", "Bovenste scherm"), nodes[0])
                        addInlineBrightnessSlider(sliders, tr("Bottom display", "Нижний экран", "Onderste scherm"), nodes[1])
                    }
                    nodes.size == 1 -> {
                        addInlineBrightnessSlider(sliders, tr("Display backlight", "Подсветка", "Schermverlichting"), nodes[0])
                        loading.text = tr("Firmware exposes only one backlight control.", "Прошивка предоставляет только один регулятор подсветки.", "De firmware biedt slechts één helderheidsregelaar.")
                        outer.addView(loading, margin(top = 2))
                    }
                    else -> {
                        loading.text = tr("Backlight controls are unavailable through sysfs.", "Регуляторы подсветки недоступны через sysfs.", "Helderheidsregelaars zijn niet beschikbaar via sysfs.")
                        outer.addView(loading, margin(top = 2))
                    }
                }
            }
        }.start()
        return outer
    }

    private fun addInlineBrightnessSlider(parent: LinearLayout, title: String, node: BacklightNode) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title, 13f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val pct = (node.current * 100f / node.maximum).toInt().coerceIn(1, 100)
        val value = label("$pct%", 14f, mint, true)
        heading.addView(value)
        item.addView(heading)
        val slider = SeekBar(this).apply {
            max = 100
            // Never allow 0 %: a fully dark panel can't be seen to turn it back up.
            min = 1
            progress = pct
            contentDescription = "$title, яркость"
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "$progress%"
                    if (fromUser) scheduleBacklightWrite(node, progress)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    scheduleBacklightWrite(node, seekBar.progress, immediate = true)
                }
            })
        }
        styleTouchSlider(slider, mint, thumbDp = 30, minHeightDp = 68)
        enableControllerFocus(slider, 8)
        item.addView(slider, LinearLayout.LayoutParams(-1, dp(68)))
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
    }

    /** Large tactile thumb with enough horizontal inset to keep its outline inside the card. */
    private fun styleTouchSlider(slider: SeekBar, tint: Int, thumbDp: Int = 30, minHeightDp: Int = 68) {
        val diameter = dp(thumbDp)
        if (layoutTheme == "jade") {
            // Jade: a soft rounded track with a green fill and a leaf-shaped knob.
            slider.setThumb(JadeThumbDrawable(tint, resources.displayMetrics.density))
            val trackHeight = dp(8)
            val back = GradientDrawable().apply { setColor(blendColor(cardBg2, Color.BLACK, 0.2f)); cornerRadius = dp(4).toFloat(); setStroke(dp(1), LivingIconView.withAlpha(tint, 40)) }
            val fillBar = android.graphics.drawable.ClipDrawable(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(blendColor(tint, Color.BLACK, 0.35f), tint)).apply { cornerRadius = dp(4).toFloat() }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            slider.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(back, fillBar)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, trackHeight)
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            slider.progressTintList = null
            slider.progressBackgroundTintList = null
            slider.thumbTintList = null
            slider.splitTrack = false
            slider.setThumbOffset(dp(14))
            slider.minimumHeight = dp(minHeightDp)
            slider.setPadding(dp(16), dp(8), dp(16), dp(8))
            return
        }
        if (layoutTheme == "bauhaus") {
            // Bauhaus: a square white bar, filled in yellow, with a round red knob.
            slider.setThumb(BauhausThumbDrawable(resources.displayMetrics.density))
            val trackHeight = dp(6)
            val back = GradientDrawable().apply { setColor(Color.rgb(70, 70, 68)) }
            val fillBar = android.graphics.drawable.ClipDrawable(GradientDrawable().apply { setColor(tint) }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            slider.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(back, fillBar)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, trackHeight)
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            slider.progressTintList = null
            slider.progressBackgroundTintList = null
            slider.thumbTintList = null
            slider.splitTrack = false
            slider.setThumbOffset(dp(13))
            slider.minimumHeight = dp(minHeightDp)
            slider.setPadding(dp(16), dp(8), dp(16), dp(8))
            return
        }
        if (layoutTheme == "hud") {
            // Hologram: a hairline ruler with ticks, a bright projected fill and a diamond knob.
            val density = resources.displayMetrics.density
            slider.setThumb(HudThumbDrawable(tint, density))
            val trackHeight = dp(14)
            val dim = HudTrackDrawable(tint, false, density)
            val lit = android.graphics.drawable.ClipDrawable(HudTrackDrawable(tint, true, density), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            slider.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(dim, lit)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, trackHeight)
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            slider.progressTintList = null
            slider.progressBackgroundTintList = null
            slider.thumbTintList = null
            slider.splitTrack = false
            slider.setThumbOffset(dp(13))
            slider.minimumHeight = dp(minHeightDp)
            slider.setPadding(dp(16), dp(8), dp(16), dp(8))
            return
        }
        if (layoutTheme == "hifi") {
            // Hi-Fi: a VU-meter row of LED segments and a mixing-desk fader cap.
            val density = resources.displayMetrics.density
            slider.setThumb(FaderCapDrawable(tint, density))
            val trackHeight = dp(10)
            val dim = LedSegmentDrawable(false, density)
            val lit = android.graphics.drawable.ClipDrawable(LedSegmentDrawable(true, density), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            slider.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(dim, lit)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, trackHeight)
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            slider.progressTintList = null
            slider.progressBackgroundTintList = null
            slider.thumbTintList = null
            slider.splitTrack = false
            slider.setThumbOffset(dp(8))
            slider.minimumHeight = dp(minHeightDp)
            val safeInset = dp(14)
            slider.setPadding(safeInset, dp(8), safeInset, dp(8))
            return
        }
        if (layoutTheme == "obsidian" || layoutTheme == "chrono" || layoutTheme == "blueprint" || layoutTheme == "cartridge" || layoutTheme == "abyss") {
            // Obsidian slider: thin hairline track, lime-to-blue fill and a ring thumb with a lit core.
            // Chronograph uses the same build: a finer track, gold fill and a crown-like thumb.
            val chrono = layoutTheme == "chrono"
            // Blueprint: a square slider block on a hairline rail.
            val bp = layoutTheme == "blueprint" || layoutTheme == "cartridge"
            val ring = GradientDrawable().apply {
                shape = if (bp) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                if (bp) cornerRadius = dp(if (layoutTheme == "cartridge") 4 else 3).toFloat()
                setSize(diameter, diameter)
                setColor(if (chrono) Color.rgb(8, 7, 6) else Color.rgb(10, 11, 14))
                setStroke(dp(if (chrono) 2 else 3), tint)
            }
            val core = GradientDrawable().apply {
                shape = if (bp) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                setColor(if (chrono) blendColor(tint, Color.WHITE, 0.25f) else tint)
            }
            slider.setThumb(android.graphics.drawable.LayerDrawable(arrayOf(ring, core)).apply {
                val inset = (diameter * (if (chrono) 0.4f else 0.33f)).toInt()
                setLayerInset(1, inset, inset, inset, inset)
            })
            val trackHeight = dp(if (layoutTheme == "abyss") 8 else if (layoutTheme == "cartridge") 6 else if (bp) 3 else if (chrono) 4 else 6)
            val track = GradientDrawable().apply {
                setColor(if (chrono) Color.rgb(20, 18, 15) else Color.rgb(24, 27, 33))
                cornerRadius = trackHeight.toFloat()
                setStroke(dp(1), if (chrono) LivingIconView.withAlpha(mint, 60) else Color.argb(44, 255, 255, 255))
            }
            val fillColors = if (bp) intArrayOf(tint, tint) else if (chrono) intArrayOf(blendColor(tint, Color.BLACK, 0.25f), blendColor(tint, Color.WHITE, 0.2f))
                else intArrayOf(tint, if (tint == cyan) mint else cyan)
            val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, fillColors).apply { cornerRadius = trackHeight.toFloat() }
            val progress = android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            val layers = android.graphics.drawable.LayerDrawable(arrayOf(track, progress)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (i in 0..1) {
                    setLayerHeight(i, trackHeight)
                    setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            slider.progressTintList = null
            slider.progressBackgroundTintList = null
            slider.progressDrawable = layers
            slider.splitTrack = false
            slider.setThumbOffset(diameter / 2)
            slider.minimumHeight = dp(minHeightDp)
            val safeInset = dp(thumbDp / 2 + 7)
            slider.setPadding(safeInset, dp(8), safeInset, dp(8))
            return
        }
        slider.setThumb(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(diameter, diameter)
            setColor(tint)
            setStroke(dp(2), blendColor(tint, white, 0.36f))
        })
        slider.setThumbOffset(diameter / 2)
        slider.minimumHeight = dp(minHeightDp)
        val safeInset = dp(thumbDp / 2 + 7)
        slider.setPadding(safeInset, dp(8), safeInset, dp(8))
    }

    private fun processorCard(title: String, glyph: String, accent: Int): LinearLayout = card().apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(112)
        val compact = layoutTheme == "compact"
        setPadding(dp(if (compact) 9 else 11), dp(if (compact) 8 else 10), dp(if (compact) 9 else 11), dp(if (compact) 7 else 9))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val glyphView = (if (usesLivingIcons()) LivingIconView(this@MainActivity, glyph, accent, animated = false) else MetricGlyphView(this@MainActivity, glyph, accent)).apply {
                background = roundedBackground(blendColor(cardBg, accent, 0.16f), blendColor(cardBg, accent, 0.42f), 10)
            }
            addView(glyphView, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
            addView(label(title, 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            val use = label("—%", 10f, accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedBackground(blendColor(cardBg, accent, 0.16f), blendColor(cardBg, accent, 0.35f), 9)
            }
            if (title == "CPU") cpuUsageValueView = use else gpuUsageValueView = use
            addView(use)
        })
        val frequencyRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(tr("FREQUENCY", "ЧАСТОТА", "FREQUENTIE", "FRECUENCIA"), 10f, muted, true),
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        val freq = label("— MHz", if (compact) 18f else 22f, accent, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (title == "CPU") cpuValueView = freq else gpuValueView = freq
        frequencyRow.addView(freq)
        addView(frequencyRow, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(3); bottomMargin = dp(4)
        })
        val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        details.addView(label(tr("TEMPERATURE", "ТЕМПЕРАТУРА", "TEMPERATUUR", "TEMPERATURA"), 10f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val temp = label("—°C", 14f, white, true).apply { maxLines = 1; gravity = Gravity.END }
        if (title == "CPU") cpuTempValueView = temp else gpuTempValueView = temp
        temp.tag = temp.currentTextColor
        details.addView(temp, LinearLayout.LayoutParams(0, -2, 1f))
        addView(details, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(5) })
    }

    private fun buildAdvanced() {
        // ---- Interface: language, accent, design ----
        content.addView(settingsSection("◐", tr("Interface", "Интерфейс", "Interface", "Interfaz"),
            tr("Language, theme and colors", "Язык, тема и цвета", "Taal, thema en kleuren", "Idioma, tema y colores")), margin(bottom = 6))
        val ui = card()
        ui.addView(label(tr("Language", "Язык", "Taal", "Idioma"), 13f, white, true), margin(bottom = 3))
        ui.addView(label(tr("Choose the app language. Your choice is saved on this device.", "Выбери язык приложения. Выбор сохранится на устройстве.", "Kies de taal van de app. Je keuze wordt op dit apparaat opgeslagen.", "Elige el idioma de la aplicación. La elección se guarda en este dispositivo."), 10f, muted).apply { setPadding(0, 0, 0, dp(6)) }, margin(bottom = 2))
        val languageCodes = listOf("en", "ru", "nl", "es", "de", "fr", "it", "zh")
        val languageNames = listOf("English", "Русский", "Nederlands", "Español", "Deutsch", "Français", "Italiano", "中文")
        val languageSpinner = Spinner(this).apply {
            background = roundedBackground(cardBg2, border, 11)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, languageNames) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(white); view.textSize = 12f; view.setPadding(dp(8), dp(8), dp(8), dp(8))
                    return view
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    view.setTextColor(white); view.setBackgroundColor(cardBg2); view.textSize = 12f; view.setPadding(dp(12), dp(10), dp(12), dp(10))
                    return view
                }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(languageCodes.indexOf(uiLanguage).coerceAtLeast(0), false)
            enableControllerFocus(this, 11)
        }
        var ignoreInitialLanguageSelection = true
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (ignoreInitialLanguageSelection) { ignoreInitialLanguageSelection = false; return }
                val code = languageCodes.getOrNull(position) ?: "en"
                if (code != uiLanguage) {
                    uiLanguage = code
                    getPreferences(Context.MODE_PRIVATE).edit().putString("ui_language", code).apply()
                    buildScreen()
                }
            }
        }
        ui.addView(languageSpinner, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })

        // The colour accent only affects the four classic designs; the others bring their own palette.
        val accentApplies = layoutTheme in ACCENT_THEMES
        if (accentApplies) ui.addView(label(tr("Color accent", "Цветовая схема", "Kleuraccent", "Color de acento"), 13f, white, true), margin(bottom = 3))
        val accents = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("red", tr("Red", "Красная", "Rood", "Rojo"), Color.rgb(255, 76, 94)),
            Triple("blue", tr("Blue", "Синяя", "Blauw", "Azul"), Color.rgb(70, 155, 255)),
            Triple("green", tr("Green", "Зелёная", "Groen", "Verde"), Color.rgb(44, 255, 177)),
            Triple("dark", tr("Dark", "Тёмная", "Donker", "Oscuro"), Color.rgb(145, 165, 184))
        ).forEach { (code, title, preview) ->
            accents.addView(settingsChoice("● $title", accentScheme == code, preview) {
                accentScheme = code
                getPreferences(Context.MODE_PRIVATE).edit().putString("accent_scheme", code).apply()
                applyAppearancePreferences()
                buildScreen()
            }, weight(1f, start = 1, end = 1))
        }
        if (accentApplies) ui.addView(accents, margin(bottom = 12))

        ui.addView(label(tr("Design", "Дизайн", "Ontwerp", "Diseño"), 13f, white, true), margin(bottom = 3))
        ui.addView(label(tr("These change card shape, density, spacing and visual character—not just the colors.", "Эти варианты меняют форму карточек, плотность, отступы и общий вид, а не только цвет.", "Deze opties wijzigen kaartvormen, dichtheid, tussenruimte en uitstraling — niet alleen kleuren."), 10f, muted).apply { setPadding(0, 0, 0, dp(6)) }, margin(bottom = 2))
        val designRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val designOptions = listOf(
            "aurora" to tr("Aurora Live", "Живая аврора", "Levend noorderlicht", "Aurora viva"),
            "dusk" to tr("Dusk", "Сумерки", "Schemering", "Crepúsculo"),
            "obsidian" to tr("Obsidian", "Обсидиан", "Obsidiaan", "Obsidiana"),
            "cartridge" to tr("Cartridge", "Картридж", "Cartridge", "Cartucho"),
            "abyss" to tr("Deep Sea", "Глубина", "Diepzee", "Abismo"),
            "hifi" to tr("Hi-Fi Deck", "Хай-Фай", "Hi-Fi-deck", "Equipo Hi-Fi"),
            "hud" to tr("Hologram", "Голограмма", "Hologram", "Holograma"),
            "bauhaus" to tr("Bauhaus", "Баухаус", "Bauhaus", "Bauhaus"),
            "jade" to tr("Jade", "Нефрит", "Jade", "Jade"),
            "glass" to tr("Glass", "Стекло", "Glas", "Cristal"),
            "compact" to tr("Compact", "Компактный", "Compact", "Compacto"),
            "neon" to tr("Neon console", "Неоновая консоль", "Neon-console", "Consola neón"),
            "solstice" to tr("Solstice", "Солнцестояние", "Zonnewende", "Solsticio"),
            "synthwave" to tr("Synthwave", "Синтвейв", "Synthwave", "Neón retro"),
            "volcanic" to tr("Volcanic", "Вулкан", "Vulkanisch", "Volcánico"),
            "eightbit" to tr("8-Bit Deck", "8-битная консоль", "8-bit-deck", "Consola 8-bit"),
            "brutal" to tr("Neo-Brutal", "Необрутализм", "Neo-brutaal", "Neobrutalista"),
            "brutal_dark" to tr("Neo-Brutal Dark", "Необрутализм тёмный", "Neo-brutaal donker", "Neobrutalista oscuro")
        )
        ui.addView(settingsDropdown(designOptions, layoutTheme) { code ->
            layoutTheme = code
            getPreferences(Context.MODE_PRIVATE).edit().putString("layout_theme", code).apply()
            applyAppearancePreferences()
            buildScreen()
        }, margin(bottom = 12))

        content.addView(ui, margin(bottom = 16))

        // ---- AYANEO button and system components ----
        content.addView(settingsSection("⚙", tr("Button & components", "Кнопка и компоненты", "Knop en onderdelen", "Botón y componentes"),
            tr("AYANEO button and system services", "Кнопка AYANEO и системные службы", "AYANEO-knop en systeemdiensten", "Botón AYANEO y servicios del sistema")), margin(bottom = 6))
        content.addView(ayaButtonSettingsCard(), margin(bottom = 12))
        content.addView(ayaComponentSettingsCard(), margin(bottom = 12))
        content.addView(label(tr(
            "Button remapping, charging fan, Wi-Fi sleep, external monitor and memory are now on the second home page → Device.",
            "Переназначение кнопок, вентилятор при зарядке, Wi-Fi во сне, внешний монитор и память теперь на втором главном экране → «Устройство».",
            "Knoppen, oplaadventilator, wifi-slaap, externe monitor en geheugen staan nu op de tweede startpagina → Apparaat.",
            "Botones, ventilador de carga, wifi en reposo, monitor externo y memoria están ahora en la segunda página → Dispositivo."
        ), 9f, muted), margin(bottom = 16))

        content.addView(settingsSection("♫", tr("Audio", "Звук", "Geluid", "Audio"),
            tr("Volume per screen and volume buttons", "Громкость по экранам и кнопки громкости", "Volume per scherm en volumeknoppen", "Volumen por pantalla y botones")), margin(bottom = 6))
        content.addView(screenAudioSettingsCard(), margin(bottom = 16))

        content.addView(settingsSection("⌨", tr("Dual-screen keyboard", "Двойная клавиатура", "Toetsenbord voor twee schermen", "Teclado de doble pantalla"),
            tr("Placement, languages, theme and keys", "Расположение, языки, тема и клавиши", "Plaats, talen, thema en toetsen", "Posición, idiomas, tema y teclas")), margin(bottom = 6))

        val imePrefs = getSharedPreferences(DualScreenImeService.PREFS, Context.MODE_PRIVATE)
        val bottomSelected = imePrefs.getString(
            DualScreenImeService.KEY_PLACEMENT,
            DualScreenImeService.PLACEMENT_BOTTOM
        ) == DualScreenImeService.PLACEMENT_BOTTOM
        val imeCard = card()
        imeCard.addView(label(tr("KEYBOARD POSITION", "ГДЕ ПОКАЗЫВАТЬ КЛАВИАТУРУ", "POSITIE VAN TOETSENBORD", "POSICIÓN DEL TECLADO"), 10f, muted, true), margin(bottom = 4))
        val placements = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        placements.addView(settingsChoice(
            tr("Bottom screen", "Нижний экран", "Onderste scherm", "Pantalla inferior"), bottomSelected
        ) {
            imePrefs.edit().putString(DualScreenImeService.KEY_PLACEMENT, DualScreenImeService.PLACEMENT_BOTTOM).apply()
            buildScreen()
        }, weight(1f, end = 3))
        placements.addView(settingsChoice(
            tr("Active screen", "Активный экран", "Actief scherm", "Pantalla activa"), !bottomSelected
        ) {
            imePrefs.edit().putString(DualScreenImeService.KEY_PLACEMENT, DualScreenImeService.PLACEMENT_ACTIVE).apply()
            buildScreen()
        }, weight(1f, start = 3))
        imeCard.addView(placements, margin(bottom = 7))

        val keyboardLanguages = listOf(
            DualScreenImeService.LANGUAGE_EN to "EN · English",
            DualScreenImeService.LANGUAGE_RU to "RU · Русский",
            DualScreenImeService.LANGUAGE_DE to "DE · Deutsch",
            DualScreenImeService.LANGUAGE_FR to "FR · Français",
            DualScreenImeService.LANGUAGE_ES to "ES · Español",
            DualScreenImeService.LANGUAGE_ZH to "中文 · 拼音"
        )
        imeCard.addView(label(tr("KEYBOARD LANGUAGES", "ЯЗЫКИ КЛАВИАТУРЫ", "TOETSENBORDTALEN", "IDIOMAS DEL TECLADO"), 10f, muted, true), margin(bottom = 4))
        imeCard.addView(label(
            tr("Choose which languages appear on the keyboard.", "Выбери, какие языки будут доступны на клавиатуре.", "Kies welke talen op het toetsenbord verschijnen.", "Elige qué idiomas aparecerán en el teclado."),
            10f, muted
        ), margin(bottom = 5))
        val languageDropdown = TextView(this).apply {
            text = keyboardLanguageSummary(imePrefs, keyboardLanguages)
            textSize = 12f
            setTextColor(white)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            minHeight = dp(48)
            background = roundedBackground(cardBg2, border, 12)
            setOnClickListener { showKeyboardLanguageDropdown(this, imePrefs, keyboardLanguages) }
        }
        imeCard.addView(languageDropdown, margin(bottom = 7))

        val selectedKeyboardTheme = imePrefs.getString(DualScreenImeService.KEY_THEME, DualScreenImeService.THEME_OCEAN)
            ?: DualScreenImeService.THEME_OCEAN
        imeCard.addView(label(tr("KEYBOARD THEME", "ТЕМА КЛАВИАТУРЫ", "TOETSENBORDTHEMA", "TEMA DEL TECLADO"), 10f, muted, true), margin(bottom = 4))
        val keyboardThemes = listOf(
            Triple(DualScreenImeService.THEME_OCEAN, tr("Ocean", "Океан", "Oceaan", "Océano"), Color.rgb(55, 151, 202)),
            Triple(DualScreenImeService.THEME_GRAPHITE, tr("Graphite", "Графит", "Grafiet", "Grafito"), Color.rgb(128, 136, 147)),
            Triple(DualScreenImeService.THEME_NEON, tr("Neon", "Неон", "Neon", "Neón"), Color.rgb(174, 77, 225)),
            Triple(DualScreenImeService.THEME_LIGHT, tr("Light", "Светлая", "Licht", "Clara"), Color.rgb(111, 180, 218)),
            Triple(DualScreenImeService.THEME_DARK, tr("Dark", "Тёмная", "Donker", "Oscura"), Color.rgb(68, 78, 91)),
            Triple(DualScreenImeService.THEME_TERMINAL, tr("Terminal", "Терминал", "Terminal", "Terminal"), Color.rgb(137, 211, 72)),
            Triple(DualScreenImeService.THEME_ORBIT, tr("Orbit", "Орбита", "Orbit", "Órbita"), Color.rgb(255, 169, 112)),
            Triple(DualScreenImeService.THEME_KEYCAP, tr("Retro keycaps", "Ретро-клавиши", "Retro-toetsen", "Teclas retro"), Color.rgb(255, 122, 52)),
            Triple(DualScreenImeService.THEME_CONSOLE, tr("Starship console", "Пульт звездолёта", "Sterrenschip-console", "Consola estelar"), Color.rgb(255, 153, 0)),
            Triple(DualScreenImeService.THEME_CARTRIDGE, tr("Cartridge", "Картридж", "Cartridge", "Cartucho"), Color.rgb(232, 86, 92)),
            Triple(DualScreenImeService.THEME_TYPEWRITER, tr("Typewriter", "Печатная машинка", "Typemachine", "Máquina de escribir"), Color.rgb(196, 52, 44)),
            Triple(DualScreenImeService.THEME_LINEA, tr("Linea", "Линия", "Linea", "Línea"), Color.rgb(255, 138, 101))
        )
        imeCard.addView(settingsDropdown(keyboardThemes.map { it.first to it.second }, selectedKeyboardTheme) { code ->
            imePrefs.edit().putString(DualScreenImeService.KEY_THEME, code).apply()
            buildScreen()
        }, margin(bottom = 7))

        val toolbarDefaultVisible = imePrefs.getBoolean(DualScreenImeService.KEY_TOOLBAR_DEFAULT_VISIBLE, true)
        imeCard.addView(label(tr("UTILITY BAR WHEN KEYBOARD OPENS", "ПАНЕЛЬ КНОПОК ПРИ ОТКРЫТИИ КЛАВИАТУРЫ", "HULPBALK BIJ OPENEN TOETSENBORD", "BARRA DE ACCIONES AL ABRIR EL TECLADO"), 10f, muted, true), margin(bottom = 4))
        val toolbarDefaultChoices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbarDefaultChoices.addView(settingsChoice(
            tr("Shown", "Показывать", "Tonen", "Mostrar"), toolbarDefaultVisible, mint
        ) {
            imePrefs.edit().putBoolean(DualScreenImeService.KEY_TOOLBAR_DEFAULT_VISIBLE, true).apply()
            buildScreen()
        }, weight(1f, end = 3))
        toolbarDefaultChoices.addView(settingsChoice(
            tr("Hidden", "Скрывать", "Verbergen", "Ocultar"), !toolbarDefaultVisible, cyan
        ) {
            imePrefs.edit().putBoolean(DualScreenImeService.KEY_TOOLBAR_DEFAULT_VISIBLE, false).apply()
            buildScreen()
        }, weight(1f, start = 3))
        imeCard.addView(toolbarDefaultChoices, margin(bottom = 7))

        val selectedKeySize = imePrefs.getString(DualScreenImeService.KEY_SIZE, DualScreenImeService.SIZE_STANDARD)
            ?: DualScreenImeService.SIZE_STANDARD
        imeCard.addView(label(tr("KEY SIZE", "РАЗМЕР КЛАВИШ", "TOETSGROOTTE", "TAMAÑO DE TECLA"), 10f, muted, true), margin(bottom = 4))
        val sizeChoices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            DualScreenImeService.SIZE_SMALL to tr("Small", "Маленькие", "Klein", "Pequeñas"),
            DualScreenImeService.SIZE_STANDARD to tr("Standard", "Обычные", "Standaard", "Estándar"),
            DualScreenImeService.SIZE_LARGE to tr("Large", "Большие", "Groot", "Grandes")
        ).forEachIndexed { index, (code, title) ->
            sizeChoices.addView(settingsChoice(title, selectedKeySize == code) {
                imePrefs.edit().putString(DualScreenImeService.KEY_SIZE, code).apply()
                buildScreen()
            }, weight(1f, start = if (index == 0) 0 else 2, end = if (index == 2) 0 else 2))
        }
        imeCard.addView(sizeChoices, margin(bottom = 7))

        val hapticsEnabled = imePrefs.getBoolean(DualScreenImeService.KEY_HAPTIC, true)
        imeCard.addView(label(tr("TOUCH FEEDBACK", "ОТКЛИК ПРИ НАЖАТИИ", "AANRAAKFEEDBACK", "RESPUESTA TÁCTIL"), 10f, muted, true), margin(bottom = 4))
        val hapticChoices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hapticChoices.addView(settingsChoice(tr("Vibration on", "Вибрация включена", "Trilling aan", "Vibración activada"), hapticsEnabled) {
            imePrefs.edit().putBoolean(DualScreenImeService.KEY_HAPTIC, true).apply()
            buildScreen()
        }, weight(1f, end = 3))
        hapticChoices.addView(settingsChoice(tr("Vibration off", "Вибрация выключена", "Trilling uit", "Vibración desactivada"), !hapticsEnabled) {
            imePrefs.edit().putBoolean(DualScreenImeService.KEY_HAPTIC, false).apply()
            buildScreen()
        }, weight(1f, start = 3))
        imeCard.addView(hapticChoices, margin(bottom = 9))

        val imeButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        imeButtons.addView(pill(tr("Enable keyboard", "Включить клавиатуру", "Toetsenbord inschakelen", "Activar teclado"), cardBg2, white) {
            try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this@MainActivity, tr("Open Android keyboard settings", "Открой настройки клавиатуры Android", "Open Android-toetsenbordinstellingen"), Toast.LENGTH_LONG).show() }
        }, weight(1f, end = 3))
        imeButtons.addView(pill(tr("Select keyboard", "Выбрать клавиатуру", "Toetsenbord kiezen", "Elegir teclado"), mint, bg) {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }, weight(1f, start = 3))
        imeCard.addView(imeButtons)
        content.addView(imeCard, margin(bottom = 16))
        content.addView(settingsSection("ⓘ", tr("About", "О программе", "Over", "Acerca de"), null), margin(bottom = 6))
        content.addView(pill(tr("About", "О программе", "Over", "Acerca de"), cardBg2, white) {
            showAboutDialog()
        }, margin(bottom = 6))
        content.addView(pill(tr("Run setup again", "Пройти начальную настройку заново", "Installatie opnieuw doen", "Repetir la configuración"), cardBg2, white) {
            showSetupWizard()
        }, margin(bottom = 12))
    }

    /**
     * Dual screen control pictograms drawn from the real Pocket DS proportions (millimetres):
     * an open clamshell of two 180 × 102 halves — the lid almost filled by the 7" 16:9
     * screen, the base with the 5" 4:3 screen centred between stick + D-pad (left) and
     * ABXY + stick (right). A lit screen is filled with the accent, a screen that is off is
     * black with a power symbol, and the controller's target screen gets corner brackets
     * plus a letter badge (A = auto).
     */
    private fun screenControlGlyph(kind: String, selected: Boolean): View = object : View(this) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = android.graphics.DashPathEffect(floatArrayOf(7f, 5f), 0f)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Grid in millimetres: 180 wide, 210 tall (lid 102 + hinge 6 + base 102).
            val gw = 180f; val gh = 210f
            val scale = minOf(width / gw, height / gh)
            if (scale <= 0f) return
            val save = canvas.save()
            canvas.translate((width - gw * scale) / 2f, (height - gh * scale) / 2f)
            canvas.scale(scale, scale)

            val accent = if (selected) mint else blendColor(muted, white, 0.25f)
            val dim = blendColor(cardBg, muted, 0.5f)
            val offFill = Color.rgb(6, 6, 9)
            fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, stroke: Float = 3f, dashed: Boolean = false): Paint = p.apply {
                this.color = color; this.style = style; strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                pathEffect = if (dashed) dash else null
                shader = null
            }
            fun rr(r: RectF, radius: Float, pt: Paint) = canvas.drawRoundRect(r, radius, radius, pt)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, stroke: Float = 4f, dashed: Boolean = false) =
                canvas.drawLine(x1, y1, x2, y2, paint(color, Paint.Style.STROKE, stroke, dashed))
            fun dot(x: Float, y: Float, radius: Float, color: Int) = canvas.drawCircle(x, y, radius, paint(color))

            val topOn = kind in setOf("display_both", "display_top", "input_cursor", "input_touchpad", "focus_auto", "focus_top", "focus_bottom")
            val bottomOn = kind in setOf("display_both", "display_bottom", "focus_auto", "focus_top", "focus_bottom")
            val bottomIsPad = kind == "input_cursor" || kind == "input_touchpad"
            val controlsLit = kind.startsWith("focus_")

            // Shells: lid, hinge, base.
            val lid = RectF(2f, 2f, 178f, 102f)
            val base = RectF(2f, 108f, 178f, 208f)
            val shellFill = blendColor(cardBg2, accent, if (selected) .10f else .04f)
            val shellLine = if (selected) accent else border
            rr(lid, 13f, paint(shellFill)); rr(lid, 13f, paint(shellLine, Paint.Style.STROKE, 3f))
            rr(RectF(30f, 101f, 150f, 109f), 3f, paint(blendColor(shellFill, Color.BLACK, .35f)))
            rr(base, 13f, paint(shellFill)); rr(base, 13f, paint(shellLine, Paint.Style.STROKE, 3f))

            // Screens at their real sizes.
            val top = RectF(12.5f, 8.5f, 167.5f, 95.5f)     // 7" 16:9 ≈ 155 × 87 mm
            val bottom = RectF(39f, 115f, 141f, 191f)       // 5" 4:3 ≈ 102 × 76 mm
            fun litScreen(r: RectF) {
                p.reset(); p.isAntiAlias = true
                p.shader = android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom,
                    blendColor(cardBg, accent, if (selected) .78f else .55f), blendColor(cardBg, accent, if (selected) .38f else .24f), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(r, 5f, 5f, p)
                p.shader = null
                line(r.left + 9f, r.top + 9f, r.left + r.width() * 0.3f, r.top + 9f, LivingIconView.withAlpha(Color.WHITE, 120), 3.5f)
                rr(r, 5f, paint(accent, Paint.Style.STROKE, 3f))
            }
            fun offScreen(r: RectF) {
                rr(r, 5f, paint(offFill))
                rr(r, 5f, paint(dim, Paint.Style.STROKE, 3f, dashed = true))
                val cx = r.centerX(); val cy = r.centerY()
                val s = minOf(r.width(), r.height()) * 0.2f
                canvas.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), -60f, 300f, false, paint(dim, Paint.Style.STROKE, 4.5f))
                line(cx, cy - s - 4f, cx, cy - 2f, dim, 4.5f)
            }
            if (topOn) litScreen(top) else offScreen(top)
            if (bottomIsPad) {
                // Dark, but it works as an input surface for the top screen.
                rr(bottom, 5f, paint(offFill))
                rr(bottom, 5f, paint(accent, Paint.Style.STROKE, 3f))
            } else if (bottomOn) litScreen(bottom) else offScreen(bottom)

            // Controls beside the lower screen.
            val ctl = if (controlsLit) accent else dim
            canvas.drawCircle(20f, 130f, 10f, paint(ctl, Paint.Style.STROKE, 3.5f))          // left stick
            rr(RectF(10f, 162f, 30f, 170f), 2f, paint(ctl)); rr(RectF(16f, 156f, 24f, 176f), 2f, paint(ctl)) // D-pad
            dot(160f, 120f, 4.2f, ctl); dot(151f, 129f, 4.2f, ctl); dot(169f, 129f, 4.2f, ctl); dot(160f, 138f, 4.2f, ctl) // ABXY
            canvas.drawCircle(160f, 166f, 10f, paint(ctl, Paint.Style.STROKE, 3.5f))         // right stick

            fun brackets(r: RectF, pad: Float, dashed: Boolean = false) {
                val l = r.left - pad; val t = r.top - pad; val rt = r.right + pad; val b = r.bottom + pad
                val k = minOf(r.width(), r.height()) * 0.26f
                val c = if (selected) white else accent
                line(l, t, l + k, t, c, 4.5f, dashed); line(l, t, l, t + k, c, 4.5f, dashed)
                line(rt, t, rt - k, t, c, 4.5f, dashed); line(rt, t, rt, t + k, c, 4.5f, dashed)
                line(l, b, l + k, b, c, 4.5f, dashed); line(l, b, l, b - k, c, 4.5f, dashed)
                line(rt, b, rt - k, b, c, 4.5f, dashed); line(rt, b, rt, b - k, c, 4.5f, dashed)
            }
            fun badge(x: Float, y: Float, letter: String) {
                val c = if (selected) white else accent
                canvas.drawCircle(x, y, 19f, paint(offFill))
                canvas.drawCircle(x, y, 19f, paint(c, Paint.Style.STROKE, 3.5f))
                text.color = c
                text.textSize = 24f
                canvas.drawText(letter, x, y - (text.descent() + text.ascent()) / 2f, text)
            }
            fun pointer(x: Float, y: Float) {
                val path = Path().apply {
                    moveTo(x, y); lineTo(x, y + 28f); lineTo(x + 7.5f, y + 21f); lineTo(x + 13f, y + 33f)
                    lineTo(x + 18f, y + 30.5f); lineTo(x + 12.5f, y + 19f); lineTo(x + 22f, y + 19f); close()
                }
                canvas.drawPath(path, paint(Color.WHITE))
                canvas.drawPath(path, paint(offFill, Paint.Style.STROKE, 2.5f))
            }

            when (kind) {
                "input_cursor" -> {
                    // Tap a spot on the lower screen = the pointer jumps to the same spot on top.
                    rr(RectF(46f, 122f, 134f, 184f), 3f, paint(LivingIconView.withAlpha(accent, 150), Paint.Style.STROKE, 2.5f, dashed = true))
                    dot(110f, 140f, 6f, accent)
                    line(110f, 140f, 128f, 45f, LivingIconView.withAlpha(accent, 210), 3f, dashed = true)
                    pointer(128f, 30f)
                }
                "input_touchpad" -> {
                    // Slide on the lower screen like a laptop touchpad; the pointer follows.
                    line(39f, 175f, 141f, 175f, LivingIconView.withAlpha(accent, 170), 2.5f)
                    line(90f, 175f, 90f, 191f, LivingIconView.withAlpha(accent, 170), 2.5f)
                    dot(68f, 156f, 7f, accent)
                    line(68f, 156f, 104f, 138f, accent, 4f)
                    line(104f, 138f, 94f, 136f, accent, 4f); line(104f, 138f, 99f, 147f, accent, 4f)
                    dot(70f, 66f, 3f, LivingIconView.withAlpha(Color.WHITE, 90))
                    dot(86f, 56f, 3.8f, LivingIconView.withAlpha(Color.WHITE, 150))
                    pointer(100f, 36f)
                }
                "focus_top" -> {
                    brackets(top, 4f)
                    badge(top.centerX(), top.centerY(), tr("T", "В", "B", "S"))
                }
                "focus_bottom" -> {
                    brackets(bottom, 4f)
                    badge(bottom.centerX(), bottom.centerY(), tr("B", "Н", "O", "I"))
                }
                "focus_auto" -> {
                    brackets(top, 4f, dashed = true)
                    brackets(bottom, 4f, dashed = true)
                    // Between the screens: focus follows the screen you touched last.
                    badge(90f, 105f, "A")
                }
            }
            canvas.restoreToCount(save)
        }
    }.apply { contentDescription = kind }

    /**
     * Small window with the five performance profiles (remapped-button action). Picking one
     * switches the profile in the running PocketHub (so only one CPU limiter runs) and closes.
     */
    private fun showProfileQuickPicker(onClosed: () -> Unit) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, bg)).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), border)
            }
        }
        panel.addView(label("⚡  " + tr("PERFORMANCE PROFILE", "ПРОФИЛЬ ПРОИЗВОДИТЕЛЬНОСТИ", "PRESTATIEPROFIEL", "PERFIL DE RENDIMIENTO"), 12f, mint, true), margin(bottom = 10))
        val main = liveInstance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed && it !== this }
        val current = getPreferences(Context.MODE_PRIVATE).getString("profile", "Balanced") ?: "Balanced"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Eco", "Balanced", "Streaming", "Gaming", "Max").forEachIndexed { index, profile ->
            val accent = profileAccent(profile)
            val active = profile == current
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                background = if (active) roundedBackground(blendColor(cardBg, accent, 0.45f), accent, 16)
                    else roundedBackground(cardBg, border, 16)
                val kind = profile.lowercase(Locale.ROOT)
                if (usesLivingIcons()) addView(LivingIconView(this@MainActivity, kind, accent, animated = active), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(label(profileDisplayName(profile), 11f, if (active) white else muted, true).apply {
                    gravity = Gravity.CENTER; maxLines = 1
                }, margin(top = 4))
                setOnClickListener {
                    if (main != null) main.runOnUiThread {
                        main.applyPerformanceProfile(profile)
                        if (!main.advanced && !main.profileEditorPage && !main.helpPage && !main.controllerPage) main.buildScreen()
                    } else applyPerformanceProfile(profile)
                    Toast.makeText(this@MainActivity, tr("Profile", "Профиль", "Profiel", "Perfil") + ": " + profileDisplayName(profile), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                enableControllerFocus(this, 16)
            }
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        panel.addView(row, margin(bottom = 10))
        // The user's own profiles (Game tuning page), for games PocketHub cannot tell apart.
        var openAutoTune = false
        val tunedList = TunedProfiles.load(this)
        if (tunedList.isNotEmpty()) {
            panel.addView(label(tr("MY PROFILES", "МОИ ПРОФИЛИ", "MIJN PROFIELEN", "MIS PERFILES"), 10f, muted, true), margin(bottom = 5))
            val tunedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tunedList.forEachIndexed { index, p ->
                val active = p.profileKey == current
                val b = pill((if (active) "● " else "") + p.name, if (active) blendColor(cardBg, cyan, 0.4f) else cardBg2, white) {
                    if (active) deactivateTunedProfile() else applyTunedProfile(p, manual = true)
                    dialog.dismiss()
                }.apply { textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; minHeight = dp(44) }
                tunedRow.addView(b, LinearLayout.LayoutParams(dp(150), -2).apply { if (index > 0) marginStart = dp(6) })
            }
            panel.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tunedRow) }, margin(bottom = 10))
        }
        panel.addView(Switch(this).apply {
            text = tr("Quiet mode — fan one level lower, chip kept cooler (for any profile)", "Тихий режим — вентилятор на уровень тише, чип холоднее (для любого профиля)",
                "Stille modus — ventilator een stand lager, chip koeler (voor elk profiel)", "Modo silencioso: ventilador un nivel más bajo, chip más frío (cualquier perfil)")
            textSize = 11f; setTextColor(white)
            isChecked = quietModeOn()
            setOnCheckedChangeListener { _, on -> setQuietMode(on) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomRow.addView(pill("⚡  " + tr("AUTO-TUNE THIS GAME", "АВТОПОДБОР ДЛЯ ИГРЫ", "DEZE GAME AFSTEMMEN", "AUTOAJUSTAR ESTE JUEGO"), mint, bg) {
            openAutoTune = true
            dialog.dismiss()
        }, weight(1.4f, end = 4))
        bottomRow.addView(pill(tr("CLOSE", "ЗАКРЫТЬ", "SLUITEN", "CERRAR"), cardBg2, white) { dialog.dismiss() }, weight(1f, start = 4))
        panel.addView(bottomRow)
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.setOnDismissListener {
            if (activeCustomDialog === dialog) activeCustomDialog = null
            if (openAutoTune) uiHandler.postDelayed({ showAutoTuneDialog(onClosed = onClosed) }, 120L)
            else uiHandler.postDelayed({ onClosed() }, 120L)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(dp(720)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun showScreenControlDialog(onClosed: (() -> Unit)? = null) {
        val mode = rootRead("settings get global dual_display_mode") ?: "1"
        val pointer = rootRead("settings get global dual_display_pointer_mode") ?: "2"
        val touchpad = rootRead("settings get global secondary_screen_as_touchpad") ?: "1"
        val focus = rootRead("settings get global locked_focus_display_id") ?: "none"
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(cardBg, border, if (layoutTheme == "compact") 12 else 20)
        }
        panel.addView(label(tr("▣  DUAL SCREEN CONTROL", "▣  УПРАВЛЕНИЕ ДВУМЯ ЭКРАНАМИ", "▣  DUBBEL SCHERM", "▣  CONTROL DE DOBLE PANTALLA"), 12f, mint, true), margin(bottom = 8))

        fun tileRow(items: List<Triple<String, String, Boolean>>, onSelect: (String) -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            items.forEachIndexed { index, (icon, title, selected) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true; isFocusable = true
                    setPadding(dp(3), dp(5), dp(3), dp(5))
                    background = roundedBackground(if (selected) blendColor(cardBg2, mint, 0.22f) else cardBg2, if (selected) mint else border, 13)
                    contentDescription = title
                    setOnClickListener { onSelect(title) }
                    addView(screenControlGlyph(icon, selected), LinearLayout.LayoutParams(-1, dp(72)))
                    addView(label(title, 10f, if (selected) white else muted, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(22)))
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(104), 1f).apply { if (index > 0) marginStart = dp(7) })
                enableControllerFocus(tile, 16 + index)
            }
            panel.addView(row, margin(bottom = 7))
        }
        panel.addView(label(tr("ACTIVE DISPLAY", "АКТИВНЫЙ ЭКРАН", "ACTIEF SCHERM", "PANTALLA ACTIVA"), 10f, muted, true), margin(bottom = 1))
        panel.addView(label(tr("Which screens are switched on. A dark screen with a power symbol is off.", "Какие экраны включены. Тёмный экран со значком питания выключен.", "Welke schermen aan staan. Een donker scherm met een aan/uit-teken is uit.", "Qué pantallas están encendidas. Una pantalla oscura con el símbolo de encendido está apagada."), 9f, muted), margin(bottom = 5))
        tileRow(listOf(
            Triple("display_both", tr("Both", "Оба", "Beide", "Ambas"), mode == "1"),
            Triple("display_top", tr("Top", "Верхний", "Boven", "Superior"), mode == "3"),
            Triple("display_bottom", tr("Bottom", "Нижний", "Onder", "Inferior"), mode == "4")
        )) { selection ->
            when (selection) {
                tr("Both", "Оба", "Beide", "Ambas") -> {
                    dialog.dismiss()
                    applyDisplaySetting("dual_display_mode", "1", tr("Both screens enabled", "Оба экрана включены", "Beide schermen ingeschakeld", "Ambas pantallas activadas"))
                }
                tr("Top", "Верхний", "Boven", "Superior") -> {
                    dialog.dismiss()
                    applyDisplaySetting("dual_display_mode", "3", tr("Top screen enabled", "Верхний экран включён", "Bovenste scherm ingeschakeld", "Pantalla superior activada"))
                }
                else -> {
                    dialog.dismiss()
                    // The OEM code uses mode 4 on firmware >= 2026-08-19 for a true
                    // system display swap. Mode 0 only disables the main panel and
                    // caused this device to mirror its image onto the lower panel.
                    applyDisplaySetting("dual_display_mode", "4", tr("Bottom screen enabled", "Нижний экран включён", "Onderste scherm ingeschakeld", "Pantalla inferior activada"))
                }
            }
        }

        panel.addView(label(tr("TOP SCREEN INPUT", "УПРАВЛЕНИЕ ВЕРХНИМ ЭКРАНОМ", "INVOER BOVENSTE SCHERM", "ENTRADA DE PANTALLA SUPERIOR"), 10f, muted, true), margin(bottom = 1))
        panel.addView(label(tr("The lower screen goes dark and controls the top one. Cursor: tap a spot and the pointer jumps there. Touchpad: slide like on a laptop.", "Нижний экран гаснет и управляет верхним. Курсор: нажми в точку — указатель прыгнет туда. Тачпад: води пальцем, как на ноутбуке.", "Het onderste scherm gaat uit en bedient het bovenste. Cursor: tik op een plek en de aanwijzer springt erheen. Touchpad: veeg zoals op een laptop.", "La pantalla inferior se apaga y controla la superior. Cursor: toca un punto y el puntero salta allí. Panel táctil: desliza como en un portátil."), 9f, muted), margin(bottom = 5))
        tileRow(listOf(
            Triple("input_cursor", tr("Cursor", "Курсор", "Cursor", "Cursor"), mode == "3" && pointer == "1" && touchpad == "0"),
            Triple("input_touchpad", tr("Touchpad", "Тачпад", "Touchpad", "Panel táctil"), mode == "3" && (touchpad == "1" || pointer == "2"))
        )) { selection ->
            val isTouchpad = selection == tr("Touchpad", "Тачпад", "Touchpad", "Panel táctil")
            dialog.dismiss()
            applyTopScreenInput(isTouchpad)
        }

        panel.addView(label(tr("CONTROLLER FOCUS", "ФОКУС КОНТРОЛЛЕРА", "CONTROLLERFOCUS", "ENFOQUE DEL MANDO"), 10f, muted, true), margin(bottom = 1))
        panel.addView(label(tr("Which screen gets the gamepad buttons. Auto: the screen you touched last.", "На какой экран идут кнопки геймпада. Авто: тот экран, которого касался последним.", "Welk scherm de gamepadknoppen krijgt. Auto: het scherm dat je het laatst aanraakte.", "Qué pantalla recibe los botones del mando. Auto: la última pantalla que tocaste."), 9f, muted), margin(bottom = 5))
        tileRow(listOf(
            Triple("focus_auto", tr("Auto", "Авто", "Auto", "Auto"), focus.isBlank() || focus == "none"),
            Triple("focus_top", tr("Top", "Верхний", "Boven", "Superior"), focus == "0"),
            Triple("focus_bottom", tr("Bottom", "Нижний", "Onder", "Inferior"), focus == "2")
        )) { selection ->
            val value = when (selection) {
                tr("Auto", "Авто", "Auto", "Auto") -> "none"
                tr("Top", "Верхний", "Boven", "Superior") -> "0"
                else -> "2"
            }
            dialog.dismiss()
            applyDisplaySetting("locked_focus_display_id", value, tr("Controller focus updated", "Фокус контроллера изменён", "Controllerfocus gewijzigd", "Enfoque del mando actualizado"))
        }
        panel.addView(pill(tr("DONE", "ГОТОВО", "KLAAR", "LISTO"), cardBg2, white) { dialog.dismiss() })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        if (onClosed != null) {
            dialog.setOnDismissListener {
                if (activeCustomDialog === dialog) activeCustomDialog = null
                // Let the setting's confirmation toast appear before the popup goes away.
                uiHandler.postDelayed({ onClosed() }, 150L)
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(700)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /** Turns the lower screen into a touchpad (or cursor pad) for the upper screen. */
    private fun applyTopScreenInput(isTouchpad: Boolean) {
        // Match AYANEO's verified modes: both use the top-only display mode
        // and keep the touch controller enabled in sysfs. Pointer mode 1
        // selects cursor, while pointer mode 2 selects touchpad.
        val flag = if (isTouchpad) "1" else "0"
        val pointerMode = if (isTouchpad) "2" else "1"
        applyDisplaySetting(
            "dual_display_mode", "3",
            if (isTouchpad) tr("Top screen touchpad enabled", "Тачпад верхнего экрана включён", "Touchpad voor bovenste scherm ingeschakeld", "Panel táctil de pantalla superior activado")
            else tr("Top screen cursor enabled", "Курсор верхнего экрана включён", "Cursor voor bovenste scherm ingeschakeld", "Cursor de pantalla superior activado"),
            "settings put global dual_display_pointer_mode $pointerMode; settings put global secondary_screen_as_touchpad $flag; settings put global locked_focus_display_id 0; echo 1 > /sys/devices/platform/soc/ac0000.qcom,qupv3_1_geni_se/a88000.i2c/i2c-2/2-005d/touchpad_mode"
        )
    }

    /** Touchpad tile: on-screen pad + mouse buttons below, cursor on the upper screen. */
    private fun toggleTouchpad() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, tr(
                "Allow PocketHub to display over other apps",
                "Разреши PocketHub показ поверх других окон",
                "Sta PocketHub toe boven andere apps te tonen",
                "Permite que PocketHub se muestre sobre otras apps"
            ), Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        try { TouchpadOverlayService.toggle(this) } catch (error: Exception) {
            Log.w("PocketHubTouchpad", "Could not start the touchpad", error)
        }
    }

    /** Keyboard tile: opens PocketHub Keyboard on the lower screen, typing into the upper screen. */
    private fun toggleFreeKeyboard() {
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val ime = DualScreenImeService.instance
        if (!defaultIme.startsWith("$packageName/") || ime == null) {
            Toast.makeText(this, tr(
                "Select PocketHub Keyboard as the keyboard first",
                "Сначала выбери PocketHub Keyboard клавиатурой по умолчанию",
                "Kies eerst PocketHub Keyboard als toetsenbord",
                "Primero elige PocketHub Keyboard como teclado"
            ), Toast.LENGTH_LONG).show()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
            try {
                if (enabled) imm?.showInputMethodPicker() else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (_: Exception) { }
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, tr(
                "Allow PocketHub to display over other apps",
                "Разреши PocketHub показ поверх других окон",
                "Sta PocketHub toe boven andere apps te tonen",
                "Permite que PocketHub se muestre sobre otras apps"
            ), Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        if (ime.isFreeTyping()) {
            ime.stopFreeTyping()
        } else if (!ime.startFreeTyping()) {
            Toast.makeText(this, tr("Could not open the keyboard", "Не удалось открыть клавиатуру", "Kan het toetsenbord niet openen", "No se pudo abrir el teclado"), Toast.LENGTH_LONG).show()
        }
    }

    private fun applyDisplaySetting(key: String, value: String, successMessage: String, extraCommand: String? = null) {
        if (rootAccessGranted != true) {
            Toast.makeText(this, tr("Root access is required for this device setting.", "Для этой настройки нужен root-доступ.", "Roottoegang is vereist voor deze instelling.", "Esta opción requiere acceso root."), Toast.LENGTH_LONG).show()
            return
        }
        Thread({
            val safeKey = key.filter { it.isLetterOrDigit() || it == '_' }
            val safeValue = value.filter { it.isLetterOrDigit() || it == '_' }
            val command = buildString {
                append("settings put global $safeKey $safeValue; ")
                if (!extraCommand.isNullOrBlank()) append(extraCommand).append("; ")
                append("settings get global $safeKey")
            }
            val (ok, output, _) = runRootCommand(command)
            runOnUiThread {
                val confirmed = ok && output.trim() == safeValue
                Toast.makeText(this, if (confirmed) successMessage else tr("Setting could not be confirmed.", "Не удалось подтвердить изменение настройки.", "Instelling kon niet worden bevestigd.", "No se pudo confirmar el ajuste."), Toast.LENGTH_SHORT).show()
            }
        }, "PocketHub-screen-setting").apply { isDaemon = true; start() }
    }

    /**
     * Device settings that the AYANEO app also offers: fan while charging with the screen
     * off (AYANEO's own system settings, written with root) and turning Wi-Fi off after the
     * screen has been off for a while (done by PocketHub, see WifiSleep).
     */
    /** Controller mode (AYANEO / Xbox) card for the controller page. */
    private fun controllerModeCard(): LinearLayout {
        val item = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        // --- Controller mode: AYANEO (DInput) or Xbox (XInput) ---
        // Same command the AYANEO app sends through its OEM service (style 00 = AYANEO, 01 = Xbox).
        item.addView(label(tr("CONTROLLER MODE", "РЕЖИМ КОНТРОЛЛЕРА", "CONTROLLERMODUS", "MODO DEL MANDO"), 12f, white, true), margin(bottom = 4))
        val currentStyle = getPreferences(Context.MODE_PRIVATE).getString(KEY_CONTROLLER_STYLE, null)
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ayaneo" to "AYANEO", "xbox" to "Xbox").forEachIndexed { index, (code, name) ->
            styleRow.addView(settingsChoice(name, currentStyle == code) {
                getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_CONTROLLER_STYLE, code).apply()
                pendingControllerCommand = "com_set_controller_style:" + if (code == "xbox") "5505010000000000" else "5505000000000000"
                if (oemAidlBound && oemServiceBinder != null && !oemClientId.isNullOrBlank()) dispatchPendingControllerCommandIfReady()
                else if (oemAidlConnection == null) testOemAidl(showToast = false)
                Toast.makeText(this, tr("Controller mode", "Режим контроллера", "Controllermodus", "Modo del mando") + ": $name", Toast.LENGTH_SHORT).show()
                buildScreen()
            }, weight(1f, start = if (index == 0) 0 else 3, end = if (index == 0) 3 else 0))
        }
        item.addView(styleRow)
        item.addView(label(tr(
            "Xbox: the controller acts as an Xbox (XInput) pad — best for most games and streaming. AYANEO: AYANEO's own mode (DInput). The controller reconnects for a moment when you switch.",
            "Xbox: контроллер работает как геймпад Xbox (XInput) — лучше для большинства игр и стриминга. AYANEO: собственный режим AYANEO (DInput). При переключении контроллер на миг переподключается.",
            "Xbox: de controller werkt als Xbox-pad (XInput) — het beste voor de meeste games en streaming. AYANEO: eigen modus (DInput). Bij wisselen maakt de controller kort opnieuw verbinding.",
            "Xbox: el mando funciona como uno de Xbox (XInput), lo mejor para la mayoría de juegos y streaming. AYANEO: modo propio (DInput). Al cambiar, el mando se reconecta un instante."
        ), 9f, muted), margin(top = 4, bottom = 10))
        return item
    }

    private fun deviceSettingsCard(): LinearLayout {
        val item = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }

        val keyNeedFan = "ayaneo/share/is_power_off_charging_need_fan"
        val keySpeed = "ayaneo/share/power_off_charging_fan_speed"

        // --- Fan while charging, screen off ---
        item.addView(label(tr("FAN WHILE CHARGING (SCREEN OFF)", "ВЕНТИЛЯТОР ПРИ ЗАРЯДКЕ (ЭКРАН ВЫКЛЮЧЕН)", "VENTILATOR TIJDENS OPLADEN (SCHERM UIT)", "VENTILADOR AL CARGAR (PANTALLA APAGADA)"), 12f, white, true), margin(bottom = 4))
        val fanOn = Settings.System.getString(contentResolver, keyNeedFan) == "1"
        val fanSpeed = Settings.System.getString(contentResolver, keySpeed)?.toIntOrNull()?.coerceIn(0, 100) ?: 18
        val speedLabel = label("$fanSpeed%", 11f, mint, true)
        val speedBar = SeekBar(this).apply {
            max = 100
            progress = fanSpeed
            isEnabled = fanOn
            alpha = if (fanOn) 1f else 0.45f
            styleTouchSlider(this, mint, thumbDp = 24, minHeightDp = 44)
            enableControllerFocus(this, 7)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { speedLabel.text = "$progress%" }
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val value = seekBar.progress
                    Thread { runRootCommand("settings put system $keySpeed $value") }.start()
                }
            })
        }
        item.addView(Switch(this).apply {
            text = tr("Turn on fan", "Включать вентилятор", "Ventilator aanzetten", "Encender ventilador")
            textSize = 12f; setTextColor(white)
            isChecked = fanOn
            setOnCheckedChangeListener { _, on ->
                speedBar.isEnabled = on
                speedBar.alpha = if (on) 1f else 0.45f
                Thread { runRootCommand("settings put system $keyNeedFan ${if (on) 1 else 0}") }.start()
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedRow.addView(label(tr("Speed", "Скорость", "Snelheid", "Velocidad"), 11f, muted), LinearLayout.LayoutParams(dp(70), -2))
        speedRow.addView(speedBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        speedRow.addView(speedLabel.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(46), -2))
        item.addView(speedRow, margin(bottom = 10))

        // --- Wi-Fi off when the screen is off ---
        item.addView(label(tr("TURN OFF WI-FI WHEN SCREEN IS OFF", "ВЫКЛЮЧАТЬ WI-FI ПРИ ВЫКЛЮЧЕННОМ ЭКРАНЕ", "WIFI UIT ALS HET SCHERM UIT IS", "APAGAR WIFI CON LA PANTALLA APAGADA"), 12f, white, true), margin(bottom = 4))
        fun minutesName(value: Int) = when (value) {
            -1 -> tr("Never", "Никогда", "Nooit", "Nunca")
            0 -> tr("Immediately", "Сразу", "Direct", "Inmediatamente")
            else -> "$value " + tr("min", "мин", "min", "min")
        }
        val current = WifiSleep.minutes(this)
        item.addView(pill(minutesName(current) + "  ▾", cardBg2, if (current >= 0) mint else white) {
            showSimpleActionDialog(tr("Turn off Wi-Fi after the screen goes off", "Выключать Wi-Fi после выключения экрана", "Wifi uitzetten nadat het scherm uitgaat", "Apagar wifi tras apagar la pantalla"),
                WifiSleep.CHOICES.map { value ->
                    ((if (value == WifiSleep.minutes(this)) "● " else "") + minutesName(value)) to {
                        WifiSleep.setMinutes(this, value)
                        buildScreen()
                    }
                })
        }.apply { minHeight = dp(42) })
        item.addView(label(tr(
            "Wi-Fi comes back on when you turn the screen on. Needs the PocketHub accessibility service. If AYANEO's own app has the same option, set it to Never there.",
            "Wi-Fi снова включается, когда включаешь экран. Нужна служба специальных возможностей PocketHub. Если в приложении AYANEO включена такая же опция, поставь там «Никогда».",
            "Wifi gaat weer aan als je het scherm aanzet. Vereist de PocketHub-toegankelijkheidsservice. Zet dezelfde optie in de AYANEO-app op Nooit.",
            "El wifi vuelve al encender la pantalla. Requiere el servicio de accesibilidad de PocketHub. Si la app de AYANEO tiene la misma opción, ponla en Nunca."
        ), 9f, muted), margin(top = 4))
        // --- External monitor (USB-C DisplayPort) ---
        // Same mechanism as the AYANEO app: a system Global key decides whether the handheld
        // screens stay on, and the output mode is forced through the DP debugfs node + replug.
        item.addView(label(tr("EXTERNAL MONITOR", "ВНЕШНИЙ МОНИТОР", "EXTERNE MONITOR", "MONITOR EXTERNO"), 12f, white, true), margin(top = 14, bottom = 4))
        val dpOnlyKey = "off_brightness_when_dp_connected"
        val onlyExternal = try { Settings.Global.getInt(contentResolver, dpOnlyKey, 0) == 1 } catch (_: Exception) { false }
        val displayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            false to tr("Handheld + monitor", "Консоль + монитор", "Console + monitor", "Consola + monitor"),
            true to tr("Monitor only", "Только монитор", "Alleen monitor", "Solo monitor")
        ).forEachIndexed { index, (only, name) ->
            displayRow.addView(settingsChoice(name, onlyExternal == only) {
                Thread {
                    val ok = runRootCommand("settings put global $dpOnlyKey ${if (only) 1 else 0}").first
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, tr("Root is required", "Нужен root", "Root vereist", "Se necesita root"), Toast.LENGTH_SHORT).show()
                        buildScreen()
                    }
                }.start()
            }, weight(1f, start = if (index == 0) 0 else 3, end = if (index == 0) 3 else 0))
        }
        item.addView(displayRow)
        item.addView(label(tr(
            "Monitor only: both handheld screens turn off while a monitor is connected.",
            "«Только монитор»: оба экрана консоли гаснут, пока подключён монитор.",
            "Alleen monitor: beide schermen van de console gaan uit zolang er een monitor is aangesloten.",
            "Solo monitor: las dos pantallas de la consola se apagan mientras haya un monitor conectado."
        ), 9f, muted), margin(top = 4, bottom = 8))

        val resPrefs = getPreferences(Context.MODE_PRIVATE)
        fun savedResolutionName(): String = resPrefs.getString(KEY_EXTERNAL_RESOLUTION, null)
            ?: tr("Default", "По умолчанию", "Standaard", "Predeterminada")
        val monitorStatus = label(tr("Monitor: tap Refresh", "Монитор: нажми «Обновить»", "Monitor: tik op Vernieuwen", "Monitor: pulsa Actualizar"), 11f, white)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(monitorStatus, LinearLayout.LayoutParams(0, -2, 1f))
        var modes: List<Triple<Int, Int, Int>> = emptyList()
        lateinit var resolutionPill: TextView
        fun refreshMonitor(then: (() -> Unit)? = null) {
            monitorStatus.text = tr("Monitor: checking…", "Монитор: проверка…", "Monitor: controleren…", "Monitor: comprobando…")
            Thread {
                val found = readExternalModes()
                runOnUiThread {
                    modes = found
                    monitorStatus.text = if (found.isEmpty())
                        tr("Monitor: not connected", "Монитор: не подключён", "Monitor: niet verbonden", "Monitor: no conectado")
                    else
                        tr("Monitor: connected", "Монитор: подключён", "Monitor: verbonden", "Monitor: conectado") + " (${found.size} " + tr("modes", "режимов", "modi", "modos") + ")"
                    monitorStatus.setTextColor(if (found.isEmpty()) muted else mint)
                    resolutionPill.alpha = if (found.isEmpty()) 0.5f else 1f
                    then?.invoke()
                }
            }.start()
        }
        statusRow.addView(pill(tr("Refresh", "Обновить", "Vernieuwen", "Actualizar"), cardBg2, white) { refreshMonitor() }.apply { minHeight = dp(40) })
        item.addView(statusRow, margin(bottom = 6))
        fun openResolutionPicker() {
            if (modes.isEmpty()) {
                Toast.makeText(this, tr("No monitor connected", "Монитор не подключён", "Geen monitor verbonden", "No hay monitor conectado"), Toast.LENGTH_SHORT).show()
                return
            }
            val current = resPrefs.getString(KEY_EXTERNAL_RESOLUTION, null)
            val actions = mutableListOf<Pair<String, () -> Unit>>()
            actions += ((if (current == null) "● " else "") + tr("Default (monitor's choice)", "По умолчанию (выбор монитора)", "Standaard (keuze van monitor)", "Predeterminada (la del monitor)")) to {
                resPrefs.edit().remove(KEY_EXTERNAL_RESOLUTION).apply()
                applyExternalMode(null)
                resolutionPill.text = tr("Resolution", "Разрешение", "Resolutie", "Resolución") + ": " + savedResolutionName() + "  ▾"
            }
            modes.forEach { (w, h, r) ->
                val name = "${w}x$h ${r}Hz"
                actions += ((if (current == name) "● " else "") + name) to {
                    resPrefs.edit().putString(KEY_EXTERNAL_RESOLUTION, name).apply()
                    applyExternalMode(Triple(w, h, r))
                    resolutionPill.text = tr("Resolution", "Разрешение", "Resolutie", "Resolución") + ": " + name + "  ▾"
                }
            }
            showSimpleActionDialog(tr("Monitor resolution", "Разрешение монитора", "Monitorresolutie", "Resolución del monitor"), actions)
        }
        resolutionPill = pill(tr("Resolution", "Разрешение", "Resolutie", "Resolución") + ": " + savedResolutionName() + "  ▾", cardBg2, white) {
            if (modes.isEmpty()) refreshMonitor { openResolutionPicker() } else openResolutionPicker()
        }.apply { minHeight = dp(42); alpha = 0.5f }
        item.addView(resolutionPill)
        item.addView(label(tr(
            "The monitor briefly reconnects when the resolution changes. If the picture disappears, wait a few seconds or choose Default.",
            "При смене разрешения монитор на секунду переподключается. Если картинка пропала, подожди пару секунд или выбери «По умолчанию».",
            "De monitor maakt kort opnieuw verbinding bij het wijzigen van de resolutie. Verdwijnt het beeld, wacht even of kies Standaard.",
            "El monitor se reconecta un momento al cambiar la resolución. Si se pierde la imagen, espera unos segundos o elige Predeterminada."
        ), 9f, muted), margin(top = 4))
        return item
    }

    // External DisplayPort monitor helpers (same debugfs node the AYANEO app uses).
    private val dpNodeShell = "mount -t debugfs none /d 2>/dev/null; D=/d/drm_dp; [ -e \$D/edid_modes ] || D=/sys/kernel/debug/drm_dp"

    private fun readExternalModes(): List<Triple<Int, Int, Int>> {
        val result = runRootCommand("$dpNodeShell; cat \$D/edid_modes 2>/dev/null")
        if (!result.first) return emptyList()
        val sizeRegex = Regex("(\\d{3,5})x(\\d{3,5})")
        val out = LinkedHashSet<Triple<Int, Int, Int>>()
        result.second.lineSequence().forEach { line ->
            val match = sizeRegex.find(line) ?: return@forEach
            val w = match.groupValues[1].toIntOrNull() ?: return@forEach
            val h = match.groupValues[2].toIntOrNull() ?: return@forEach
            val rate = line.substring(match.range.last + 1).split(Regex("[\\s,:@]+"))
                .firstNotNullOfOrNull { it.removeSuffix("Hz").removeSuffix("hz").toDoubleOrNull() }?.toInt() ?: return@forEach
            if (rate >= 50) out += Triple(w, h, rate)
        }
        return out.toList()
    }

    private fun applyExternalMode(mode: Triple<Int, Int, Int>?) {
        val modeArgs = mode?.let { "${it.first} ${it.second} ${it.third}" } ?: "0 0 0"
        val dpOnlyKey = "off_brightness_when_dp_connected"
        val keep = try { Settings.Global.getInt(contentResolver, dpOnlyKey, 0) } catch (_: Exception) { 0 }
        Toast.makeText(this, tr("Reconnecting monitor…", "Переподключаю монитор…", "Monitor opnieuw verbinden…", "Reconectando monitor…"), Toast.LENGTH_SHORT).show()
        Thread {
            val ok = runRootCommand("$dpNodeShell; echo $modeArgs 0 > \$D/edid_modes; sleep 0.3; echo 0 > \$D/hpd; sleep 0.6; echo 1 > \$D/hpd; settings put global $dpOnlyKey $keep").first
            Log.i("PocketHubDP", "apply mode=$modeArgs ok=$ok")
            if (!ok) runOnUiThread { Toast.makeText(this, tr("Could not change resolution (root?)", "Не удалось сменить разрешение (root?)", "Resolutie wijzigen mislukt (root?)", "No se pudo cambiar la resolución (¿root?)"), Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun screenAudioSettingsCard(): LinearLayout {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)
        val item = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(9))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = label(tr("SCREEN AUDIO", "ЗВУК ПО ЭКРАНАМ", "SCHERMGELUID", "AUDIO POR PANTALLA"), 13f, white, true)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val audioSwitch = Switch(this).apply {
            text = tr("Control", "Управление", "Bediening", "Control")
            textSize = 10f
            setTextColor(white)
            isChecked = enabled
            thumbTintList = ColorStateList.valueOf(if (enabled) mint else muted)
            trackTintList = ColorStateList.valueOf(if (enabled) blendColor(cardBg, mint, 0.65f) else border)
        }
        row.addView(audioSwitch)
        item.addView(row)
        item.addView(label(tr(
            "Enable independent volume sliders for the upper and lower displays.",
            "Включает независимые ползунки громкости верхнего и нижнего экранов.",
            "Schakelt aparte volumeschuifregelaars voor het bovenste en onderste scherm in.",
            "Activa controles de volumen independientes para las pantallas superior e inferior."
        ), 9f, muted).apply { setPadding(0, dp(2), 0, 0) })
        audioSwitch.setOnCheckedChangeListener { _, checked ->
            try {
                if (checked) {
                    ScreenAudioVolumeService.enable(this)
                    Toast.makeText(this, tr(
                        "Approve root access for PocketHub to start screen audio control.",
                        "Разреши PocketHub root-доступ для управления звуком по экранам.",
                        "Geef PocketHub root-toegang voor schermgeluid.",
                        "Autoriza root para activar el audio por pantalla."
                    ), Toast.LENGTH_LONG).show()
                } else {
                    ScreenAudioVolumeService.disable(this)
                }
            } catch (error: Exception) {
                prefs.edit().putBoolean(ScreenAudioVolumeService.KEY_ENABLED, false).apply()
                audioSwitch.setOnCheckedChangeListener(null)
                audioSwitch.isChecked = false
                audioSwitch.thumbTintList = ColorStateList.valueOf(muted)
                Toast.makeText(this, error.message ?: tr("Could not update sound control.", "Не удалось изменить управление звуком.", "Geluidsbediening wijzigen mislukt.", "No se pudo cambiar el control de audio."), Toast.LENGTH_LONG).show()
            }
        }

        val keysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        keysRow.addView(label(tr("VOLUME KEYS", "КНОПКИ ГРОМКОСТИ", "VOLUMEKNOPPEN", "BOTONES DE VOLUMEN"), 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val keysEnabled = prefs.getBoolean(ScreenAudioVolumeService.KEY_VOLUME_KEYS, false)
        val keysSwitch = Switch(this).apply {
            text = tr("PocketHub panel", "Панель PocketHub", "PocketHub-paneel", "Panel PocketHub")
            textSize = 10f
            setTextColor(white)
            isChecked = keysEnabled
            thumbTintList = ColorStateList.valueOf(if (keysEnabled) mint else muted)
            trackTintList = ColorStateList.valueOf(if (keysEnabled) blendColor(cardBg, mint, 0.65f) else border)
        }
        keysRow.addView(keysSwitch)
        item.addView(keysRow)
        item.addView(label(tr(
            "The volume buttons show PocketHub's panel: overall, upper and lower screen. Tap a row's name to choose what the buttons change. Needs the PocketHub accessibility service.",
            "Кнопки громкости открывают панель PocketHub: общая громкость, верхний и нижний экран. Нажми на название строки, чтобы выбрать, что меняют кнопки. Нужна служба специальных возможностей PocketHub.",
            "De volumeknoppen tonen het PocketHub-paneel: totaal, boven- en onderscherm. Tik op een regel om te kiezen wat de knoppen regelen. Vereist de PocketHub-toegankelijkheidsservice.",
            "Los botones de volumen muestran el panel de PocketHub: general, superior e inferior. Toca el nombre de una fila para elegir qué cambian. Requiere el servicio de accesibilidad de PocketHub."
        ), 9f, muted).apply { setPadding(0, dp(2), 0, 0) })
        if (keysEnabled) {
            fun choiceRow(title: String, key: String, default: String, options: List<Pair<String, String>>) {
                val current = prefs.getString(key, default) ?: default
                item.addView(label(title, 10f, muted, true).apply { setPadding(0, dp(7), 0, dp(4)) })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                options.forEachIndexed { index, (code, name) ->
                    row.addView(settingsChoice(name, current == code) {
                        prefs.edit().putString(key, code).apply()
                        VolumePanel.hide()
                        buildScreen()
                    }.apply { textSize = 10f }, weight(1f, start = if (index == 0) 0 else 2, end = if (index == options.lastIndex) 0 else 2))
                }
                item.addView(row)
            }
            choiceRow(tr("SHOW THE PANEL ON", "ГДЕ ПОКАЗЫВАТЬ ПАНЕЛЬ", "PANEEL TONEN OP", "MOSTRAR EL PANEL EN"),
                ScreenAudioVolumeService.KEY_VOLUME_PANEL_DISPLAY, "upper", listOf(
                    "upper" to tr("Upper screen", "Верхний экран", "Bovenscherm", "Superior"),
                    "lower" to tr("Lower screen", "Нижний экран", "Onderscherm", "Inferior"),
                    "both" to tr("Both screens", "Оба экрана", "Beide schermen", "Ambas")))
            choiceRow(tr("BUTTONS CHANGE BY DEFAULT", "КНОПКИ МЕНЯЮТ ПО УМОЛЧАНИЮ", "KNOPPEN REGELEN STANDAARD", "LOS BOTONES CAMBIAN"),
                ScreenAudioVolumeService.KEY_VOLUME_KEYS_TARGET, "master", listOf(
                    "master" to tr("Overall", "Общую", "Totaal", "General"),
                    "upper" to tr("Upper screen", "Верхний экран", "Bovenscherm", "Superior"),
                    "lower" to tr("Lower screen", "Нижний экран", "Onderscherm", "Inferior")))
        }
        keysSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(ScreenAudioVolumeService.KEY_VOLUME_KEYS, checked).apply()
            keysSwitch.thumbTintList = ColorStateList.valueOf(if (checked) mint else muted)
            keysSwitch.trackTintList = ColorStateList.valueOf(if (checked) blendColor(cardBg, mint, 0.65f) else border)
            buildScreen()
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, tr("Allow PocketHub to display over other apps.", "Разреши PocketHub показ поверх других приложений.", "Sta PocketHub toe boven andere apps te tonen.", "Permite a PocketHub mostrarse sobre otras apps."), Toast.LENGTH_LONG).show()
            }
        }
        return item
    }

    private fun remapActionName(action: String?): String = when {
        action != null && action.startsWith(ButtonRemap.APP_PREFIX) ->
            "▶ " + appLabel(action.removePrefix(ButtonRemap.APP_PREFIX))
        else -> remapActionBaseName(action)
    }

    private fun remapActionBaseName(action: String?): String = when (action) {
        null -> "—"
        "back" -> tr("Back", "Назад", "Terug", "Atrás")
        "home" -> tr("Home", "Домой", "Start", "Inicio")
        "recents" -> tr("Recent apps", "Недавние приложения", "Recente apps", "Apps recientes")
        "last_app" -> tr("Previous app", "Предыдущее приложение", "Vorige app", "App anterior")
        "launch_app" -> tr("Launch an app…", "Запустить приложение…", "App starten…", "Abrir una app…")
        "notifications" -> tr("Notifications", "Уведомления", "Meldingen", "Notificaciones")
        "quick_settings" -> tr("Quick settings", "Быстрые настройки", "Snelle instellingen", "Ajustes rápidos")
        "screenshot" -> tr("Screenshot (both screens)", "Снимок экрана (оба экрана)", "Schermafbeelding (beide schermen)", "Captura (ambas pantallas)")
        "lock" -> tr("Lock screen", "Заблокировать экран", "Scherm vergrendelen", "Bloquear pantalla")
        "pockethub" -> tr("Show / hide PocketHub", "Показать / скрыть PocketHub", "PocketHub tonen / verbergen", "Mostrar / ocultar PocketHub")
        "profile_next" -> tr("Next performance profile", "Следующий профиль", "Volgend profiel", "Siguiente perfil")
        "profiles" -> tr("Quick profile switch", "Быстрая смена профиля", "Profiel snel wisselen", "Cambio rápido de perfil")
        "screens" -> tr("Dual screen controls", "Двойные экраны (управление)", "Dubbel scherm-bediening", "Control de doble pantalla")
        "autotune" -> tr("Auto-tune CPU / GPU for the game", "Автоподбор частот для игры", "CPU / GPU afstemmen voor de game", "Autoajuste de CPU / GPU para el juego")
        else -> tr("Do nothing (block)", "Ничего (отключить)", "Niets (blokkeren)", "Nada (bloquear)")
    }

    private fun pressTypeName(type: String): String = when (type) {
        ButtonRemap.PRESS_LONG -> tr("Long press", "Долгое нажатие", "Lang indrukken", "Pulsación larga")
        ButtonRemap.PRESS_DOUBLE -> tr("Double press", "Двойное нажатие", "Dubbel indrukken", "Doble pulsación")
        else -> tr("Short press", "Короткое нажатие", "Kort indrukken", "Pulsación corta")
    }

    /** Settings card: extra buttons with their own short, long and double press actions. */
    private fun buttonRemapCard(): LinearLayout {
        val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        card.addView(label(tr("BUTTON REMAPPING", "ПЕРЕНАЗНАЧЕНИЕ КНОПОК", "KNOPPEN TOEWIJZEN", "REASIGNAR BOTONES"), 13f, white, true), margin(bottom = 4))
        card.addView(label(tr(
            "Give the device's extra buttons new jobs: one for a short press, one for a long press and one for a double press. Uses the PocketHub accessibility service, no root needed.",
            "Назначь дополнительным кнопкам устройства действия: отдельно на короткое, долгое и двойное нажатие. Работает через службу специальных возможностей PocketHub, root не нужен.",
            "Geef de extra knoppen nieuwe taken: voor kort, lang en dubbel indrukken. Werkt via de PocketHub-toegankelijkheidsservice, zonder root.",
            "Da a los botones extra nuevas funciones: pulsación corta, larga y doble. Usa el servicio de accesibilidad de PocketHub, sin root."
        ), 9.5f, muted), margin(bottom = 6))
        val map = ButtonRemap.load(this)
        if (map.isEmpty()) {
            card.addView(label(tr("No buttons remapped yet.", "Пока ни одна кнопка не переназначена.", "Nog geen knoppen toegewezen.", "Aún no hay botones reasignados."), 10f, muted), margin(bottom = 6))
        }
        map.toSortedMap().forEach { (code, binding) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground(cardBg2, border, 14)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label("🎮  " + ButtonRemap.keyName(code), 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(pill(tr("Remove", "Удалить", "Verwijderen", "Quitar"), cardBg, white) {
                ButtonRemap.remove(this, code)
                buildScreen()
            }.apply { textSize = 10f; minHeight = dp(34) })
            box.addView(head, margin(bottom = 4))
            ButtonRemap.PRESS_TYPES.forEach { type ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(label(pressTypeName(type), 10f, muted), LinearLayout.LayoutParams(0, -2, 1f))
                val action = binding.get(type)
                row.addView(pill(remapActionName(action), if (action != null) blendColor(cardBg, mint, 0.18f) else cardBg, if (action != null) mint else muted) {
                    showRemapActionPicker(code, type)
                }.apply { textSize = 10f; minHeight = dp(34) }, LinearLayout.LayoutParams(0, -2, 1.3f))
                box.addView(row, margin(bottom = 3))
            }
            card.addView(box, margin(bottom = 6))
        }
        card.addView(pill("＋  " + tr("ADD A BUTTON", "ДОБАВИТЬ КНОПКУ", "KNOP TOEVOEGEN", "AÑADIR BOTÓN"), mint, onAccentInk()) { startButtonLearning() }
            .apply { minHeight = dp(42) }, margin(top = 2))
        return card
    }

    /** Waits for the user to press a button, then asks what it should do. */
    private fun startButtonLearning() {
        if (!ButtonRemap.serviceRunning) {
            Toast.makeText(this, tr("Turn on the PocketHub accessibility service first.", "Сначала включи службу специальных возможностей PocketHub.",
                "Zet eerst de PocketHub-toegankelijkheidsservice aan.", "Activa primero el servicio de accesibilidad de PocketHub."), Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) { }
            return
        }
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = roundedBackground(cardBg, border, 20)
        }
        panel.addView(label("🎮  " + tr("Press the button now", "Нажми кнопку сейчас", "Druk nu op de knop", "Pulsa el botón ahora"), 15f, mint, true), margin(bottom = 6))
        panel.addView(label(tr("Press the extra button you want to remap. Waiting 15 seconds…",
            "Нажми дополнительную кнопку, которую хочешь переназначить. Жду 15 секунд…",
            "Druk op de extra knop die je wilt toewijzen. 15 seconden wachten…",
            "Pulsa el botón extra que quieres reasignar. Esperando 15 segundos…"), 11f, white), margin(bottom = 10))
        panel.addView(pill(tr("CANCEL", "ОТМЕНА", "ANNULEREN", "CANCELAR"), cardBg2, white) { dialog.dismiss() })
        dialog.setContentView(panel)
        val timeout = Runnable { if (dialog.isShowing) dialog.dismiss() }
        dialog.setOnDismissListener {
            ButtonRemap.learnListener = null
            uiHandler.removeCallbacks(timeout)
        }
        ButtonRemap.learnListener = { code ->
            dialog.setOnDismissListener(null)
            uiHandler.removeCallbacks(timeout)
            if (dialog.isShowing) dialog.dismiss()
            showRemapActionPicker(code, ButtonRemap.PRESS_SHORT)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        uiHandler.postDelayed(timeout, 15_000L)
    }

    private fun showRemapActionPicker(code: Int, type: String) {
        val current = ButtonRemap.load(this)[code]?.get(type)
        val actions = ButtonRemap.ACTIONS.map { action ->
            val selected = action == current || (action == "launch_app" && current?.startsWith(ButtonRemap.APP_PREFIX) == true)
            ((if (selected) "● " else "") + remapActionName(if (action == "launch_app" && selected) current else action)) to {
                if (action == "launch_app") {
                    // Pick the app to start with this press.
                    showFavoriteAppPicker(tr("APP FOR THIS BUTTON", "ПРИЛОЖЕНИЕ ДЛЯ КНОПКИ", "APP VOOR DEZE KNOP", "APP PARA ESTE BOTÓN")) { pkg ->
                        ButtonRemap.set(this, code, type, ButtonRemap.APP_PREFIX + pkg)
                        if (advanced || devicePage) buildScreen()
                    }
                } else {
                    ButtonRemap.set(this, code, type, action)
                    if (advanced || devicePage) buildScreen()
                }
            }
        }.toMutableList()
        if (current != null) actions += tr("Clear (not used)", "Очистить (не назначено)", "Wissen (niet gebruikt)", "Borrar (sin usar)") to {
            ButtonRemap.set(this, code, type, null)
            if (advanced || devicePage) buildScreen()
        }
        showSimpleActionDialog(ButtonRemap.keyName(code) + " · " + pressTypeName(type), actions)
    }

    private fun ayaButtonSettingsCard(): LinearLayout {
        val prefs = getSharedPreferences(AyaButtonAccessibilityService.PREFS, Context.MODE_PRIVATE)
        val card = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        card.addView(label(tr("AYANEO BUTTON", "КНОПКА AYANEO", "AYANEO-KNOP", "BOTÓN AYANEO"), 13f, white, true), margin(bottom = 5))
        card.addView(label(tr(
            "Press the AYANEO button to show or hide PocketHub. This is always on: PocketHub needs its button service, and it keeps AYANEO's competing button service off.",
            "Кнопка AYANEO показывает или скрывает PocketHub. Это включено всегда: PocketHub нужна своя служба кнопки, а конфликтующую службу AYANEO он держит выключенной.",
            "De AYANEO-knop toont of verbergt PocketHub. Dit staat altijd aan: PocketHub heeft zijn knopservice nodig en houdt de conflicterende AYANEO-service uit.",
            "El botón AYANEO muestra u oculta PocketHub. Siempre está activo: PocketHub necesita su servicio de botón y mantiene desactivado el servicio AYANEO que entra en conflicto."
        ), 10f, muted).apply { setPadding(0, 0, 0, dp(6)) }, margin(bottom = 4))

        val status = when {
            isAyaButtonAccessibilityServiceEnabled() -> tr(
                "PocketHub owns the AYANEO button and removes the competing AYANEO key service while takeover is on, including after boot.",
                "PocketHub управляет кнопкой AYANEO и убирает конфликтующую службу AYANEO, пока перехват включён, в том числе после перезагрузки.",
                "PocketHub beheert de AYANEO-knop en verwijdert de conflicterende AYANEO-service zolang overname aanstaat, ook na het opstarten.",
                "PocketHub controla el botón AYANEO y quita el servicio AYANEO que entra en conflicto mientras la opción está activa, también tras reiniciar."
            )
            else -> tr(
                "Setup required: enable PocketHub accessibility below to take over the AYANEO button. The native key service will return when takeover is turned off.",
                "Нужна настройка: включи специальные возможности PocketHub ниже. Родная служба кнопки AYANEO восстановится, когда ты выключишь перехват.",
                "Instellen vereist: schakel PocketHub-toegankelijkheid hieronder in. De originele knopservice wordt hersteld wanneer je overname uitschakelt.",
                "Configuración necesaria: activa la accesibilidad de PocketHub abajo. El servicio original se restaurará cuando desactives el control."
            )
        }
        card.addView(label(status, 9f, muted).apply { setPadding(0, dp(2), 0, dp(7)) })
        card.addView(pill(
            tr("Accessibility settings", "Настройки специальных возможностей", "Toegankelijkheidsinstellingen", "Ajustes de accesibilidad"),
            cardBg2, white
        ) { openAyaButtonAccessibilitySettings() })
        return card
    }

    private fun isAyaButtonAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').filter { it.isNotBlank() }
        val component = ComponentName(this, AyaButtonAccessibilityService::class.java).flattenToString()
        return enabledServices.any { it.equals(component, ignoreCase = true) }
    }

    private fun setNativeAccessibilityServiceForTakeover(takeover: Boolean) {
        Thread {
            val result = PocketHubButtonTakeover.apply(this, takeover)
            runOnUiThread {
                if (!result.ok) {
                    Log.e("PocketHubGameWindow", "Could not update AYANEO accessibility services: ${result.output}")
                    Toast.makeText(this, tr(
                        "Could not change the AYANEO button service. Check PocketHub's root permission.",
                        "Не удалось изменить службу кнопки AYANEO. Проверь root-разрешение PocketHub.",
                        "AYANEO-knopservice wijzigen mislukt. Controleer de roottoestemming van PocketHub.",
                        "No se pudo cambiar el servicio del botón AYANEO. Comprueba el permiso root de PocketHub."
                    ), Toast.LENGTH_LONG).show()
                    buildScreen()
                } else {
                    if (takeover && !isAyaButtonAccessibilityServiceEnabled()) {
                        Toast.makeText(this, tr(
                            "PocketHub is enabled in Accessibility. The button service may take a few seconds to connect.",
                            "PocketHub включён в специальных возможностях. Службе может понадобиться несколько секунд для запуска.",
                            "PocketHub is ingeschakeld bij Toegankelijkheid. De service kan enkele seconden nodig hebben.",
                            "PocketHub está activado en Accesibilidad. El servicio puede tardar unos segundos en conectarse."
                        ), Toast.LENGTH_LONG).show()
                    }
                    buildScreen()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun openAyaButtonAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, tr("Could not open Accessibility settings.", "Не удалось открыть настройки специальных возможностей.", "Toegankelijkheidsinstellingen openen mislukt."), Toast.LENGTH_LONG).show()
        }
    }

    private fun showAboutDialog() {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, bg)).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), border)
            }
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(blendColor(cardBg, mint, 0.32f), cardBg)).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), mint)
            }
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "PocketHub"
        }
        hero.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12) })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(label("PocketHub", 22f, white, true))
        brand.addView(label(tr("AYANEO Pocket DS control hub", "Центр управления AYANEO Pocket DS", "AYANEO Pocket DS-bedieningscentrum", "Centro de control para AYANEO Pocket DS"), 10f, muted).apply { maxLines = 2 })
        hero.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(hero, margin(bottom = 12))
        panel.addView(label(tr(
            "A dual-screen control center with system monitoring, hardware controls and a custom keyboard.",
            "Центр управления двумя экранами, системными датчиками, настройками устройства и собственной клавиатурой.",
            "Een bedieningscentrum voor twee schermen, systeemmetingen, apparaatinstellingen en een eigen toetsenbord.",
            "Centro de control de doble pantalla con sensores, ajustes del dispositivo y teclado propio."
        ), 12f, white).apply { setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(2), 0, dp(2), dp(12)) })
        val github = label("⌘   GitHub  ·  github.com/Emile86/PocketHub", 11f, cyan, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(cardBg, border, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Emile86/PocketHub"))) }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "Could not open GitHub link", Toast.LENGTH_SHORT).show() }
            }
        }
        panel.addView(github, margin(bottom = 10))
        panel.addView(pill(tr("Close", "Закрыть", "Sluiten", "Cerrar"), mint, bg) { dialog.dismiss() })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost(dp(600)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /** Section heading on the Settings page: icon badge, title, short subtitle and a divider. */
    private fun settingsSection(icon: String, title: String, subtitle: String?): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(icon, 15f, onAccentInk(), true).apply {
            gravity = Gravity.CENTER
            background = if (isBrutal()) brutalBox(mint) else roundedBackground(mint, mint, 10)
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title.uppercase(Locale.getDefault()), 14f, white, true).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        if (subtitle != null) texts.addView(label(subtitle, 9.5f, muted))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)
        box.addView(View(this).apply { setBackgroundColor(blendColor(border, mint, 0.45f)) },
            LinearLayout.LayoutParams(-1, dp(if (isBrutal()) 2 else 1)).apply { topMargin = dp(7) })
        return box
    }

    private fun settingsChoice(title: String, selected: Boolean, accentOverride: Int? = null, action: () -> Unit): TextView {
        val activeColor = accentOverride ?: mint
        val face = if (selected) activeColor else cardBg2
        val textColor = if (selected) bg else white
        return pill(title, face, textColor) { action() }.apply {
            textSize = if (layoutTheme == "compact") 10f else 11f
            minHeight = dp(42)
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = roundedBackground(face, if (selected) activeColor else border, when (layoutTheme) { "compact" -> 8; "eightbit" -> 3; else -> 12 })
        }
    }

    private fun settingsDropdown(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit): Spinner {
        val names = options.map { it.second }
        return Spinner(this).apply {
            background = roundedBackground(cardBg2, border, 11)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, names) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(white); view.textSize = 12f; view.setPadding(dp(8), dp(8), dp(8), dp(8))
                    return view
                }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    view.setTextColor(white); view.setBackgroundColor(cardBg2); view.textSize = 12f; view.setPadding(dp(12), dp(10), dp(12), dp(10))
                    return view
                }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(options.indexOfFirst { it.first == selected }.coerceAtLeast(0), false)
            var initial = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initial) { initial = false; return }
                    options.getOrNull(position)?.first?.let(onSelected)
                }
            }
            enableControllerFocus(this, 11)
        }
    }

    private fun virtualMemorySettingsCard(): LinearLayout {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        var selectedSizeGb = prefs.getInt(PocketHubSwap.PREFERENCE_KEY, 0).coerceIn(0, 16)
        val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        card.addView(label(tr("VIRTUAL MEMORY (SWAP)", "ВИРТУАЛЬНАЯ ПАМЯТЬ (SWAP)", "VIRTUEEL GEHEUGEN (SWAP)", "MEMORIA VIRTUAL (SWAP)"), 13f, white, true), margin(bottom = 4))
        card.addView(label(
            tr(
                "Uses internal storage for swap. It is much slower than RAM and zRAM and does not increase physical RAM.",
                "Использует внутренний накопитель как swap. Он намного медленнее RAM и zRAM и не увеличивает физический объём ОЗУ.",
                "Gebruikt interne opslag als swap. Dit is veel trager dan RAM en zRAM en vergroot het fysieke RAM niet.",
                "Usa el almacenamiento interno como swap. Es mucho más lento que la RAM y zRAM y no aumenta la RAM física."
            ), 10f, muted
        ).apply { setPadding(0, 0, 0, dp(6)) }, margin(bottom = 2))

        val choices = (0..16).map { size ->
            size.toString() to if (size == 0) tr("Off", "Выкл.", "Uit", "Desactivado") else "$size GB"
        }
        val selector = settingsDropdown(choices, selectedSizeGb.toString()) { value ->
            selectedSizeGb = value.toIntOrNull()?.coerceIn(0, 16) ?: 0
        }
        card.addView(selector, margin(bottom = 7))

        val status = label(tr("Reading virtual memory status…", "Читаю состояние виртуальной памяти…", "Status virtueel geheugen lezen…", "Leyendo el estado de la memoria virtual…"), 10f, cyan)
        card.addView(status, margin(bottom = 6))
        val applying = java.util.concurrent.atomic.AtomicBoolean(false)
        var applyButton: TextView? = null
        val apply = pill(tr("APPLY VIRTUAL MEMORY", "ПРИМЕНИТЬ ВИРТУАЛЬНУЮ ПАМЯТЬ", "VIRTUEEL GEHEUGEN TOEPASSEN", "APLICAR MEMORIA VIRTUAL"), mint, bg) {
            if (!applying.compareAndSet(false, true)) return@pill
            applyButton?.isEnabled = false
            applyButton?.alpha = 0.55f
            val requested = selectedSizeGb
            status.text = if (requested == 0) tr("Turning off storage swap…", "Отключаю swap на накопителе…", "Opslagswap uitschakelen…", "Desactivando swap en almacenamiento…")
            else tr("Creating and enabling swap file…", "Создаю и включаю файл swap…", "Swapbestand maken en inschakelen…", "Creando y activando el archivo swap…")
            Thread {
                Thread {
                    while (applying.get()) {
                        try { Thread.sleep(2500L) } catch (_: InterruptedException) { break }
                        if (!applying.get()) break
                        val progress = PocketHubSwap.run(this, "status")
                        val phase = Regex("PHASE=([A-Z_]+)").find(progress.output)?.groupValues?.getOrNull(1) ?: ""
                        if (progress.ok && phase.isNotBlank() && phase != "IDLE") {
                            runOnUiThread {
                                if (applying.get() && status.parent != null) status.text = virtualSwapPhaseText(phase, requested)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val result = PocketHubSwap.run(this, "apply", requested)
                runOnUiThread {
                    applying.set(false)
                    applyButton?.isEnabled = true
                    applyButton?.alpha = 1f
                    if (result.ok) {
                        prefs.edit().putInt(PocketHubSwap.PREFERENCE_KEY, requested).apply()
                        val refreshed = PocketHubSwap.run(this, "status")
                        status.text = if (refreshed.ok) formatVirtualSwapStatus(refreshed.output) else result.output
                        Toast.makeText(
                            this,
                            if (requested == 0) tr("Storage swap is off; Android zRAM remains active.", "Swap на накопителе выключен; Android zRAM остаётся активным.", "Opslagswap is uit; Android zRAM blijft actief.", "El swap de almacenamiento está apagado; zRAM de Android sigue activa.")
                            else tr("Storage swap enabled: ", "Swap на накопителе включён: ", "Opslagswap ingeschakeld: ", "Swap de almacenamiento activado: ") + "$requested GB",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        status.text = virtualSwapErrorText(result.output)
                        Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                    }
                }
            }.apply { isDaemon = true; start() }
        }.apply { minHeight = dp(42) }
        applyButton = apply
        // Keep the button reachable for touch, D-pad, and controller navigation.
        card.addView(apply, margin(bottom = 2))
        Thread {
            val current = PocketHubSwap.run(this, "status")
            if (current.ok) runOnUiThread { if (status.parent != null) status.text = formatVirtualSwapStatus(current.output) }
        }.apply { isDaemon = true; start() }
        return card
    }

    private fun virtualSwapPhaseText(phase: String, sizeGb: Int): String = when (phase) {
        "ALLOCATING" -> tr("Allocating ${sizeGb} GB swap file…", "Выделяю файл swap на ${sizeGb} ГБ…", "Swapbestand van ${sizeGb} GB reserveren…", "Reservando archivo swap de ${sizeGb} GB…")
        "ACTIVATING" -> tr("Activating swap on F2FS… large sizes can take several minutes; please wait.", "Активирую swap на F2FS… большие объёмы могут занять несколько минут, подожди.", "Swap op F2FS activeren… grote bestanden kunnen enkele minuten duren.", "Activando swap en F2FS… los tamaños grandes pueden tardar varios minutos.")
        "PREPARING" -> tr("Preparing swap file…", "Подготавливаю файл swap…", "Swapbestand voorbereiden…", "Preparando archivo swap…")
        "DISABLING" -> tr("Disabling storage swap…", "Отключаю swap на накопителе…", "Opslagswap uitschakelen…", "Desactivando swap en almacenamiento…")
        else -> tr("Checking swap settings…", "Проверяю настройки swap…", "Swapinstellingen controleren…", "Comprobando la configuración de swap…")
    }

    private fun formatVirtualSwapStatus(output: String): String {
        val match = Regex("FILE_KB=(\\d+) ZRAM_KB=(\\d+) FILE_BYTES=(\\d+)").find(output)
        val fileKb = match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val zramKb = match?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
        val fileBytes = match?.groupValues?.getOrNull(3)?.toLongOrNull() ?: 0L
        fun gb(kb: Long) = String.format(Locale.US, "%.1f GB", kb / 1_048_576.0)
        val diskText = when (uiLanguage) {
            "ru" -> "Файловый swap: ${gb(fileKb)}${if (fileKb == 0L && fileBytes > 0L) " (файл выделен, но не активен)" else ""}"
            "nl" -> "Bestandsswap: ${gb(fileKb)}"
            "es" -> "Swap en archivo: ${gb(fileKb)}"
            "de" -> "Datei-Swap: ${gb(fileKb)}"
            "fr" -> "Swap fichier : ${gb(fileKb)}"
            "it" -> "Swap su file: ${gb(fileKb)}"
            "zh" -> "文件交换空间：${gb(fileKb)}"
            else -> "Disk swap: ${gb(fileKb)}${if (fileKb == 0L && fileBytes > 0L) " (file reserved, inactive)" else ""}"
        }
        val zramText = when (uiLanguage) {
            "ru" -> "zRAM Android: ${gb(zramKb)}"
            "nl" -> "Android-zRAM: ${gb(zramKb)}"
            "es" -> "zRAM de Android: ${gb(zramKb)}"
            "de" -> "Android-zRAM: ${gb(zramKb)}"
            "fr" -> "zRAM Android : ${gb(zramKb)}"
            "it" -> "zRAM Android: ${gb(zramKb)}"
            "zh" -> "Android zRAM：${gb(zramKb)}"
            else -> "Android zRAM: ${gb(zramKb)}"
        }
        return "$diskText  ·  $zramText"
    }

    private fun virtualSwapErrorText(output: String): String {
        val code = Regex("RESULT=ERROR:([A-Z_]+)").find(output)?.groupValues?.getOrNull(1)
        val message = when (code) {
            "NOT_ENOUGH_STORAGE" -> tr("Not enough free internal storage for that size.", "Недостаточно свободного места во внутреннем накопителе.", "Onvoldoende vrije interne opslag voor deze grootte.", "No hay suficiente espacio interno libre para ese tamaño.")
            "NOT_ENOUGH_FREE_RAM_TO_RESIZE" -> tr("Not enough free memory to safely resize the active swap file.", "Недостаточно свободной памяти для безопасного изменения активного swap-файла.", "Onvoldoende vrij geheugen om het actieve swapbestand veilig te wijzigen.", "No hay memoria libre suficiente para cambiar de forma segura el archivo swap activo.")
            "SWAPOFF_FAILED" -> tr("Android could not safely turn off the old swap file.", "Android не смог безопасно отключить старый swap-файл.", "Android kon het oude swapbestand niet veilig uitschakelen.", "Android no pudo desactivar de forma segura el archivo swap anterior.")
            "FILE_ALLOCATION_FAILED" -> tr("Could not allocate the swap file on internal storage.", "Не удалось выделить место для swap-файла на накопителе.", "Het swapbestand kon niet op interne opslag worden aangemaakt.", "No se pudo crear el archivo swap en el almacenamiento interno.")
            "MKSWAP_FAILED" -> tr("The kernel rejected the swap file format.", "Ядро отклонило формат swap-файла.", "De kernel heeft het swapbestandsformaat afgewezen.", "El kernel rechazó el formato del archivo swap.")
            "SWAPON_FAILED" -> tr("The kernel could not enable the swap file.", "Ядро не смогло включить swap-файл.", "De kernel kon het swapbestand niet activeren.", "El kernel no pudo activar el archivo swap.")
            "SWAPFILE_MISSING" -> tr("The saved swap file is missing. Apply the size again.", "Сохранённый swap-файл отсутствует. Примени размер ещё раз.", "Het opgeslagen swapbestand ontbreekt. Pas de grootte opnieuw toe.", "Falta el archivo swap guardado. Aplica el tamaño de nuevo.")
            else -> tr("Could not configure virtual memory. Check root access and available storage.", "Не удалось настроить виртуальную память. Проверь root-доступ и свободное место.", "Virtueel geheugen instellen is mislukt. Controleer roottoegang en vrije opslag.", "No se pudo configurar la memoria virtual. Comprueba el acceso root y el espacio libre.")
        }
        return message + if (code == null) " ${output.take(100)}" else ""
    }

    private fun ayaComponentSettingsCard(): LinearLayout {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        card.addView(label(tr("AYANEO SYSTEM COMPONENTS", "СИСТЕМНЫЕ КОМПОНЕНТЫ AYANEO", "AYANEO-SYSTEEMONDERDELEN", "COMPONENTES DEL SISTEMA AYANEO"), 13f, white, true), margin(bottom = 5))
        fun componentSwitch(title: String, prefKey: String, component: String) {
            var suppressChange = false
            val control = Switch(this).apply {
                text = title
                textSize = 12f
                setTextColor(white)
                isChecked = prefs.getBoolean(prefKey, true)
            }
            // Preferences can be stale after a reboot, a native settings change, or
            // package updates. Ask PackageManager for the component's real state.
            Thread {
                val query = "cmd package query-activities --brief --components --user 0 -n '$component'"
                val (queryOk, output, _) = runAyaRootCommand(query)
                if (queryOk) {
                    val actuallyEnabled = !output.contains("No activities found", ignoreCase = true)
                    runOnUiThread {
                        suppressChange = true
                        control.isChecked = actuallyEnabled
                        suppressChange = false
                        prefs.edit().putBoolean(prefKey, actuallyEnabled).apply()
                    }
                }
            }.start()
            control.setOnCheckedChangeListener { _, enabled ->
                if (suppressChange) return@setOnCheckedChangeListener
                // `disable-user` silently leaves protected AYANEO activity components at
                // their default state on this firmware. `disable` is the supported command;
                // use the fully-qualified component name and validate PackageManager's reply.
                val command = if (enabled) {
                    // On this firmware the activity cannot be enabled while its parent
                    // package remains in the default state. Enable the package first.
                    "pm enable --user 0 'com.ayaneo.home' >/dev/null && pm enable --user 0 '$component'"
                } else {
                    "pm disable --user 0 '$component'"
                }
                Thread {
                    // Use AYANEO's xsu daemon here. On this firmware the app's generic
                    // `su` path can report success while package-manager state stays put.
                    val (ok, output, _) = runAyaRootCommand(command)
                    val expectedState = if (enabled) "new state: enabled" else "new state: disabled"
                    val (queryOk, observed, _) = runAyaRootCommand(
                        "cmd package query-activities --brief --components --user 0 -n '$component'"
                    )
                    val actuallyEnabled = queryOk && !observed.contains("No activities found", ignoreCase = true)
                    val applied = ok && output.contains(expectedState, ignoreCase = true) && queryOk && actuallyEnabled == enabled
                    runOnUiThread {
                        if (applied) prefs.edit().putBoolean(prefKey, enabled).apply()
                        else {
                            suppressChange = true
                            if (queryOk) control.isChecked = actuallyEnabled else control.isChecked = !enabled
                            suppressChange = false
                            val details = if (queryOk && actuallyEnabled == enabled) observed else output
                            Toast.makeText(this, tr("AYANEO component state was not changed: ", "Состояние компонента AYANEO не изменилось: ", "AYANEO-component is niet gewijzigd: ") + details.take(140), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            card.addView(control, LinearLayout.LayoutParams(-1, dp(44)))
        }
        componentSwitch("AYANEO Home", "aya_home_enabled", "com.ayaneo.home/com.ayaneo.home.ui.main.LauncherActivity")
        componentSwitch("AYASpace", "aya_space_enabled", "com.ayaneo.home/com.ayaneo.space.ui.global.SpaceActivity")
        val auto = Switch(this).apply {
            text = tr("Start PocketHub after boot", "Запускать PocketHub после загрузки", "Start PocketHub na het opstarten", "Iniciar PocketHub al arrancar")
            textSize = 12f; setTextColor(white)
            isChecked = prefs.getBoolean("auto_start_pockethub", false)
            setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("auto_start_pockethub", enabled).apply() }
        }
        card.addView(auto, LinearLayout.LayoutParams(-1, dp(44)))
        val hideRecents = Switch(this).apply {
            text = tr("Hide PocketHub from Recent apps", "Скрывать PocketHub из недавних приложений", "PocketHub verbergen in recente apps", "Ocultar PocketHub de apps recientes")
            textSize = 12f; setTextColor(white)
            isChecked = prefs.getBoolean(KEY_HIDE_FROM_RECENTS, true)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(KEY_HIDE_FROM_RECENTS, enabled).apply()
                applyRecentsVisibility()
            }
        }
        card.addView(hideRecents, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(label(tr(
            "PocketHub won't show up in Recents and can't be swiped away there. Open it with the AYANEO button.",
            "PocketHub не видно в недавних, и его нельзя там случайно смахнуть и закрыть. Открывай его кнопкой AYANEO.",
            "PocketHub verschijnt niet in Recent en kan daar niet worden weggeveegd. Open het met de AYANEO-knop.",
            "PocketHub no aparece en Recientes y no se puede cerrar deslizando. Ábrelo con el botón AYANEO."
        ), 9f, muted))
        return card
    }

    /** Hides (or shows) PocketHub's task in Recent apps, so it can't be swiped away there. */
    private fun applyRecentsVisibility() {
        val hide = getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_HIDE_FROM_RECENTS, true)
        try {
            (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.setExcludeFromRecents(hide) }
        } catch (error: Exception) { Log.w("PocketHub", "Could not change Recents visibility", error) }
    }

    private fun selectedKeyboardLanguages(
        prefs: android.content.SharedPreferences,
        languages: List<Pair<String, String>>
    ): Set<String> {
        val validCodes = languages.map { it.first }.toSet()
        val stored = prefs.getStringSet(
            DualScreenImeService.KEY_ENABLED_LANGUAGES,
            DualScreenImeService.DEFAULT_ENABLED_LANGUAGES
        )?.filter { it in validCodes }?.toSet().orEmpty()
        return stored.ifEmpty { DualScreenImeService.DEFAULT_ENABLED_LANGUAGES }
    }

    private fun keyboardLanguageSummary(
        prefs: android.content.SharedPreferences,
        languages: List<Pair<String, String>>
    ): String {
        val selected = languages.filter { it.first in selectedKeyboardLanguages(prefs, languages) }.map { it.second }
        val shown = if (selected.size <= 3) selected.joinToString("  ·  ") else "${selected.take(2).joinToString("  ·  ")}  +${selected.size - 2}"
        return "$shown     ▾"
    }

    private fun showKeyboardLanguageDropdown(
        anchor: TextView,
        prefs: android.content.SharedPreferences,
        languages: List<Pair<String, String>>
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(cardBg, border, 14)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        lateinit var popup: PopupWindow

        fun renderChoices() {
            list.removeAllViews()
            val selected = selectedKeyboardLanguages(prefs, languages)
            languages.forEach { (code, title) ->
                val checked = code in selected
                list.addView(settingsChoice("${if (checked) "✓" else "□"}   $title", checked) {
                    val updated = selectedKeyboardLanguages(prefs, languages).toMutableSet()
                    if (code in updated) updated.remove(code) else updated.add(code)
                    if (updated.isEmpty()) {
                        Toast.makeText(this, tr("Keep at least one keyboard language.", "Оставь включённым хотя бы один язык.", "Behoud minstens één toetsenbordtaal.", "Mantén al menos un idioma de teclado."), Toast.LENGTH_SHORT).show()
                        return@settingsChoice
                    }
                    val orderedEnabled = languages.map { it.first }.filter { it in updated }
                    val activeLanguage = prefs.getString(DualScreenImeService.KEY_LANGUAGE, DualScreenImeService.LANGUAGE_EN)
                    prefs.edit()
                        .putStringSet(DualScreenImeService.KEY_ENABLED_LANGUAGES, updated)
                        .putString(DualScreenImeService.KEY_LANGUAGE, activeLanguage?.takeIf { it in updated } ?: orderedEnabled.first())
                        .apply()
                    anchor.text = keyboardLanguageSummary(prefs, languages)
                    renderChoices()
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) })
            }
        }

        renderChoices()
        if (keyboardLanguagePopup?.isShowing == true) return
        popup = PopupWindow(panel, anchor.width.coerceAtLeast(dp(240)), dp(320), true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }
        keyboardLanguagePopup = popup
        popup.showAsDropDown(anchor)
    }

    private fun applyAppearancePreferences() {
        when (accentScheme) {
            "red" -> {
                bg = Color.rgb(10, 9, 13); cardBg = Color.rgb(25, 20, 27); cardBg2 = Color.rgb(38, 28, 37)
                mint = Color.rgb(255, 76, 94); cyan = Color.rgb(255, 140, 150); white = Color.rgb(248, 241, 245)
                muted = Color.rgb(188, 164, 176); border = Color.rgb(104, 58, 70)
            }
            "blue" -> {
                bg = Color.rgb(5, 12, 22); cardBg = Color.rgb(13, 27, 43); cardBg2 = Color.rgb(20, 39, 61)
                mint = Color.rgb(70, 155, 255); cyan = Color.rgb(80, 220, 255); white = Color.rgb(240, 247, 255)
                muted = Color.rgb(153, 180, 210); border = Color.rgb(49, 91, 130)
            }
            "green" -> {
                bg = Color.rgb(5, 13, 12); cardBg = Color.rgb(12, 28, 26); cardBg2 = Color.rgb(18, 42, 38)
                mint = Color.rgb(44, 255, 177); cyan = Color.rgb(74, 230, 199); white = Color.rgb(237, 251, 247)
                muted = Color.rgb(148, 190, 178); border = Color.rgb(43, 104, 87)
            }
            else -> {
                bg = Color.rgb(3, 5, 8); cardBg = Color.rgb(9, 12, 17); cardBg2 = Color.rgb(17, 22, 29)
                mint = Color.rgb(143, 170, 190); cyan = Color.rgb(102, 139, 165); white = Color.rgb(222, 229, 236)
                muted = Color.rgb(135, 150, 162); border = Color.rgb(42, 53, 64)
            }
        }
        if (layoutTheme == "solstice") {
            bg = Color.rgb(242, 234, 211)
            cardBg = Color.rgb(255, 250, 233)
            cardBg2 = Color.rgb(231, 221, 194)
            mint = Color.rgb(194, 80, 39)
            cyan = Color.rgb(35, 112, 98)
            white = Color.rgb(38, 48, 39)
            muted = Color.rgb(103, 103, 81)
            border = Color.rgb(177, 149, 99)
        }
        if (layoutTheme == "synthwave") {
            bg = Color.rgb(8, 5, 24)
            cardBg = Color.rgb(19, 11, 42)
            cardBg2 = Color.rgb(30, 16, 57)
            mint = Color.rgb(255, 72, 190)
            cyan = Color.rgb(59, 229, 255)
            white = Color.rgb(249, 239, 255)
            muted = Color.rgb(181, 158, 211)
            border = Color.rgb(111, 54, 149)
        }
        if (layoutTheme == "volcanic") {
            bg = Color.rgb(12, 8, 9)
            cardBg = Color.rgb(29, 16, 14)
            cardBg2 = Color.rgb(47, 25, 17)
            mint = Color.rgb(255, 153, 65)
            cyan = Color.rgb(255, 211, 133)
            white = Color.rgb(255, 244, 226)
            muted = Color.rgb(190, 153, 131)
            border = Color.rgb(125, 62, 38)
        }
        if (layoutTheme == "pop") {
            // Pop Gradient: bright moving mesh gradient, white cards, hot pink and violet.
            bg = Color.rgb(255, 238, 246)
            cardBg = Color.rgb(255, 255, 255)
            cardBg2 = Color.rgb(251, 238, 248)
            mint = Color.rgb(255, 46, 136)
            cyan = Color.rgb(118, 72, 255)
            white = Color.rgb(30, 20, 50)
            muted = Color.rgb(112, 98, 138)
            border = Color.rgb(238, 210, 236)
        }
        LivingIconView.popStyle = layoutTheme == "pop"
        if (layoutTheme == "aurora") {
            // Aurora Live: deep night sky, glowing teal / violet / pink, glass cards.
            bg = Color.rgb(9, 8, 24)
            cardBg = Color.rgb(28, 24, 60)
            cardBg2 = Color.rgb(40, 34, 82)
            mint = Color.rgb(64, 240, 196)
            cyan = Color.rgb(255, 112, 200)
            white = Color.rgb(242, 240, 255)
            muted = Color.rgb(166, 160, 210)
            border = Color.rgb(84, 76, 140)
        }
        LivingIconView.bridgeStyle = layoutTheme == "bridge"
        if (layoutTheme == "bridge") {
            // Starship Console: black, warm orange / peach / lavender console colours.
            bg = Color.rgb(0, 0, 0)
            cardBg = Color.rgb(12, 11, 18)
            cardBg2 = Color.rgb(28, 24, 38)
            mint = Color.rgb(255, 153, 0)
            cyan = Color.rgb(204, 153, 255)
            white = Color.rgb(255, 236, 214)
            muted = Color.rgb(153, 153, 204)
            border = Color.rgb(102, 92, 140)
        }
        if (layoutTheme == "dusk") {
            // Dusk: Aurora's glass layout in quiet slate and indigo, soft sage and dusty-rose accents.
            bg = Color.rgb(16, 18, 26)
            cardBg = Color.rgb(30, 34, 46)
            cardBg2 = Color.rgb(40, 45, 60)
            mint = Color.rgb(126, 200, 180)
            cyan = Color.rgb(214, 150, 160)
            white = Color.rgb(232, 234, 240)
            muted = Color.rgb(150, 156, 176)
            border = Color.rgb(62, 68, 88)
        }
        LivingIconView.neoStyle = layoutTheme == "obsidian"
        LivingIconView.chronoStyle = layoutTheme == "chrono"
        LivingIconView.blueprintStyle = layoutTheme == "blueprint"
        LivingIconView.cartStyle = layoutTheme == "cartridge"
        LivingIconView.abyssStyle = layoutTheme == "abyss"
        LivingIconView.hifiStyle = layoutTheme == "hifi"
        LivingIconView.hudStyle = layoutTheme == "hud"
        LivingIconView.bauhausStyle = layoutTheme == "bauhaus"
        LivingIconView.jadeStyle = layoutTheme == "jade"
        if (layoutTheme == "jade") {
            // Jade: deep forest green, jade and pale lime light.
            bg = Color.rgb(5, 14, 10)
            cardBg = Color.rgb(11, 27, 20)
            cardBg2 = Color.rgb(16, 39, 29)
            mint = Color.rgb(70, 214, 142)
            cyan = Color.rgb(176, 230, 120)
            white = Color.rgb(224, 244, 231)
            muted = Color.rgb(116, 154, 132)
            border = Color.rgb(32, 72, 52)
        }
        if (layoutTheme == "bauhaus") {
            // Bauhaus: black and off-white with the three primaries — yellow, red, blue.
            bg = Color.rgb(14, 14, 14)
            cardBg = Color.rgb(27, 27, 26)
            cardBg2 = Color.rgb(40, 40, 38)
            mint = Color.rgb(255, 200, 0)
            cyan = Color.rgb(36, 110, 230)
            white = Color.rgb(242, 238, 228)
            muted = Color.rgb(150, 146, 138)
            border = Color.rgb(70, 70, 68)
        }
        if (layoutTheme == "hud") {
            // Hologram: projected cyan light on black, amber for highlights.
            bg = Color.rgb(2, 7, 11)
            cardBg = Color.rgb(5, 22, 30)
            cardBg2 = Color.rgb(8, 34, 45)
            mint = Color.rgb(0, 226, 255)
            cyan = Color.rgb(255, 196, 0)
            white = Color.rgb(212, 248, 255)
            muted = Color.rgb(92, 150, 166)
            border = Color.rgb(18, 82, 98)
        }
        FpsGraphView.spectrumStyle = layoutTheme == "hifi"
        if (layoutTheme == "hifi") {
            // Hi-Fi Deck: an 80s stereo component — brushed dark metal, amber and teal display light.
            bg = Color.rgb(13, 12, 11)
            cardBg = Color.rgb(31, 30, 28)
            cardBg2 = Color.rgb(46, 44, 41)
            mint = Color.rgb(255, 178, 48)
            cyan = Color.rgb(92, 236, 214)
            white = Color.rgb(238, 232, 220)
            muted = Color.rgb(156, 148, 134)
            border = Color.rgb(76, 72, 66)
        }
        if (layoutTheme == "abyss") {
            // Deep Sea: dark water, lit by turquoise and violet; profiles keep their own colours.
            bg = Color.rgb(4, 10, 18)
            cardBg = Color.rgb(10, 21, 33)
            cardBg2 = Color.rgb(15, 31, 47)
            mint = Color.rgb(64, 222, 206)
            cyan = Color.rgb(156, 128, 255)
            white = Color.rgb(226, 240, 248)
            muted = Color.rgb(116, 146, 166)
            border = Color.rgb(32, 60, 80)
        }
        if (layoutTheme == "cartridge") {
            // Cartridge: retro console plastic in a modern cut — charcoal shells, cartridge red and cream gold.
            bg = Color.rgb(17, 17, 21)
            cardBg = Color.rgb(31, 31, 37)
            cardBg2 = Color.rgb(42, 42, 50)
            mint = Color.rgb(232, 86, 92)
            cyan = Color.rgb(236, 198, 124)
            white = Color.rgb(240, 237, 232)
            muted = Color.rgb(146, 143, 154)
            border = Color.rgb(60, 60, 70)
        }
        if (layoutTheme == "blueprint") {
            // Blueprint: an engineering drawing at night — pale ink on deep navy, one muted blue.
            bg = Color.rgb(7, 13, 24)
            cardBg = Color.rgb(11, 21, 37)
            cardBg2 = Color.rgb(17, 31, 52)
            mint = Color.rgb(206, 222, 242)
            cyan = Color.rgb(118, 160, 214)
            white = Color.rgb(226, 234, 245)
            muted = Color.rgb(112, 132, 162)
            border = Color.rgb(42, 64, 98)
        }
        if (layoutTheme == "chrono") {
            // Chronograph: near-black like a watch dial at night, champagne gold and cold steel.
            bg = Color.rgb(3, 3, 4)
            cardBg = Color.rgb(15, 14, 12)
            cardBg2 = Color.rgb(27, 25, 21)
            mint = Color.rgb(222, 182, 108)
            cyan = Color.rgb(146, 180, 212)
            white = Color.rgb(242, 236, 224)
            muted = Color.rgb(148, 138, 120)
            border = Color.rgb(60, 53, 41)
        }
        if (layoutTheme == "obsidian") {
            // Obsidian: near-black graphite, hairline panels, electric lime with ion-blue.
            bg = Color.rgb(6, 7, 9)
            cardBg = Color.rgb(16, 18, 22)
            cardBg2 = Color.rgb(25, 28, 34)
            mint = Color.rgb(190, 242, 74)
            cyan = Color.rgb(92, 158, 255)
            white = Color.rgb(236, 238, 242)
            muted = Color.rgb(130, 136, 150)
            border = Color.rgb(42, 46, 54)
        }
        if (layoutTheme == "brutal_dark") {
            // Same neo-brutal design on a near-black page: light outlines and hard light shadows.
            bg = Color.rgb(22, 22, 24)
            cardBg = Color.rgb(36, 36, 40)
            cardBg2 = Color.rgb(48, 47, 52)
            mint = Color.rgb(92, 140, 255)
            cyan = Color.rgb(255, 112, 112)
            white = BRUTAL_PAPER
            muted = Color.rgb(176, 170, 156)
            border = BRUTAL_PAPER
        }
        if (layoutTheme == "brutal") {
            // Neo-brutalism: warm paper, ink-black outlines, flat saturated accents.
            bg = Color.rgb(246, 240, 226)
            cardBg = Color.rgb(255, 255, 255)
            cardBg2 = Color.rgb(240, 233, 216)
            mint = Color.rgb(51, 102, 255)
            cyan = Color.rgb(255, 92, 92)
            white = BRUTAL_INK
            muted = Color.rgb(92, 86, 74)
            border = BRUTAL_INK
        }
        if (layoutTheme == "eightbit") {
            bg = Color.rgb(8, 12, 27)
            cardBg = Color.rgb(17, 25, 47)
            cardBg2 = Color.rgb(27, 39, 68)
            mint = Color.rgb(205, 255, 75)
            cyan = Color.rgb(91, 224, 213)
            white = Color.rgb(255, 248, 221)
            muted = Color.rgb(161, 180, 211)
            border = Color.rgb(72, 91, 139)
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    private fun tr(en: String, ru: String, nl: String = en, es: String = ""): String = when (uiLanguage) {
        "ru" -> ru
        "nl" -> nl
        "es" -> es.ifBlank { spanishUiLabels[en] ?: en }
        "de" -> germanUiLabels[en] ?: en
        "fr" -> frenchUiLabels[en] ?: en
        "it" -> italianUiLabels[en] ?: en
        "zh" -> chineseUiLabels[en] ?: en
        else -> en
    }

    private val germanUiLabels = mapOf(
        "Starship Console" to "Raumschiff-Konsole", "Starship console" to "Raumschiff-Konsole",
        "Dusk" to "Dämmerung", "Obsidian" to "Obsidian", "Chronograph" to "Chronograph", "Blueprint" to "Blaupause", "Cartridge" to "Spielmodul", "Deep Sea" to "Tiefsee", "Hi-Fi Deck" to "Hi-Fi-Anlage", "Hologram" to "Hologramm", "Jade" to "Jade",
        "DUAL SCREEN" to "2 DISPLAYS", "▣  DUAL SCREEN CONTROL" to "▣  ZWEI-DISPLAY-STEUERUNG", "Dual screen controls" to "Zwei-Display-Steuerung",
        "PC KEYBOARD" to "PC-TASTATUR",
        "Full computer layout · Ctrl, Alt, F1–F12" to "Volles PC-Layout · Strg, Alt, F1–F12",
        "FAVORITES" to "FAVORITEN",
        "Your apps" to "Deine Apps",
        "Tap to open on the top screen · hold for more" to "Tippen öffnet oben · halten für mehr",
        "ADD" to "HINZUFÜGEN",
        "TOOLS" to "WERKZEUGE",
        "Add app" to "App hinzufügen",
        "ADD TO FAVORITES" to "ZU FAVORITEN HINZUFÜGEN",
        "Search" to "Suchen",
        "CLOSE" to "SCHLIESSEN",
        "Open on top screen" to "Oben öffnen",
        "Open on bottom screen" to "Unten öffnen",
        "Move left" to "Nach links",
        "Move right" to "Nach rechts",
        "Remove from favorites" to "Aus Favoriten entfernen",
        "App not found" to "App nicht gefunden",
        "KEYBOARD" to "TASTATUR",
        "Type into the top screen" to "Eingabe auf dem oberen Bildschirm",
        "TOUCHPAD" to "TOUCHPAD",
        "Mouse for the top screen" to "Maus für den oberen Bildschirm",
        "Select PocketHub Keyboard as the keyboard first" to "Wähle zuerst PocketHub Keyboard als Tastatur",
        "Allow PocketHub to display over other apps" to "Erlaube PocketHub, über anderen Apps anzuzeigen",
        "Could not open the keyboard" to "Tastatur konnte nicht geöffnet werden",
        "VIRTUAL MEMORY (SWAP)" to "VIRTUELLER SPEICHER (SWAP)",
        "SWAP FILE · USED / FREE" to "SWAP-DATEI · BELEGT / FREI",
        "Uses internal storage for swap. It is much slower than RAM and zRAM and does not increase physical RAM." to "Verwendet den internen Speicher als Swap. Er ist deutlich langsamer als RAM und zRAM und erhöht den physischen RAM nicht.",
        "APPLY VIRTUAL MEMORY" to "VIRTUELLEN SPEICHER ANWENDEN", "Off" to "Aus",
        "Reading virtual memory status…" to "Status des virtuellen Speichers wird gelesen…",
        "Swap status unavailable: " to "Swap-Status nicht verfügbar: ",
        "Turning off storage swap…" to "Speicher-Swap wird ausgeschaltet…",
        "Creating and enabling swap file…" to "Swap-Datei wird erstellt und aktiviert…",
        "Storage swap is off; Android zRAM remains active." to "Speicher-Swap ist aus; Android-zRAM bleibt aktiv.",
        "Storage swap enabled: " to "Speicher-Swap aktiviert: ",
        "8-Bit Deck" to "8-Bit-Systemdeck", "8-BIT CONTROL DECK" to "8-BIT-SYSTEMDECK",
        "POWER MODES" to "LEISTUNGSMODI", "SYSTEM TELEMETRY" to "SYSTEMTELEMETRIE",
        "Volcanic" to "Vulkan", "Orbit" to "Orbit",
        "UTILITY BAR WHEN KEYBOARD OPENS" to "WERKZEUGLEISTE BEIM ÖFFNEN DER TASTATUR", "Shown" to "Anzeigen", "Hidden" to "Ausblenden",
        "CAPTURE" to "SCREENSHOT", "Capture both screens" to "Beide Displays aufnehmen",
        "Capturing both screens…" to "Beide Displays werden aufgenommen…", "Could not capture both screens" to "Beide Displays konnten nicht aufgenommen werden",
        "Choose a screen to save" to "Wähle ein Display zum Speichern", "Top screen" to "Oberes Display", "Bottom screen" to "Unteres Display",
        "Screenshot saved" to "Screenshot gespeichert", "Could not save screenshot" to "Screenshot konnte nicht gespeichert werden",
        "SAVE BOTH" to "BEIDE SPEICHERN", "Both screenshots saved" to "Beide Screenshots gespeichert", "Could not save both screenshots" to "Beide Screenshots konnten nicht gespeichert werden",
        "PERFORMANCE PROFILE" to "LEISTUNGSPROFIL", "FAN" to "LÜFTER", "DISPLAY" to "ANZEIGE",
        "RAM" to "RAM", "STORAGE" to "SPEICHER", "Tap to control" to "Tippen zum Steuern",
        "Tap to choose" to "Tippen zum Auswählen", "Used / installed" to "Belegt / installiert",
        "Main display game FPS" to "Spiel-FPS auf dem Hauptdisplay", "Brightness" to "Helligkeit",
        "Sound" to "Ton", "SETTINGS" to "EINSTELLUNGEN", "LIVE STATUS" to "LIVE-STATUS",
        "Refresh: 2 s" to "Aktualisierung: 2 s", "Language" to "Sprache",
        "Choose the app language. Your choice is saved on this device." to "Wähle die App-Sprache. Deine Auswahl wird auf diesem Gerät gespeichert.",
        "COLOR ACCENT" to "AKZENTFARBE", "Four palettes, independent of the layout style." to "Vier Farbpaletten, unabhängig vom Layoutstil.",
        "INTERFACE DESIGN" to "OBERFLÄCHENDESIGN", "These change card shape, density, spacing and visual character—not just the colors." to "Diese Optionen ändern Kartenform, Dichte, Abstände und Stil, nicht nur die Farben.",
        "Red" to "Rot", "Blue" to "Blau", "Green" to "Grün", "Dark" to "Dunkel",
        "Glass" to "Glas", "Compact" to "Kompakt", "Neon console" to "Neon-Konsole", "Arcade" to "Arcade", "Solstice" to "Sonnenwende", "Terminal" to "Terminal",
        "DUAL-SCREEN KEYBOARD" to "TASTATUR FÜR ZWEI DISPLAYS", "Prototype: type on the main screen and show this keyboard below." to "Prototyp: Tippe auf dem Hauptdisplay, die Tastatur erscheint unten.",
        "Bottom screen" to "Unteres Display", "Active screen" to "Aktives Display", "KEYBOARD LANGUAGES" to "TASTATURSPRACHEN",
        "Choose which languages appear on the keyboard." to "Wähle die Sprachen für die Tastatur.", "KEYBOARD THEME" to "TASTATURTHEMA",
        "Ocean" to "Ozean", "Graphite" to "Graphit", "Neon" to "Neon", "Light" to "Hell",
        "KEY SIZE" to "TASTENGRÖSSE", "Small" to "Klein", "Standard" to "Standard", "Large" to "Groß",
        "TOUCH FEEDBACK" to "TOUCH-FEEDBACK", "Vibration on" to "Vibration an", "Vibration off" to "Vibration aus",
        "Enable keyboard" to "Tastatur aktivieren", "Select keyboard" to "Tastatur auswählen", "About" to "Über",
        "Close" to "Schließen", "Changes apply immediately and are saved automatically." to "Änderungen werden sofort übernommen und automatisch gespeichert.",
        "PocketHub\n\nA control hub and dual-screen keyboard for AYANEO Pocket DS.\n\nGitHub link coming soon." to "PocketHub\n\nSteuerzentrale und Dual-Screen-Tastatur für AYANEO Pocket DS.\n\nGitHub-Link folgt.",
        "Display brightness settings" to "Displayhelligkeit einstellen", "Sound settings" to "Toneinstellungen",
        "Top display" to "Oberes Display", "Bottom display" to "Unteres Display", "BATTERY" to "AKKU",
        "Charging" to "Wird geladen", "On battery" to "Akkubetrieb", "POWER" to "LEISTUNG",
        "DONE" to "FERTIG", "Eco" to "Eco", "Balanced" to "Ausgeglichen", "Streaming" to "Streaming",
        "Gaming" to "Gaming", "Max" to "Maximal", "Notifications" to "Benachrichtigungen",
        "Ringtone" to "Klingelton", "Alarm" to "Wecker", "System sounds" to "Systemtöne",
        "Volume controls" to "Lautstärkeregler", "Per-app volume" to "Lautstärke je App",
        "Android sound settings" to "Android-Toneinstellungen", "DISPLAY BRIGHTNESS" to "DISPLAYHELLIGKEIT",
        "Adjust both screens independently" to "Beide Displays getrennt einstellen", "Top display" to "Oberes Display",
        "Bottom display" to "Unteres Display", "TEMPERATURE" to "TEMPERATUR", "CPU" to "CPU", "GPU" to "GPU"
    )

    private val frenchUiLabels = mapOf(
        "Starship Console" to "Console de vaisseau", "Starship console" to "Console de vaisseau",
        "Dusk" to "Crépuscule", "Obsidian" to "Obsidienne", "Chronograph" to "Chronographe", "Blueprint" to "Plan technique", "Cartridge" to "Cartouche", "Deep Sea" to "Abysses", "Hi-Fi Deck" to "Chaîne hi-fi", "Hologram" to "Hologramme", "Jade" to "Jade",
        "DUAL SCREEN" to "2 ÉCRANS", "▣  DUAL SCREEN CONTROL" to "▣  CONTRÔLE DES DEUX ÉCRANS", "Dual screen controls" to "Contrôle des deux écrans",
        "PC KEYBOARD" to "CLAVIER PC",
        "Full computer layout · Ctrl, Alt, F1–F12" to "Disposition PC complète · Ctrl, Alt, F1–F12",
        "FAVORITES" to "FAVORIS",
        "Your apps" to "Vos apps",
        "Tap to open on the top screen · hold for more" to "Touchez pour ouvrir en haut · maintenez pour plus",
        "ADD" to "AJOUTER",
        "TOOLS" to "OUTILS",
        "Add app" to "Ajouter",
        "ADD TO FAVORITES" to "AJOUTER AUX FAVORIS",
        "Search" to "Rechercher",
        "CLOSE" to "FERMER",
        "Open on top screen" to "Ouvrir en haut",
        "Open on bottom screen" to "Ouvrir en bas",
        "Move left" to "Déplacer à gauche",
        "Move right" to "Déplacer à droite",
        "Remove from favorites" to "Retirer des favoris",
        "App not found" to "App introuvable",
        "KEYBOARD" to "CLAVIER",
        "Type into the top screen" to "Saisie sur l'écran supérieur",
        "TOUCHPAD" to "PAVÉ TACTILE",
        "Mouse for the top screen" to "Souris pour l'écran supérieur",
        "Select PocketHub Keyboard as the keyboard first" to "Choisissez d'abord PocketHub Keyboard comme clavier",
        "Allow PocketHub to display over other apps" to "Autorisez PocketHub à s'afficher par-dessus les autres apps",
        "Could not open the keyboard" to "Impossible d'ouvrir le clavier",
        "VIRTUAL MEMORY (SWAP)" to "MÉMOIRE VIRTUELLE (SWAP)",
        "SWAP FILE · USED / FREE" to "FICHIER SWAP · UTILISÉ / LIBRE",
        "Uses internal storage for swap. It is much slower than RAM and zRAM and does not increase physical RAM." to "Utilise le stockage interne comme swap. C’est beaucoup plus lent que la RAM et zRAM et cela n’augmente pas la RAM physique.",
        "APPLY VIRTUAL MEMORY" to "APPLIQUER LA MÉMOIRE VIRTUELLE", "Off" to "Désactivé",
        "Reading virtual memory status…" to "Lecture de l’état de la mémoire virtuelle…",
        "Swap status unavailable: " to "État du swap indisponible : ",
        "Turning off storage swap…" to "Désactivation du swap de stockage…",
        "Creating and enabling swap file…" to "Création et activation du fichier swap…",
        "Storage swap is off; Android zRAM remains active." to "Le swap de stockage est désactivé ; la zRAM Android reste active.",
        "Storage swap enabled: " to "Swap de stockage activé : ",
        "8-Bit Deck" to "Console 8 bits", "8-BIT CONTROL DECK" to "CONSOLE DE CONTRÔLE 8-BIT",
        "POWER MODES" to "MODES DE PERFORMANCE", "SYSTEM TELEMETRY" to "TÉLÉMÉTRIE SYSTÈME",
        "Volcanic" to "Volcanique", "Orbit" to "Orbite",
        "UTILITY BAR WHEN KEYBOARD OPENS" to "BARRE D’ACTIONS À L’OUVERTURE DU CLAVIER", "Shown" to "Afficher", "Hidden" to "Masquer",
        "CAPTURE" to "CAPTURE", "Capture both screens" to "Capturer les deux écrans",
        "Capturing both screens…" to "Capture des deux écrans…", "Could not capture both screens" to "Impossible de capturer les deux écrans",
        "Choose a screen to save" to "Choisissez l’écran à enregistrer", "Top screen" to "Écran supérieur", "Bottom screen" to "Écran inférieur",
        "Screenshot saved" to "Capture enregistrée", "Could not save screenshot" to "Impossible d’enregistrer la capture",
        "SAVE BOTH" to "TOUT ENREGISTRER", "Both screenshots saved" to "Les deux captures sont enregistrées", "Could not save both screenshots" to "Impossible d’enregistrer les deux captures",
        "PERFORMANCE PROFILE" to "PROFIL DE PERFORMANCE", "FAN" to "VENTILATEUR", "DISPLAY" to "ÉCRAN",
        "RAM" to "RAM", "STORAGE" to "STOCKAGE", "Tap to control" to "Toucher pour régler",
        "Tap to choose" to "Toucher pour choisir", "Used / installed" to "Utilisée / installée",
        "Main display game FPS" to "FPS du jeu sur l’écran principal", "Brightness" to "Luminosité",
        "Sound" to "Son", "SETTINGS" to "PARAMÈTRES", "LIVE STATUS" to "ÉTAT EN DIRECT",
        "Refresh: 2 s" to "Actualisation : 2 s", "Language" to "Langue",
        "Choose the app language. Your choice is saved on this device." to "Choisissez la langue de l’application. Votre choix est enregistré sur cet appareil.",
        "COLOR ACCENT" to "COULEUR D’ACCENT", "Four palettes, independent of the layout style." to "Quatre palettes, indépendantes du style de l’interface.",
        "INTERFACE DESIGN" to "STYLE DE L’INTERFACE", "These change card shape, density, spacing and visual character—not just the colors." to "Ces options modifient la forme des cartes, la densité, les espacements et le style, pas seulement les couleurs.",
        "Red" to "Rouge", "Blue" to "Bleu", "Green" to "Vert", "Dark" to "Sombre",
        "Glass" to "Verre", "Compact" to "Compact", "Neon console" to "Console néon", "Arcade" to "Arcade", "Solstice" to "Solstice", "Terminal" to "Terminal",
        "DUAL-SCREEN KEYBOARD" to "CLAVIER À DEUX ÉCRANS", "Prototype: type on the main screen and show this keyboard below." to "Prototype : saisissez sur l’écran principal et affichez le clavier en bas.",
        "Bottom screen" to "Écran inférieur", "Active screen" to "Écran actif", "KEYBOARD LANGUAGES" to "LANGUES DU CLAVIER",
        "Choose which languages appear on the keyboard." to "Choisissez les langues disponibles sur le clavier.", "KEYBOARD THEME" to "THÈME DU CLAVIER",
        "Ocean" to "Océan", "Graphite" to "Graphite", "Neon" to "Néon", "Light" to "Clair",
        "KEY SIZE" to "TAILLE DES TOUCHES", "Small" to "Petite", "Standard" to "Standard", "Large" to "Grande",
        "TOUCH FEEDBACK" to "RETOUR TACTILE", "Vibration on" to "Vibration activée", "Vibration off" to "Vibration désactivée",
        "Enable keyboard" to "Activer le clavier", "Select keyboard" to "Choisir le clavier", "About" to "À propos",
        "Close" to "Fermer", "Changes apply immediately and are saved automatically." to "Les modifications sont appliquées immédiatement et enregistrées automatiquement.",
        "PocketHub\n\nA control hub and dual-screen keyboard for AYANEO Pocket DS.\n\nGitHub link coming soon." to "PocketHub\n\nCentre de contrôle et clavier à deux écrans pour AYANEO Pocket DS.\n\nLien GitHub bientôt disponible.",
        "Display brightness settings" to "Réglages de luminosité des écrans", "Sound settings" to "Réglages du son",
        "Top display" to "Écran supérieur", "Bottom display" to "Écran inférieur", "BATTERY" to "BATTERIE",
        "Charging" to "En charge", "On battery" to "Sur batterie", "POWER" to "PUISSANCE", "DONE" to "TERMINÉ",
        "Eco" to "Éco", "Balanced" to "Équilibré", "Streaming" to "Streaming", "Gaming" to "Jeu", "Max" to "Max",
        "Notifications" to "Notifications", "Ringtone" to "Sonnerie", "Alarm" to "Alarme", "System sounds" to "Sons système",
        "Volume controls" to "Commandes de volume", "Per-app volume" to "Volume par application", "Android sound settings" to "Réglages audio Android",
        "DISPLAY BRIGHTNESS" to "LUMINOSITÉ DE L’ÉCRAN", "Adjust both screens independently" to "Réglez séparément les deux écrans",
        "TEMPERATURE" to "TEMPÉRATURE", "CPU" to "CPU", "GPU" to "GPU"
    )

    private val italianUiLabels = mapOf(
        "Starship Console" to "Console stellare", "Starship console" to "Console stellare",
        "Dusk" to "Crepuscolo", "Obsidian" to "Ossidiana", "Chronograph" to "Cronografo", "Blueprint" to "Progetto", "Cartridge" to "Cartuccia", "Deep Sea" to "Abisso", "Hi-Fi Deck" to "Impianto hi-fi", "Hologram" to "Ologramma", "Jade" to "Giada",
        "DUAL SCREEN" to "2 SCHERMI", "▣  DUAL SCREEN CONTROL" to "▣  CONTROLLO DOPPIO SCHERMO", "Dual screen controls" to "Controllo doppio schermo",
        "PC KEYBOARD" to "TASTIERA PC",
        "Full computer layout · Ctrl, Alt, F1–F12" to "Layout PC completo · Ctrl, Alt, F1–F12",
        "FAVORITES" to "PREFERITI",
        "Your apps" to "Le tue app",
        "Tap to open on the top screen · hold for more" to "Tocca per aprire sopra · tieni premuto per altro",
        "ADD" to "AGGIUNGI",
        "TOOLS" to "STRUMENTI",
        "Add app" to "Aggiungi",
        "ADD TO FAVORITES" to "AGGIUNGI AI PREFERITI",
        "Search" to "Cerca",
        "CLOSE" to "CHIUDI",
        "Open on top screen" to "Apri sopra",
        "Open on bottom screen" to "Apri sotto",
        "Move left" to "Sposta a sinistra",
        "Move right" to "Sposta a destra",
        "Remove from favorites" to "Rimuovi dai preferiti",
        "App not found" to "App non trovata",
        "KEYBOARD" to "TASTIERA",
        "Type into the top screen" to "Scrivi sullo schermo superiore",
        "TOUCHPAD" to "TOUCHPAD",
        "Mouse for the top screen" to "Mouse per lo schermo superiore",
        "Select PocketHub Keyboard as the keyboard first" to "Seleziona prima PocketHub Keyboard come tastiera",
        "Allow PocketHub to display over other apps" to "Consenti a PocketHub di apparire sopra le altre app",
        "Could not open the keyboard" to "Impossibile aprire la tastiera",
        "VIRTUAL MEMORY (SWAP)" to "MEMORIA VIRTUALE (SWAP)",
        "SWAP FILE · USED / FREE" to "FILE SWAP · USATA / LIBERA",
        "Uses internal storage for swap. It is much slower than RAM and zRAM and does not increase physical RAM." to "Usa la memoria interna come swap. È molto più lenta della RAM e zRAM e non aumenta la RAM fisica.",
        "APPLY VIRTUAL MEMORY" to "APPLICA MEMORIA VIRTUALE", "Off" to "Disattivato",
        "Reading virtual memory status…" to "Lettura dello stato della memoria virtuale…",
        "Swap status unavailable: " to "Stato swap non disponibile: ",
        "Turning off storage swap…" to "Disattivazione dello swap su memoria…",
        "Creating and enabling swap file…" to "Creazione e attivazione del file swap…",
        "Storage swap is off; Android zRAM remains active." to "Lo swap su memoria è disattivato; zRAM Android resta attiva.",
        "Storage swap enabled: " to "Swap su memoria attivato: ",
        "8-Bit Deck" to "Console 8-bit", "8-BIT CONTROL DECK" to "CENTRO DI CONTROLLO 8-BIT",
        "POWER MODES" to "MODALITÀ PRESTAZIONI", "SYSTEM TELEMETRY" to "TELEMETRIA DI SISTEMA",
        "Volcanic" to "Vulcanico", "Orbit" to "Orbita",
        "UTILITY BAR WHEN KEYBOARD OPENS" to "BARRA AZIONI ALL’APERTURA DELLA TASTIERA", "Shown" to "Mostra", "Hidden" to "Nascondi",
        "CAPTURE" to "ACQUISISCI", "Capture both screens" to "Acquisisci entrambi gli schermi",
        "Capturing both screens…" to "Acquisizione di entrambi gli schermi…", "Could not capture both screens" to "Impossibile acquisire entrambi gli schermi",
        "Choose a screen to save" to "Scegli quale schermo salvare", "Top screen" to "Schermo superiore", "Bottom screen" to "Schermo inferiore",
        "Screenshot saved" to "Screenshot salvato", "Could not save screenshot" to "Impossibile salvare lo screenshot",
        "SAVE BOTH" to "SALVA ENTRAMBI", "Both screenshots saved" to "Entrambi gli screenshot salvati", "Could not save both screenshots" to "Impossibile salvare entrambi gli screenshot",
        "PERFORMANCE PROFILE" to "PROFILO PRESTAZIONI", "FAN" to "VENTOLA", "DISPLAY" to "SCHERMO",
        "RAM" to "RAM", "STORAGE" to "ARCHIVIAZIONE", "Tap to control" to "Tocca per controllare",
        "Tap to choose" to "Tocca per scegliere", "Used / installed" to "Usata / installata",
        "Main display game FPS" to "FPS di gioco sullo schermo principale", "Brightness" to "Luminosità",
        "Sound" to "Audio", "SETTINGS" to "IMPOSTAZIONI", "LIVE STATUS" to "STATO IN TEMPO REALE",
        "Refresh: 2 s" to "Aggiornamento: 2 s", "Language" to "Lingua",
        "Choose the app language. Your choice is saved on this device." to "Scegli la lingua dell’app. La scelta viene salvata su questo dispositivo.",
        "COLOR ACCENT" to "COLORE ACCENTO", "Four palettes, independent of the layout style." to "Quattro palette, indipendenti dallo stile dell’interfaccia.",
        "INTERFACE DESIGN" to "DESIGN INTERFACCIA", "These change card shape, density, spacing and visual character—not just the colors." to "Queste opzioni cambiano forma delle schede, densità, spazi e stile, non solo i colori.",
        "Red" to "Rosso", "Blue" to "Blu", "Green" to "Verde", "Dark" to "Scuro",
        "Glass" to "Vetro", "Compact" to "Compatto", "Neon console" to "Console neon", "Arcade" to "Arcade", "Solstice" to "Solstizio", "Terminal" to "Terminale",
        "DUAL-SCREEN KEYBOARD" to "TASTIERA A DOPPIO SCHERMO", "Prototype: type on the main screen and show this keyboard below." to "Prototipo: scrivi sullo schermo principale e mostra la tastiera in basso.",
        "Bottom screen" to "Schermo inferiore", "Active screen" to "Schermo attivo", "KEYBOARD LANGUAGES" to "LINGUE TASTIERA",
        "Choose which languages appear on the keyboard." to "Scegli le lingue disponibili sulla tastiera.", "KEYBOARD THEME" to "TEMA TASTIERA",
        "Ocean" to "Oceano", "Graphite" to "Grafite", "Neon" to "Neon", "Light" to "Chiaro",
        "KEY SIZE" to "DIMENSIONE TASTI", "Small" to "Piccoli", "Standard" to "Standard", "Large" to "Grandi",
        "TOUCH FEEDBACK" to "FEEDBACK TATTILE", "Vibration on" to "Vibrazione attiva", "Vibration off" to "Vibrazione disattivata",
        "Enable keyboard" to "Attiva tastiera", "Select keyboard" to "Seleziona tastiera", "About" to "Informazioni",
        "Close" to "Chiudi", "Changes apply immediately and are saved automatically." to "Le modifiche si applicano subito e vengono salvate automaticamente.",
        "PocketHub\n\nA control hub and dual-screen keyboard for AYANEO Pocket DS.\n\nGitHub link coming soon." to "PocketHub\n\nCentro di controllo e tastiera a doppio schermo per AYANEO Pocket DS.\n\nLink GitHub in arrivo.",
        "Display brightness settings" to "Impostazioni luminosità schermi", "Sound settings" to "Impostazioni audio",
        "Top display" to "Schermo superiore", "Bottom display" to "Schermo inferiore", "BATTERY" to "BATTERIA",
        "Charging" to "In carica", "On battery" to "A batteria", "POWER" to "POTENZA", "DONE" to "FATTO",
        "Eco" to "Eco", "Balanced" to "Bilanciato", "Streaming" to "Streaming", "Gaming" to "Gioco", "Max" to "Massimo",
        "Notifications" to "Notifiche", "Ringtone" to "Suoneria", "Alarm" to "Sveglia", "System sounds" to "Suoni di sistema",
        "Volume controls" to "Controlli volume", "Per-app volume" to "Volume per app", "Android sound settings" to "Impostazioni audio Android",
        "DISPLAY BRIGHTNESS" to "LUMINOSITÀ SCHERMI", "Adjust both screens independently" to "Regola separatamente entrambi gli schermi",
        "TEMPERATURE" to "TEMPERATURA", "CPU" to "CPU", "GPU" to "GPU"
    )

    private val chineseUiLabels = mapOf(
        "Starship Console" to "星舰控制台", "Starship console" to "星舰控制台",
        "Dusk" to "暮色", "Obsidian" to "黑曜石", "Chronograph" to "计时码表", "Blueprint" to "蓝图", "Cartridge" to "卡带", "Deep Sea" to "深海", "Hi-Fi Deck" to "高保真音响", "Hologram" to "全息", "Jade" to "翡翠",
        "DUAL SCREEN" to "双屏", "▣  DUAL SCREEN CONTROL" to "▣  双屏控制", "Dual screen controls" to "双屏控制",
        "PC KEYBOARD" to "电脑键盘",
        "Full computer layout · Ctrl, Alt, F1–F12" to "完整电脑布局 · Ctrl、Alt、F1–F12",
        "FAVORITES" to "收藏",
        "Your apps" to "你的应用",
        "Tap to open on the top screen · hold for more" to "点按在上屏打开 · 长按查看更多",
        "ADD" to "添加",
        "TOOLS" to "工具",
        "Add app" to "添加应用",
        "ADD TO FAVORITES" to "添加到收藏",
        "Search" to "搜索",
        "CLOSE" to "关闭",
        "Open on top screen" to "在上屏打开",
        "Open on bottom screen" to "在下屏打开",
        "Move left" to "左移",
        "Move right" to "右移",
        "Remove from favorites" to "从收藏中移除",
        "App not found" to "未找到应用",
        "KEYBOARD" to "键盘",
        "Type into the top screen" to "在上屏输入",
        "TOUCHPAD" to "触控板",
        "Mouse for the top screen" to "上屏鼠标",
        "Select PocketHub Keyboard as the keyboard first" to "请先将 PocketHub Keyboard 设为默认键盘",
        "Allow PocketHub to display over other apps" to "请允许 PocketHub 显示在其他应用上层",
        "Could not open the keyboard" to "无法打开键盘",
        "VIRTUAL MEMORY (SWAP)" to "虚拟内存（交换空间）",
        "SWAP FILE · USED / FREE" to "交换文件 · 已用 / 可用",
        "Uses internal storage for swap. It is much slower than RAM and zRAM and does not increase physical RAM." to "使用内置存储作为交换空间。速度远慢于 RAM 和 zRAM，且不会增加物理内存。",
        "APPLY VIRTUAL MEMORY" to "应用虚拟内存", "Off" to "关闭",
        "Reading virtual memory status…" to "正在读取虚拟内存状态…",
        "Swap status unavailable: " to "交换空间状态不可用：",
        "Turning off storage swap…" to "正在关闭存储交换空间…",
        "Creating and enabling swap file…" to "正在创建并启用交换文件…",
        "Storage swap is off; Android zRAM remains active." to "存储交换空间已关闭；Android zRAM 仍保持启用。",
        "Storage swap enabled: " to "已启用存储交换空间：",
        "8-Bit Deck" to "8位控制台", "8-BIT CONTROL DECK" to "8位控制台",
        "POWER MODES" to "性能模式", "SYSTEM TELEMETRY" to "系统遥测",
        "Volcanic" to "火山", "Orbit" to "轨道",
        "UTILITY BAR WHEN KEYBOARD OPENS" to "键盘打开时显示工具栏", "Shown" to "显示", "Hidden" to "隐藏",
        "CAPTURE" to "截图", "Capture both screens" to "截取两个屏幕",
        "Capturing both screens…" to "正在截取两个屏幕…", "Could not capture both screens" to "无法截取两个屏幕",
        "Choose a screen to save" to "选择要保存的屏幕", "Top screen" to "上屏", "Bottom screen" to "下屏",
        "Screenshot saved" to "截图已保存", "Could not save screenshot" to "无法保存截图",
        "SAVE BOTH" to "保存两张", "Both screenshots saved" to "两张截图已保存", "Could not save both screenshots" to "无法保存两张截图",
        "PERFORMANCE PROFILE" to "性能模式", "FAN" to "风扇", "DISPLAY" to "显示屏", "RAM" to "内存", "STORAGE" to "存储",
        "Tap to control" to "点击控制", "Tap to choose" to "点击选择", "Used / installed" to "已用 / 总量",
        "Main display game FPS" to "主屏游戏帧率", "Brightness" to "亮度", "Sound" to "声音", "SETTINGS" to "设置",
        "LIVE STATUS" to "实时状态", "Refresh: 2 s" to "刷新：2 秒", "Language" to "语言",
        "Choose the app language. Your choice is saved on this device." to "选择应用语言。你的选择将保存在此设备上。",
        "COLOR ACCENT" to "主题强调色", "Four palettes, independent of the layout style." to "四种配色方案，可独立于界面布局选择。",
        "INTERFACE DESIGN" to "界面设计", "These change card shape, density, spacing and visual character—not just the colors." to "这些选项会改变卡片形状、密度、间距和整体风格，而不仅是颜色。",
        "Red" to "红色", "Blue" to "蓝色", "Green" to "绿色", "Dark" to "深色", "Glass" to "玻璃", "Compact" to "紧凑",
        "Neon console" to "霓虹主机", "Arcade" to "街机风格", "Solstice" to "至日", "Terminal" to "终端风格", "DUAL-SCREEN KEYBOARD" to "双屏键盘",
        "Prototype: type on the main screen and show this keyboard below." to "原型：在主屏输入，并在下方显示键盘。",
        "Bottom screen" to "下屏", "Active screen" to "当前屏幕", "KEYBOARD LANGUAGES" to "键盘语言",
        "Choose which languages appear on the keyboard." to "选择要在键盘中使用的语言。", "KEYBOARD THEME" to "键盘主题",
        "Ocean" to "海洋", "Graphite" to "石墨", "Neon" to "霓虹", "Light" to "浅色", "KEY SIZE" to "按键大小",
        "Small" to "小", "Standard" to "标准", "Large" to "大", "TOUCH FEEDBACK" to "触觉反馈",
        "Vibration on" to "开启振动", "Vibration off" to "关闭振动", "Enable keyboard" to "启用键盘",
        "Select keyboard" to "选择键盘", "About" to "关于", "Close" to "关闭",
        "Changes apply immediately and are saved automatically." to "更改会立即生效并自动保存。",
        "PocketHub\n\nA control hub and dual-screen keyboard for AYANEO Pocket DS.\n\nGitHub link coming soon." to "PocketHub\n\nAYANEO Pocket DS 的控制中心和双屏键盘。\n\nGitHub 链接即将推出。",
        "Display brightness settings" to "屏幕亮度设置", "Sound settings" to "声音设置", "Top display" to "上屏", "Bottom display" to "下屏",
        "BATTERY" to "电池", "Charging" to "正在充电", "On battery" to "电池供电", "POWER" to "功耗", "DONE" to "完成",
        "Eco" to "节能", "Balanced" to "均衡", "Streaming" to "串流", "Gaming" to "游戏", "Max" to "最高",
        "Notifications" to "通知", "Ringtone" to "铃声", "Alarm" to "闹钟", "System sounds" to "系统声音",
        "Volume controls" to "音量控制", "Per-app volume" to "应用音量", "Android sound settings" to "Android 声音设置",
        "DISPLAY BRIGHTNESS" to "屏幕亮度", "Adjust both screens independently" to "分别调节两个屏幕",
        "TEMPERATURE" to "温度", "CPU" to "CPU", "GPU" to "GPU"
    )

    private val spanishUiLabels = mapOf(
        "8-Bit Deck" to "Consola de 8 bits", "8-BIT CONTROL DECK" to "CENTRO DE CONTROL 8-BIT",
        "POWER MODES" to "MODOS DE POTENCIA", "SYSTEM TELEMETRY" to "TELEMETRÍA DEL SISTEMA",
        "Volcanic" to "Volcánico", "Orbit" to "Órbita",
        "UTILITY BAR WHEN KEYBOARD OPENS" to "BARRA AL ABRIR EL TECLADO", "Shown" to "Mostrar", "Hidden" to "Ocultar",
        "CAPTURE" to "CAPTURA", "Capture both screens" to "Capturar ambas pantallas",
        "Capturing both screens…" to "Capturando ambas pantallas…", "Could not capture both screens" to "No se pudieron capturar ambas pantallas",
        "Choose a screen to save" to "Elige qué pantalla guardar", "Top screen" to "Pantalla superior", "Bottom screen" to "Pantalla inferior",
        "Screenshot saved" to "Captura guardada", "Could not save screenshot" to "No se pudo guardar la captura",
        "SAVE BOTH" to "GUARDAR AMBAS", "Both screenshots saved" to "Ambas capturas guardadas", "Could not save both screenshots" to "No se pudieron guardar ambas capturas",
        "PERFORMANCE PROFILE" to "PERFIL DE RENDIMIENTO", "FAN" to "VENTILADOR", "DISPLAY" to "PANTALLA",
        "RAM" to "RAM", "STORAGE" to "ALMACENAMIENTO", "Tap to control" to "Toca para controlar",
        "Tap to choose" to "Toca para elegir", "Used / installed" to "Usada / instalada",
        "Main display game FPS" to "FPS del juego en pantalla principal", "Brightness" to "Brillo",
        "Sound" to "Sonido", "SETTINGS" to "AJUSTES", "LIVE STATUS" to "ESTADO EN VIVO",
        "Refresh: 2 s" to "Actualización: 2 s", "Language" to "Idioma",
        "COLOR ACCENT" to "COLOR DE ACENTO", "INTERFACE DESIGN" to "DISEÑO DE INTERFAZ",
        "Three palettes, independent of the layout style." to "Cuatro paletas, independientes del diseño.",
        "These change card shape, density, spacing and visual character—not just the colors." to "Cambian la forma de las tarjetas, densidad, espacios y estilo visual, no solo los colores.",
        "Changes apply immediately and are saved automatically." to "Los cambios se aplican al instante y se guardan automáticamente.",
        "Media / apps" to "Multimedia / apps", "Notifications" to "Notificaciones", "Ringtone" to "Tono de llamada",
        "Alarm" to "Alarma", "System sounds" to "Sonidos del sistema", "Volume controls" to "Controles de volumen",
        "Per-app volume" to "Volumen por aplicación", "Android sound settings" to "Ajustes de sonido de Android",
        "DISPLAY BRIGHTNESS" to "BRILLO DE PANTALLA", "Adjust both screens independently" to "Ajusta ambas pantallas por separado",
        "Top display" to "Pantalla superior", "Bottom display" to "Pantalla inferior", "BATTERY" to "BATERÍA",
        "Charging" to "Cargando", "On battery" to "Con batería", "POWER" to "POTENCIA", "DONE" to "LISTO",
        "Glass" to "Cristal", "Compact" to "Compacto", "Neon console" to "Consola neón", "Arcade" to "Arcade",
        "Eco" to "Eco", "Balanced" to "Equilibrado", "Streaming" to "Streaming", "Gaming" to "Juegos", "Max" to "Máximo"
    )

    private fun localizedStreamName(stream: Int): String = when (stream) {
        AudioManager.STREAM_MUSIC -> tr("Media / apps", "Мультимедиа / приложения", "Media / apps", "Multimedia / apps")
        AudioManager.STREAM_NOTIFICATION -> tr("Notifications", "Уведомления", "Meldingen", "Notificaciones")
        AudioManager.STREAM_RING -> tr("Ringtone", "Звонок", "Beltoon", "Tono de llamada")
        AudioManager.STREAM_ALARM -> tr("Alarm", "Будильник", "Alarm", "Alarma")
        AudioManager.STREAM_SYSTEM -> tr("System sounds", "Системные звуки", "Systeemgeluiden", "Sonidos del sistema")
        else -> tr("Audio", "Звук", "Audio", "Audio")
    }

    private fun buildFocusOutline(radiusDp: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); setStroke(dp(2), cyan); cornerRadius = dp(radiusDp).toFloat()
        })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(Color.argb(24, Color.red(mint), Color.green(mint), Color.blue(mint)))
            setStroke(dp(2), mint); cornerRadius = dp(radiusDp).toFloat()
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun enableControllerFocus(view: View, radiusDp: Int = 12) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.foreground = buildFocusOutline(radiusDp)
    }

    private fun openPerAppVolumeMixer() {
        perAppOpenRequested = true
        rootAudioBridge?.let { existing ->
            if (existing.isAlive) {
                showPerAppVolumeMixerDialog(existing)
                return
            }
            try { existing.close() } catch (_: Exception) { }
            rootAudioBridge = null
        }
        if (rootAudioBridgeStarting) return
        rootAudioBridgeStarting = true
        Thread {
            var bridge: RootAudioBridgeClient? = null
            var failure: String? = null
            try {
                bridge = RootAudioBridgeClient(applicationContext)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                try { bridge?.close() } catch (_: Exception) { }
            }
            val connected = bridge
            val error = failure
            uiHandler.post {
                rootAudioBridgeStarting = false
                if (isFinishing || isDestroyed) {
                    try { connected?.close() } catch (_: Exception) { }
                    return@post
                }
                if (connected != null) {
                    rootAudioBridge = connected
                    if (perAppOpenRequested) showPerAppVolumeMixerDialog(connected)
                } else {
                    perAppOpenRequested = false
                    showPerAppVolumeInfoDialog(tr(
                        "Root audio helper could not start: ${error ?: "unknown error"}. Approve the su request for PocketHub. No Shizuku or separate mixer app is needed.",
                        "Не удалось запустить root-аудиосервис: ${error ?: "неизвестная ошибка"}. Разреши root-доступ PocketHub в запросе su. Shizuku и отдельный микшер не нужны.",
                        "De root-audioservice kon niet starten: ${error ?: "onbekende fout"}. Sta het su-verzoek voor PocketHub toe. Shizuku of een aparte mixer is niet nodig.",
                        "No se pudo iniciar el servicio de audio root: ${error ?: "error desconocido"}. Autoriza la solicitud su de PocketHub. No hace falta Shizuku ni otra app mezcladora."
                    ))
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun resolveAppForUid(uid: Int): Pair<String, String> {
        val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (pkg in packages) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                val label = packageManager.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                return label to pkg
            } catch (_: Exception) { }
        }
        return tr("App UID $uid", "Приложение UID $uid", "App UID $uid", "App UID $uid") to "UID $uid"
    }

    private fun displayRoleLabel(displayId: Int): String {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays.toList()
        val largest = displays.maxByOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight.toLong() }
        val smallest = displays.minByOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight.toLong() }
        return when {
            largest?.displayId == displayId -> tr("Upper / main screen", "Верхний / основной экран", "Bovenste / hoofdscherm", "Pantalla superior / principal")
            smallest != null && smallest.displayId == displayId && smallest.displayId != largest?.displayId -> tr("Lower / secondary screen", "Нижний / дополнительный экран", "Onderste / tweede scherm", "Pantalla inferior / secundaria")
            displays.any { it.displayId == displayId } -> "Display $displayId"
            else -> tr("Display $displayId (not connected)", "Дисплей $displayId (не подключён)", "Scherm $displayId (niet verbonden)", "Pantalla $displayId (no conectada)")
        }
    }

    private fun displayAssociationForUid(uid: Int, snapshot: RootAudioSnapshot): String {
        val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        val ids = packages.flatMap { snapshot.packageDisplayIds[it].orEmpty() }.distinct()
        if (ids.isEmpty()) {
            return tr("Background / display not detected", "Фоновый звук / экран не определён", "Achtergrondgeluid / scherm onbekend", "Audio en segundo plano / pantalla desconocida")
        }
        return ids.joinToString(" + ") { displayRoleLabel(it) }
    }

    private fun showPerAppVolumeMixerDialog(audioBridge: RootAudioBridgeClient) {
        perAppOpenRequested = false
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(12))
            background = roundedBackground(cardBg, border, 20)
        }
        panel.addView(label(tr("PER-APP VOLUME", "ГРОМКОСТЬ ПРИЛОЖЕНИЙ", "VOLUME PER APP", "VOLUMEN POR APP"), 11f, mint, true), margin(bottom = 4))
        panel.addView(label(tr(
            "Individual app volume is applied to active players. Display labels are inferred from the app's current task on the main/secondary screens; background audio or multi-window apps may be unassigned. This does not route audio by screen automatically.",
            "Индивидуальная громкость применяется к активным аудиоплеерам. Экран определяется по текущей задаче приложения; фоновый звук и multi-window могут остаться без метки. Это не маршрутизация звука по экранам.",
            "App-volume wordt toegepast op actieve spelers. Het scherm wordt afgeleid van de huidige taak; achtergrondgeluid en multi-window kunnen onbekend blijven. Dit routeert audio niet automatisch per scherm.",
            "El volumen se aplica a reproductores activos. La pantalla se estima por la tarea actual; el audio en segundo plano o multi-ventana puede quedar sin etiqueta. Esto no enruta audio automáticamente por pantalla."
        ), 10f, muted).apply { setPadding(0, 0, 0, dp(8)) })
        val state = label(tr("Loading active audio players…", "Загрузка активных аудиоплееров…", "Actieve audiospelers laden…", "Cargando reproductores activos…"), 10f, cyan).apply { setPadding(0, 0, 0, dp(6)) }
        panel.addView(state)
        val players = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(players)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refreshButton = pill(tr("REFRESH APPS", "ОБНОВИТЬ", "APPS VERVERSEN", "ACTUALIZAR APPS"), cardBg2, white) { }.apply { minHeight = dp(42); textSize = 10f }
        val doneButton = pill(tr("DONE", "ГОТОВО", "KLAAR", "LISTO"), mint, bg) { dialog.dismiss() }.apply { minHeight = dp(42); textSize = 10f }
        controls.addView(refreshButton, weight(1f, end = 4))
        controls.addView(doneButton, weight(1f, start = 4))
        panel.addView(controls, margin(top = 7))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel); setBackgroundColor(Color.TRANSPARENT) }
        dialog.setContentView(scroll)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(640)), (resources.displayMetrics.heightPixels * 0.86f).toInt())
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        fun refreshPlayers() {
            state.text = tr("Scanning active players…", "Поиск активных аудиоплееров…", "Actieve spelers scannen…", "Buscando reproductores activos…")
            Thread {
                var snapshot: RootAudioSnapshot? = null
                var error: String? = null
                try {
                    snapshot = audioBridge.listActive()
                    snapshot.uids.forEach { uid ->
                        val key = "per_app_volume_$uid"
                        if (getPreferences(Context.MODE_PRIVATE).contains(key)) {
                            try { audioBridge.setVolume(uid, getPreferences(Context.MODE_PRIVATE).getInt(key, 100).coerceIn(0, 100) / 100f) } catch (_: Exception) { }
                        }
                    }
                } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                val result = snapshot
                val failure = error
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    players.removeAllViews()
                    when {
                        failure != null -> state.text = tr("Root audio service error: $failure", "Ошибка root-аудиосервиса: $failure", "Fout in root-audioservice: $failure", "Error del servicio de audio root: $failure")
                        result == null -> state.text = tr("No response from root audio service", "Нет ответа от root-аудиосервиса", "Geen antwoord van root-audioservice", "El servicio de audio root no respondió")
                        result.uids.isEmpty() -> state.text = tr("No active audio players. Start playback in a game/app, then tap Refresh Apps.", "Активных аудиоплееров нет. Запусти звук в игре/приложении и нажми «Обновить».", "Geen actieve audiospelers. Start audio in een game/app en tik op vernieuwen.", "No hay reproductores activos. Inicia audio en un juego/app y pulsa actualizar.")
                        else -> {
                            state.text = tr("${result.uids.size} active app(s) · sliders apply immediately", "Активных приложений: ${result.uids.size} · ползунки применяются сразу", "${result.uids.size} actieve apps · schuifregelaars werken direct", "${result.uids.size} apps activas · los controles se aplican al instante")
                            result.uids.forEach { uid ->
                                val (appName, packageName) = resolveAppForUid(uid)
                                val saved = getPreferences(Context.MODE_PRIVATE).getInt("per_app_volume_$uid", 100).coerceIn(0, 100)
                                val screenLabel = displayAssociationForUid(uid, result)
                                val count = result.playerCounts[uid] ?: 1
                                players.addView(perAppVolumeRow(audioBridge, uid, appName, packageName, screenLabel, count, saved, state), margin(bottom = 5))
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        refreshButton.setOnClickListener { refreshPlayers() }
        refreshPlayers()
    }

    private fun perAppVolumeRow(
        audioBridge: RootAudioBridgeClient,
        uid: Int,
        appName: String,
        packageName: String,
        displayLabel: String,
        activePlayerCount: Int,
        initialVolume: Int,
        statusView: TextView
    ): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(5))
            background = roundedBackground(cardBg2, border, 12)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(appName, 12f, white, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        names.addView(label(packageName, 8f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        names.addView(label("${displayLabel} · ${tr("$activePlayerCount player(s)", "$activePlayerCount плеер(а/ов)", "$activePlayerCount speler(s)", "$activePlayerCount reproductor(es)")}", 9f, cyan).apply { maxLines = 2 })
        header.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val percent = label("$initialVolume%", 12f, mint, true)
        header.addView(percent)
        item.addView(header)
        var pending: Runnable? = null
        item.addView(SeekBar(this).apply {
            max = 100
            progress = initialVolume
            minHeight = dp(68)
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            styleTouchSlider(this, mint, thumbDp = 30, minHeightDp = 68)
            enableControllerFocus(this, 8)
            fun submitVolume(value: Int) {
                getPreferences(Context.MODE_PRIVATE).edit().putInt("per_app_volume_$uid", value).apply()
                Thread {
                    var error: String? = null
                    val success = try { audioBridge.setVolume(uid, value / 100f) } catch (e: Exception) { error = e.message; false }
                    if (!success) uiHandler.post {
                        if (!isFinishing && !isDestroyed) statusView.text = error ?: tr(
                            "No active player for this app; refresh the list.",
                            "У этого приложения уже нет активного плеера; обнови список.",
                            "Geen actieve speler voor deze app; vernieuw de lijst.",
                            "No hay un reproductor activo para esta app; actualiza la lista."
                        )
                    }
                }.apply { isDaemon = true; start() }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    percent.text = "$progress%"
                    if (fromUser) {
                        pending?.let { uiHandler.removeCallbacks(it) }
                        val task = Runnable { submitVolume(progress) }
                        pending = task
                        uiHandler.postDelayed(task, 100L)
                    }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    pending?.let { uiHandler.removeCallbacks(it) }
                    submitVolume(seekBar.progress)
                }
            })
        })
        return item
    }

    private fun showPerAppVolumeInfoDialog(message: String? = null) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = roundedBackground(cardBg, border, 18)
        }
        panel.addView(label(tr("PER-APP VOLUME", "ГРОМКОСТЬ ОТДЕЛЬНЫХ ПРИЛОЖЕНИЙ", "APP-VOLUME", "VOLUMEN POR APLICACIÓN"), 11f, mint, true), margin(bottom = 5))
        panel.addView(label(message ?: tr(
            "A root helper launched through su reads active Android audio players. Approve root access for PocketHub. No Shizuku or separate mixer app is required. Display labels are best-effort and do not automatically route sound by screen.",
            "Root-сервис, запущенный через su, читает активные аудиоплееры Android. Разреши root-доступ PocketHub. Shizuku и отдельный микшер не нужны. Метки экрана определяются приблизительно и не маршрутизируют звук автоматически.",
            "Een root-helper via su leest actieve Android-audiospelers. Sta root-toegang voor PocketHub toe. Shizuku of een aparte mixer is niet nodig. Schermlabels zijn indicatief en routeren audio niet automatisch.",
            "Un asistente root iniciado mediante su lee los reproductores activos de Android. Autoriza el acceso root a PocketHub. No hace falta Shizuku ni otra app mezcladora. Las etiquetas de pantalla son aproximadas y no enrutan el audio automáticamente."
        ), 12f, white).apply { setPadding(0, 0, 0, dp(14)) })
        panel.addView(pill(tr("CLOSE", "ЗАКРЫТЬ", "SLUITEN", "CERRAR"), mint, bg) { dialog.dismiss() })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(620)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /** Localizes exact labels used by older dialogs so the language choice applies throughout the UI. */
    private fun localize(value: String): String {
        val entries = listOf(
            Triple("УПРАВЛЕНИЕ ВЕНТИЛЯТОРОМ", "FAN CONTROL", "VENTILATORBEDIENING"),
            Triple("Управление вентилятором", "Fan control", "Ventilatorbediening"),
            Triple("Режимы передаются штатному AYANEO-сервису. AUTO запускает OEM-скрипт; прямые значения PWM приложением не записываются.", "Modes are sent to the stock AYANEO service. AUTO runs the OEM script; the app does not write PWM values directly.", "Modi worden naar de AYANEO-service gestuurd. AUTO start het OEM-script; de app schrijft geen PWM-waarden rechtstreeks."),
            Triple("Подключить OEM AIDL", "Connect OEM AIDL", "OEM AIDL verbinden"),
            Triple("Тихий", "Quiet", "Stil"), Triple("Баланс", "Balanced", "Gebalanceerd"), Triple("Турбо", "Turbo", "Turbo"),
            Triple("Пользовательский", "Custom", "Aangepast"), Triple("Выключить вентилятор…", "Turn fan off…", "Ventilator uitschakelen…"),
            Triple("⌁  НАСТРОИТЬ ГРАФИК ВЕНТИЛЯТОРА", "⌁  CONFIGURE FAN CURVE", "⌁  VENTILATORCURVE INSTELLEN"),
            Triple("Кривая настраивается здесь. Сохранение обновляет конфигурацию OEM и делает резервную копию перед первым изменением.", "Configure the curve here. Saving updates the OEM configuration and creates a backup before the first change.", "Stel de curve hier in. Opslaan werkt de OEM-configuratie bij en maakt vóór de eerste wijziging een back-up."),
            Triple("ЗВУК", "SOUND", "GELUID"), Triple("Громкость мультимедиа", "Media volume", "Mediavolume"),
            Triple("Меняется общая громкость мультимедиа. Отдельная громкость верхнего и нижнего экрана пока не предоставляется стандартным Android AudioManager.", "Changes overall media volume. Standard Android AudioManager does not currently expose independent volume for the top and bottom displays.", "Past het algemene mediavolume aan. Android AudioManager biedt geen afzonderlijk volume voor het bovenste en onderste scherm."),
            Triple("ГОТОВО", "DONE", "KLAAR"), Triple("НАСТРОЙКА КРИВОЙ ВЕНТИЛЯТОРА", "FAN CURVE EDITOR", "VENTILATORCURVE-EDITOR"),
            Triple("Температура CPU → скорость вентилятора", "CPU temperature → fan speed", "CPU-temperatuur → ventilatorsnelheid"),
            Triple("Читаю конфигурацию OEM…", "Reading OEM configuration…", "OEM-configuratie lezen…"),
            Triple("Температура", "Temperature", "Temperatuur"), Triple("Скорость вентилятора", "Fan speed", "Ventilatorsnelheid"),
            Triple("СОХРАНИТЬ", "SAVE", "OPSLAAN"), Triple("СОХРАНИТЬ + CUSTOM", "SAVE + CUSTOM", "OPSLAAN + CUSTOM"),
            Triple("СБРОСИТЬ КРИВУЮ", "RESET CURVE", "CURVE HERSTELLEN"), Triple("ВОССТАНОВИТЬ КОПИЮ", "RESTORE BACKUP", "BACK-UP HERSTELLEN"),
            Triple("ЯРКОСТЬ ЭКРАНОВ", "DISPLAY BRIGHTNESS", "SCHERMHELDERHEID"), Triple("ВЕРХНИЙ / НИЖНИЙ ЭКРАН", "TOP / BOTTOM DISPLAY", "BOVEN / ONDERSTE SCHERM"),
            Triple("Частота основного экрана", "Main display refresh rate", "Vernieuwingsfrequentie hoofdscherm"),
            Triple("БАТАРЕЯ", "BATTERY", "ACCU"), Triple("МОЩНОСТЬ", "POWER", "VERMOGEN"),
            Triple("Заряжается", "Charging", "Opladen"), Triple("От батареи", "On battery", "Op accu"),
            Triple("НАСТРОЙКИ", "SETTINGS", "INSTELLINGEN"), Triple("ТЕКУЩАЯ ЧАСТОТА", "LIVE CLOCK", "LIVE KLOK"),
            Triple("НАСТРОЙКИ И ВНЕШНИЙ ВИД", "SETTINGS & PERSONALIZATION", "INSTELLINGEN EN WEERGAVE"),
            Triple("Напряжение —", "Voltage —", "Spanning —"), Triple("Температура —", "Temperature —", "Temperatuur —"),
            Triple("Накопитель", "Storage", "Opslag"), Triple("Профили переключаются через штатный сервис AYANEO", "Profiles are controlled by the AYANEO service", "Profielen worden beheerd door de AYANEO-service"),
            Triple("Профиль ещё не применялся в этом запуске.", "No performance profile has been applied in this session yet.", "Er is deze sessie nog geen prestatieprofiel toegepast."),
            Triple("Нажми P1–P6, чтобы выбрать точку. Температуры и скорости сохраняются по возрастанию; от 90°C целевая скорость не ниже 80%, конечная точка — 100°C / 100%.", "Tap P1–P6 to select a point. Temperature and speed values must increase; from 90°C the target fan speed is at least 80%, and the final point is 100°C / 100%.", "Tik op P1–P6 om een punt te kiezen. Temperatuur en snelheid moeten oplopen; vanaf 90°C is het ventilatiedoel minimaal 80%, het laatste punt is 100°C / 100%."),
            Triple("ОСТОРОЖНО", "CAUTION", "LET OP"), Triple("Выключить вентилятор?", "Turn fan off?", "Ventilator uitschakelen?"),
            Triple("ВЫКЛЮЧИТЬ", "TURN OFF", "UITSCHAKELEN"), Triple("ОТМЕНА", "CANCEL", "ANNULEREN"),
            Triple("Восстанавливаю резервную копию…", "Restoring backup…", "Back-up herstellen…"),
            Triple("OEM-файл не загружен; восстановление недоступно.", "OEM file is not loaded; restore is unavailable.", "OEM-bestand is niet geladen; herstellen is niet beschikbaar."),
            Triple("Вентилятор будет переведён в OEM-режим OFF. Защита ОС не отключается, но охлаждение может резко снизиться. Используй этот режим осознанно.", "The fan will be switched to OEM OFF mode. OS protections remain enabled, but cooling may drop sharply. Use this mode with care.", "De ventilator wordt in OEM-OFF gezet. Beveiligingen van het besturingssysteem blijven actief, maar de koeling kan sterk afnemen. Gebruik deze modus voorzichtig."),
            Triple("Режим вентилятора передаётся штатной службе AYANEO. Приложение не записывает PWM напрямую.", "Fan mode is sent to the stock AYANEO service. The app does not write PWM directly.", "De ventilatormodus wordt naar de AYANEO-service gestuurd. De app schrijft PWM niet rechtstreeks."),
            Triple("Отправка OEM-команды:", "Sending OEM command:", "OEM-opdracht verzenden:"),
            Triple("Сначала подключи OEM AIDL и дождись регистрации client ID", "Connect OEM AIDL and wait for the client ID registration first", "Verbind eerst OEM AIDL en wacht op de registratie van de client-ID"),
            Triple("Команда не отправлена: OEM Binder или client ID ещё не готовы. Нажми «Подключить OEM AIDL».", "Command not sent: OEM Binder or client ID is not ready. Tap ‘Connect OEM AIDL’.", "Opdracht niet verzonden: OEM Binder of client-ID is nog niet gereed. Tik op ‘OEM AIDL verbinden’."),
            Triple("OEM Binder принял транзакцию для", "OEM Binder accepted the transaction for", "OEM Binder heeft de transactie geaccepteerd voor"),
            Triple("Эта прошивка не разрешает автоматический запуск на другом дисплее", "This firmware does not allow automatic launch on another display", "Deze firmware staat automatisch starten op een ander scherm niet toe"),
            Triple("Не удалось перенести приложение на маленький экран:", "Could not move the app to the small display:", "Kan de app niet naar het kleine scherm verplaatsen:"),
            Triple("Подключение получено, но это не ожидаемый AIDL-интерфейс.", "Connection received, but this is not the expected AIDL interface.", "Verbinding ontvangen, maar dit is niet de verwachte AIDL-interface."),
            Triple("Получен неподходящий Binder descriptor", "Unexpected Binder descriptor received", "Onverwachte Binder-descriptor ontvangen"),
            Triple("AIDL подключён; callback-вызовы:", "AIDL connected; callback calls:", "AIDL verbonden; callback-aanroepen:"),
            Triple("OEM AIDL готов: callback зарегистрирован", "OEM AIDL ready: callback registered", "OEM AIDL gereed: callback geregistreerd"),
            Triple("OEM AIDL отключён:", "OEM AIDL disconnected:", "OEM AIDL verbroken:"),
            Triple("OEM AIDL вернул пустой Binder:", "OEM AIDL returned an empty Binder:", "OEM AIDL gaf een lege Binder terug:"),
            Triple("Подключение к штатному AyaAidlInterface без action…", "Connecting to the stock AyaAidlInterface without an action…", "Verbinden met de standaard AyaAidlInterface zonder action…"),
            Triple("OEM AIDL: bindService вернул false. Команды не отправлялись.", "OEM AIDL: bindService returned false. No commands were sent.", "OEM AIDL: bindService gaf false terug. Er zijn geen opdrachten verzonden."),
            Triple("AYANEO AIDL не подключился (bindService=false)", "AYANEO AIDL did not connect (bindService=false)", "AYANEO AIDL is niet verbonden (bindService=false)"),
            Triple("Ошибка подключения OEM AIDL:", "OEM AIDL connection error:", "Verbindingsfout OEM AIDL:"),
            Triple("Профиль не применён: нет доступных для записи узлов", "Profile not applied: no writable nodes available", "Profiel niet toegepast: geen beschrijfbare nodes beschikbaar"),
            Triple("Профиль обработан. Проверь статус на экране.", "Profile command processed. Check the status on screen.", "Profielopdracht verwerkt. Controleer de status op het scherm."),
            Triple("Команда отправлена через OEM AIDL", "Command sent through OEM AIDL", "Opdracht verzonden via OEM AIDL"),
            Triple("Команда вентилятора не отправлена", "Fan command was not sent", "Ventilatoropdracht niet verzonden"),
            Triple("Не удалось изменить яркость:", "Could not change brightness:", "Kan de helderheid niet wijzigen:"),
            Triple("Кривая сохранена; отправляю FAN_MODE_CUSTOM.", "Curve saved; sending FAN_MODE_CUSTOM.", "Curve opgeslagen; FAN_MODE_CUSTOM wordt verzonden."),
            Triple("Кривая записана в конфигурацию OEM.", "Curve written to the OEM configuration.", "Curve geschreven naar de OEM-configuratie."),
            Triple("Кривая сохранена. Подключи OEM AIDL, затем нажми «Пользовательский» для активации.", "Curve saved. Connect OEM AIDL, then tap ‘Custom’ to activate it.", "Curve opgeslagen. Verbind OEM AIDL en tik daarna op ‘Aangepast’ om te activeren."),
            Triple("Запись OEM-кривой не подтверждена. Существующий файл не удаляй; пришли диагностику.", "OEM curve write could not be verified. Do not delete the existing file; send the diagnostics.", "De OEM-curve kon niet worden geverifieerd. Verwijder het bestaande bestand niet; stuur de diagnostiek."),
            Triple("Подожди: конфигурация OEM ещё читается.", "Please wait: OEM configuration is still being read.", "Wacht even: de OEM-configuratie wordt nog gelezen."),
            Triple("Сохранено в Overlay, но файл кривой OEM не доступен или имеет неизвестный формат. На вентилятор это не повлияло.", "Saved in Overlay, but the OEM curve file is unavailable or has an unknown format. The fan was not changed.", "Opgeslagen in Overlay, maar het OEM-curvebestand is niet beschikbaar of heeft een onbekend formaat. De ventilator is niet gewijzigd."),
            Triple("Кривая не прошла проверку безопасности. Температуры должны возрастать, скорость не должна снижаться, последняя точка — 100°C / 100%.", "The curve failed safety validation. Temperatures must rise, fan speed cannot decrease, and the final point must be 100°C / 100%.", "De curve is niet veilig gevalideerd. Temperaturen moeten oplopen, de ventilatorsnelheid mag niet dalen en het laatste punt moet 100°C / 100% zijn."),
            Triple("Сохраняю OEM-кривую и проверяю запись…", "Saving OEM curve and verifying the write…", "OEM-curve opslaan en schrijven controleren…"),
            Triple("OEM-кривая записана. Резервная копия:", "OEM curve written. Backup:", "OEM-curve opgeslagen. Back-up:"),
            Triple("Не удалось подтвердить запись OEM-файла. Локальная копия сохранена; режим вентилятора не менялся.", "Could not verify the OEM file write. A local copy was saved; fan mode was not changed.", "De schrijfactie van het OEM-bestand kon niet worden bevestigd. Een lokale kopie is opgeslagen; de ventilatormodus is niet gewijzigd."),
            Triple("Скорость 90°C+ минимум 80%", "90°C+ fan target minimum 80%", "Ventilatordoel bij 90°C+ minimaal 80%")
        )
        val matched = entries.firstOrNull { value == it.first || value == it.second || value == it.third }
        if (matched != null) return when (uiLanguage) {
            "ru" -> matched.first
            "nl" -> matched.third
            "es" -> spanishUiLabels[matched.second] ?: matched.second
            "de" -> germanUiLabels[matched.second] ?: matched.second
            "fr" -> frenchUiLabels[matched.second] ?: matched.second
            "it" -> italianUiLabels[matched.second] ?: matched.second
            "zh" -> chineseUiLabels[matched.second] ?: matched.second
            else -> matched.second
        }
        if (uiLanguage == "ru") return value
        var result = value
        val replacements = if (uiLanguage == "nl") listOf(
            "Профили переключаются через штатный сервис AYANEO" to "Profielen worden beheerd door de AYANEO-service",
            " · применён, узлов CPU/GPU изменено: " to " · toegepast; CPU/GPU-nodes gewijzigd: ",
            " · ошибка применения" to " · toepassen mislukt",
            " · не найдено доступных узлов CPU/GPU для записи" to " · geen schrijfbare CPU/GPU-nodes gevonden",
            "Скорость: " to "Snelheid: ", " · питание включено" to " · voeding aan",
            " · питание выключено" to " · voeding uit", " · управление через OEM" to " · bediening via OEM",
            "Команда не отправлена" to "Opdracht niet verzonden", "Ошибка OEM-команды:" to "OEM-opdrachtfout:"
        ) else listOf(
            "Профили переключаются через штатный сервис AYANEO" to "Profiles are controlled by the AYANEO service",
            " · применён, узлов CPU/GPU изменено: " to " · applied; CPU/GPU nodes changed: ",
            " · ошибка применения" to " · apply failed",
            " · не найдено доступных узлов CPU/GPU для записи" to " · no writable CPU/GPU nodes found",
            "Скорость: " to "Speed: ", " · питание включено" to " · power on",
            " · питание выключено" to " · power off", " · управление через OEM" to " · controlled by OEM",
            "Команда не отправлена" to "Command not sent", "Ошибка OEM-команды:" to "OEM command error:"
        )
        replacements.forEach { (from, to) -> result = result.replace(from, to) }
        return result
    }

    /** Applies the requested CPU/GPU caps using frequency nodes verified on AYANEO Pocket DS. */
    /**
     * @param fanModeOverride when non-null, send this fan mode instead of the profile's
     * default. Used on startup so a manual fan choice survives a reboot: the profile
     * sets the default fan mode only when the user explicitly picks a profile.
     */
    private fun applyPerformanceProfile(profile: String, fanModeOverride: String? = null) {
        if (popupMode) {
            // The popup window must not own the limit keepers; hand the change to the running PocketHub.
            val host = liveInstance?.get()?.takeIf { it !== this && !it.isFinishing && !it.isDestroyed }
            if (host != null) { host.runOnUiThread { host.applyPerformanceProfile(profile, fanModeOverride) }; selectedProfile = profile; return }
        }
        AdaptiveGovernor.stop()
        val tunedProfile = TunedProfiles.byKey(this, profile)
        val adaptiveProfile = tunedProfile?.takeIf { it.mode == TunedProfile.MODE_ADAPTIVE }
        // Temperature limit: adaptive profiles tuned for a noise level, and every profile in Quiet mode.
        val quietMode = quietModeOn()
        val noiseLevel = effectiveNoiseLevel(profile)
        // Which temperature to keep, and on which sensor:
        //  - temperature goal: its own target (in Quiet mode also the quieter level's limit, whichever is lower);
        //  - noise goal or Quiet mode: the noise level's safe temperature on the hottest zone;
        //  - otherwise none.
        val goal = tunedProfile?.goal()
        var tempSensor = Thermal.SENSOR_MAX
        val tempLimit = when {
            goal != null && goal.kind == TuneGoal.KIND_TEMP && quietMode && goal.sensor == Thermal.SENSOR_MAX ->
                minOf(goal.tempTarget, NoiseLevel.tempLimit(noiseLevel).takeIf { it > 0 } ?: goal.tempTarget)
            goal != null && goal.kind == TuneGoal.KIND_TEMP && !quietMode -> { tempSensor = goal.sensor; goal.tempTarget }
            quietMode || (goal != null && goal.kind == TuneGoal.KIND_NOISE) -> NoiseLevel.tempLimit(noiseLevel)
            else -> 0.0
        }
        // Fixed limits with a temperature to keep: guard it (lower when hot, back up when cool).
        val heatGuard = adaptiveProfile == null && tempLimit > 0
        val governed = adaptiveProfile != null || heatGuard
        stopGpuLimitEnforcer()
        if (!isGameProfile(profile)) stopGameWatch()
        selectedProfile = profile
        val prefs = getPreferences(Context.MODE_PRIVATE)
        prefs.edit().putString("profile", profile).apply()
        if (fanModeOverride != null) queueFanMode(fanModeOverride) else queueProfileFanMode(profile)
        profileButtons.forEach { (name, _) -> refreshProfileButton(name, name == profile) }
        // Turbo follows the selected profile: on if this profile has it, otherwise off.
        if (prefs.getBoolean(TurboBoost.prefKey(profile), false)) TurboBoost.startAsync(this) else TurboBoost.stop(this)
        val profileKey = profile.lowercase(Locale.ROOT)
        val gpuKey = "tune_gpu_${profile.lowercase(Locale.ROOT)}"
        val lockKey = "tune_gpu_lock_${profile.lowercase(Locale.ROOT)}"
        val legacyCpu = prefs.getLong("tune_cpu_$profileKey", defaultCpuTarget(profile))
        val cpuTargets = cpuPolicySteps.mapValues { (policy, steps) ->
            val key = "tune_cpu_${policy}_${profileKey}"
            val oldKey = when (policy) {
                "policy0" -> "tune_cpu_little_${profileKey}"
                "policy3" -> "tune_cpu_big_${profileKey}"
                else -> "tune_cpu_${policy}_${profileKey}"
            }
            val target = when {
                prefs.contains(key) -> prefs.getLong(key, 0L)
                oldKey != key && prefs.contains(oldKey) -> prefs.getLong(oldKey, 0L)
                else -> legacyCpu
            }
            resolveCpuTarget(target, steps)
        }
        val gpuTarget = prefs.getLong(gpuKey, defaultGpuTarget(profile))
        val gpuLocked = prefs.getBoolean(lockKey, profile == "Max")
        // Preserve the stock Max path until this profile has explicit PocketHub tuning.
        val cpuScript = buildString {
            appendLine("apply_cpu_limits() {")
            appendLine("failed=0")
            appendLine("report=''")
            cpuTargets.forEach { (policy, target) ->
                appendLine("d=/sys/devices/system/cpu/cpufreq/$policy")
                appendLine("min=\$d/scaling_min_freq; max=\$d/scaling_max_freq; hwmin=\$d/cpuinfo_min_freq")
                appendLine("[ -d \"\$d\" ] && [ -w \"\$max\" ] || { echo '$policy: node unavailable'; failed=1; }")
                appendLine("if [ -w \"\$max\" ]; then")
                appendLine("  curmin=\$(cat \"\$min\" 2>/dev/null); floor=\$(cat \"\$hwmin\" 2>/dev/null)")
                appendLine("  if [ -n \"\$curmin\" ] && [ -n \"\$floor\" ] && [ \"\$curmin\" -gt $target ]; then echo \"\$floor\" > \"\$min\" 2>/dev/null || true; fi")
                appendLine("  if echo '$target' > \"\$max\" 2>/dev/null; then actual=\$(cat \"\$max\" 2>/dev/null); report=\"\$report $policy:$target;\"; [ \"\$actual\" = '$target' ] || failed=1; else echo '$policy: write failed'; failed=1; fi")
                appendLine("fi")
            }
            appendLine("return \$failed")
            appendLine("}")
            appendLine("apply_cpu_limits")
            appendLine("initial_failed=\$?")
            appendLine("echo CPU_APPLIED=${cpuTargets.size} FAILED=\$initial_failed \$report")
            // Keep the limits: check every 1.5 s using only shell built-ins (no extra processes)
            // and rewrite only when the system has changed a value. The old loop rewrote all
            // clusters 8 times a second and spawned dozens of processes per second.
            appendLine("limits_ok() {")
            cpuTargets.forEach { (policy, target) ->
                appendLine("  read cur < /sys/devices/system/cpu/cpufreq/$policy/scaling_max_freq 2>/dev/null || return 1")
                appendLine("  [ \"\$cur\" = '$target' ] || return 1")
                appendLine("  read cur < /sys/devices/system/cpu/cpufreq/$policy/scaling_min_freq 2>/dev/null && [ \"\$cur\" -gt $target ] 2>/dev/null && return 1")
            }
            appendLine("  return 0")
            appendLine("}")
            appendLine("iteration=0")
            appendLine("while [ -f '__POCKETHUB_CPU_MARKER__' ] && [ -d /proc/${android.os.Process.myPid()} ]; do")
            appendLine("  limits_ok || { apply_cpu_limits >/dev/null 2>&1; echo CPU_REAPPLIED; }")
            appendLine("  iteration=\$((iteration+1))")
            appendLine("  if [ \"\$iteration\" -eq 2 ]; then limits_ok && echo CPU_STABLE || echo CPU_DRIFTING; fi")
            appendLine("  sleep 1.5")
            appendLine("done")
        }

        val gpuMarker = if (profile == "Max") File(cacheDir, "gpu-max-${System.currentTimeMillis()}.active").also {
            it.createNewFile()
            gpuLimitEnforcerMarker = it
        } else null
        val script = """
            gpu_target=${gpuTarget}
            gpu_locked=${if (gpuLocked) 1 else 0}
            is_max=${if (profile == "Max") 1 else 0}
            changed=0
            report=""

            kgsl=/sys/class/kgsl/kgsl-3d0
            if [ "${'$'}is_max" = "1" ]; then
              # Max uses the device-advertised 1 GHz peak via both Qualcomm nodes.
              for d in /sys/class/devfreq/*; do
                case "${'$'}d" in *kgsl*|*3d00000*) ;; *) continue ;; esac
                max_node="${'$'}d/max_freq"
                min_node="${'$'}d/min_freq"
                if echo 1000000000 > "${'$'}max_node" 2>/dev/null; then max_write=ok; else max_write=denied; fi
                if echo 1000000000 > "${'$'}min_node" 2>/dev/null; then min_write=ok; else min_write=denied; fi
                report="${'$'}report GPU-Max-devfreq: min_write=${'$'}min_write min=${'$'}(cat "${'$'}min_node" 2>/dev/null) max_write=${'$'}max_write max=${'$'}(cat "${'$'}max_node" 2>/dev/null);"
                changed=${'$'}((changed+1))
              done
              if echo 1000000000 > "${'$'}kgsl/max_gpuclk" 2>/dev/null; then
                sleep 1
                report="${'$'}report GPU-Max-KGSL: max_gpuclk=${'$'}(cat "${'$'}kgsl/max_gpuclk" 2>/dev/null) gpuclk=${'$'}(cat "${'$'}kgsl/gpuclk" 2>/dev/null);"
              else
                report="${'$'}report GPU-Max-KGSL: write denied;"
              fi
              # AYANEO's performance service restores its own GPU cap after profile
              # changes. Reassert Max's advertised peak while this Activity lives.
              # Checked once a second with shell built-ins; values are rewritten only when
              # something changed them (was: rewritten 5 times a second).
              (while [ -f '__GPU_MAX_MARKER__' ] && [ -d /proc/__POCKETHUB_PID__ ]; do
                for md in /sys/class/devfreq/*; do
                  case "${'$'}md" in *kgsl*|*3d00000*)
                    read v < "${'$'}md/max_freq" 2>/dev/null; [ "${'$'}v" = 1000000000 ] || echo 1000000000 > "${'$'}md/max_freq" 2>/dev/null || true
                    read v < "${'$'}md/min_freq" 2>/dev/null; [ "${'$'}v" = 1000000000 ] || echo 1000000000 > "${'$'}md/min_freq" 2>/dev/null || true
                    ;;
                  esac
                done
                read v < "${'$'}kgsl/max_gpuclk" 2>/dev/null; [ "${'$'}v" = 1000000000 ] || echo 1000000000 > "${'$'}kgsl/max_gpuclk" 2>/dev/null || true
                sleep 1
              done) >/dev/null 2>&1 </dev/null &
            else
            for d in /sys/class/devfreq/*; do
                case "${'$'}d" in *kgsl*|*3d00000*) ;; *) continue ;; esac
                min_node="${'$'}d/min_freq"
                max_node="${'$'}d/max_freq"
                target_node="${'$'}d/target_freq"
                avail="${'$'}d/available_frequencies"
                [ -r "${'$'}avail" ] && [ -w "${'$'}max_node" ] || continue
                lowest=0; highest=0; chosen=0
                for f in ${'$'}(cat "${'$'}avail" | tr ' ' '\n'); do
                  case "${'$'}f" in ''|*[!0-9]*) continue ;; esac
                  [ "${'$'}lowest" -eq 0 ] || [ "${'$'}f" -lt "${'$'}lowest" ] && lowest="${'$'}f"
                  [ "${'$'}f" -gt "${'$'}highest" ] && highest="${'$'}f"
                  [ "${'$'}f" -le "${'$'}gpu_target" ] && [ "${'$'}f" -gt "${'$'}chosen" ] && chosen="${'$'}f"
                done
                [ "${'$'}chosen" -gt 0 ] || chosen="${'$'}lowest"
                if [ "${'$'}gpu_locked" = "1" ]; then
                  [ -w "${'$'}max_node" ] && echo "${'$'}highest" > "${'$'}max_node" 2>/dev/null || true
                  [ -w "${'$'}min_node" ] && echo "${'$'}chosen" > "${'$'}min_node" 2>/dev/null || true
                  echo "${'$'}chosen" > "${'$'}max_node" 2>/dev/null || true
                else
                  [ -w "${'$'}min_node" ] && echo "${'$'}lowest" > "${'$'}min_node" 2>/dev/null || true
                  echo "${'$'}chosen" > "${'$'}max_node" 2>/dev/null || true
                fi
                [ -w "${'$'}target_node" ] && echo "${'$'}chosen" > "${'$'}target_node" 2>/dev/null || true
                if [ -w "${'$'}kgsl/max_gpuclk" ]; then
                  echo "${'$'}chosen" > "${'$'}kgsl/max_gpuclk" 2>/dev/null || true
                fi
                sleep 1
                report="${'$'}report GPU: requested=${'$'}gpu_target chosen=${'$'}chosen devfreq_max=${'$'}(cat "${'$'}max_node" 2>/dev/null) cur=${'$'}(cat "${'$'}d/cur_freq" 2>/dev/null) gpuclk=${'$'}(cat "${'$'}kgsl/gpuclk" 2>/dev/null) max_gpuclk=${'$'}(cat "${'$'}kgsl/max_gpuclk" 2>/dev/null) thermal_pwrlevel=${'$'}(cat "${'$'}kgsl/thermal_pwrlevel" 2>/dev/null);"
                changed=${'$'}((changed+1))
              done
            fi
            echo "CHANGED=${'$'}changed${'$'}report"
        """.trimIndent().replace("__GPU_MAX_MARKER__", gpuMarker?.absolutePath ?: "/dev/null")
            .replace("__POCKETHUB_PID__", android.os.Process.myPid().toString())
        Thread {
            val cpuResult = if (governed) {
                // Adaptive mode keeps the limits itself; PocketHub's fixed keeper must not fight it.
                stopCpuLimitEnforcer()
                "OK adaptive"
            } else startCpuLimitEnforcer(cpuScript, cpuTargets.size)
            Log.i("PocketHubCPU", cpuResult)
            val gpuResult = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                val output = process.inputStream.bufferedReader().readText().trim()
                val errors = process.errorStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                if (code == 0 && output.contains("CHANGED=")) output else "Ошибка применения (код $code). $output $errors".trim()
            } catch (e: Exception) { "Ошибка root: ${e.message ?: "unknown"}" }
            if (governed && selectedProfile == profile) {
                // Max keeps its own GPU loop; the governor needs to be able to lower the GPU too.
                stopGpuLimitEnforcer()
                AdaptiveGovernor.start(profile, if (adaptiveProfile != null) adaptiveTarget(adaptiveProfile) else 0, cpuTargets, gpuTarget,
                    cpuPolicySteps.mapValues { it.value.toList() }, FreqControl.gpuSteps(), tempLimit, tempSensor)
            }
            val result = "CPU_LIMIT=$cpuResult\n$gpuResult"
            Log.i("PocketHubGPU", "$profile $gpuResult")
            runOnUiThread {
                selectedProfile = profile
                profileDiagnostics = result
                val changedCount = Regex("CHANGED=(\\d+)").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull()
                profileStatus = when {
                    !cpuResult.startsWith("OK") -> "$profile · лимит CPU не применён"
                    changedCount == 0 -> "$profile · лимиты CPU применены; управление GPU недоступно"
                    changedCount != null -> "$profile · лимиты CPU и GPU применены"
                    else -> "$profile · лимиты CPU применены"
                }
                getPreferences(Context.MODE_PRIVATE).edit().putString("profile", profile).apply()
                profileButtons.forEach { (name, _) -> refreshProfileButton(name, name == selectedProfile) }
                retroProfileTitleView?.apply {
                    text = profileDisplayName(selectedProfile).uppercase(Locale.ROOT)
                    setTextColor(profileAccent(selectedProfile))
                }
                profileStatusValueView?.text = localize(profileStatus.take(240))
                val toastMessage = when {
                    !cpuResult.startsWith("OK") -> tr("CPU limit failed: ", "Не удалось ограничить CPU: ") + cpuResult.take(140)
                    changedCount == 0 -> tr("CPU limits applied; GPU node was not found", "Лимиты CPU применены, узел GPU не найден", "CPU-limieten toegepast; GPU-node niet gevonden", "Límites de CPU aplicados; no se encontró el nodo GPU")
                    else -> tr("Profile applied; CPU cluster limits are active", "Профиль применён, лимиты кластеров CPU активны", "Profiel toegepast; CPU-clusterlimieten zijn actief", "Perfil aplicado; límites de clústeres CPU activos")
                }
                Toast.makeText(this, toastMessage, if (cpuResult.startsWith("OK")) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun startCpuLimitEnforcer(script: String, policyCount: Int): String {
        stopCpuLimitEnforcer()
        val marker = File(cacheDir, "cpu-limit-${System.currentTimeMillis()}.active")
        return try {
            check(marker.createNewFile()) { "could not create root-enforcer marker" }
            val process = ProcessBuilder("su", "-c", script.replace("__POCKETHUB_CPU_MARKER__", marker.absolutePath))
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            val firstLine = reader.readLine().orEmpty()
            if (!firstLine.contains("CPU_APPLIED=$policyCount") || !firstLine.contains("FAILED=0")) {
                marker.delete()
                process.destroy()
                return firstLine.ifBlank { "root CPU command returned no status" }
            }
            cpuLimitEnforcerMarker = marker
            cpuLimitEnforcerProcess = process
            Thread {
                try { reader.lineSequence().forEach { Log.i("PocketHubCPU", it) } } catch (_: Exception) { }
            }.apply { isDaemon = true; name = "PocketHub-CPU-diagnostics"; start() }
            "OK $firstLine · удерживаю лимиты"
        } catch (error: Exception) {
            marker.delete()
            "${error.javaClass.simpleName}: ${error.message ?: "root CPU command failed"}"
        }
    }

    @Synchronized
    private fun stopCpuLimitEnforcer() {
        cpuLimitEnforcerMarker?.delete()
        cpuLimitEnforcerMarker = null
        cpuLimitEnforcerProcess?.destroy()
        cpuLimitEnforcerProcess = null
    }

    private fun stopGpuLimitEnforcer() {
        gpuLimitEnforcerMarker?.delete()
        gpuLimitEnforcerMarker = null
    }

    private fun resolveCpuTarget(target: Long, steps: List<Int>): Int =
        if (target <= 0L) steps.last() else steps.lastOrNull { it.toLong() <= target } ?: steps.first()

    private fun fanControlCard(): LinearLayout = card().apply {
        fanModeButtons.clear()
        addView(label(tr("Fan control", "Управление вентилятором", "Ventilatorbediening", "Control del ventilador"), 17f, white, true).apply { setPadding(0, dp(5), 0, dp(3)) })

        val rowAuto = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addFanModeButton(rowAuto, "auto", tr("AUTO · OEM", "АВТО · OEM", "AUTO · OEM", "AUTO · OEM"), weight(1f))
        addView(rowAuto)

        val rowQuietBalanceTurbo = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addFanModeButton(rowQuietBalanceTurbo, "FAN_MODE_MUTE", tr("Quiet", "Тихий", "Stil", "Silencioso"), weight(1f, end = 3))
        addFanModeButton(rowQuietBalanceTurbo, "FAN_MODE_BALANCE", tr("Balanced", "Баланс", "Gebalanceerd", "Equilibrado"), weight(1f, start = 3, end = 3))
        addFanModeButton(rowQuietBalanceTurbo, "FAN_MODE_TURBO", tr("Turbo", "Турбо", "Turbo", "Turbo"), weight(1f, start = 3))
        addView(rowQuietBalanceTurbo, margin(top = 6))

        val rowCustomOff = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addFanModeButton(rowCustomOff, "FAN_MODE_CUSTOM", tr("Custom curve", "Пользовательский", "Aangepaste curve", "Curva personalizada"), weight(1f, end = 3))
        addFanModeButton(rowCustomOff, "FAN_MODE_OFF", tr("Fan off…", "Выключить…", "Ventilator uit…", "Apagar ventilador…"), weight(1f, start = 3))
        addView(rowCustomOff, margin(top = 6))

        addView(pill("⌁  ${tr("CONFIGURE FAN CURVE", "НАСТРОИТЬ ГРАФИК ВЕНТИЛЯТОРА", "VENTILATORCURVE INSTELLEN", "CONFIGURAR CURVA DEL VENTILADOR")}", cardBg2, cyan) {
            showFanCurveEditor()
        }.apply { minHeight = dp(44) }, margin(top = 6))

        val guardStatus = label("", 10f, muted)
        val guardSwitch = Switch(this@MainActivity).apply {
            text = tr("Overheat protection when the fan is off", "Защита от перегрева при выключенном вентиляторе",
                "Oververhittingsbeveiliging als de ventilator uit staat", "Protección contra sobrecalentamiento con el ventilador apagado")
            textSize = 12f; setTextColor(white)
            isChecked = FanGuard.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled ->
                FanGuard.setEnabled(this@MainActivity, enabled)
                refreshFanGuardStatus(guardStatus)
            }
        }
        addView(guardSwitch, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        addView(guardStatus)
        refreshFanGuardStatus(guardStatus)
        fanStatusValueView = null
        refreshFanModeUi()
    }

    /** Shows the guard thresholds and what it is doing right now (read through root). */
    private fun refreshFanGuardStatus(view: TextView) {
        val rules = tr(
            "Over ${FanGuard.LOW_ON_C}°C for 1 min: low (off below ${FanGuard.LOW_OFF_C}°C) · over ${FanGuard.MID_ON_C}°C for 1 min: 50% (back below ${FanGuard.MID_OFF_C}°C) · over ${FanGuard.MAX_ON_C}°C for 2 min: 100%",
            "Выше ${FanGuard.LOW_ON_C}°C 1 мин — низкие (выкл. ниже ${FanGuard.LOW_OFF_C}°C) · выше ${FanGuard.MID_ON_C}°C 1 мин — 50% (назад ниже ${FanGuard.MID_OFF_C}°C) · выше ${FanGuard.MAX_ON_C}°C 2 мин — 100%",
            "Boven ${FanGuard.LOW_ON_C}°C 1 min: laag (uit onder ${FanGuard.LOW_OFF_C}°C) · boven ${FanGuard.MID_ON_C}°C 1 min: 50% (terug onder ${FanGuard.MID_OFF_C}°C) · boven ${FanGuard.MAX_ON_C}°C 2 min: 100%",
            "Más de ${FanGuard.LOW_ON_C}°C 1 min: baja (apaga bajo ${FanGuard.LOW_OFF_C}°C) · más de ${FanGuard.MID_ON_C}°C 1 min: 50% (vuelve bajo ${FanGuard.MID_OFF_C}°C) · más de ${FanGuard.MAX_ON_C}°C 2 min: 100%"
        )
        if (!FanGuard.isEnabled(this)) {
            view.text = tr("Protection is off.", "Защита выключена.", "Beveiliging staat uit.", "Protección desactivada.")
            return
        }
        view.text = rules
        val path = FanGuard.stateFilePath(this)
        Thread {
            val parts = rootRead("cat '$path' 2>/dev/null")?.trim()?.split(" ").orEmpty()
            val level = parts.getOrNull(0)?.toIntOrNull()
            val temp = parts.getOrNull(1)
            val now = when (level) {
                1 -> tr("Active now: low speed", "Сейчас работает: низкие обороты", "Nu actief: laag toerental", "Activa ahora: velocidad baja") + " · $temp°C"
                2 -> tr("Active now: 50%", "Сейчас работает: 50%", "Nu actief: 50%", "Activa ahora: 50%") + " · $temp°C"
                3 -> tr("Active now: 100%", "Сейчас работает: 100%", "Nu actief: 100%", "Activa ahora: 100%") + " · $temp°C"
                -1 -> tr("Fan PWM control not found", "Управление PWM вентилятора не найдено", "PWM-bediening van ventilator niet gevonden", "Control PWM del ventilador no encontrado")
                else -> null
            }
            runOnUiThread { view.text = if (now != null) "$rules\n$now" else rules }
        }.apply { isDaemon = true; start() }
    }

    private fun addFanModeButton(parent: LinearLayout, mode: String, title: String, params: LinearLayout.LayoutParams) {
        val button = pill(title, cardBg2, white) { setFanMode(mode, 0) }.apply {
            minHeight = dp(42)
            textSize = if (layoutTheme == "compact") 10f else 11f
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }
        fanModeButtons[mode] = button
        parent.addView(button, params)
        refreshFanModeUi()
    }

    /**
     * Default fan mode per profile, used on a fresh install and after "Reset" in the
     * profile editor: Eco off, Balanced and Streaming low, Gaming high, Max full speed.
     * With the fan off, FanGuard still steps in if the CPU overheats.
     */
    private fun fanModeForProfile(profile: String): String = when (profile) {
        "Eco" -> "FAN_MODE_OFF"
        "Balanced" -> "FAN_MODE_MUTE"
        "Streaming" -> "FAN_MODE_MUTE"
        "Gaming" -> "FAN_MODE_BALANCE"
        else -> if (isGameProfile(profile) || TunedProfiles.isTuned(profile)) "FAN_MODE_BALANCE" else "FAN_MODE_TURBO"
    }

    private fun defaultCpuTarget(profile: String): Long = when (profile) {
        "Eco" -> 672000L
        "Balanced" -> 1500000L
        "Streaming" -> 1000000L
        else -> 0L // Gaming and Max follow the highest frequency exposed by each CPU policy.
    }

    private fun defaultGpuTarget(profile: String): Long = when {
        isGameProfile(profile) || TunedProfiles.isTuned(profile) -> 860000000L
        else -> defaultBuiltInGpuTarget(profile)
    }

    private fun defaultBuiltInGpuTarget(profile: String): Long = when (profile) {
        "Eco" -> 220000000L
        "Balanced" -> 680000000L
        "Streaming" -> 124800000L
        "Gaming" -> 860000000L
        else -> 1000000000L
    }

    private fun fanModeDisplay(mode: String): String = when (mode) {
        "auto" -> tr("Auto", "Авто", "Auto", "Auto")
        "FAN_MODE_OFF" -> tr("Off", "Выключен", "Uit", "Apagado")
        "FAN_MODE_MUTE" -> tr("Quiet", "Тихий", "Stil", "Silencioso")
        "FAN_MODE_BALANCE" -> tr("Balanced", "Баланс", "Gebalanceerd", "Equilibrado")
        "FAN_MODE_CUSTOM" -> tr("Custom", "Пользовательский", "Aangepast", "Personalizado")
        "FAN_MODE_TURBO" -> tr("Turbo", "Турбо", "Turbo", "Turbo")
        else -> mode
    }

    private fun currentFanSubtitle(): String = "${profileDisplayName(selectedProfile)} · ${fanModeDisplay(selectedFanMode)}"

    private fun refreshFanModeUi() {
        fanSubtitleView?.text = "${currentFanSubtitle()}  ⌄"
        fanModeButtons.forEach { (mode, button) ->
            val active = mode == selectedFanMode
            val face = if (active) mint else cardBg2
            button.background = roundedBackground(face, if (active) mint else border, if (layoutTheme == "compact") 8 else 12)
            button.setTextColor(if (active) bg else white)
        }
    }

    private fun selectFanMode(mode: String) {
        selectedFanMode = mode
        getPreferences(Context.MODE_PRIVATE).edit().putString("fan_mode", mode).apply()
        FanGuard.syncMode(this, mode)
        refreshFanModeUi()
    }

    private fun queueProfileFanMode(profile: String) {
        val mode = getPreferences(Context.MODE_PRIVATE).getString(
            "tune_fan_${profile.lowercase(Locale.ROOT)}", fanModeForProfile(profile)
        ) ?: fanModeForProfile(profile)
        // Quiet mode: one noise level lower than the profile asks for.
        queueFanMode(if (quietModeOn()) NoiseLevel.fanMode(NoiseLevel.quieter(NoiseLevel.fromFanMode(mode))) else mode)
    }

    private fun quietModeOn(): Boolean = getPreferences(Context.MODE_PRIVATE).getBoolean(TunedProfiles.KEY_QUIET_MODE, false)

    /** Noise level a profile runs at right now (its own level, one lower in Quiet mode). */
    private fun effectiveNoiseLevel(profile: String): String {
        val tuned = TunedProfiles.byKey(this, profile)
        val own = tuned?.goal()?.takeIf { it.kind == TuneGoal.KIND_NOISE }?.noise ?: NoiseLevel.fromFanMode(
            getPreferences(Context.MODE_PRIVATE).getString("tune_fan_${profile.lowercase(Locale.ROOT)}", fanModeForProfile(profile)))
        return if (quietModeOn()) NoiseLevel.quieter(own) else own
    }

    /** Turns Quiet mode on or off and re-applies the current profile with it. */
    internal fun setQuietMode(on: Boolean) {
        val host = liveInstance?.get()?.takeIf { it !== this && !it.isFinishing && !it.isDestroyed }
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(TunedProfiles.KEY_QUIET_MODE, on).apply()
        val target = host ?: this
        target.runOnUiThread { target.applyPerformanceProfile(target.selectedProfile) }
        Toast.makeText(this, if (on) tr("Quiet mode on: fan one level lower, chip kept cooler", "Тихий режим включён: вентилятор на уровень тише, чип держится холоднее",
            "Stille modus aan: ventilator een stand lager, chip koeler gehouden", "Modo silencioso: ventilador un nivel más bajo y chip más frío")
            else tr("Quiet mode off", "Тихий режим выключен", "Stille modus uit", "Modo silencioso desactivado"), Toast.LENGTH_SHORT).show()
    }

    private fun queueFanMode(mode: String) {
        selectFanMode(mode)
        pendingFanMode = mode
        if (oemAidlBound && oemServiceBinder != null && oemClientId != null) dispatchPendingFanModeIfReady()
    }

    private fun dispatchPendingFanModeIfReady() {
        val mode = pendingFanMode ?: return
        if (!oemAidlBound || oemServiceBinder == null || oemBinderDescriptor != OEM_AIDL_DESCRIPTOR || oemClientId.isNullOrBlank()) return
        pendingFanMode = null
        sendOemFanMode(mode, showToast = false)
    }

    private fun applyCpuSchedulerMode(mode: String) {
        pendingCpuSchedulerMode = mode
        if (oemAidlBound && oemServiceBinder != null && oemClientId != null) dispatchPendingCpuSchedulerModeIfReady()
        else if (oemAidlConnection == null) testOemAidl(showToast = false)
    }

    private fun dispatchPendingCpuSchedulerModeIfReady() {
        val mode = pendingCpuSchedulerMode ?: return
        val binder = oemServiceBinder ?: return
        val clientId = oemClientId ?: return
        if (!oemAidlBound || oemBinderDescriptor != OEM_AIDL_DESCRIPTOR) return
        pendingCpuSchedulerMode = null
        Thread {
            val result = try {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(OEM_AIDL_DESCRIPTOR)
                    data.writeString("$clientId:msg_type_performance:com_set_performance_scheduler:$mode")
                    if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) error("OEM scheduler command not handled")
                    reply.readException()
                    null
                } finally { data.recycle(); reply.recycle() }
            } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                if (result == null) getPreferences(Context.MODE_PRIVATE).edit().putString("cpu_scheduler_mode", mode).apply()
                else Toast.makeText(this, "CPU scheduler: $result", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun fanStatus(): String {
        // On this Pocket DS firmware pwm1 can read 0 while the fan is spinning, so
        // report the platform driver's RPM and power state instead of a misleading PWM value.
        val rawRpm = rootRead("cat /sys/devices/platform/soc/soc:pwm-fan/fan_rpm_state 2>/dev/null")
        val rpm = Regex("\\d+").find(rawRpm.orEmpty())?.value?.toIntOrNull()
        val power = rootRead("cat /sys/devices/platform/soc/soc:pwm-fan/fan_power_state 2>/dev/null")
        val powerText = when (power) { "1" -> "питание включено"; "0" -> "питание выключено"; else -> "состояние питания —" }
        return "Скорость: ${rpm?.let { "$it RPM" } ?: "RPM —"} · $powerText · управление через OEM"
    }

    private fun setFanMode(mode: String, pwm: Int) {
        if (mode == "FAN_MODE_OFF") {
            showFanOffConfirmation {
                selectFanMode(mode)
                pendingFanMode = mode
                sendOemFanMode(mode, showToast = false)
            }
            return
        }
        selectFanMode(mode)
        if (mode != "auto") {
            pendingFanMode = mode
            sendOemFanMode(mode, showToast = false)
            return
        }
        // AUTO is the stock charger-fan policy helper, not a direct PWM write.
        pendingFanMode = null
        val script = "if [ -x /vendor/bin/charger_fan_speed.sh ]; then /vendor/bin/charger_fan_speed.sh; else echo 'OEM fan script unavailable'; exit 2; fi; " +
            "echo FAN_MODE=OEM_AUTO; echo FAN_RPM=\$(cat /sys/devices/platform/soc/soc:pwm-fan/fan_rpm_state 2>/dev/null); echo FAN_POWER=\$(cat /sys/devices/platform/soc/soc:pwm-fan/fan_power_state 2>/dev/null)"
        Thread {
            val result = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                val out = process.inputStream.bufferedReader().readText().trim()
                val err = process.errorStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                if (code == 0) "$out${if (err.isNotBlank()) " · $err" else ""}" else "Fan error (code $code): $out $err".trim()
            } catch (e: Exception) { "Root error: ${e.message ?: "unknown"}" }
            runOnUiThread {
                updateFanStatusAsync(result)
                if (result.contains("error", ignoreCase = true) || result.contains("unavailable", ignoreCase = true)) {
                    Toast.makeText(this, result.take(180), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showFanOffConfirmation(onConfirm: () -> Unit) {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = roundedBackground(cardBg, border, 20)
        }
        panel.addView(label("ОСТОРОЖНО", 11f, Color.rgb(255, 135, 112), true))
        panel.addView(label("Выключить вентилятор?", 20f, white, true).apply { setPadding(0, dp(5), 0, dp(8)) })
        panel.addView(label("Вентилятор будет переведён в OEM-режим OFF. Защита ОС не отключается, но охлаждение может резко снизиться. Используй этот режим осознанно.", 12f, muted).apply { setPadding(0, 0, 0, dp(14)) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill("ОТМЕНА", cardBg2, white) { dialog.dismiss() }, weight(1f, end = 5))
        buttons.addView(pill("ВЫКЛЮЧИТЬ", Color.rgb(105, 45, 45), white) { dialog.dismiss(); onConfirm() }, weight(1f, start = 5))
        panel.addView(buttons)
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.74f).toInt().coerceAtMost(dp(600)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun sendOemFanMode(mode: String, showToast: Boolean = false) {
        val binder = oemServiceBinder
        val clientId = oemClientId
        if (!oemAidlBound || binder == null || oemBinderDescriptor != OEM_AIDL_DESCRIPTOR || clientId.isNullOrBlank()) {
            pendingFanMode = mode
            fanStatusValueView?.text = tr(
                "Selected fan mode is queued while OEM connects.",
                "Режим вентилятора выбран; команда ожидает подключения OEM.",
                "De ventilatormodus is gekozen en wacht op de OEM-verbinding.",
                "El modo de ventilador está seleccionado y espera la conexión OEM."
            )
            if (oemAidlConnection == null) testOemAidl(showToast = false)
            return
        }
        pendingFanMode = null
        fanStatusValueView?.text = "${tr("Applying fan mode", "Применяю режим вентилятора", "Ventilatormodus toepassen", "Aplicando modo de ventilador")}: ${fanModeDisplay(mode)}…"
        Thread {
            val result = try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(OEM_AIDL_DESCRIPTOR)
                    data.writeString("$clientId:msg_type_performance:com_set_performance_fan:$mode")
                    val handled = binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                    if (!handled) throw IllegalStateException("Binder transaction 1 not handled")
                    reply.readException()
                    "OEM accepted fan-mode command: $mode"
                } finally { data.recycle(); reply.recycle() }
            } catch (e: Exception) { "OEM fan-command error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}" }
            runOnUiThread {
                if (result.contains("error", ignoreCase = true)) {
                    pendingFanMode = mode
                    fanStatusValueView?.text = result
                    if (showToast) Toast.makeText(this, result.take(180), Toast.LENGTH_LONG).show()
                } else {
                    updateFanStatusAsync("${tr("Selected mode", "Выбран режим", "Geselecteerde modus", "Modo seleccionado")}: ${fanModeDisplay(mode)}")
                }
            }
        }.start()
    }

    private fun metricCard(
        title: String,
        value: String,
        subtitle: String,
        accent: Int,
        glyph: String = "",
        expandable: Boolean = false,
        onClick: (() -> Unit)? = null
    ): LinearLayout = card().apply {
        // Starship Console cards lose some height to the frame: stack from the top instead of
        // centring, so nothing is pushed up into the frame.
        gravity = if (isBridge()) Gravity.TOP else Gravity.CENTER_VERTICAL
        setPadding(dp(if (layoutTheme == "compact") 8 else 10), dp(if (layoutTheme == "compact") 7 else 8), dp(if (layoutTheme == "compact") 8 else 10), dp(7))
        minimumHeight = dp(102)
        if (onClick != null) {
            background = if (isBrutal()) brutalBox(blendColor(cardBg, accent, 0.16f))
            else if (glassTheme()) auroraGlass(26, accent)
            else if (isPop()) popCard(28, accent)
            else if (layoutTheme == "eightbit") GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(4).toFloat()
                setStroke(dp(2), accent)
            } else GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(blendColor(cardBg2, accent, 0.13f), cardBg)).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            elevation = if (flatTheme()) 0f else dp(5).toFloat()
        }
        val heading = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (title == "FAN") {
            if (usesLivingIcons()) heading.addView(LivingIconView(this@MainActivity, "fan", accent, animated = false).also { auroraFanIcon = it },
                LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(3) })
            else heading.addView(FanGlyphView(this@MainActivity, accent).apply { contentDescription = tr("Fan", "Вентилятор", "Ventilator", "Ventilador") }, LinearLayout.LayoutParams(dp(18), dp(18)))
        } else if (glyph.isNotBlank()) {
            val glyphKey = when (title) { "DISPLAY" -> "display"; "FPS" -> "fps"; "RAM" -> "ram"; else -> glyph }
            if (usesLivingIcons()) heading.addView(LivingIconView(this@MainActivity, glyphKey, accent, animated = false), LinearLayout.LayoutParams(dp(if (isBridge()) 22 else 26), dp(if (isBridge()) 22 else 26)).apply { marginEnd = dp(3) })
            else heading.addView(MetricGlyphView(this@MainActivity, glyphKey, accent), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(3) })
        }
        heading.addView(label(when (title) {
            "FAN" -> tr("FAN", "ВЕНТИЛЯТОР", "VENTILATOR", "VENTILADOR")
            "DISPLAY" -> tr("DISPLAY", "ЭКРАН", "SCHERM", "PANTALLA")
            "FPS" -> tr("FPS", "КАДРЫ/С", "FPS", "FPS")
            "RAM" -> tr("RAM", "ОЗУ", "RAM", "RAM")
            else -> tr("STORAGE", "НАКОПИТЕЛЬ", "OPSLAG", "ALMACENAMIENTO")
        }, 10f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (expandable && onClick != null) {
            heading.addView(label("⌄", 13f, accent, true).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(blendColor(cardBg, accent, 0.2f), Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)), 11)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(5) })
        }
        addView(heading)
        val valueSize = when (title) {
            "FPS" -> if (layoutTheme == "compact" || isBridge()) 24f else 31f
            "FAN" -> if (layoutTheme == "compact") 20f else 24f
            else -> if (layoutTheme == "compact") 18f else 22f
        }
        val valueLabel = label(value, valueSize, accent, true).apply {
            setPadding(0, dp(5), 0, dp(2)); maxLines = 1
        }
        when (title) {
            "FAN" -> fanValueView = valueLabel
            "DISPLAY" -> displayValueView = valueLabel
            "RAM" -> memoryValueView = valueLabel
            "STORAGE" -> storageValueView = valueLabel
            "FPS" -> fpsValueView = valueLabel
        }
        addView(valueLabel)
        if (title == "FPS") {
            // Every theme shows recent FPS as a small line chart instead of a caption.
            val graph = FpsGraphView(this@MainActivity, accent, muted).apply { setValues(fpsHistory) }
            fpsGraphView = graph
            addView(graph, LinearLayout.LayoutParams(-1, dp(if (isBridge()) 18 else 22)).apply { topMargin = dp(2) })
        } else {
            val subText = if (title == "FAN") currentFanSubtitle() else subtitle
            val subtitleView = label(subText, 9f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            if (title == "FAN") fanSubtitleView = subtitleView
            addView(subtitleView)
        }
        if (onClick != null) {
            isClickable = true
            enableControllerFocus(this, if (layoutTheme == "compact") 8 else 13)
            setOnClickListener { onClick.invoke() }
            contentDescription = when (title) {
                "FAN" -> tr("Open fan mode controls", "Открыть управление режимом вентилятора", "Ventilatormodus instellen", "Abrir controles del ventilador")
                "DISPLAY" -> tr("Open refresh rate choices", "Открыть выбор частоты экрана", "Verversingssnelheid kiezen", "Elegir frecuencia de pantalla")
                else -> subtitle
            }
        }
    }

    private fun systemCard(title: String, value: String, subtitle: String, glyph: String): LinearLayout = card().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label(glyph, 20f, mint, true), LinearLayout.LayoutParams(dp(30), -2))
            addView(label(title, 12f, white, true))
        })
        val valueLabel = label(value, 15f, mint, true).apply { setPadding(0, dp(5), 0, dp(2)); maxLines = 2 }
        when (title) { "CPU" -> cpuValueView = valueLabel; "GPU" -> gpuValueView = valueLabel; "MEMORY" -> memoryValueView = valueLabel; "STORAGE" -> storageValueView = valueLabel }
        addView(valueLabel)
        addView(label(subtitle, 11f, muted))
    }

    /** Chunked meter used by the 8-bit command HUD. */
    private inner class SegmentedUsageView(context: Context, private val accent: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 47, 62) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(6, 11, 19); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        var progress: Int = 0
            set(value) { field = value.coerceIn(0, 100); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val count = 14
            val gap = dp(2).toFloat()
            val w = (width - gap * (count - 1)).coerceAtLeast(0f) / count
            val h = (height.toFloat() - dp(3)).coerceAtLeast(0f)
            val top = (height - h) / 2f
            val lit = (progress * count / 100f).toInt()
            for (i in 0 until count) {
                val left = i * (w + gap)
                val rect = RectF(left, top, left + w, top + h)
                canvas.drawRect(rect, if (i < lit) fill else track)
                canvas.drawRect(rect, edge)
            }
        }
    }

    /** Hand-drawn pixel-style panel frame, avoiding generic rounded cards in this layout. */
    private inner class RetroFrameDrawable(
        private val fillColor: Int,
        private val edgeColor: Int,
        private val notchDp: Int
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edgeColor; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val pixelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edgeColor }
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val n = dp(notchDp).toFloat().coerceAtMost(minOf(b.width(), b.height()) / 4f)
            val l = b.left.toFloat(); val t = b.top.toFloat(); val r = b.right.toFloat(); val bot = b.bottom.toFloat()
            val path = Path().apply {
                moveTo(l + n, t); lineTo(r - n, t); lineTo(r - n, t + n); lineTo(r, t + n)
                lineTo(r, bot - n); lineTo(r - n, bot - n); lineTo(r - n, bot); lineTo(l + n, bot)
                lineTo(l + n, bot - n); lineTo(l, bot - n); lineTo(l, t + n); lineTo(l + n, t + n); close()
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, edgePaint)
            val px = dp(3).toFloat()
            canvas.drawRect(l + n, t + n, l + n + px, t + n + px, pixelPaint)
            canvas.drawRect(r - n - px, bot - n - px, r - n, bot - n, pixelPaint)
        }
        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; edgePaint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fillPaint.colorFilter = colorFilter; edgePaint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Tiny original pixel-art marks replace the generic emoji and circular glyphs. */
    private inner class PixelHandheldMark(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mint }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = (minOf(width / 12f, height / 8f)).coerceAtLeast(dp(1).toFloat())
            val x = (width - unit * 10) / 2f; val y = (height - unit * 7) / 2f
            val rows = listOf("0011111100", "0111111110", "1111111111", "1101101011", "1101101011", "1111111111", "0111111110")
            rows.forEachIndexed { row, line -> line.forEachIndexed { col, bit ->
                if (bit == '1') canvas.drawRect(x + col * unit, y + row * unit, x + (col + 1) * unit - 1, y + (row + 1) * unit - 1, p)
            } }
            p.color = bg
            canvas.drawRect(x + unit * 2, y + unit * 3, x + unit * 4, y + unit * 5, p)
            canvas.drawRect(x + unit * 7, y + unit * 3, x + unit * 8, y + unit * 4, p)
            canvas.drawRect(x + unit * 8, y + unit * 4, x + unit * 9, y + unit * 5, p)
            p.color = mint
        }
    }

    private inner class PixelHeroSprite(context: Context) : View(context) {
        private val pixel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 255, 75) }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 112, 97) }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(6, 15, 25) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cell = (minOf(width / 14f, height / 12f)).coerceAtLeast(dp(1).toFloat())
            val left = (width - cell * 12) / 2f
            val top = (height - cell * 10) / 2f
            val art = listOf("000001111100", "000111111110", "001111111111", "011110011111", "111110011111", "111111111111", "011011110110", "001111111100", "011001100110", "110001100011")
            art.forEachIndexed { row, line -> line.forEachIndexed { col, bit ->
                if (bit == '1') canvas.drawRect(left + col * cell, top + row * cell, left + (col + 1) * cell - 1, top + (row + 1) * cell - 1, if (row < 3) shade else pixel)
            } }
            canvas.drawRect(left + cell * 3, top + cell * 4, left + cell * 5, top + cell * 5, dark)
            canvas.drawRect(left + cell * 7, top + cell * 4, left + cell * 9, top + cell * 5, dark)
            canvas.drawRect(left + cell * 5, top + cell * 6, left + cell * 7, top + cell * 7, dark)
        }
    }

    /** Small live percentage bar with custom AYANEO-style paint rather than an Android default widget. */
    private inner class UsageBarView(context: Context, private val accent: Int) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 52, 63) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private var percent: Float? = null
        fun setValue(value: Float?) { percent = value?.coerceIn(0f, 100f); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            val r = h / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
            val p = percent ?: return
            val end = width * p / 100f
            if (end > 0f) canvas.drawRoundRect(0f, 0f, end, h, r, r, fill)
        }
    }

    /** Rolling one-minute line graph. Missing telemetry is not replaced by invented samples. */
    private inner class MiniLineChartView(
        context: Context,
        private val accent: Int,
        private val maxValue: Float,
        private val unit: String
    ) : View(context) {
        private val values = mutableListOf<Float>()
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 130, 166, 177); strokeWidth = dp(1).toFloat() }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = sp(9f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private val line = Path()
        private val area = Path()
        fun push(value: Float?) {
            if (value != null && value.isFinite() && value >= 0f) {
                values.add(value.coerceAtMost(maxValue))
                while (values.size > 30) values.removeAt(0)
                invalidate()
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(2).toFloat()
            val right = width - dp(2).toFloat()
            val top = dp(10).toFloat()
            val bottom = height - dp(5).toFloat()
            if (right <= left || bottom <= top) return
            for (i in 0..2) {
                val y = top + (bottom - top) * i / 2f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            canvas.drawText("${maxValue.toInt()} $unit", left, dp(8).toFloat(), textPaint)
            if (values.size < 2) {
                canvas.drawText("Collecting samples…", left, (top + bottom) / 2f, textPaint)
                return
            }
            val dx = (right - left) / (values.size - 1).toFloat()
            line.reset(); area.reset()
            values.forEachIndexed { index, value ->
                val x = left + dx * index
                val y = bottom - (value / maxValue) * (bottom - top)
                if (index == 0) { line.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y) }
                else { line.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(right, bottom); area.close()
            fillPaint.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent)), Shader.TileMode.CLAMP)
            canvas.drawPath(area, fillPaint)
            fillPaint.shader = null
            canvas.drawPath(line, linePaint)
            val last = values.last()
            textPaint.color = accent
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(Locale.US, "%.0f", last), right, dp(8).toFloat(), textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = muted
        }
        private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
    }

    private data class BacklightNode(val path: String, val current: Int, val maximum: Int, val label: String)

    /** Finds real backlight sysfs controls. All writes are done through su because these nodes
     * are normally writable only by the system. No display, CPU, GPU or thermal settings change. */
    private fun backlightNodes(): List<BacklightNode> {
        val script = """
            seen=' '
            for d in /sys/class/backlight/* /sys/class/leds/*backlight*; do
              [ -e "${'$'}d/brightness" ] || continue
              b="${'$'}(readlink -f "${'$'}d/brightness" 2>/dev/null)"
              [ -n "${'$'}b" ] || b="${'$'}d/brightness"
              case "${'$'}seen" in *" ${'$'}b "*) continue ;; esac
              seen="${'$'}seen${'$'}b "
              m="${'$'}(cat "${'$'}d/max_brightness" 2>/dev/null)"
              c="${'$'}(cat "${'$'}d/brightness" 2>/dev/null)"
              case "${'$'}m:${'$'}c" in *[!0-9:]*|:*) continue ;; esac
              [ "${'$'}m" -gt 0 ] 2>/dev/null || continue
              echo "${'$'}d|${'$'}c|${'$'}m"
            done
        """.trimIndent()
        val raw = rootRead(script) ?: return emptyList()
        val nodes = raw.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 3) return@mapNotNull null
            val cur = p[1].toIntOrNull() ?: return@mapNotNull null
            val max = p[2].toIntOrNull() ?: return@mapNotNull null
            if (max <= 0) return@mapNotNull null
            BacklightNode(p[0], cur.coerceIn(0, max), max, p[0].substringAfterLast('/'))
        }.distinctBy { it.path }.toMutableList()

        // Prefer explicit hardware names when present. Otherwise keep kernel enumeration order:
        // the first node is the upper display and the second is the lower display on this device.
        if (nodes.size >= 2) {
            val upper = nodes.firstOrNull { it.label.contains("top", true) || it.label.contains("upper", true) || it.label.contains("inner", true) }
            val lower = nodes.firstOrNull { it.label.contains("bottom", true) || it.label.contains("lower", true) || it.label.contains("secondary", true) }
            if (upper != null && lower != null && upper.path != lower.path) {
                nodes.remove(upper); nodes.remove(lower); nodes.add(0, upper); nodes.add(1, lower)
            }
        }
        return nodes.take(2)
    }

    private fun scheduleBacklightWrite(node: BacklightNode, percent: Int, immediate: Boolean = false) {
        val key = node.path
        brightnessWriteRunnables.remove(key)?.let { uiHandler.removeCallbacks(it) }
        val task = Runnable { setBacklightPercent(node, percent) }
        brightnessWriteRunnables[key] = task
        uiHandler.postDelayed(task, if (immediate) 0L else 120L)
    }

    private fun setBacklightPercent(node: BacklightNode, percent: Int) {
        val target = (node.maximum * percent.coerceIn(1, 100) / 100.0).toInt().coerceIn(1, node.maximum)
        val safePath = node.path.replace("'", "'\\''")
        Thread {
            val result = rootRead("echo $target > '$safePath/brightness' 2>/dev/null && cat '$safePath/brightness' || echo WRITE_FAILED")
            runOnUiThread {
                if (result.isNullOrBlank() || result.contains("WRITE_FAILED")) {
                    Toast.makeText(this, "Не удалось изменить яркость: ${node.label}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }


    private fun testOemAidl(showToast: Boolean = true) {
        if (oemAidlBound && oemBinderDescriptor == OEM_AIDL_DESCRIPTOR && !oemClientId.isNullOrBlank()) {
            updateFanStatusAsync("OEM AIDL подключён\nDescriptor: $oemBinderDescriptor\nClient ID: $oemClientId")
            if (showToast) Toast.makeText(this, "OEM AIDL подключён; client ID зарегистрирован", Toast.LENGTH_SHORT).show()
            return
        }
        // No action: the special com.ayaneo.aidl.server action returns a local Binder,
        // whereas an actionless explicit bind returns AyaAidlInterface.
        releaseOemAidl()
        val intent = Intent().setClassName(
            "com.ayaneo.gamewindow",
            "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                oemAidlBound = true
                oemServiceBinder = service
                val descriptor = try { service.interfaceDescriptor ?: "(descriptor отсутствует)" }
                    catch (e: Exception) { "descriptor error: ${e.javaClass.simpleName}: ${e.message}" }
                oemBinderDescriptor = descriptor
                if (descriptor != OEM_AIDL_DESCRIPTOR) {
                    fanStatusValueView?.text = "Подключение получено, но это не ожидаемый AIDL-интерфейс.\nDescriptor: $descriptor\nОжидался: $OEM_AIDL_DESCRIPTOR"
                    if (showToast) Toast.makeText(this@MainActivity, "Получен неподходящий Binder descriptor", Toast.LENGTH_LONG).show()
                    return
                }
                oemRegistrationCheckActive = true
                Thread {
                    val registration = try {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(OEM_AIDL_DESCRIPTOR)
                            data.writeStrongBinder(oemCallbackBinder)
                            val handled = service.transact(2, data, reply, 0)
                            if (!handled) throw IllegalStateException("Binder transaction 2 (register callback) не обработан")
                            reply.readException()
                            oemCallbackRegistered = true
                            // Some OEM builds deliver the registration callback one-way/asynchronously.
                            val deadline = System.currentTimeMillis() + 2500L
                            while (oemClientId.isNullOrBlank() && System.currentTimeMillis() < deadline) {
                                Thread.sleep(40L)
                            }
                            "Транзакция регистрации callback выполнена"
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    } catch (e: Exception) {
                        "Ошибка регистрации callback: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    } finally {
                        oemRegistrationCheckActive = false
                    }
                    runOnUiThread {
                        val id = oemClientId
                        if (id.isNullOrBlank()) {
                            val hits = oemCallbackTransactions.get()
                            val lastMessage = oemLastCallbackMessage?.take(240) ?: "нет полученных строк"
                            val callbackDiag = oemLastCallbackDiagnostic ?: "callback onTransact не вызывался"
                            updateFanStatusAsync(
                                "Binder интерфейс доступен, но client ID не получен.\n$registration\n" +
                                "Callback-вызовов: $hits\n$callbackDiag\nПоследнее сообщение: $lastMessage"
                            )
                            if (showToast) Toast.makeText(this@MainActivity, "AIDL подключён; callback-вызовы: $hits", Toast.LENGTH_LONG).show()
                        } else {
                            updateFanStatusAsync("OEM AIDL готов\nDescriptor: $descriptor\nClient ID: $id\n$registration")
                            dispatchPendingFanModeIfReady()
                            dispatchPendingCpuSchedulerModeIfReady()
                            dispatchPendingControllerCommandIfReady()
                            if (showToast) Toast.makeText(this@MainActivity, "OEM AIDL готов: callback зарегистрирован", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                clearOemConnectionState()
                oemAidlConnection = null
                fanStatusValueView?.text = "OEM AIDL отключён: ${name.flattenToShortString()}"
            }
            override fun onBindingDied(name: ComponentName) {
                clearOemConnectionState()
                oemAidlConnection = null
                fanStatusValueView?.text = "OEM AIDL binding died: ${name.flattenToShortString()}"
            }
            override fun onNullBinding(name: ComponentName) {
                clearOemConnectionState()
                oemAidlConnection = null
                fanStatusValueView?.text = "OEM AIDL вернул пустой Binder: ${name.flattenToShortString()}"
            }
        }
        oemAidlConnection = connection
        fanStatusValueView?.text = "Подключение к штатному AyaAidlInterface без action…"
        try {
            val bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                fanStatusValueView?.text = "OEM AIDL: bindService вернул false. Команды не отправлялись."
                oemAidlConnection = null
                if (showToast) Toast.makeText(this, "AYANEO AIDL не подключился (bindService=false)", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            fanStatusValueView?.text = "OEM AIDL: ${e.javaClass.simpleName}: ${e.message}"
            oemAidlConnection = null
            if (showToast) Toast.makeText(this, "Ошибка подключения OEM AIDL: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOemCallback(message: String) {
        oemLastCallbackMessage = message
        if (message.startsWith("msg_type_register:")) {
            oemClientId = message.substringAfter("msg_type_register:").substringBefore(':').trim().takeIf { it.isNotEmpty() }
            uiHandler.post {
                dispatchPendingFanModeIfReady()
                dispatchPendingControllerCommandIfReady()
            }
        }
        if (message.contains("com_get_abxy_mode:", ignoreCase = true)) {
            val value = Regex("(?i)com_get_abxy_mode:.*?(STANDARD|JAPAN)").find(message)?.groupValues?.getOrNull(1)
            if (value != null) {
                controllerAbxyMode = value.uppercase(Locale.ROOT)
                uiHandler.post {
                    updateControllerChoiceUi()
                    controllerStatusValueView?.text = tr("Button layout read from AYANEO", "Раскладка кнопок прочитана из AYANEO", "Knopindeling gelezen van AYANEO", "Distribución leída de AYANEO")
                }
            }
        }
        // Do not let an early callback status overwrite the registration summary while its
        // diagnostic wait is still running; that summary includes count, raw message and errors.
        if (!oemRegistrationCheckActive) {
            uiHandler.post {
                val id = oemClientId
                val callbackKind = when {
                    message.startsWith("msg_type_register:") -> "callback регистрации получен"
                    message.startsWith("msg_type_performance:com_set_performance_mode:") -> "получено обновление профиля производительности"
                    message.startsWith("msg_type_performance:") -> "получено сообщение управления производительностью"
                    else -> "получен callback от OEM"
                }
                val summary = if (!id.isNullOrBlank()) "OEM AIDL подключён · client ID: $id\n$callbackKind" else "OEM callback: $callbackKind"
                updateFanStatusAsync(summary)
            }
        }
    }

    private fun updateFanStatusAsync(prefix: String) {
        fanStatusValueView?.text = localize(prefix)
        Thread {
            val telemetry = fanStatus()
            uiHandler.post {
                if (!isFinishing && !isDestroyed) fanStatusValueView?.text = localize("$prefix\n$telemetry")
            }
        }.start()
    }

    private fun clearOemConnectionState() {
        oemAidlBound = false
        oemServiceBinder = null
        oemBinderDescriptor = null
        oemClientId = null
        oemCallbackRegistered = false
        oemRegistrationCheckActive = false
        oemLastCallbackMessage = null
        oemLastCallbackDiagnostic = null
        oemCallbackTransactions.set(0)
    }

    private fun releaseOemAidl() {
        val connection = oemAidlConnection
        val binder = oemServiceBinder
        if (binder != null && oemBinderDescriptor == OEM_AIDL_DESCRIPTOR && oemCallbackRegistered) {
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(OEM_AIDL_DESCRIPTOR)
                    data.writeStrongBinder(oemCallbackBinder)
                    if (binder.transact(3, data, reply, 0)) reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (_: Exception) { }
        }
        if (connection != null) {
            try { unbindService(connection) } catch (_: Exception) { }
        }
        oemAidlConnection = null
        clearOemConnectionState()
    }

    private fun showFanDialog() {
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = roundedBackground(cardBg, border, 20)
        }
        panel.addView(label("УПРАВЛЕНИЕ ВЕНТИЛЯТОРОМ", 12f, mint, true), margin(bottom = 8))
        panel.addView(fanControlCard().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }, margin(bottom = 8))
        // Custom themed dialog avoids the stock gray AlertDialog surface.
        panel.addView(pill("ГОТОВО", mint, bg) { dialog.dismiss() })
        dialog.setContentView(panel)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.78f).toInt().coerceAtMost(dp(760)), -2)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        Thread {
            val status = fanStatus()
            runOnUiThread {
                if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                fanStatusValueView?.text = status
            }
        }.start()
    }

    private fun showSoundDialog() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, bg)).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), border)
            }
        }
        panel.addView(label("♫  ${tr("SOUND", "ЗВУК", "GELUID", "SONIDO")}", 12f, mint, true), margin(bottom = 2))
        val screenMixer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val soundPrefs = getPreferences(Context.MODE_PRIVATE)
        val screenAudioEnabled = soundPrefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)
        val mediaTitle = label(tr("Media volume", "Общая громкость медиа", "Mediavolume", "Volumen multimedia"), 19f, white, true)
        val mediaControl = audioStreamControl(audio, AudioManager.STREAM_MUSIC)

        // On/off for per-screen volume right here, so the sliders are never silently inactive.
        val separateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        separateRow.addView(label(tr("Separate volume per screen", "Отдельная громкость по экранам", "Apart volume per scherm", "Volumen separado por pantalla"), 11f, white, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val separateSwitch = Switch(this).apply { isChecked = screenAudioEnabled }
        separateRow.addView(separateSwitch)
        screenMixer.addView(separateRow, margin(bottom = 2))
        val statusView = label("", 9f, muted).apply { setPadding(0, 0, 0, dp(4)) }
        screenMixer.addView(statusView)
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val masterControl = screenAudioControl(tr("Overall volume", "Общая громкость", "Totaal volume", "Volumen general"), ScreenAudioVolumeService.KEY_MASTER)
        sliders.addView(masterControl, margin(bottom = 3))
        sliders.addView(screenAudioControl(tr("Upper screen", "Верхний экран", "Bovenste scherm", "Pantalla superior"), ScreenAudioVolumeService.KEY_UPPER), margin(bottom = 3))
        sliders.addView(screenAudioControl(tr("Lower screen", "Нижний экран", "Onderste scherm", "Pantalla inferior"), ScreenAudioVolumeService.KEY_LOWER), margin(bottom = 3))
        screenMixer.addView(sliders)

        fun showSeparateState(enabled: Boolean) {
            sliders.alpha = if (enabled) 1f else 0.45f
            masterControl.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) (masterControl.getChildAt(1) as? SeekBar)?.progress =
                soundPrefs.getInt(ScreenAudioVolumeService.KEY_MASTER, 100).coerceIn(0, 100)
            mediaTitle.visibility = if (enabled) View.GONE else View.VISIBLE
            mediaControl.visibility = if (enabled) View.GONE else View.VISIBLE
            if (separateSwitch.isChecked != enabled) separateSwitch.isChecked = enabled
        }
        fun refreshStatus() {
            statusView.text = screenAudioStatusText(soundPrefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false),
                soundPrefs.getString(ScreenAudioVolumeService.KEY_STATUS, "") ?: "")
        }
        separateSwitch.setOnCheckedChangeListener { _, checked ->
            setScreenAudioEnabled(checked)
            showSeparateState(checked)
            refreshStatus()
        }
        // Moving a screen slider while the feature is off turns it on.
        screenAudioEnableRequest = {
            if (!soundPrefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)) {
                setScreenAudioEnabled(true)
                showSeparateState(true)
                refreshStatus()
            }
        }
        showSeparateState(screenAudioEnabled)
        refreshStatus()
        val statusTicker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) { screenAudioEnableRequest = null; return }
                refreshStatus()
                uiHandler.postDelayed(this, 1000L)
            }
        }
        uiHandler.postDelayed(statusTicker, 1000L)
        panel.addView(screenMixer, margin(bottom = 8))
        panel.addView(mediaTitle, margin(bottom = 8))
        panel.addView(mediaControl, margin(bottom = 7))

        val extraStreamsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        listOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM, AudioManager.STREAM_SYSTEM).forEach { stream ->
            extraStreamsPanel.addView(audioStreamControl(audio, stream), margin(bottom = 5))
        }
        val extrasButton = pill(tr("Additional volumes  ⌄", "Дополнительные уровни  ⌄", "Extra volumes  ⌄", "Volúmenes adicionales  ⌄"), cardBg2, white) { }
            .apply { minHeight = dp(44); textSize = 11f }
        extrasButton.setOnClickListener {
            val show = extraStreamsPanel.visibility != View.VISIBLE
            extraStreamsPanel.visibility = if (show) View.VISIBLE else View.GONE
            extrasButton.text = tr(
                if (show) "Additional volumes  ⌃" else "Additional volumes  ⌄",
                if (show) "Дополнительные уровни  ⌃" else "Дополнительные уровни  ⌄",
                if (show) "Extra volumes  ⌃" else "Extra volumes  ⌄",
                if (show) "Volúmenes adicionales  ⌃" else "Volúmenes adicionales  ⌄"
            )
        }
        panel.addView(extrasButton, margin(bottom = 5))
        panel.addView(extraStreamsPanel, margin(bottom = 6))
        panel.addView(pill(tr("Per-app volume", "Громкость приложений", "Volume per app", "Volumen por app"), cardBg2, white) {
            openPerAppVolumeMixer()
        }.apply { minHeight = dp(44) }, margin(bottom = 6))
        panel.addView(pill(tr("DONE", "ГОТОВО", "KLAAR", "LISTO"), mint, bg) { dialog.dismiss() })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(panel); setBackgroundColor(Color.TRANSPARENT) }
        dialog.setContentView(scroll)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(620)), (resources.displayMetrics.heightPixels * 0.88f).toInt())
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /** Set by the open Sound dialog: turns per-screen volume on when a screen slider is moved. */
    private var screenAudioEnableRequest: (() -> Unit)? = null

    private fun setScreenAudioEnabled(enabled: Boolean) {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        try {
            if (enabled) ScreenAudioVolumeService.enable(this) else ScreenAudioVolumeService.disable(this)
        } catch (error: Exception) {
            prefs.edit().putBoolean(ScreenAudioVolumeService.KEY_ENABLED, false).apply()
            Toast.makeText(this, error.message ?: tr("Could not update sound control.", "Не удалось изменить управление звуком.", "Geluidsbediening wijzigen mislukt.", "No se pudo cambiar el control de audio."), Toast.LENGTH_LONG).show()
        }
    }

    /** Human-readable line for ScreenAudioVolumeService.KEY_STATUS. */
    private fun screenAudioStatusText(enabled: Boolean, status: String): String {
        if (!enabled) return tr("Off: the screen sliders do nothing, media volume is used.",
            "Выключено: ползунки экранов не действуют, работает общая громкость.",
            "Uit: de schermschuiven doen niets, mediavolume wordt gebruikt.",
            "Apagado: los controles de pantalla no actúan, se usa el volumen multimedia.")
        val parts = status.split("|")
        return when (parts.firstOrNull()) {
            "ok" -> {
                val upper = parts.getOrNull(1).orEmpty()
                val lower = parts.getOrNull(2).orEmpty()
                val failed = parts.getOrNull(3).orEmpty()
                val unassigned = parts.getOrNull(4)?.toIntOrNull() ?: 0
                val lines = mutableListOf<String>()
                if (upper.isEmpty() && lower.isEmpty() && failed.isEmpty() && unassigned == 0)
                    lines += tr("Working. Nothing is playing right now.", "Работает. Сейчас ничего не играет.", "Actief. Er speelt nu niets.", "Activo. No suena nada ahora.")
                if (upper.isNotEmpty()) lines += tr("Upper", "Верхний", "Boven", "Superior") + ": " + upper
                if (lower.isNotEmpty()) lines += tr("Lower", "Нижний", "Onder", "Inferior") + ": " + lower
                if (failed.isNotEmpty()) lines += "⚠ " + tr("Volume cannot be changed for", "Не удаётся менять громкость у", "Volume kan niet worden gewijzigd voor", "No se puede cambiar el volumen de") + ": " + failed
                if (unassigned > 0) lines += "⚠ " + tr("Sound with unknown screen", "Звук без определённого экрана", "Geluid zonder bekend scherm", "Sonido sin pantalla conocida") + ": $unassigned"
                lines.joinToString("\n")
            }
            "root_failed" -> "⚠ " + tr("Root helper did not start", "Root-помощник не запустился", "Root-hulp start niet", "El asistente root no arrancó") + ": " + parts.drop(1).joinToString("|")
            "scan_failed" -> "⚠ " + tr("Audio scan failed", "Ошибка опроса звука", "Geluidsscan mislukt", "Fallo al leer el audio") + ": " + parts.drop(1).joinToString("|")
            else -> tr("Starting…", "Запуск…", "Starten…", "Iniciando…")
        }
    }

    private fun screenAudioControl(title: String, preferenceKey: String): LinearLayout {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val initial = prefs.getInt(preferenceKey, 100).coerceIn(0, 100)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(1))
            background = roundedBackground(cardBg2, border, 10)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title, 11f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("$initial%", 11f, mint, true)
        head.addView(value)
        row.addView(head)
        row.addView(SeekBar(this).apply {
            max = 100
            progress = initial
            minHeight = dp(62)
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            styleTouchSlider(this, mint, thumbDp = 28, minHeightDp = 62)
            enableControllerFocus(this, 7)
            var pending: Runnable? = null
            fun save(progress: Int) {
                prefs.edit().putInt(preferenceKey, progress.coerceIn(0, 100)).apply()
                screenAudioEnableRequest?.invoke()
                if (prefs.getBoolean(ScreenAudioVolumeService.KEY_ENABLED, false)) {
                    try { ScreenAudioVolumeService.gainChanged(this@MainActivity, preferenceKey) } catch (_: Exception) { }
                }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "$progress%"
                    if (fromUser) {
                        pending?.let { uiHandler.removeCallbacks(it) }
                        val task = Runnable { save(progress) }
                        pending = task
                        uiHandler.postDelayed(task, 80L)
                    }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    pending?.let { uiHandler.removeCallbacks(it) }
                    save(seekBar.progress)
                }
            })
        })
        return row
    }

    private fun audioStreamControl(audio: AudioManager, stream: Int): LinearLayout {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val current = audio.getStreamVolume(stream).coerceIn(0, max)
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(3))
            background = roundedBackground(cardBg, border, 10)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(localizedStreamName(stream), 11f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("${current * 100 / max}%", 11f, mint, true)
        head.addView(value)
        item.addView(head)
        item.addView(SeekBar(this).apply {
            this.max = max; progress = current; minHeight = dp(68)
            enableControllerFocus(this, 7)
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            styleTouchSlider(this, mint, thumbDp = 30, minHeightDp = 68)
            var pending: Runnable? = null
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "${progress * 100 / max}%"
                    if (fromUser) {
                        pending?.let { uiHandler.removeCallbacks(it) }
                        val task = Runnable { try { audio.setStreamVolume(stream, progress, 0) } catch (_: Exception) { } }
                        pending = task; uiHandler.postDelayed(task, 60L)
                    }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    pending?.let { uiHandler.removeCallbacks(it) }
                    try { audio.setStreamVolume(stream, seekBar.progress, 0) } catch (_: Exception) { }
                }
            })
        })
        return item
    }

    private fun batterySummary(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = latestBatteryIntent ?: registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra("temperature", -1) ?: -1
        val voltageMv = intent?.getIntExtra("voltage", -1) ?: -1
        val status = intent?.getIntExtra("status", -1) ?: -1
        val plugged = intent?.getIntExtra("plugged", 0) ?: 0
        // Use the physical power connection as the primary truth. Some firmware keeps
        // BATTERY_STATUS_CHARGING for a short time after the cable has been removed.
        val charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        val discharging = plugged == 0 && status != BatteryManager.BATTERY_STATUS_UNKNOWN
        // Sample power/status every live refresh. OEM kernels often report power in µW,
        // current in µA, voltage in µV; ignore implausible values instead of showing 95 W.
        val raw = rootRead("""
            for d in /sys/class/power_supply/*; do
              [ -r "${'$'}d/type" ] || continue
              [ "${'$'}(cat "${'$'}d/type" 2>/dev/null)" = Battery ] || continue
              p="${'$'}(cat "${'$'}d/power_now" 2>/dev/null)"
              c="${'$'}(cat "${'$'}d/current_now" 2>/dev/null)"
              v="${'$'}(cat "${'$'}d/voltage_now" 2>/dev/null)"
              q="${'$'}(cat "${'$'}d/charge_now" 2>/dev/null)"
              e="${'$'}(cat "${'$'}d/energy_now" 2>/dev/null)"
              qf="${'$'}(cat "${'$'}d/charge_full" 2>/dev/null)"
              ef="${'$'}(cat "${'$'}d/energy_full" 2>/dev/null)"
              echo "${'$'}p|${'$'}c|${'$'}v|${'$'}q|${'$'}e|${'$'}qf|${'$'}ef"
              exit 0
            done
        """.trimIndent())
        val fields = raw?.lineSequence()?.firstOrNull()?.split('|') ?: emptyList()
        val directPowerUw = fields.getOrNull(0)?.toLongOrNull()
        val currentUa = fields.getOrNull(1)?.toLongOrNull()
        val voltageUv = fields.getOrNull(2)?.toLongOrNull() ?: voltageMv.toLong().takeIf { it > 0L }?.times(1000L)
        val chargeNowUah = fields.getOrNull(3)?.toLongOrNull()
        val energyNowUwh = fields.getOrNull(4)?.toLongOrNull()
        val chargeFullUah = fields.getOrNull(5)?.toLongOrNull()
        val energyFullUwh = fields.getOrNull(6)?.toLongOrNull()
        fun plausiblePower(value: Double): Double? = value.takeIf { it.isFinite() && it >= 0.05 && it <= 35.0 }
        val directW = directPowerUw?.let { kotlin.math.abs(it.toDouble()) / 1_000_000.0 } ?: Double.NaN
        val calculatedW = if (currentUa != null && voltageUv != null && voltageUv > 0L) {
            kotlin.math.abs(currentUa.toDouble() * voltageUv.toDouble()) / 1_000_000_000_000.0
        } else Double.NaN
        val powerW = plausiblePower(directW) ?: plausiblePower(calculatedW)
        val powerText = powerW?.let {
            val sign = when { charging && status != BatteryManager.BATTERY_STATUS_FULL -> "+"; discharging -> "−"; else -> "" }
            sign + String.format(Locale.US, "%.1f W", it)
        } ?: "— W"

        val now = SystemClock.elapsedRealtime()
        val stateChanged = status != lastBatteryRuntimeStatus || plugged != lastBatteryRuntimePlugged
        if (stateChanged) { batteryPowerAvgW = null; batteryStateSinceMs = now }
        // Smooth the power draw so a short spike doesn't swing the estimate.
        if (powerW != null) batteryPowerAvgW = batteryPowerAvgW?.let { it * 0.75 + powerW * 0.25 } ?: powerW
        // Quick first estimate, then refine: every 5 s while there is no number yet,
        // every 15 s for the first 3 minutes, then once a minute.
        val sinceState = now - batteryStateSinceMs
        val refreshMs = when {
            lastBatteryRuntimeText == "—" || lastBatteryRuntimeText.endsWith("…") -> 5_000L
            sinceState < 180_000L -> 15_000L
            else -> 60_000L
        }
        val updateRuntime = lastBatteryRuntimeSampleElapsedMs == 0L || stateChanged ||
            now - lastBatteryRuntimeSampleElapsedMs >= refreshMs
        if (updateRuntime) {
            var remainingWh = energyNowUwh?.takeIf { it > 0L }?.toDouble()?.div(1_000_000.0)?.takeIf { it in 1.0..120.0 }
            if (remainingWh == null && chargeNowUah != null && chargeNowUah > 0L && voltageUv != null && voltageUv > 0L) {
                remainingWh = (chargeNowUah.toDouble() * voltageUv.toDouble() / 1_000_000_000_000.0).takeIf { it in 1.0..120.0 }
            }
            if (remainingWh == null) {
                val energyCounterNwh = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) } catch (_: Exception) { 0L }
                remainingWh = (energyCounterNwh.toDouble() / 1_000_000_000.0).takeIf { energyCounterNwh > 0L && it in 1.0..120.0 }
            }
            if (remainingWh == null && voltageUv != null && voltageUv > 0L) {
                // Charge counter (µAh) is reported by almost every kernel.
                val chargeCounterUah = try { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (_: Exception) { 0L }
                remainingWh = (chargeCounterUah.toDouble() * voltageUv.toDouble() / 1_000_000_000_000.0).takeIf { chargeCounterUah > 0L && it in 1.0..120.0 }
            }
            val fullWh = energyFullUwh?.takeIf { it > 0L }?.toDouble()?.div(1_000_000.0)
                ?: if (chargeFullUah != null && chargeFullUah > 0L && voltageUv != null && voltageUv > 0L)
                    chargeFullUah.toDouble() * voltageUv.toDouble() / 1_000_000_000_000.0 else null
                ?: if (remainingWh != null && level in 1..99) remainingWh * 100.0 / level else null
            val powerEstimate = (batteryPowerAvgW ?: powerW)?.takeIf { it >= 0.3 }
            var etaMinutes: Int? = if (powerEstimate != null && remainingWh != null) {
                when {
                    charging && fullWh != null -> (((fullWh - remainingWh).coerceAtLeast(0.0) / (powerEstimate * 0.82)) * 60).toInt().coerceIn(0, 24 * 60)
                    discharging -> ((remainingWh / powerEstimate) * 60).toInt().coerceIn(1, 24 * 60)
                    else -> null
                }
            } else null
            // Fallback for kernels that hide charge/energy counters: estimate rate from
            // the battery percentage trend over at least two minutes.
            val previousLevel = lastBatteryRuntimeLevel
            val previousAt = lastBatteryRuntimeLevelElapsedMs
            if (etaMinutes == null && previousLevel != null && level in 0..100 && previousAt > 0L) {
                val elapsedMinutes = (now - previousAt) / 60_000.0
                val delta = level - previousLevel
                if (elapsedMinutes >= 1.0 && ((charging && delta > 0) || (discharging && delta < 0))) {
                    val ratePerMinute = kotlin.math.abs(delta) / elapsedMinutes
                    etaMinutes = if (charging) ((100 - level) / ratePerMinute).toInt().coerceIn(0, 24 * 60)
                        else (level / ratePerMinute).toInt().coerceIn(1, 24 * 60)
                }
            }
            lastBatteryRuntimeText = when {
                charging && (status == BatteryManager.BATTERY_STATUS_FULL || level >= 100) -> tr("Fully charged", "Полностью заряжена", "Volledig opgeladen", "Carga completa")
                etaMinutes != null && charging -> tr("~${formatDuration(etaMinutes!!)} to full", "≈ ${formatDuration(etaMinutes!!)} до полного заряда", "~${formatDuration(etaMinutes!!)} tot vol", "~${formatDuration(etaMinutes!!)} hasta llena")
                etaMinutes != null && discharging -> tr("~${formatDuration(etaMinutes!!)} left", "≈ ${formatDuration(etaMinutes!!)} осталось", "~${formatDuration(etaMinutes!!)} over", "~${formatDuration(etaMinutes!!)} restantes")
                charging -> tr("Calculating…", "Расчёт…", "Berekenen…", "Calculando…")
                discharging -> tr("Calculating…", "Расчёт…", "Berekenen…", "Calculando…")
                else -> "—"
            }
            if (level in 0..100 && lastBatteryRuntimeLevel == null) {
                lastBatteryRuntimeLevel = level
                lastBatteryRuntimeLevelElapsedMs = now
            } else if (level in 0..100 && lastBatteryRuntimeLevel != level &&
                ((charging && level > (lastBatteryRuntimeLevel ?: level)) || (discharging && level < (lastBatteryRuntimeLevel ?: level)))) {
                // Keep the previous percentage as the trend baseline until it changes;
                // sampling every minute would otherwise make a 1% step look too fast.
                lastBatteryRuntimeLevel = level
                lastBatteryRuntimeLevelElapsedMs = now
            }
            lastBatteryRuntimeSampleElapsedMs = now
            lastBatteryRuntimeStatus = status
            lastBatteryRuntimePlugged = plugged
        }
        val statusText = when {
            charging -> tr("Charging", "Заряжается", "Opladen", "Cargando")
            discharging -> tr("On battery", "От батареи", "Op accu", "Con batería")
            else -> tr("Battery", "Батарея", "Accu", "Batería")
        }
        val detail = "$statusText · ${if (voltageMv > 0) String.format(Locale.US, "%.2f V", voltageMv / 1000f) else "— V"} · ${if (temp >= 0) String.format(Locale.US, "%.1f°C", temp / 10f) else "—°C"}"
        return "${if (level >= 0) "$level%" else "—"}||$detail||$powerText||$lastBatteryRuntimeText"
    }

    private fun formatDuration(totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun processorInfo(which: String): String {
        val freq = rootMetric(which)
        val raw = rootRead("""for z in /sys/class/thermal/thermal_zone*; do [ -r "${'$'}z/type" ] && [ -r "${'$'}z/temp" ] || continue; echo "${'$'}(cat "${'$'}z/type")|${'$'}(cat "${'$'}z/temp")"; done""")
        val pairs = raw.orEmpty().lineSequence().mapNotNull { line ->
            val a = line.split('|', limit = 2); if (a.size != 2) null else a[0].lowercase(Locale.ROOT) to (a[1].toDoubleOrNull() ?: return@mapNotNull null)
        }
        val match = if (which == "cpu") pairs.firstOrNull { (n, _) -> n.contains("cpu") || n.contains("cluster") || n.contains("silver") || n.contains("gold") } else pairs.firstOrNull { (n, _) -> n.contains("gpu") || n.contains("kgsl") || n.contains("graphics") }
        val temp = match?.second?.let { if (it > 1000) it / 1000.0 else it }
        return "$freq\n${temp?.let { String.format(Locale.US, "%.1f°C", it) } ?: "Temp —"}"
    }

    private fun batteryCard(): LinearLayout {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = latestBatteryIntent ?: registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage = intent?.getIntExtra("voltage", -1) ?: -1
        val temp = intent?.getIntExtra("temperature", -1) ?: -1
        val status = intent?.getIntExtra("status", -1) ?: -1
        val plugged = intent?.getIntExtra("plugged", 0) ?: 0
        val charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        return card().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            addView(label("🔋", 18f, mint, true), LinearLayout.LayoutParams(dp(25), -2))
            val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            info.addView(label(tr("BATTERY", "БАТАРЕЯ", "ACCU", "BATERÍA"), 10f, muted, true))
            val details = label("${if (charging) tr("Charging", "Заряжается", "Opladen", "Cargando") else tr("On battery", "От батареи", "Op accu", "Con batería")} · ${if (voltage > 0) String.format(Locale.US, "%.2f V", voltage / 1000f) else "— V"} · ${if (temp >= 0) String.format(Locale.US, "%.1f°C", temp / 10f) else "—°C"}", 9f, muted).apply {
                setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            batteryDetailValueView = details
            info.addView(details)
            val runtime = label("—", 11f, cyan, true).apply { maxLines = 1; gravity = Gravity.START; setPadding(0, dp(3), 0, 0) }
            batteryRuntimeValueView = runtime
            info.addView(runtime)
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val powerColumn = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            // Title and value share the column width and are both centred, so "POWER" sits
            // straight above the watts whatever their length.
            powerColumn.addView(label(tr("POWER", "МОЩНОСТЬ", "VERMOGEN", "POTENCIA"), 9f, muted, true).apply { gravity = Gravity.CENTER; maxLines = 1 },
                LinearLayout.LayoutParams(-1, -2))
            val power = label("— W", 13f, mint, true).apply { maxLines = 1; gravity = Gravity.CENTER }
            batteryPowerValueView = power
            powerColumn.addView(power, LinearLayout.LayoutParams(-1, -2))
            addView(powerColumn, LinearLayout.LayoutParams(dp(64), -2).apply { marginStart = dp(3) })
            val ring = MetricRingView(this@MainActivity, mint).apply { setPercent(level.takeIf { it >= 0 }) }
            batteryRingView = ring
            addView(ring, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(3) })
        }
    }

    private fun allUsableDisplays(): List<android.view.Display> {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.filter { it.supportedModes.isNotEmpty() }
    }

    /**
     * Display.getType()/TYPE_INTERNAL are not part of the public Android SDK on all
     * compile SDKs. The default display (ID 0) is the stable public identifier for
     * the primary/main panel; use it when changing the main panel's refresh rate.
     */
    private fun primaryDisplay(): android.view.Display? {
        val displays = allUsableDisplays()
        return displays.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY }
            ?: displays.maxByOrNull { displayArea(it) }
    }

    private fun displayArea(display: android.view.Display): Long {
        val mode = display.mode
        return mode.physicalWidth.toLong() * mode.physicalHeight.toLong()
    }

    /** The refresh tile controls Android's default/main panel even while the app runs on the small panel. */
    private fun refreshControlDisplay(): android.view.Display? = primaryDisplay()

    private fun currentActivityDisplayId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
    }

    private fun showRefreshRateDialog() {
        val panelDisplay = refreshControlDisplay()
        if (panelDisplay == null) {
            Toast.makeText(this, tr("Could not identify the main display.", "Не удалось определить основной экран", "Kan het hoofdscherm niet bepalen."), Toast.LENGTH_LONG).show()
            return
        }
        val active = panelDisplay.mode
        val targetRates = listOf(165, 144, 120, 90, 60)
        // Only offer rates exposed by Android at the primary panel's active resolution.
        val modesByRate = targetRates.mapNotNull { target ->
            val candidates = panelDisplay.supportedModes.filter { kotlin.math.abs(it.refreshRate - target) < 1.5f }
            val sameResolution = candidates.firstOrNull {
                it.physicalWidth == active.physicalWidth && it.physicalHeight == active.physicalHeight
            }
            sameResolution?.let { target to it }
        }.distinctBy { it.first }

        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(cardBg2, bg)).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), border)
            }
        }
        panel.addView(label(tr("DISPLAY RATE", "ЧАСТОТА ЭКРАНА", "SCHERMFREQUENTIE"), 10f, mint, true))
        panel.addView(label(tr("Main display", "Основной экран", "Hoofdscherm"), 17f, white, true).apply { setPadding(0, dp(2), 0, dp(2)) })
        panel.addView(label(tr("Current: ", "Сейчас: ", "Huidig: ") + "${panelDisplay.refreshRate.toInt()} Hz", 12f, muted).apply {
            setPadding(0, 0, 0, dp(6))
        })

        if (modesByRate.isEmpty()) {
            panel.addView(label(tr("Firmware did not expose 165/144/120/90/60 Hz modes at the current main-display resolution.", "Прошивка не предоставила частоты 165/144/120/90/60 Hz для текущего разрешения основного экрана.", "De firmware biedt geen 165/144/120/90/60 Hz-modi bij de huidige resolutie van het hoofdscherm."), 13f, muted).apply {
                setPadding(0, 0, 0, dp(12))
            })
        } else {
            modesByRate.chunked(2).forEach { rowModes ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowModes.forEach { (rate, _) ->
                    val selected = kotlin.math.abs(panelDisplay.refreshRate - rate) < 1.5f
                    row.addView(pill("$rate Hz", if (selected) mint else cardBg2, if (selected) bg else white) {
                        applySystemRefreshRate(rate, dialog)
                    }.apply {
                        textSize = 16f
                        minHeight = dp(46)
                    }, weight(1f, start = 2, end = 2))
                }
                if (rowModes.size == 1) row.addView(View(this).apply { alpha = 0f }, weight(1f, start = 2, end = 2))
                panel.addView(row, margin(bottom = 4))
            }
        }
        // Back/B closes the chooser; removing a footer keeps the rate choices compact.
        dialog.setContentView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost(dp(430))
        val dialogMaxHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        val rows = modesByRate.chunked(2).size.coerceAtLeast(1)
        val compactHeight = dp(82 + rows * 52).coerceAtMost(dialogMaxHeight)
        dialog.window?.setLayout(dialogWidth, compactHeight)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun applySystemRefreshRate(rate: Int, dialog: android.app.Dialog) {
        val targetDisplay = refreshControlDisplay()
        if (targetDisplay == null) {
            Toast.makeText(this, tr("Main display is unavailable.", "Основной экран недоступен.", "Hoofdscherm is niet beschikbaar."), Toast.LENGTH_LONG).show()
            return
        }
        val mode = targetDisplay.supportedModes.firstOrNull {
            kotlin.math.abs(it.refreshRate - rate) < 1.5f &&
                it.physicalWidth == targetDisplay.mode.physicalWidth && it.physicalHeight == targetDisplay.mode.physicalHeight
        }
        if (mode == null) {
            Toast.makeText(this, tr("This refresh rate is not supported at the current resolution.", "Эта частота не поддерживается при текущем разрешении.", "Deze vernieuwingsfrequentie wordt niet ondersteund bij de huidige resolutie."), Toast.LENGTH_LONG).show()
            return
        }
        val width = mode.physicalWidth
        val height = mode.physicalHeight
        val displayId = targetDisplay.displayId
        Toast.makeText(this, tr("Requesting system display mode…", "Запрашиваю системный режим экрана…", "Systeemschermmodus aanvragen…"), Toast.LENGTH_SHORT).show()
        Thread {
            // Use DisplayManagerService's preferred-mode API first. Unlike writing only the
            // min/peak settings, this asks SurfaceFlinger/DisplayManager for the target mode
            // on the main display (ID 0) while this app can run on the secondary panel.
            val command = "cmd display set-user-preferred-display-mode $width $height ${rate}.0 $displayId 2>&1; " +
                "echo __PREFERRED__; cmd display get-user-preferred-display-mode $displayId 2>&1; " +
                "echo __ACTIVE__; dumpsys display 2>/dev/null | grep -m 1 -E 'mActiveModeId|activeModeId'"
            val result = rootRead(command).orEmpty()
            val preferredConfirmed = result.lineSequence().any { line ->
                line.contains("$width $height") && line.contains(rate.toString()) && !line.contains("Error", true)
            }
            // Keep the Android refresh-rate constraints in sync with the preferred mode.
            // This can help vendor builds whose DisplayModeDirector still consults min/peak.
            val fallback = rootRead(
                "settings put system min_refresh_rate ${rate}.0 2>/dev/null; " +
                "settings put system peak_refresh_rate ${rate}.0 2>/dev/null; " +
                "echo MIN=\$(settings get system min_refresh_rate 2>/dev/null); " +
                "echo PEAK=\$(settings get system peak_refresh_rate 2>/dev/null)"
            ).orEmpty()
            val minValue = Regex("MIN=([0-9.]+)").find(fallback)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val peakValue = Regex("PEAK=([0-9.]+)").find(fallback)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            val fallbackAccepted = minValue != null && peakValue != null &&
                kotlin.math.abs(minValue - rate) < 0.1f && kotlin.math.abs(peakValue - rate) < 0.1f
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!preferredConfirmed && !fallbackAccepted) {
                    Toast.makeText(this, tr("System rejected the refresh-rate request.", "Система отклонила запрос частоты.", "Het systeem heeft de frequentieaanvraag geweigerd.") + " " + result.take(160), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                displayValueView?.text = displayHz() // do not display a requested value as if it were active
                dialog.dismiss()
                refreshHandler.postDelayed({
                    val actual = refreshControlDisplay()?.refreshRate
                    displayValueView?.text = displayHz()
                    if (actual != null && kotlin.math.abs(actual - rate) < 1.5f) {
                        Toast.makeText(this, tr("Main display is now ", "Основной экран теперь ", "Hoofdscherm staat nu op ") + "${actual.toInt()} Hz", Toast.LENGTH_LONG).show()
                    } else {
                        val actualText = actual?.let { "${it.toInt()} Hz" } ?: "—"
                        Toast.makeText(this,
                            tr("Requested ", "Запрошено ", "Aangevraagd ") + "$rate Hz, " +
                                tr("but the main display is still ", "но основной экран по-прежнему ", "maar het hoofdscherm staat nog op ") + "$actualText. " +
                                tr("The firmware may be overriding the preferred mode.", "Возможно, прошивка переопределяет режим.", "De firmware overschrijft mogelijk de voorkeursmodus."),
                            Toast.LENGTH_LONG).show()
                    }
                }, 1800L)
            }
        }.start()
    }

    /** Try to relaunch this task on the panel with the smallest native pixel area. */
    private fun redirectToSmallestDisplayIfPossible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            intent.getBooleanExtra(EXTRA_DISPLAY_REDIRECTED, false)) return false
        val displays = allUsableDisplays()
        if (displays.size < 2) return false
        // Display.getType() is not available in the public SDK on every compile setup.
        // On Pocket DS, choose the smallest usable display by native pixel area; avoid
        // relying on the hidden TYPE_INTERNAL API so the project compiles with public SDKs.
        val target = displays.minByOrNull { displayArea(it) } ?: return false
        val here = currentActivityDisplayId()
        if (target.displayId == here) return false

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            Toast.makeText(this, tr("This firmware does not allow automatic launch on another display", "Эта прошивка не разрешает автоматический запуск на другом дисплее", "Deze firmware staat automatisch starten op een ander scherm niet toe"), Toast.LENGTH_LONG).show()
            return false
        }
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_DISPLAY_REDIRECTED, true)
            putExtra(EXTRA_TARGET_DISPLAY_ID, target.displayId)
        }
        // Avoid ActivityManager.isActivityStartAllowedOnDisplay here: it is not exposed
        // by all compile SDK stubs. startActivity() below is the authoritative check;
        // OEM restrictions are handled by the surrounding try/catch.
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
            startActivity(launch, options.toBundle())
            finish()
            true
        } catch (e: Exception) {
            Toast.makeText(this, tr("Could not move the app to the small display: ", "Не удалось перенести приложение на маленький экран: ", "Kan de app niet naar het kleine scherm verplaatsen: ") + (e.message ?: tr("Android restriction", "ограничение Android", "Android-beperking")), Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun showFanCurveEditor() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val localCurve = parseFanCurve(prefs.getString("fan_curve_local", null)) ?: defaultFanCurve.map { it.copy() }
        val points = localCurve.map { it.copy() }.toMutableList()
        var selectedPoint = 2
        var userEdited = false
        var configReadFinished = false
        var oemPath: String? = null
        var oemFormatSupported = false

        val dialog = GuardedDialog()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(cardBg, border, 22)
        }
        panel.addView(label("НАСТРОЙКА КРИВОЙ ВЕНТИЛЯТОРА", 12f, mint, true), margin(bottom = 3))
        panel.addView(label("Температура CPU → скорость вентилятора", 16f, white, true), margin(bottom = 4))
        val configStatus = label("Читаю конфигурацию OEM…", 10f, muted).apply {
            maxLines = 2
            setPadding(0, 0, 0, dp(4))
        }
        panel.addView(configStatus)

        val graph = FanCurveGraphView(this).apply {
            temperatures = points.map { it.temperature }.toIntArray()
            speeds = points.map { it.speedPercent }.toIntArray()
            selected = selectedPoint
            contentDescription = "График температуры процессора и скорости вентилятора"
        }
        panel.addView(graph, LinearLayout.LayoutParams(-1, dp(145)).apply { bottomMargin = dp(4) })

        var updateEditorWidgets: () -> Unit = {}
        val pointScroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val pointRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pointButtons = mutableListOf<TextView>()
        repeat(points.size) { index ->
            val button = pill("P${index + 1}", cardBg2, white) {
                selectedPoint = index
                updateEditorWidgets()
            }.apply { minWidth = dp(54); minHeight = dp(38) }
            pointButtons.add(button)
            pointRow.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(2); marginEnd = dp(4)
            })
        }
        pointScroller.addView(pointRow)
        panel.addView(pointScroller, margin(bottom = 5))

        val tempHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tempHeader.addView(label("Температура", 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val tempValue = label("${points[selectedPoint].temperature}°C", 12f, mint, true)
        tempHeader.addView(tempValue)
        panel.addView(tempHeader)
        val tempSlider = SeekBar(this).apply {
            max = 70
            minHeight = dp(58)
            progressTintList = ColorStateList.valueOf(mint)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            styleTouchSlider(this, mint, thumbDp = 28, minHeightDp = 60)
        }
        panel.addView(tempSlider, margin(bottom = 3))

        val speedHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedHeader.addView(label("Скорость вентилятора", 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val speedValue = label("${points[selectedPoint].speedPercent}%", 12f, cyan, true)
        speedHeader.addView(speedValue)
        panel.addView(speedHeader)
        val speedSlider = SeekBar(this).apply {
            max = 100
            minHeight = dp(58)
            progressTintList = ColorStateList.valueOf(cyan)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 61, 70))
            styleTouchSlider(this, cyan, thumbDp = 28, minHeightDp = 60)
        }
        panel.addView(speedSlider, margin(bottom = 4))
        panel.addView(label("Нажми P1–P6, чтобы выбрать точку. Температуры и скорости сохраняются по возрастанию; от 90°C целевая скорость не ниже 80%, конечная точка — 100°C / 100%.", 10f, muted).apply {
            setPadding(0, dp(1), 0, dp(6))
        })

        fun serialized(): String = serializeFanCurve(points)
        fun setCurveToDefaults() {
            points.clear()
            points.addAll(defaultFanCurve.map { it.copy() })
            selectedPoint = 2
            userEdited = true
            updateEditorWidgets()
        }
        fun showEditorMessage(message: String, long: Boolean = true) {
            val localized = localize(message)
            configStatus.text = localized
            Toast.makeText(this, localized, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
        fun persistLocal() {
            prefs.edit().putString("fan_curve_local", serialized()).apply()
        }
        fun writeOemCurve(activate: Boolean) {
            if (!configReadFinished) {
                showEditorMessage("Подожди: конфигурация OEM ещё читается.")
                return
            }
            if (!oemFormatSupported || oemPath == null) {
                persistLocal()
                showEditorMessage("Сохранено в Overlay, но файл кривой OEM не доступен или имеет неизвестный формат. На вентилятор это не повлияло.")
                return
            }
            if (!isValidFanCurve(points)) {
                showEditorMessage("Кривая не прошла проверку безопасности. Температуры должны возрастать, скорость не должна снижаться, последняя точка — 100°C / 100%.")
                return
            }
            val value = serialized()
            persistLocal()
            configStatus.text = localize("Сохраняю OEM-кривую и проверяю запись…")
            Thread {
                val result = saveOemFanCurve(oemPath!!, value)
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (result) {
                        configStatus.text = localize("OEM-кривая записана. Резервная копия:") + " ${oemPath}.bak.overlay"
                        if (activate) {
                            val binderReady = oemAidlBound && oemServiceBinder != null &&
                                oemBinderDescriptor == OEM_AIDL_DESCRIPTOR && !oemClientId.isNullOrBlank()
                            if (binderReady) {
                                sendOemFanMode("FAN_MODE_CUSTOM")
                                Toast.makeText(this, localize("Кривая сохранена; отправляю FAN_MODE_CUSTOM."), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, localize("Кривая сохранена. Подключи OEM AIDL, затем нажми «Пользовательский» для активации."), Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(this, localize("Кривая записана в конфигурацию OEM."), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        configStatus.text = localize("Не удалось подтвердить запись OEM-файла. Локальная копия сохранена; режим вентилятора не менялся.")
                        Toast.makeText(this, localize("Запись OEM-кривой не подтверждена. Существующий файл не удаляй; пришли диагностику."), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        updateEditorWidgets = {
            val p = points[selectedPoint]
            graph.temperatures = points.map { it.temperature }.toIntArray()
            graph.speeds = points.map { it.speedPercent }.toIntArray()
            graph.selected = selectedPoint
            graph.invalidate()
            pointButtons.forEachIndexed { index, view ->
                val item = points[index]
                val active = index == selectedPoint
                view.text = "P${index + 1} · ${item.temperature}°"
                view.background = roundedBackground(if (active) mint else cardBg2, if (active) mint else border, 10)
                view.setTextColor(if (active) bg else white)
            }
            tempValue.text = "${p.temperature}°C"
            speedValue.text = "${p.speedPercent}%"
            tempSlider.setOnSeekBarChangeListener(null)
            tempSlider.progress = (p.temperature - 30).coerceIn(0, 70)
            tempSlider.isEnabled = selectedPoint != points.lastIndex
            speedSlider.setOnSeekBarChangeListener(null)
            speedSlider.progress = p.speedPercent.coerceIn(0, 100)
            speedSlider.isEnabled = selectedPoint != points.lastIndex
            tempSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || selectedPoint == points.lastIndex) return
                    val minTemp = if (selectedPoint == 0) 30 else points[selectedPoint - 1].temperature + 5
                    val maxTemp = minOf(points[selectedPoint + 1].temperature - 5, if (selectedPoint == 4) 90 else 100)
                    points[selectedPoint].temperature = (progress + 30).coerceIn(minTemp, maxTemp)
                    userEdited = true
                    val current = points[selectedPoint]
                    tempValue.text = "${current.temperature}°C"
                    updateEditorWidgets()
                }
            })
            speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val minSpeed = when {
                        selectedPoint == 0 -> 0
                        selectedPoint == 4 -> maxOf(points[selectedPoint - 1].speedPercent, 80)
                        else -> points[selectedPoint - 1].speedPercent
                    }
                    val maxSpeed = if (selectedPoint == points.lastIndex) 100 else points[selectedPoint + 1].speedPercent
                    points[selectedPoint].speedPercent = progress.coerceIn(minSpeed, maxSpeed)
                    if (selectedPoint == points.lastIndex) points[selectedPoint].speedPercent = 100
                    userEdited = true
                    speedValue.text = "${points[selectedPoint].speedPercent}%"
                    updateEditorWidgets()
                }
            })
        }
        updateEditorWidgets()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("СОХРАНИТЬ", cardBg2, white) { writeOemCurve(false) }, weight(1f, end = 3))
        actions.addView(pill("СОХРАНИТЬ + CUSTOM", mint, bg) { writeOemCurve(true) }, weight(1f, start = 3))
        panel.addView(actions, margin(top = 2, bottom = 5))
        val lowerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lowerActions.addView(pill("СБРОСИТЬ КРИВУЮ", cardBg2, cyan) { setCurveToDefaults() }, weight(1f, end = 3))
        lowerActions.addView(pill("ВОССТАНОВИТЬ КОПИЮ", cardBg2, white) {
            val target = oemPath
            if (!configReadFinished || target == null) {
                showEditorMessage("OEM-файл не загружен; восстановление недоступно.")
            } else {
                configStatus.text = "Восстанавливаю резервную копию…"
                Thread {
                    val restored = restoreOemFanCurve(target)
                    val restoredConfig = if (restored) readOemFanCurveConfig() else null
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                        val parsed = restoredConfig?.let { parseFanCurve(it.raw) }
                        if (parsed != null) {
                            points.clear(); points.addAll(parsed.map { it.copy() })
                            oemPath = restoredConfig.path
                            persistLocal()
                            updateEditorWidgets()
                            showEditorMessage("Резервная OEM-кривая восстановлена. Чтобы включить её, нажми «Сохранить + CUSTOM».")
                        } else {
                            showEditorMessage("Не удалось восстановить копию. Исходный файл не удаляй.")
                        }
                    }
                }.start()
            }
        }, weight(1f, start = 3))
        panel.addView(lowerActions, margin(bottom = 5))
        panel.addView(pill("ГОТОВО", mint, bg) { dialog.dismiss() })

        // The editor is intended to run on the small Pocket DS panel; keep it scrollable
        // so the curve graph and all save/restore actions remain reachable in landscape.
        val outerScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        outerScroll.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2))
        dialog.setContentView(outerScroll)
        prepareDialogNavigation(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(dp(760)),
            (resources.displayMetrics.heightPixels * 0.90f).toInt().coerceAtLeast(dp(320))
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Read the actual OEM curve as the source of truth. If its format is not recognized,
        // allow local editing but deliberately prevent a write into an unknown system format.
        Thread {
            val config = readOemFanCurveConfig()
            val parsed = config?.let { parseFanCurve(it.raw) }
            runOnUiThread {
                if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                configReadFinished = true
                if (config != null && parsed != null) {
                    oemPath = config.path
                    oemFormatSupported = true
                    configStatus.text = "OEM-кривая загружена: ${config.path.substringAfterLast('/')} · 6 точек распознано"
                    if (!userEdited) {
                        points.clear(); points.addAll(parsed.map { it.copy() })
                        updateEditorWidgets()
                    }
                } else {
                    oemFormatSupported = false
                    configStatus.text = if (config == null) {
                        "OEM-файл кривой не найден/не прочитан. Можно редактировать локально, но запись в прошивку отключена."
                    } else {
                        "Формат OEM-файла не распознан. Запись отключена, чтобы не повредить конфигурацию."
                    }
                }
            }
        }.start()
    }

    private fun serializeFanCurve(points: List<FanCurvePoint>): String =
        points.joinToString("|") { "${it.speedPercent},${it.temperature}" }

    private fun parseFanCurve(raw: String?): MutableList<FanCurvePoint>? {
        if (raw.isNullOrBlank()) return null
        val points = raw.trim().split('|').mapNotNull { item ->
            val pair = item.split(',')
            if (pair.size != 2) return@mapNotNull null
            val speed = pair[0].trim().toIntOrNull() ?: return@mapNotNull null
            val temp = pair[1].trim().toIntOrNull() ?: return@mapNotNull null
            FanCurvePoint(temp, speed)
        }
        if (points.size != 6 || !isValidFanCurve(points)) return null
        return points.toMutableList()
    }

    private fun isValidFanCurve(points: List<FanCurvePoint>): Boolean {
        if (points.size != 6) return false
        if (points.any { it.temperature !in 30..100 || it.speedPercent !in 0..100 }) return false
        if (points.last().temperature != 100 || points.last().speedPercent != 100) return false
        // Keep a strong-cooling safety point: at 90°C or lower, target at least 80% fan.
        if (points[4].temperature > 90 || points[4].speedPercent < 80) return false
        for (i in 1 until points.size) {
            if (points[i].temperature < points[i - 1].temperature + 5) return false
            if (points[i].speedPercent < points[i - 1].speedPercent) return false
        }
        return true
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun readOemFanCurveConfig(): FanCurveConfig? {
        val script = "for f in /data/media/0/.aya/aya_fan_mode_config_v5.conf /data/media/0/.aya/aya_fan_mode_config.conf; do " +
            "[ -f \"\$f\" ] || continue; printf 'PATH=%s\\n' \"\$f\"; printf 'VALUE='; cat \"\$f\"; printf '\\n'; exit 0; done; echo NO_CONFIG; exit 3"
        val wrapped = "su -g 1023 -G 10144 10144 -c ${shellQuote(script)}"
        val result = rootRead(wrapped).orEmpty()
        val path = Regex("(?m)^PATH=(/data/media/0/\\.aya/(?:aya_fan_mode_config_v5\\.conf|aya_fan_mode_config\\.conf))$").find(result)?.groupValues?.getOrNull(1)
        val raw = Regex("(?m)^VALUE=(.*)$").find(result)?.groupValues?.getOrNull(1)
        if (path == null || raw == null) return null
        return FanCurveConfig(path, raw.trim())
    }

    private fun saveOemFanCurve(path: String, value: String): Boolean {
        if (path !in listOf("/data/media/0/.aya/aya_fan_mode_config_v5.conf", "/data/media/0/.aya/aya_fan_mode_config.conf")) return false
        val script = "p=${shellQuote(path)}; [ -f \"\$p\" ] || { echo ERR_NO_CONFIG; exit 2; }; " +
            "b=\"\$p.bak.overlay\"; [ -f \"\$b\" ] || cp \"\$p\" \"\$b\" || { echo ERR_BACKUP; exit 3; }; " +
            "printf '%s' ${shellQuote(value)} > \"\$p\" || { echo ERR_WRITE; exit 4; }; " +
            "got=\"\$(cat \"\$p\" 2>/dev/null)\"; [ \"\$got\" = ${shellQuote(value)} ] || { echo ERR_VERIFY; exit 5; }; " +
            "echo SAVED=1; echo PATH=\"\$p\"; echo VALUE=\"\$got\""
        val wrapped = "su -g 1023 -G 10144 10144 -c ${shellQuote(script)}"
        val result = rootRead(wrapped).orEmpty()
        return result.contains("SAVED=1") && result.contains("VALUE=$value")
    }

    private fun restoreOemFanCurve(path: String): Boolean {
        if (path !in listOf("/data/media/0/.aya/aya_fan_mode_config_v5.conf", "/data/media/0/.aya/aya_fan_mode_config.conf")) return false
        val script = "p=${shellQuote(path)}; b=\"\$p.bak.overlay\"; [ -f \"\$b\" ] || { echo ERR_NO_BACKUP; exit 2; }; " +
            "cp \"\$b\" \"\$p\" || { echo ERR_RESTORE; exit 3; }; echo RESTORED=1; printf 'VALUE='; cat \"\$p\"; printf '\\n'"
        val wrapped = "su -g 1023 -G 10144 10144 -c ${shellQuote(script)}"
        return rootRead(wrapped).orEmpty().contains("RESTORED=1")
    }

    private inner class FanCurveGraphView(context: Context) : View(context) {
        var temperatures: IntArray = intArrayOf(40, 55, 65, 75, 85, 100)
        var speeds: IntArray = intArrayOf(0, 10, 45, 50, 100, 100)
        var selected: Int = 2
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 70, 80); strokeWidth = dp(1).toFloat() }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mint; strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(35, Color.red(mint), Color.green(mint), Color.blue(mint)); style = Paint.Style.FILL }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mint; style = Paint.Style.FILL }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = sp(9f); typeface = Typeface.DEFAULT_BOLD }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(30).toFloat(); val right = width - dp(10).toFloat()
            val top = dp(10).toFloat(); val bottom = height - dp(22).toFloat()
            if (right <= left || bottom <= top) return
            fun x(temp: Int) = left + ((temp.coerceIn(30, 100) - 30) / 70f) * (right - left)
            fun y(speed: Int) = bottom - (speed.coerceIn(0, 100) / 100f) * (bottom - top)
            for (tick in intArrayOf(0, 25, 50, 75, 100)) {
                val yy = y(tick)
                canvas.drawLine(left, yy, right, yy, gridPaint)
                canvas.drawText("$tick%", 0f, yy + textPaint.textSize / 3f, textPaint)
            }
            for (tick in intArrayOf(40, 60, 80, 100)) {
                val xx = x(tick)
                canvas.drawLine(xx, top, xx, bottom, gridPaint)
                canvas.drawText("${tick}°", xx - dp(9), height - dp(4).toFloat(), textPaint)
            }
            if (temperatures.isEmpty() || temperatures.size != speeds.size) return
            val path = Path()
            val area = Path()
            temperatures.indices.forEach { i ->
                val px = x(temperatures[i]); val py = y(speeds[i])
                if (i == 0) { path.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py) }
                else { path.lineTo(px, py); area.lineTo(px, py) }
            }
            area.lineTo(x(temperatures.last()), bottom); area.close()
            canvas.drawPath(area, fillPaint)
            canvas.drawPath(path, linePaint)
            temperatures.indices.forEach { i ->
                val px = x(temperatures[i]); val py = y(speeds[i])
                if (i == selected) canvas.drawCircle(px, py, dp(7).toFloat(), selectedPaint)
                canvas.drawCircle(px, py, dp(4).toFloat(), dotPaint)
            }
        }
        private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
    }

    /**
     * Best-effort foreground game FPS for the default/main display. Android's public
     * Display API reports refresh rate, not another app's rendered FPS. On rooted builds,
     * SurfaceFlinger --latency exposes recent timestamps for a named layer.
     */
    private fun readMainDisplayFps(): Int? {
        return try {
            val windowDump = rootRead("dumpsys window displays 2>/dev/null") ?: return null
            val displayBlock = Regex("""(?s)Display: mDisplayId=0(.*?)(?=\n\s*Display: mDisplayId=|\z)""")
                .find(windowDump)?.groupValues?.getOrNull(1) ?: windowDump
            val focusLines = (displayBlock.lineSequence() + windowDump.lineSequence())
                .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
                .distinct()
                .toList()
            val packageName = focusLines.asSequence().mapNotNull { line ->
                Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
                    .find(line)?.groupValues?.getOrNull(1)
            }.firstOrNull { it != packageNameSelf() && it.contains('.') } ?: return null
            val layers = rootRead("dumpsys SurfaceFlinger --list 2>/dev/null")?.lineSequence()
                ?.filter { it.contains(packageName) && !it.contains("com.example.ayaneooverlay") }
                ?.toList().orEmpty()
            if (layers.isEmpty()) return null
            val preferred = layers.filter { it.contains("SurfaceView", true) || it.contains("BLAST", true) || it.contains("BufferQueue", true) }
                .ifEmpty { layers }
            preferred.take(5).mapNotNull { layer ->
                val escaped = layer.replace("'", "'\\''")
                val dump = rootRead("dumpsys SurfaceFlinger --latency '$escaped' 2>/dev/null") ?: return@mapNotNull null
                val timestamps = dump.lineSequence().drop(1).mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    val ts = columns.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                    if (ts <= 0L || ts >= Long.MAX_VALUE / 2) null else ts
                }.distinct().sorted().toList().takeLast(100)
                if (timestamps.size < 4) return@mapNotNull null
                val span = timestamps.last() - timestamps.first()
                if (span <= 0L) return@mapNotNull null
                val fps = (timestamps.size - 1) * 1_000_000_000.0 / span
                fps.takeIf { it in 1.0..240.0 }?.let { kotlin.math.round(it).toInt() }
            }.maxOrNull()
        } catch (_: Exception) { null }
    }

    private fun packageNameSelf(): String = packageName

    private fun displayInfo(): String {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.joinToString("\n\n") { d -> "Display ${d.displayId}: ${d.name}\n${d.mode.physicalWidth} × ${d.mode.physicalHeight} · ${d.refreshRate.toInt()} Hz" }
            .ifBlank { "Дисплеи не обнаружены" }
    }

    private fun displayHz(): String {
        return refreshControlDisplay()?.let { "${it.refreshRate.toInt()} Hz" } ?: "—"
    }

    private fun checkRoot(): Boolean = checkRootDetailed() == "granted"

    /**
     * Asks su for root. Magisk shows its "Grant root?" prompt for this call. Waits up to
     * 30 s for the user to answer; an unanswered prompt counts as "timeout".
     */
    private fun checkRootDetailed(): String = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            "timeout"
        } else {
            val out = process.inputStream.bufferedReader().readText()
            if (process.exitValue() == 0 && out.contains("uid=0")) "granted" else "denied"
        }
    } catch (_: java.io.IOException) {
        "no_su"
    } catch (_: Exception) {
        "denied"
    }

    private fun rootProblemText(status: String): String = when (status) {
        "no_su" -> tr("Root (su) was not found on this device. PocketHub needs a rooted device (Magisk).",
            "На устройстве не найден root (su). PocketHub нужен root через Magisk.",
            "Geen root (su) gevonden op dit apparaat. PocketHub heeft root nodig (Magisk).",
            "No se encontró root (su). PocketHub necesita root (Magisk).")
        "timeout" -> tr("Magisk did not answer the root request. Open Magisk → Superuser and allow PocketHub, or tap Ask again and answer the prompt.",
            "Magisk не ответил на запрос root. Открой Magisk → Суперпользователь и разреши PocketHub, или нажми «Запросить снова» и ответь на окно Magisk.",
            "Magisk gaf geen antwoord op het rootverzoek. Open Magisk → Superuser en sta PocketHub toe, of tik op Opnieuw vragen.",
            "Magisk no respondió. Abre Magisk → Superusuario y permite PocketHub, o toca Pedir de nuevo.")
        else -> tr("Root access was refused. Open Magisk → Superuser and turn PocketHub on. Also check Magisk settings: Automatic response must be \"Prompt\".",
            "Root-доступ не выдан. Открой Magisk → Суперпользователь и включи PocketHub. Также проверь настройки Magisk: «Автоматический ответ» должен быть «Запрос».",
            "Roottoegang geweigerd. Open Magisk → Superuser en zet PocketHub aan. Controleer ook: Automatisch antwoord moet \"Vragen\" zijn.",
            "Acceso root denegado. Abre Magisk → Superusuario y activa PocketHub. Revisa también: Respuesta automática debe ser \"Preguntar\".")
    }

    private fun openMagisk() {
        val packages = listOf("com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk", "me.weishu.kernelsu", "me.bmax.apatch")
        val intent = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else Toast.makeText(this, tr("Magisk app not found. It may be hidden under another name.", "Приложение Magisk не найдено. Возможно, оно скрыто под другим именем.",
            "Magisk-app niet gevonden. Mogelijk verborgen onder een andere naam.", "No se encontró Magisk. Puede estar oculto con otro nombre."), Toast.LENGTH_LONG).show()
    }

    /** Big warning at the top of the main page while PocketHub has no root. */
    private fun rootWarningBanner(): View? {
        if (rootState == "granted" || rootState == "checking") return null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(70, 18, 24), Color.rgb(255, 80, 90), 16)
        }
        box.addView(label("⚠  " + tr("NO ROOT ACCESS", "НЕТ ROOT-ДОСТУПА", "GEEN ROOTTOEGANG", "SIN ACCESO ROOT"), 13f, Color.rgb(255, 120, 128), true))
        box.addView(label(tr("Profiles, fan, Turbo, keyboard input, sound control and most other features do not work without root.",
            "Профили, вентилятор, Turbo, ввод с клавиатуры, звук и большинство других функций без root не работают.",
            "Profielen, ventilator, Turbo, toetsenbordinvoer, geluid en de meeste andere functies werken niet zonder root.",
            "Perfiles, ventilador, Turbo, teclado, sonido y casi todo lo demás no funcionan sin root."), 10f, Color.WHITE), margin(top = 3, bottom = 3))
        box.addView(label(rootProblemText(rootState), 9.5f, Color.rgb(255, 205, 205)), margin(bottom = 7))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(tr("ASK AGAIN", "ЗАПРОСИТЬ СНОВА", "OPNIEUW VRAGEN", "PEDIR DE NUEVO"), Color.rgb(255, 80, 90), Color.WHITE) {
            rootState = "checking"
            buildScreen()
            checkRootAccessAsync()
        }.apply { textSize = 11f; minHeight = dp(40) }, weight(1f, end = 3))
        buttons.addView(pill(tr("OPEN MAGISK", "ОТКРЫТЬ MAGISK", "MAGISK OPENEN", "ABRIR MAGISK"), cardBg2, white) { openMagisk() }
            .apply { textSize = 11f; minHeight = dp(40) }, weight(1f, start = 3))
        box.addView(buttons)
        return box
    }

    private fun showRootMissingDialog() {
        if (rootWarningDialogShown || isFinishing || isDestroyed) return
        rootWarningDialogShown = true
        showSimpleActionDialog("⚠  " + tr("PocketHub has no root access", "У PocketHub нет root-доступа", "PocketHub heeft geen roottoegang", "PocketHub no tiene acceso root") +
            "\n\n" + rootProblemText(rootState), listOf(
            tr("Ask for root again", "Запросить root снова", "Opnieuw om root vragen", "Pedir root de nuevo") to {
                rootState = "checking"; rootWarningDialogShown = false; buildScreen(); checkRootAccessAsync()
            },
            tr("Open Magisk", "Открыть Magisk", "Magisk openen", "Abrir Magisk") to { openMagisk() },
            tr("Continue without root", "Продолжить без root", "Doorgaan zonder root", "Continuar sin root") to { }
        ))
    }

    private fun checkRootAccessAsync() {
        if (rootProbeRunning) return
        rootProbeRunning = true
        Thread {
            val status = checkRootDetailed()
            val granted = status == "granted"
            val previousStatus = rootState
            rootState = status
            rootAccessGranted = granted
            if (granted) {
                loadCpuPolicyFrequencyStepsFromRoot()
                val touchpadEnabled = runRootCommand("settings get global secondary_screen_as_touchpad").second.trim() == "1"
                val pointerMode = runRootCommand("settings get global dual_display_pointer_mode").second.trim()
                val touchpadHardwareMode = runRootCommand("cat /sys/devices/platform/soc/ac0000.qcom,qupv3_1_geni_se/a88000.i2c/i2c-2/2-005d/touchpad_mode 2>/dev/null").second.trim()
                // Recover the known working touchpad path if a previous cursor toggle
                // left the lower touchscreen disconnected. Do not alter the user's
                // explicit choice to disable touchpad mode.
                if (touchpadEnabled && (pointerMode == "0" || touchpadHardwareMode != "1")) {
                    val (restored, output, _) = runRootCommand(
                        "settings put global dual_display_pointer_mode 2; echo 1 > /sys/devices/platform/soc/ac0000.qcom,qupv3_1_geni_se/a88000.i2c/i2c-2/2-005d/touchpad_mode; cat /sys/devices/platform/soc/ac0000.qcom,qupv3_1_geni_se/a88000.i2c/i2c-2/2-005d/touchpad_mode 2>/dev/null"
                    )
                    Log.i("PocketHubScreenInput", "Restored touchpad input=$restored mode=${output.trim()}")
                }
            }
            val startupGame = pendingStartupGameProfile
            val startupGameRunning = granted && startupGame != null && isPackageRunning(startupGame.removePrefix(GAME_PROFILE_PREFIX))
            runOnUiThread {
                rootProbeRunning = false
                refreshRootAccessBadge()
                // Show or remove the big warning on the main page when the answer changes.
                if (status != previousStatus && !advanced && !profileEditorPage && !helpPage && !controllerPage) buildScreen()
                if (!granted) showRootMissingDialog()
                if (granted) {
                    refreshLiveValues()
                    startTunedAutoSwitch()
                    if (controllerPage) refreshControllerSettings()
                    if (!startupProfileRestored && !isFinishing && !isDestroyed) {
                        startupProfileRestored = true
                        pendingStartupGameProfile = null
                        // Restore the last profile, but keep the last fan mode (which may be a
                        // manual override of that profile's default) instead of resetting it.
                        val savedFanMode = getPreferences(Context.MODE_PRIVATE).getString("fan_mode", null)
                        if (startupGame != null && startupGameRunning) {
                            // The game is still open: keep its profile and keep watching it.
                            applyPerformanceProfile(startupGame, fanModeOverride = savedFanMode)
                            startGameWatch(startupGame.removePrefix(GAME_PROFILE_PREFIX), initialDelayMs = GAME_WATCH_INTERVAL_MS)
                        } else {
                            // The game closed while PocketHub was away: undo its controller overrides too.
                            if (startupGame != null) { restoreControllerAfterGame(); restoreEqualizerAfterGame() }
                            applyPerformanceProfile(selectedProfile, fanModeOverride = savedFanMode)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun refreshRootAccessBadge() {
        val badge = rootAccessBadge ?: return
        badge.visibility = if (rootAccessGranted == false) View.VISIBLE else View.GONE
    }

    private fun rootRead(command: String): String? {
        if (rootAccessGranted != true) return null
        // Shared root shell first (one su for all reads); if it is busy or unavailable,
        // fall back to a separate su process exactly as before.
        RootShell.main.run(command)?.let { return it.output.trim().takeIf { out -> out.isNotBlank() } }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun runRootCommand(command: String): Triple<Boolean, String, Int> {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            Triple(exitCode == 0, output, exitCode)
        } catch (error: Exception) {
            Triple(false, error.message.orEmpty(), -1)
        }
    }

    private fun runAyaRootCommand(command: String): Triple<Boolean, String, Int> {
        return try {
            val process = ProcessBuilder("/product/bin/xsu", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            Triple(exitCode == 0, output, exitCode)
        } catch (error: Exception) {
            Triple(false, error.message.orEmpty(), -1)
        }
    }

    private fun rootMetric(which: String): String {
        val paths = if (which == "cpu") listOf("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq") else listOf("/sys/class/devfreq", "/sys/class/kgsl/kgsl-3d0/gpuclk")
        if (which == "gpu") {
            val v = rootRead("cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null")?.toLongOrNull()
            if (v != null && v > 0) return "${v / 1000000} MHz"
            val devfreq = rootRead("for f in /sys/class/devfreq/*/cur_freq; do [ -r \"\$f\" ] && echo \"\$(cat \"\$f\")\"; done | head -1")?.lineSequence()?.firstOrNull()?.toLongOrNull()
            return devfreq?.let { "${it / 1000000} MHz" } ?: "—"
        }
        for (p in paths) {
            val v = rootRead("cat $p 2>/dev/null")?.toLongOrNull()
            if (v != null && v > 0) return "${v / 1000} MHz"
        }
        return "—"
    }

    private fun frequencyInfo(which: String): String = rootMetric(which)

    private fun thermalInfo(): String {
        val raw = rootRead("for f in /sys/class/thermal/thermal_zone*/temp; do [ -r \"\$f\" ] && echo \"\$(basename \$(dirname \"\$f\")): \$(cat \"\$f\")\"; done | head -8")
        if (raw.isNullOrBlank()) return "Данные недоступны"
        return raw.lines().joinToString("\n") { line ->
            val parts = line.split(":", limit = 2)
            val n = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (n == null) line else "${parts[0]}: ${if (n > 1000) String.format(Locale.US, "%.1f°C", n / 1000) else String.format(Locale.US, "%.1f°C", n)}"
        }
    }

    private fun memoryInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gib = 1024.0 * 1024.0 * 1024.0
        val usableTotalGiB = mi.totalMem / gib
        val usedGiB = ((mi.totalMem - mi.availMem).coerceAtLeast(0L)) / gib
        // Hardware/firmware reserves a portion of physical RAM, so Android's MemTotal is
        // normally below the advertised module capacity (e.g. 10.5 GiB available for 12 GB).
        // Report the nearest common installed configuration rather than incorrectly labelling
        // a 12 GB unit as a 10 GB device.
        val standardCapacities = listOf(8, 12, 16, 24, 32)
        val installedGiB = when {
            usableTotalGiB in 9.0..<13.5 -> 12
            usableTotalGiB in 6.0..<9.0 -> 8
            usableTotalGiB in 13.5..<18.5 -> 16
            usableTotalGiB in 18.5..<26.0 -> 24
            usableTotalGiB >= 26.0 -> 32
            else -> standardCapacities.minByOrNull { kotlin.math.abs(it - usableTotalGiB) } ?: kotlin.math.ceil(usableTotalGiB).toInt()
        }
        return "${String.format(Locale.US, "%.1f", usedGiB)} / $installedGiB GB"
    }

    /** Show only storage-backed swap. zRAM is intentionally excluded from this card. */
    private fun swapInfo(): String = try {
        val swapRows = File("/proc/swaps").readLines().drop(1)
        val storageSwap = swapRows.mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            val name = columns.getOrNull(0) ?: return@mapNotNull null
            if (name.contains("zram", ignoreCase = true)) return@mapNotNull null
            val sizeKb = columns.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            val usedKb = columns.getOrNull(3)?.toLongOrNull() ?: 0L
            sizeKb to usedKb
        }
        val totalKb = storageSwap.sumOf { it.first }
        val usedKb = storageSwap.sumOf { it.second }.coerceIn(0L, totalKb)
        if (totalKb <= 0L) {
            "Off · 0.0 GB"
        } else {
            val freeKb = totalKb - usedKb
            val gib = 1024.0 * 1024.0
            val used = usedKb / gib
            val free = freeKb / gib
            val total = totalKb / gib
            "${String.format(Locale.US, "%.1f", used)} used · ${String.format(Locale.US, "%.1f", free)} free / ${String.format(Locale.US, "%.1f", total)} GB"
        }
    } catch (_: Exception) { "—" }

    private fun storageInfo(): String = try {
        val s = StatFs(Environment.getDataDirectory().path)
        val total = s.totalBytes / (1024 * 1024 * 1024)
        val free = s.availableBytes / (1024 * 1024 * 1024)
        "${total - free} / ${total} GB"
    } catch (_: Exception) { "—" }

    private fun profileDisplayName(p: String): String = if (TunedProfiles.isTuned(p)) (TunedProfiles.byKey(this, p)?.name ?: p.removePrefix(TunedProfile.PREFIX)) else if (isGameProfile(p)) appLabel(p.removePrefix(GAME_PROFILE_PREFIX)) else when (p) {
        "Eco" -> tr("Eco", "Эко", "Eco")
        "Balanced" -> tr("Balanced", "Баланс", "Gebalanceerd")
        "Streaming" -> tr("Streaming", "Стриминг", "Streaming")
        "Gaming" -> tr("Gaming", "Игровой", "Gaming")
        else -> tr("Max", "Максимум", "Max")
    }

    private fun profileIcon(p: String) = when (p) {
        "Eco" -> "🌱"
        "Balanced" -> "🎚️"
        "Streaming" -> "📡"
        "Gaming" -> "🎮"
        else -> "🔥"
    }

    private fun profileAccent(p: String) = if (TunedProfiles.isTuned(p)) cyan else if (isPop()) when (p) {
        "Eco" -> Color.rgb(16, 200, 140)
        "Balanced" -> Color.rgb(40, 140, 255)
        "Streaming" -> Color.rgb(140, 80, 255)
        "Gaming" -> Color.rgb(255, 140, 30)
        else -> Color.rgb(255, 46, 136)
    } else if (layoutTheme == "bridge") when (p) {
        "Eco" -> Color.rgb(153, 153, 255)
        "Balanced" -> Color.rgb(255, 204, 153)
        "Streaming" -> Color.rgb(204, 153, 204)
        "Gaming" -> Color.rgb(255, 153, 0)
        else -> Color.rgb(221, 68, 68)
    } else if (layoutTheme == "jade") when (p) {
        "Eco" -> Color.rgb(120, 228, 140)
        "Balanced" -> Color.rgb(70, 214, 172)
        "Streaming" -> Color.rgb(120, 196, 220)
        "Gaming" -> Color.rgb(214, 208, 108)
        else -> Color.rgb(236, 128, 92)
    } else if (layoutTheme == "bauhaus") when (p) {
        "Eco" -> Color.rgb(36, 110, 230)
        "Balanced" -> Color.rgb(255, 200, 0)
        "Streaming" -> Color.rgb(242, 238, 228)
        "Gaming" -> Color.rgb(255, 126, 24)
        else -> Color.rgb(230, 57, 45)
    } else if (layoutTheme == "hud") when (p) {
        "Eco" -> Color.rgb(60, 255, 170)
        "Balanced" -> Color.rgb(0, 226, 255)
        "Streaming" -> Color.rgb(176, 120, 255)
        "Gaming" -> Color.rgb(255, 196, 0)
        else -> Color.rgb(255, 64, 96)
    } else if (layoutTheme == "hifi") when (p) {
        "Eco" -> Color.rgb(120, 222, 110)
        "Balanced" -> Color.rgb(92, 236, 214)
        "Streaming" -> Color.rgb(120, 172, 255)
        "Gaming" -> Color.rgb(255, 178, 48)
        else -> Color.rgb(255, 76, 58)
    } else if (layoutTheme == "abyss") when (p) {
        "Eco" -> Color.rgb(84, 222, 150)
        "Balanced" -> Color.rgb(64, 196, 245)
        "Streaming" -> Color.rgb(170, 134, 255)
        "Gaming" -> Color.rgb(255, 176, 92)
        else -> Color.rgb(255, 92, 124)
    } else if (layoutTheme == "cartridge") when (p) {
        "Eco" -> Color.rgb(132, 196, 116)
        "Balanced" -> Color.rgb(112, 170, 226)
        "Streaming" -> Color.rgb(176, 146, 226)
        "Gaming" -> Color.rgb(236, 198, 124)
        else -> Color.rgb(232, 86, 92)
    } else if (layoutTheme == "blueprint") when (p) {
        "Eco" -> Color.rgb(150, 196, 170)
        "Balanced" -> Color.rgb(130, 172, 226)
        "Streaming" -> Color.rgb(170, 160, 222)
        "Gaming" -> Color.rgb(222, 188, 128)
        else -> Color.rgb(230, 126, 100)
    } else if (layoutTheme == "chrono") when (p) {
        "Eco" -> Color.rgb(156, 192, 146)
        "Balanced" -> Color.rgb(146, 180, 212)
        "Streaming" -> Color.rgb(178, 156, 214)
        "Gaming" -> Color.rgb(222, 182, 108)
        else -> Color.rgb(214, 96, 92)
    } else if (layoutTheme == "obsidian") when (p) {
        "Eco" -> Color.rgb(132, 226, 122)
        "Balanced" -> Color.rgb(92, 158, 255)
        "Streaming" -> Color.rgb(168, 132, 255)
        "Gaming" -> Color.rgb(255, 176, 72)
        else -> Color.rgb(255, 86, 112)
    } else if (layoutTheme == "dusk") when (p) {
        "Eco" -> Color.rgb(140, 200, 152)
        "Balanced" -> Color.rgb(122, 170, 212)
        "Streaming" -> Color.rgb(170, 152, 210)
        "Gaming" -> Color.rgb(222, 184, 124)
        else -> Color.rgb(214, 124, 134)
    } else if (layoutTheme == "aurora") when (p) {
        "Eco" -> Color.rgb(110, 240, 160)
        "Balanced" -> Color.rgb(90, 200, 255)
        "Streaming" -> Color.rgb(190, 140, 255)
        "Gaming" -> Color.rgb(255, 196, 92)
        else -> Color.rgb(255, 92, 146)
    } else if (isBrutal()) when (p) {
        "Eco" -> Color.rgb(107, 203, 119)
        "Balanced" -> Color.rgb(77, 150, 255)
        "Streaming" -> Color.rgb(179, 136, 255)
        "Gaming" -> Color.rgb(255, 210, 63)
        else -> Color.rgb(255, 107, 107)
    } else if (layoutTheme == "eightbit") when (p) {
        "Eco" -> Color.rgb(181, 230, 86)
        "Balanced" -> Color.rgb(205, 255, 75)
        "Streaming" -> Color.rgb(91, 224, 213)
        "Gaming" -> Color.rgb(255, 164, 76)
        else -> Color.rgb(255, 95, 118)
    } else if (layoutTheme == "volcanic") when (p) {
        "Eco" -> Color.rgb(189, 202, 111)
        "Balanced" -> Color.rgb(255, 210, 133)
        "Streaming" -> Color.rgb(230, 139, 82)
        "Gaming" -> Color.rgb(255, 106, 64)
        else -> Color.rgb(228, 70, 50)
    } else when (p) {
        "Eco" -> Color.rgb(97, 211, 164)
        "Balanced" -> Color.rgb(69, 183, 255)
        "Streaming" -> Color.rgb(177, 133, 255)
        "Gaming" -> Color.rgb(255, 159, 83)
        else -> Color.rgb(255, 87, 119)
    }

    private fun refreshProfileButton(profile: String, active: Boolean) {
        val views = profileButtons[profile] ?: return
        views.root.background = profileButtonBackground(profile, active)
        views.root.elevation = if (flatTheme()) 0f else dp(if (active) 6 else 2).toFloat()
        views.title.setTextColor(if (active) onAccentInk() else white)
        (views.root.getChildAt(0) as? LivingIconView)?.let { icon ->
            icon.setTint(if (active) Color.WHITE else profileAccent(profile))
            icon.animated = active
        }
    }

    private inner class ProfileGlyphView(context: Context, profile: String) : TextView(context) {
        init {
            text = profileIcon(profile)
            textSize = 21f
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = profileDisplayName(profile)
        }
    }

    private fun profileButtonBackground(profile: String, active: Boolean): Drawable {
        val accent = profileAccent(profile)
        if (isBrutal()) return brutalBox(if (active) accent else cardBg, offsetDp = if (active) 4 else 2)
        if (isPop()) return if (active) popCard(24, accent, strong = true) else GradientDrawable().apply {
            setColor(Color.argb(175, 255, 255, 255))
            cornerRadius = dp(24).toFloat()
        }
        if (glassTheme()) return if (active) auroraGlass(22, accent, strong = true) else GradientDrawable().apply {
            setColor(Color.argb(46, 255, 255, 255))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(55, 255, 255, 255))
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (active) intArrayOf(accent, blendColor(accent, bg, 0.22f)) else intArrayOf(cardBg2, cardBg)).apply {
            cornerRadius = dp(when (layoutTheme) { "compact" -> 12; "solstice", "synthwave" -> 5; "volcanic" -> 10; "eightbit" -> 3; else -> 16 }).toFloat()
            setStroke(dp(if (active) 2 else 1), if (active) accent else border)
        }
    }

    /** Retro-futurist night sky with a striped sunset and perspective neon grid. */
    private inner class SynthwaveBackdrop : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 5, 24); style = Paint.Style.STROKE }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val left = b.left.toFloat()
            val top = b.top.toFloat()
            paint.shader = LinearGradient(left, top, left + w * 0.8f, top + h,
                intArrayOf(Color.rgb(5, 3, 20), Color.rgb(26, 8, 48), Color.rgb(9, 8, 32)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
            paint.shader = null

            for (i in 0 until 38) {
                val x = left + w * ((i * 73 % 997) / 997f)
                val y = top + h * ((i * 131 + 17) % 997) / 997f * 0.76f
                paint.color = Color.argb(if (i % 4 == 0) 220 else 115, 225, 219, 255)
                canvas.drawCircle(x, y, dp(if (i % 5 == 0) 2 else 1).toFloat(), paint)
            }

            val centerX = left + w * 0.72f
            val centerY = top + h * 0.68f
            val radius = minOf(w * 0.23f, h * 0.24f)
            val sun = Path().apply { addCircle(centerX, centerY, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(sun)
            paint.shader = LinearGradient(centerX, centerY - radius, centerX, centerY + radius,
                intArrayOf(Color.rgb(255, 213, 102), Color.rgb(255, 94, 141), Color.rgb(179, 67, 232)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(centerX, centerY, radius, paint)
            paint.shader = null
            stripe.strokeWidth = dp(4).toFloat()
            for (i in 0..5) {
                val y = centerY + radius * 0.18f + dp(i * 7).toFloat()
                canvas.drawLine(centerX - radius, y, centerX + radius, y, stripe)
            }
            canvas.restore()

            val horizon = top + h * 0.74f
            grid.color = Color.argb(115, 74, 223, 255)
            grid.strokeWidth = dp(1).toFloat()
            canvas.drawLine(left, horizon, left + w, horizon, grid)
            for (i in -8..8) {
                val bottomX = left + w * (0.5f + i * 0.12f)
                grid.color = if (i % 2 == 0) Color.argb(76, 255, 72, 190) else Color.argb(78, 74, 223, 255)
                canvas.drawLine(centerX, horizon, bottomX, top + h, grid)
            }
            for (i in 1..8) {
                val t = i / 8f
                val y = horizon + (top + h - horizon) * t * t
                grid.color = Color.argb((35 + i * 8).coerceAtMost(105), 74, 223, 255)
                canvas.drawLine(left, y, left + w, y, grid)
            }
        }

        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(colorFilter: ColorFilter?) { }
        @Deprecated("Deprecated by Android")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private inner class MetricGlyphView(context: Context, private val kind: String, tint: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width / 28f
            val sy = height / 28f
            canvas.save()
            canvas.scale(sx, sy)
            when (kind) {
                "cpu" -> {
                    canvas.drawRoundRect(7f, 7f, 21f, 21f, 3f, 3f, stroke)
                    canvas.drawRoundRect(10f, 10f, 18f, 18f, 2f, 2f, stroke)
                    for (p in 9..19 step 5) {
                        canvas.drawLine(p.toFloat(), 3f, p.toFloat(), 7f, stroke)
                        canvas.drawLine(p.toFloat(), 21f, p.toFloat(), 25f, stroke)
                        canvas.drawLine(3f, p.toFloat(), 7f, p.toFloat(), stroke)
                        canvas.drawLine(21f, p.toFloat(), 25f, p.toFloat(), stroke)
                    }
                }
                "gpu" -> {
                    canvas.drawRoundRect(3f, 6f, 24f, 21f, 3f, 3f, stroke)
                    canvas.drawCircle(12f, 13.5f, 4.8f, stroke)
                    canvas.drawCircle(12f, 13.5f, 1f, fill)
                    canvas.drawLine(5f, 23.5f, 21f, 23.5f, stroke)
                    canvas.drawLine(25f, 9f, 25f, 18f, stroke)
                }
                "display" -> {
                    canvas.drawRoundRect(3f, 4f, 25f, 19f, 3f, 3f, stroke)
                    canvas.drawLine(10f, 23f, 18f, 23f, stroke)
                    canvas.drawLine(14f, 19f, 14f, 23f, stroke)
                    canvas.drawLine(7f, 25f, 21f, 25f, stroke)
                }
                "fps" -> {
                    canvas.drawLine(4f, 23f, 4f, 5f, stroke)
                    canvas.drawLine(4f, 23f, 25f, 23f, stroke)
                    canvas.drawLine(7f, 18f, 12f, 13f, stroke)
                    canvas.drawLine(12f, 13f, 17f, 16f, stroke)
                    canvas.drawLine(17f, 16f, 23f, 7f, stroke)
                }
                "ram" -> {
                    canvas.drawRoundRect(3f, 8f, 25f, 20f, 2f, 2f, stroke)
                    for (x in 7..21 step 5) canvas.drawLine(x.toFloat(), 10f, x.toFloat(), 16f, stroke)
                    for (x in 7..21 step 5) canvas.drawLine(x.toFloat(), 20f, x.toFloat(), 24f, stroke)
                }
            }
            canvas.restore()
        }
    }

    private inner class MetricRingView(context: Context, private val tint: Int) : View(context) {
        private var percent: Int? = null
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, Color.red(tint), Color.green(tint), Color.blue(tint))
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(4).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textAlign = Paint.Align.CENTER
            textSize = 11f * resources.displayMetrics.scaledDensity
            typeface = Typeface.DEFAULT_BOLD
        }
        private val arcBounds = RectF()

        fun setPercent(value: Int?) {
            percent = value?.coerceIn(0, 100)
            contentDescription = percent?.let { "$it percent used" } ?: "Usage unavailable"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - dp(5)
            if (radius <= 0f) return
            canvas.drawCircle(cx, cy, radius, trackPaint)
            val value = percent
            if (value != null) {
                arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawArc(arcBounds, -90f, 360f * value / 100f, false, progressPaint)
            }
            val label = percent?.let { "$it%" } ?: "—"
            val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    /** Small vector fan glyph avoids the washer-like emoji used in earlier builds. */
    private class FanGlyphView(context: Context, private val tint: Int) : View(context) {
        private val bladePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }
        private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.46f
            for (i in 0..2) {
                canvas.save()
                canvas.rotate(i * 120f - 18f, cx, cy)
                val blade = Path().apply {
                    moveTo(cx, cy - r * 0.10f)
                    cubicTo(cx + r * 0.52f, cy - r * 0.12f, cx + r * 0.75f, cy - r * 0.78f, cx + r * 0.23f, cy - r * 0.90f)
                    cubicTo(cx - r * 0.05f, cy - r * 0.95f, cx - r * 0.10f, cy - r * 0.45f, cx, cy - r * 0.10f)
                    close()
                }
                canvas.drawPath(blade, bladePaint)
                canvas.restore()
            }
            canvas.drawCircle(cx, cy, r * 0.19f, hubPaint)
        }
    }

    /** Cartridge button: a rubber console key — face on top of a darker lip. */
    private fun cartButton(fill: Int, outline: Int, radiusDp: Int): Drawable {
        val r = dp(minOf(radiusDp, 12)).toFloat()
        val accentFill = fill == mint || fill == cyan
        val faceColor = if (Color.alpha(fill) < 255) blendColor(cardBg2, fill, 0.5f) else fill
        val lip = GradientDrawable().apply { setColor(blendColor(faceColor, Color.BLACK, 0.5f)); cornerRadius = r }
        val face = GradientDrawable().apply {
            setColor(faceColor)
            cornerRadius = r
            if (!accentFill && (outline == mint || outline == cyan)) setStroke(dp(1), outline)
            else setStroke(dp(1), LivingIconView.withAlpha(Color.WHITE, if (accentFill) 40 else 16))
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(lip, face)).apply { setLayerInset(1, 0, 0, 0, dp(3)) }
    }

    /**
     * Hi-Fi button: a hard key of a stereo front panel. Lit keys (accent fill) glow amber or
     * teal; the others are dark metal with a light top edge and a dark bottom edge.
     */
    private fun hifiButton(fill: Int, outline: Int): Drawable {
        val r = dp(4).toFloat()
        val accentFill = fill == mint || fill == cyan
        val shadow = GradientDrawable().apply { setColor(Color.rgb(6, 6, 5)); cornerRadius = r }
        val face = if (accentFill) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blendColor(fill, Color.WHITE, 0.25f), fill, blendColor(fill, Color.BLACK, 0.2f)))
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blendColor(if (Color.alpha(fill) < 255) cardBg2 else fill, Color.WHITE, 0.12f), blendColor(if (Color.alpha(fill) < 255) cardBg2 else fill, Color.BLACK, 0.18f)))
        face.cornerRadius = r
        face.setStroke(dp(1), when {
            accentFill -> blendColor(fill, Color.BLACK, 0.35f)
            outline == mint || outline == cyan -> outline
            else -> Color.argb(60, 255, 255, 255)
        })
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, face)).apply { setLayerInset(1, 0, 0, 0, dp(2)) }
    }

    private fun roundedBackground(fill: Int, outline: Int = border, radiusDp: Int = 15): Drawable =
        if (isBrutal()) brutalBox(fill, offsetDp = if (radiusDp <= 8) 2 else 3)
        else if (isBridge()) GradientDrawable().apply {
            // Console buttons: solid capsules, no hairline outlines.
            setColor(fill)
            cornerRadius = dp(28).toFloat()
            if (outline == mint && fill != mint) setStroke(dp(2), mint)
        }
        else if (isPop()) GradientDrawable().apply {
            // Soft, very round, almost-white chips that float over the moving gradient.
            setColor(LivingIconView.withAlpha(fill, if (Color.alpha(fill) == 255) 228 else Color.alpha(fill)))
            cornerRadius = dp(radiusDp + 10).toFloat()
            if (outline != border) setStroke(dp(2), outline)
        }
        else if (layoutTheme == "cartridge") cartButton(fill, outline, radiusDp)
        else if (layoutTheme == "hifi") hifiButton(fill, outline)
        else if (layoutTheme == "jade") GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (fill == mint || fill == cyan) intArrayOf(blendColor(fill, Color.WHITE, 0.15f), blendColor(fill, Color.BLACK, 0.12f))
            else intArrayOf(blendColor(if (Color.alpha(fill) < 255) cardBg2 else fill, Color.WHITE, 0.04f), blendColor(if (Color.alpha(fill) < 255) cardBg2 else fill, Color.BLACK, 0.15f))).apply {
            // Jade buttons: leaf-shaped (big rounding on two opposite corners).
            val big = dp(radiusDp.coerceIn(12, 18)).toFloat(); val small = dp(4).toFloat()
            cornerRadii = floatArrayOf(big, big, small, small, big, big, small, small)
            setStroke(dp(1), when {
                fill == mint || fill == cyan -> LivingIconView.withAlpha(Color.WHITE, 50)
                outline == mint || outline == cyan -> outline
                else -> LivingIconView.withAlpha(mint, 60)
            })
        }
        else if (layoutTheme == "bauhaus") GradientDrawable().apply {
            // Bauhaus buttons: flat square blocks; filled ones solid, the others with a heavy edge.
            val accentFill = fill == mint || fill == cyan
            setColor(if (Color.alpha(fill) < 255) cardBg2 else fill)
            cornerRadius = 0f
            if (!accentFill) setStroke(dp(2), if (outline == mint || outline == cyan) outline else LivingIconView.withAlpha(white, 150))
        }
        else if (layoutTheme == "hud") HudButtonDrawable(
            if (Color.alpha(fill) < 255) cardBg2 else fill,
            if (outline == mint || outline == cyan) outline else LivingIconView.withAlpha(mint, 120),
            fill == mint || fill == cyan, resources.displayMetrics.density)
        else if (layoutTheme == "abyss") (if (fill == mint || fill == cyan)
            // Deep Sea: filled buttons are a turquoise-to-violet capsule, others dark with a soft edge.
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                if (fill == mint) intArrayOf(mint, blendColor(mint, cyan, 0.55f)) else intArrayOf(cyan, blendColor(cyan, mint, 0.4f)))
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blendColor(fill, Color.WHITE, 0.04f), blendColor(fill, Color.BLACK, 0.12f)))).apply {
            cornerRadius = dp(radiusDp.coerceAtLeast(16)).toFloat()
            setStroke(dp(1), when {
                fill == mint || fill == cyan -> LivingIconView.withAlpha(Color.WHITE, 40)
                outline == mint || outline == cyan -> LivingIconView.withAlpha(outline, 200)
                else -> LivingIconView.withAlpha(mint, 46)
            })
        }
        else if (layoutTheme == "blueprint") GradientDrawable().apply {
            // Blueprint buttons: square, flat; solid ink when filled with an accent, otherwise an outline.
            val accentFill = fill == mint || fill == cyan
            setColor(if (accentFill) fill else LivingIconView.withAlpha(fill, 245))
            cornerRadius = dp(3).toFloat()
            setStroke(dp(1), when {
                accentFill -> fill
                outline == mint || outline == cyan -> LivingIconView.withAlpha(outline, 210)
                else -> LivingIconView.withAlpha(mint, 60)
            })
        }
        else if (layoutTheme == "chrono") (if (fill == mint || fill == cyan)
            // Chronograph buttons: polished metal capsules when filled with an accent…
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blendColor(fill, Color.WHITE, 0.28f), fill, blendColor(fill, Color.BLACK, 0.18f)))
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            // …otherwise a dark capsule with a fine gold line.
            LivingIconView.withAlpha(blendColor(fill, Color.WHITE, 0.04f), 242), LivingIconView.withAlpha(blendColor(fill, Color.BLACK, 0.3f), 242)))).apply {
            cornerRadius = dp(minOf(radiusDp + 8, 26)).toFloat()
            setStroke(dp(1), if (outline == mint || outline == cyan) LivingIconView.withAlpha(outline, 190) else LivingIconView.withAlpha(mint, 70))
        }
        else if (layoutTheme == "obsidian") GradientDrawable().apply {
            // Obsidian buttons: solid accent when filled with it, otherwise dark with a hairline.
            val accentFill = fill == mint || fill == cyan
            setColor(if (accentFill) fill else LivingIconView.withAlpha(fill, if (Color.alpha(fill) == 255) 240 else Color.alpha(fill)))
            cornerRadius = dp(minOf(radiusDp, 12)).toFloat()
            setStroke(dp(1), when {
                accentFill -> fill
                outline == mint || outline == cyan -> LivingIconView.withAlpha(outline, 170)
                else -> Color.argb(38, 255, 255, 255)
            })
        }
        else if (isAuroraFamily()) GradientDrawable().apply {
            // Translucent and extra round so the moving aurora glows through.
            setColor(LivingIconView.withAlpha(fill, if (Color.alpha(fill) == 255) 205 else Color.alpha(fill)))
            cornerRadius = dp(radiusDp + 8).toFloat()
            setStroke(dp(1), LivingIconView.withAlpha(outline, 130))
        }
        else GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), outline)
        }

    /**
     * Neo-brutalist block: flat fill, thick ink outline and a hard, unblurred shadow
     * offset to the bottom right.
     */
    private fun brutalBox(fill: Int, offsetDp: Int = 4, radiusDp: Int = 6): Drawable {
        val offset = dp(offsetDp)
        val shadow = GradientDrawable().apply {
            setColor(brutalLine())
            cornerRadius = dp(radiusDp).toFloat()
        }
        val face = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(2), brutalLine())
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(0, offset, offset, 0, 0)
            setLayerInset(1, 0, 0, offset, offset)
        }
    }

    /** Frosted-glass panel for the Aurora Live theme, optionally tinted with an accent. */
    private fun auroraGlass(radiusDp: Int = 26, accent: Int? = null, strong: Boolean = false): Drawable {
        if (isBridge()) {
            // Plain panels get the bent "elbow" bar; accented tiles/buttons get a left bar only.
            // Large panels get a top bar only, tiles/buttons a short accent bar; the bent
            // "elbow" frame is only used by card(), which reserves room for it.
            return ConsoleFrameDrawable(accent ?: mint, cyan, if (accent == null) cardBg else cardBg2,
                resources.displayMetrics.density, if (accent == null) ConsoleFrameDrawable.TOPBAR else ConsoleFrameDrawable.ACCENT,
                strong = strong, radiusDp = (radiusDp - 4).toFloat())
        }
        if (layoutTheme == "jade") {
            // Jade: leaf-shaped green panes (see JadeCardDrawable).
            return JadeCardDrawable(if (accent == null) cardBg else cardBg2, accent ?: mint, resources.displayMetrics.density,
                strong, tinted = accent != null, radiusDp = radiusDp.toFloat().coerceAtMost(24f))
        }
        if (layoutTheme == "bauhaus") {
            // Bauhaus: flat square blocks with a quarter circle and a bar (see BauhausCardDrawable).
            return BauhausCardDrawable(if (accent == null) cardBg else cardBg2, accent, resources.displayMetrics.density, strong)
        }
        if (layoutTheme == "hud") {
            // Hologram: every panel is a projected plate with cut corners (see HudPanelDrawable).
            return HudPanelDrawable(if (accent == null) cardBg else cardBg2, accent ?: mint,
                resources.displayMetrics.density, strong, tinted = accent != null)
        }
        if (layoutTheme == "hifi") {
            // Hi-Fi: every panel is a stereo front panel (see HiFiPanelDrawable).
            return HiFiPanelDrawable(if (accent == null) cardBg else blendColor(cardBg, accent, 0.05f), accent,
                resources.displayMetrics.density, 5f, strong)
        }
        if (layoutTheme == "abyss") {
            // Deep Sea: dark panes with depth and a corner glow (see AbyssCardDrawable).
            return AbyssCardDrawable(if (accent == null) cardBg else cardBg2, accent ?: mint, border,
                resources.displayMetrics.density, radiusDp.toFloat().coerceAtMost(22f), strong, tinted = accent != null)
        }
        if (layoutTheme == "cartridge") {
            // Cartridge: every panel is a cartridge shell (see CartridgeCardDrawable).
            return CartridgeCardDrawable(cardBg, accent, cyan, resources.displayMetrics.density, strong)
        }
        if (layoutTheme == "blueprint") {
            // Blueprint: every panel is a sheet with corner marks (see BlueprintCardDrawable).
            return BlueprintCardDrawable(mint, accent, cardBg, resources.displayMetrics.density, strong)
        }
        if (layoutTheme == "chrono") {
            // Chronograph: every panel is a watch dial (see ChronoCardDrawable).
            return ChronoCardDrawable(accent ?: mint, cardBg, resources.displayMetrics.density,
                radiusDp = (radiusDp - 6).coerceAtLeast(12).toFloat(), tinted = accent != null, strong = strong, ticks = accent == null)
        }
        if (layoutTheme == "obsidian") {
            // Obsidian: opaque graphite slabs, tighter corners, a hairline edge; accents only tint.
            val top = Color.rgb(20, 22, 27)
            val bottom = Color.rgb(11, 12, 15)
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                LivingIconView.withAlpha(if (accent != null) blendColor(top, accent, if (strong) 0.30f else 0.07f) else top, 238),
                LivingIconView.withAlpha(if (accent != null && strong) blendColor(bottom, accent, 0.14f) else bottom, 238)
            )).apply {
                cornerRadius = dp((radiusDp - 10).coerceAtLeast(10)).toFloat()
                setStroke(dp(1), if (accent != null) LivingIconView.withAlpha(accent, if (strong) 235 else 105)
                    else Color.argb(32, 255, 255, 255))
            }
        }
        val dusk = layoutTheme == "dusk"
        // Dusk keeps accents subtle: weaker tints, slate glass instead of violet.
        val tint = if (dusk) (if (strong) 150 else 55) else (if (strong) 210 else 95)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            if (accent != null) LivingIconView.withAlpha(accent, tint) else if (dusk) Color.argb(210, 36, 40, 54) else Color.argb(178, 46, 38, 98),
            if (accent != null && strong) LivingIconView.withAlpha(blendColor(accent, cyan, 0.45f), if (dusk) 130 else 200)
            else if (dusk) Color.argb(190, 24, 27, 38) else Color.argb(150, 18, 16, 48)
        )).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), if (accent != null) LivingIconView.withAlpha(accent, if (dusk) (if (strong) 170 else 90) else if (strong) 220 else 150)
                else Color.argb(if (dusk) 40 else 70, 255, 255, 255))
        }
    }

    /** Dusk page background: a still gradient with two faint glows (no animation). */
    private fun duskBackdrop(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(24, 27, 39), bg, Color.rgb(10, 11, 17)))
        val size = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).toFloat()
        fun glow(color: Int, alpha: Int, x: Float, y: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = size * 0.6f
            setGradientCenter(x, y)
            colors = intArrayOf(LivingIconView.withAlpha(color, alpha), Color.TRANSPARENT)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(base,
            glow(mint, 34, 0.12f, 0.05f),
            glow(cyan, 26, 0.95f, 0.95f)))
    }

    /** Large tool tiles on the second home page. */
    private fun toolTileBackground(accent: Int, tint: Float): Drawable =
        if (isBrutal()) brutalBox(blendColor(cardBg, accent, 0.28f))
        else if (glassTheme()) auroraGlass(30, accent)
        else if (isPop()) popCard(30, accent)
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(blendColor(cardBg2, accent, tint), cardBg)).apply {
            cornerRadius = dp(if (layoutTheme == "eightbit") 7 else 24).toFloat()
            setStroke(dp(1), blendColor(border, accent, if (tint >= .22f) .6f else .55f))
        }

    /** Text colour on top of an accent fill: ink on the bright neo-brutal accents, page colour otherwise. */
    private fun onAccentInk(): Int = if (isBrutal()) BRUTAL_INK else if (layoutTheme == "pop") Color.WHITE else if (isBridge()) Color.BLACK else bg

    private fun isBrutal(): Boolean = layoutTheme == "brutal" || layoutTheme == "brutal_dark"
    /** Reads the saved design; designs that were removed fall back to the default (and the
     *  fallback is saved so the touchpad and PC keyboard follow it too). */
    private fun loadLayoutTheme(prefs: android.content.SharedPreferences): String {
        val saved = prefs.getString("layout_theme", DEFAULT_LAYOUT_THEME) ?: DEFAULT_LAYOUT_THEME
        val theme = normalizeLayoutTheme(saved)
        if (theme != saved) prefs.edit().putString("layout_theme", theme).apply()
        return theme
    }

    private fun isPop(): Boolean = layoutTheme == "pop"
    /** Themes that draw the animated icon set instead of emoji and glyphs. */
    private fun usesLivingIcons(): Boolean = isAuroraFamily() || layoutTheme == "pop" || isBridge()
    /** Starship Console: black space, console panels with bent accent bars, condensed type. */
    private fun isBridge(): Boolean = layoutTheme == "bridge"
    /** Themes whose cards and tiles come from auroraGlass() (which draws console panels for Starship Console). */
    private fun glassTheme(): Boolean = isAuroraFamily() || isBridge()
    /** Aurora Live and its calm, static sibling Dusk share the same glass layout. */
    private fun isAuroraFamily(): Boolean = layoutTheme == "aurora" || layoutTheme == "dusk" || layoutTheme == "obsidian" || layoutTheme == "chrono" || layoutTheme == "blueprint" || layoutTheme == "cartridge" || layoutTheme == "abyss" || layoutTheme == "hifi" || layoutTheme == "hud" || layoutTheme == "bauhaus" || layoutTheme == "jade"
    /** Themes with their own depth (hard shadows, glass): no soft elevation. */
    private fun flatTheme(): Boolean = isBrutal() || isAuroraFamily() || isBridge()

    /**
     * Pop Gradient card: nearly white and very round; with [strong] it becomes a vivid
     * gradient from [accent] to violet (selected profile, active tiles).
     */
    private fun popCard(radiusDp: Int = 28, accent: Int? = null, strong: Boolean = false): Drawable =
        if (accent != null && strong) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(accent, blendColor(accent, cyan, 0.55f))).apply { cornerRadius = dp(radiusDp).toFloat() }
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.argb(238, 255, 255, 255),
            if (accent != null) LivingIconView.withAlpha(blendColor(Color.WHITE, accent, 0.18f), 230) else Color.argb(215, 255, 248, 252)
        )).apply { cornerRadius = dp(radiusDp).toFloat() }

    /** Outline and hard-shadow colour: ink on the light variant, paper on the dark one. */
    private fun brutalLine(): Int = if (layoutTheme == "brutal_dark") BRUTAL_PAPER else BRUTAL_INK

    /** Flat paper with a fine dot grid, the neo-brutal page background. */
    private inner class DotGridBackdrop : Drawable() {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (layoutTheme == "brutal_dark") Color.rgb(58, 57, 62) else Color.rgb(214, 204, 182)
        }
        override fun draw(canvas: Canvas) {
            canvas.drawColor(bg)
            val step = dp(18).toFloat()
            val radius = dp(1).toFloat() * 1.1f
            var y = step / 2f
            while (y < bounds.height()) {
                var x = step / 2f
                while (x < bounds.width()) {
                    canvas.drawCircle(bounds.left + x, bounds.top + y, radius, dot)
                    x += step
                }
                y += step
            }
        }
        override fun setAlpha(alpha: Int) { dot.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { dot.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private fun blendColor(base: Int, accent: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) * (1f - t) + Color.red(accent) * t).toInt(),
            (Color.green(base) * (1f - t) + Color.green(accent) * t).toInt(),
            (Color.blue(base) * (1f - t) + Color.blue(accent) * t).toInt()
        )
    }

    private fun percentFromFraction(value: String): Int? {
        val parts = value.substringBefore("GB", value).split("/")
        val used = parts.getOrNull(0)?.trim()?.replace(',', '.')?.toFloatOrNull() ?: return null
        val total = parts.getOrNull(1)?.trim()?.replace(',', '.')?.toFloatOrNull() ?: return null
        return if (total > 0f) (used * 100f / total).toInt().coerceIn(0, 100) else null
    }

    private fun pill(text: String, color: Int, textColor: Int, action: () -> Unit): TextView = TextView(this).apply {
        this.text = localize(text); setTextColor(textColor); textSize = 12f
        typeface = if (isBrutal()) Typeface.create("sans-serif-black", Typeface.NORMAL)
            else if (isBridge()) Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else if (layoutTheme == "obsidian") Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else if (layoutTheme == "chrono") Typeface.create("sans-serif", Typeface.NORMAL)
            else if (layoutTheme == "blueprint") Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else if (layoutTheme == "cartridge") Typeface.create("monospace", Typeface.BOLD)
            else if (layoutTheme == "abyss") Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else if (layoutTheme == "hifi") Typeface.create("sans-serif-condensed", Typeface.BOLD)
            else if (layoutTheme == "hud") Typeface.create("monospace", Typeface.BOLD)
            else if (layoutTheme == "bauhaus") Typeface.create("sans-serif-black", Typeface.NORMAL)
            else if (layoutTheme == "jade") Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else Typeface.DEFAULT_BOLD
        if (isBridge()) setAllCaps(true)
        if (layoutTheme == "obsidian") letterSpacing = 0.03f
        if (layoutTheme == "chrono") letterSpacing = 0.1f
        if (layoutTheme == "blueprint") letterSpacing = 0.08f
        if (layoutTheme == "hifi") { letterSpacing = 0.12f; setAllCaps(true) }
        if (layoutTheme == "hud") { letterSpacing = 0.14f; setAllCaps(true) }
        if (layoutTheme == "bauhaus") { letterSpacing = 0.04f; setAllCaps(true) }
        gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10))
        val shape = when (layoutTheme) { "compact" -> 7; "neon", "arcade", "synthwave" -> 5; "solstice" -> 4; "volcanic" -> 9; "eightbit" -> 3; "aurora", "dusk" -> 16; "obsidian" -> 12; "chrono" -> 22; "blueprint" -> 3; "cartridge" -> 10; "abyss" -> 24; "hifi" -> 4; "hud" -> 2; "bauhaus" -> 0; "jade" -> 16; else -> 13 }
        val outline = if (layoutTheme == "neon" || layoutTheme == "arcade" || layoutTheme == "synthwave" || layoutTheme == "volcanic" || layoutTheme == "eightbit" || color == mint) mint else border
        background = roundedBackground(color, outline, shape)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        enableControllerFocus(this, shape)
    }

    private fun card(): LinearLayout = object : LinearLayout(this) {
        // Starship Console cards keep clear of the bent frame however a caller sets the padding.
        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            if (isBridge()) super.setPadding(
                maxOf(left, dp(ConsoleFrameDrawable.ELBOW_LEFT_DP.toInt() + 9)),
                maxOf(top, dp(ConsoleFrameDrawable.ELBOW_TOP_DP.toInt() + 6)),
                maxOf(right, dp(8)), maxOf(bottom, dp(6)))
            else super.setPadding(left, top, right, bottom)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        val compact = layoutTheme == "compact"
        elevation = dp(if (compact) 2 else 4).toFloat()
        setPadding(dp(if (compact) 8 else 12), dp(if (compact) 8 else 12), dp(if (compact) 8 else 12), dp(if (compact) 8 else 12))
        if (flatTheme()) elevation = 0f
        background = when (layoutTheme) {
            "brutal", "brutal_dark" -> brutalBox(cardBg)
            "aurora", "dusk", "obsidian", "chrono", "blueprint", "cartridge", "abyss", "hifi", "hud", "bauhaus", "jade" -> auroraGlass(26)
            "bridge" -> ConsoleFrameDrawable(mint, cyan, cardBg, resources.displayMetrics.density, ConsoleFrameDrawable.ELBOW, radiusDp = 22f)
            "pop" -> popCard(28)
            "solstice" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(255, 252, 240), cardBg, Color.rgb(245, 237, 213))).apply {
                cornerRadius = dp(5).toFloat(); setStroke(dp(2), border)
            }
            "compact" -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cardBg2, cardBg)).apply {
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
            }
            "neon" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 10, 16), cardBg)).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), mint)
            }
            "arcade" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(20, 16, 28), cardBg)).apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(2), if (accentScheme == "green") Color.rgb(255, 191, 92) else mint)
            }
            "synthwave" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(35, 13, 63), Color.rgb(12, 8, 31))).apply {
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), Color.rgb(255, 72, 190))
            }
            "volcanic" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(68, 30, 16), Color.rgb(28, 14, 13))).apply {
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), Color.rgb(178, 81, 43))
            }
            "eightbit" -> GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(4).toFloat()
                setStroke(dp(2), border)
            }
            else -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(cardBg2, cardBg)).apply {
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), border)
            }
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = localize(value); textSize = size; setTextColor(color)
        typeface = when {
            bold && isBrutal() -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            bold && isBridge() -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            bold && layoutTheme == "obsidian" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // Chronograph: refined regular weight with wide tracking instead of heavy bold.
            bold && layoutTheme == "chrono" -> Typeface.create("sans-serif", Typeface.NORMAL)
            // Blueprint: condensed lettering, like the text on a drawing.
            bold && layoutTheme == "blueprint" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            layoutTheme == "blueprint" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            // Cartridge: monospace headings like a console menu; body text stays readable sans.
            bold && layoutTheme == "cartridge" -> Typeface.create("monospace", Typeface.BOLD)
            // Deep Sea: medium-weight headings, light body text.
            bold && layoutTheme == "abyss" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // Hi-Fi: condensed front-panel lettering for headings, plain text otherwise.
            bold && layoutTheme == "hifi" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            // Hologram: readout-style monospace headings, condensed light body text.
            bold && layoutTheme == "hud" -> Typeface.create("monospace", Typeface.BOLD)
            // Bauhaus: heavy black headings, plain body text.
            bold && layoutTheme == "bauhaus" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            // Jade: medium headings, light body text.
            bold && layoutTheme == "jade" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutTheme == "jade" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            layoutTheme == "hud" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            layoutTheme == "abyss" -> Typeface.create("sans-serif", Typeface.NORMAL)
            layoutTheme == "chrono" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            bold -> Typeface.DEFAULT_BOLD
            isBridge() -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> typeface
        }
        setLineSpacing(dp(2).toFloat(), 1f)
        if (bold && layoutTheme == "chrono") letterSpacing = 0.06f
    }

    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun weight(w: Float, start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, -2, w).apply { if (start > 0) marginStart = dp(start); if (end > 0) marginEnd = dp(end) }
    private fun matchHeightWeight(w: Float, start: Int = 0, end: Int = 0) = LinearLayout.LayoutParams(0, -1, w).apply { if (start > 0) marginStart = dp(start); if (end > 0) marginEnd = dp(end) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object {
        private const val EXTRA_REMOTE_COMMAND = "pockethub_remote_command"
        private const val EXTRA_POPUP_COMMAND = "pockethub_popup_command"
        /** The Dual screen controls popup while it is open (a second button press closes it). */
        @Volatile private var popupInstance: java.lang.ref.WeakReference<MainActivity>? = null
        @Volatile private var liveInstance: java.lang.ref.WeakReference<MainActivity>? = null

        /** Starts the small popup activity on the lower screen (over the current app). */
        private fun launchPopup(context: Context, command: String) {
            val intent = Intent(context, DualScreenPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_POPUP_COMMAND, command)
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val lower = dm.displays.firstOrNull { it.displayId != android.view.Display.DEFAULT_DISPLAY &&
                (it.flags and android.view.Display.FLAG_PRIVATE) == 0 && it.name?.contains("virtual", true) != true }
            val options = lower?.let { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
            try { context.startActivity(intent, options) } catch (_: Exception) { context.startActivity(intent) }
        }

        /**
         * Runs a remapped-button command. Profile switching works in the background when
         * PocketHub is already running; the screens window needs PocketHub in front.
         */
        fun remoteCommand(context: Context, command: String) {
            if (command == "capture") {
                launchPopup(context, "capture")
                return
            }
            if (command == "screens" || command == "profiles" || command == "autotune") {
                // Pressed again while the popup is open: close it instead of opening another.
                val open = popupInstance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (open != null) {
                    open.runOnUiThread {
                        val dialog = open.activeCustomDialog
                        if (dialog != null && dialog.isShowing) dialog.dismiss() else open.finish()
                    }
                    return
                }
                // Only the Dual screen controls / profile window, on the lower screen, over the current app.
                launchPopup(context, command)
                return
            }
            val activity = liveInstance?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (activity != null && command == "profile_next") {
                activity.runOnUiThread { activity.runRemoteCommand(command) }
                return
            }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_REMOTE_COMMAND, command))
        }

        private const val ACTION_HIDE_FROM_AYA_BUTTON = "com.example.ayaneooverlay.action.HIDE_FROM_AYA_BUTTON"
        private const val OEM_AIDL_DESCRIPTOR = "com.ayaneo.gamewindow.AyaAidlInterface"
        private const val OEM_CALLBACK_DESCRIPTOR = "com.ayaneo.gamewindow.AyaAidlCallback"
        private const val OEM_LOG_TAG = "AyaneoOverlayAidl"
        private const val EXTRA_DISPLAY_REDIRECTED = "display_redirected_once"
        private const val EXTRA_TARGET_DISPLAY_ID = "target_display_id"
        private const val KEY_FAVORITE_APPS = "favorite_apps"
        private const val KEY_QUICK_TILES = "quick_tiles"
        private const val HOME_PAGE_COUNT = 5
        private val ACCENT_THEMES = setOf("glass", "compact", "neon")
        private const val KEY_GAME_PROFILES = "game_profiles"
        private const val KEY_PROFILE_BEFORE_GAME = "profile_before_game"
        private const val KEY_PROFILE_BEFORE_TUNED = "profile_before_tuned"
        private const val KEY_TUNED_AUTO_APPLIED = "tuned_auto_applied"
        private const val TUNED_SWITCH_INTERVAL_MS = 5000L
        private const val KEY_CONTROLLER_BEFORE_GAME_ABXY = "controller_before_game_abxy"
        private const val KEY_CONTROLLER_BEFORE_GAME_VIB = "controller_before_game_vib"
        private const val KEY_CONTROLLER_STYLE = "controller_style"
        private const val KEY_SETUP_DONE = "setup_wizard_done"
        private const val KEY_EXTERNAL_RESOLUTION = "external_resolution"
        private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
        private const val KEY_EQ_BEFORE_GAME = "eq_before_game"
        private const val GAME_PROFILE_PREFIX = "app:"
        private const val MAX_GAME_PROFILES = 20
        private const val GAME_WATCH_START_DELAY_MS = 8000L
        private const val GAME_WATCH_INTERVAL_MS = 3000L
        private const val FPS_POLL_MS = 1000L
        const val DEFAULT_LAYOUT_THEME = "aurora"
        /** Designs that were removed (0.12.3, Blueprint in 0.16.0); a saved one is replaced by the default. */
        private val REMOVED_LAYOUT_THEMES = setOf("chrono", "bridge", "pop", "arcade", "blueprint")
        @JvmStatic fun normalizeLayoutTheme(theme: String?): String =
            if (theme == null || theme in REMOVED_LAYOUT_THEMES) DEFAULT_LAYOUT_THEME else theme
        private val BRUTAL_INK = Color.rgb(17, 17, 17)
        private val BRUTAL_PAPER = Color.rgb(240, 235, 222)
    }
}
