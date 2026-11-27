package com.example.ayaneooverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * Applies the two screen master gains to active app audio players.
 * Screen attribution is inferred from the app's Android task; background audio
 * follows its last unambiguous display assignment for a short grace period.
 */
class ScreenAudioVolumeService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var bridge: RootAudioBridgeClient? = null
    private var startingBridge = false
    private var nextConnectAllowedAt = 0L
    @Volatile private var stopping = false
    private val lastPlayerGain = mutableMapOf<Int, Float>()
    private val lastUnsupportedLogAt = mutableMapOf<Int, Long>()
    private data class ControlledPlayer(val uid: Int, val role: ScreenRole, val appGain: Float)
    private val controlledPlayers = mutableMapOf<Int, ControlledPlayer>()
    private val lastPackageRole = mutableMapOf<String, Pair<ScreenRole, Long>>()

    private enum class ScreenRole { UPPER, LOWER }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (stopping || bridge == null) return
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, 15L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (bridge == null && !startingBridge && System.currentTimeMillis() >= nextConnectAllowedAt) connectBridge()
            val current = bridge
            if (current != null) {
                try {
                    applySnapshot(current.listActive())
                } catch (error: Exception) {
                    Log.w(TAG, "Audio scan failed", error)
                    writeStatus("scan_failed|" + (error.message ?: error.javaClass.simpleName))
                    lastPlayerGain.clear()
                    closeBridge()
                }
            }
            handler.postDelayed(this, if (bridge == null) 5000L else POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        writeStatus("starting")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        worker = HandlerThread("PocketHub-screen-audio").apply { start() }
        handler = Handler(worker.looper)
        handler.post(poll)
        // React within milliseconds when a player starts (next song, seeking, a new video)
        // instead of waiting for the next scan, so new sound is turned down right away.
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).registerAudioPlaybackCallback(playbackCallback, handler)
        } catch (error: Exception) { Log.w(TAG, "Playback callback unavailable", error) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_GAIN_CHANGED) {
            val role = intent.getStringExtra(EXTRA_ROLE)
            if (role != null) handler.post { applyRoleGain(role) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Exception) { }
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        restorePlayerGains()
        closeBridge()
        writeStatus("off")
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    private fun connectBridge() {
        startingBridge = true
        nextConnectAllowedAt = System.currentTimeMillis() + 15_000L
        Thread({
            val connected = try { RootAudioBridgeClient(applicationContext) } catch (error: Exception) {
                Log.w(TAG, "Root audio bridge unavailable", error)
                writeStatus("root_failed|" + (error.message ?: error.javaClass.simpleName))
                null
            }
            handler.post {
                startingBridge = false
                if (stopping) {
                    try { connected?.close() } catch (_: Exception) { }
                } else if (connected != null) {
                    bridge = connected
                }
            }
        }, "PocketHub-audio-root-connect").apply { isDaemon = true; start() }
    }

    private fun applySnapshot(snapshot: RootAudioSnapshot) {
        rememberPreviousMediaVolume()
        // Lower the media stream before turning player gains up; raise it only after the
        // gains are down, so a level change never makes the sound jump for a moment.
        val raiseMediaTo = syncMediaBeforeGains()
        val roleByDisplay = screenRoles()
        val now = System.currentTimeMillis()
        val packageRoles = mutableMapOf<String, ScreenRole?>()
        val activePackages = snapshot.playersByUid.keys
            .flatMap { uid -> try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() } }
            .toSet()
        for ((packageName, displayIds) in snapshot.packageDisplayIds) {
            val roles = displayIds.mapNotNull { roleByDisplay[it] }.distinct()
            if (roles.size == 1) {
                packageRoles[packageName] = roles.single()
                lastPackageRole[packageName] = roles.single() to now
            } else if (roles.size > 1) {
                packageRoles[packageName] = null
            }
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentPlayerIds = mutableSetOf<Int>()
        var unassigned = 0
        val upperApps = linkedSetOf<String>()
        val lowerApps = linkedSetOf<String>()
        val failedApps = linkedSetOf<String>()
        for ((uid, playerIds) in snapshot.playersByUid) {
            currentPlayerIds.addAll(playerIds)
            val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            val resolvedPackages = packages.map { pkg ->
                if (packageRoles.containsKey(pkg)) packageRoles[pkg]
                else lastPackageRole[pkg]?.takeIf { now - it.second <= BACKGROUND_GRACE_MS }?.first
            }
            if (resolvedPackages.isEmpty() || resolvedPackages.any { it == null }) { unassigned += playerIds.size; continue }
            val assigned = resolvedPackages.filterNotNull().distinct()
            // Shared UIDs or apps visible on both displays are intentionally left alone.
            if (assigned.size != 1) { unassigned += playerIds.size; continue }
            val role = assigned.single()
            val appName = packages.firstOrNull()?.let { appLabel(it) } ?: uid.toString()
            if (role == ScreenRole.UPPER) upperApps += appName else lowerApps += appName
            val screenPercent = prefs.getInt(if (role == ScreenRole.UPPER) KEY_UPPER else KEY_LOWER, 100).coerceIn(0, 100)
            val appPercent = prefs.getInt("per_app_volume_$uid", 100).coerceIn(0, 100)
            val masterPercent = prefs.getInt(KEY_MASTER, 100).coerceIn(0, 100)
            val gain = playerGain(masterPercent / 100f * screenPercent / 100f * appPercent / 100f)
            for (playerId in playerIds) {
                controlledPlayers[playerId] = ControlledPlayer(uid, role, appPercent / 100f)
                if (lastPlayerGain[playerId]?.let { kotlin.math.abs(it - gain) < 0.005f } == true) continue
                try {
                    if (bridge?.setPlayerVolume(uid, playerId, gain) == true) {
                        lastPlayerGain[playerId] = gain
                        lastUnsupportedLogAt.remove(playerId)
                    } else {
                        // Some players (for example certain native/AAudio pipelines) expose no
                        // usable IPlayer proxy. Do not cache a failed write as success: retry while
                        // the player is active and let the log identify unsupported pipelines.
                        lastPlayerGain.remove(playerId)
                        logUnsupportedPlayer(playerId, now)
                        failedApps += appName
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Could not apply screen gain to player $playerId", error)
                    lastPlayerGain.remove(playerId)
                    failedApps += appName
                }
            }
        }
        if (raiseMediaTo >= 0) setMediaIndex(raiseMediaTo)
        writeStatus(listOf("ok", upperApps.joinToString(", "), lowerApps.joinToString(", "),
            failedApps.joinToString(", "), unassigned.toString()).joinToString("|"))
        lastPlayerGain.keys.retainAll(currentPlayerIds)
        controlledPlayers.keys.retainAll(currentPlayerIds)
        // Keep the last known display assignment for an actively playing app. A foreground task
        // is not listed once the app moves behind another window; expiring it after 30 seconds
        // made long background playback impossible to control by screen.
        lastPackageRole.entries.removeAll { now - it.value.second > BACKGROUND_GRACE_MS && it.key !in activePackages }
    }

    /**
     * The Pocket DS main (upper) screen is always display 0. Every other built-in display
     * is the lower screen. (Sorting by size was unreliable: virtual displays from casting
     * or recording could be picked as a "screen".)
     */
    private fun screenRoles(): Map<Int, ScreenRole> {
        val displays = try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays.toList() } catch (_: Exception) { emptyList() }
        val roles = mutableMapOf(0 to ScreenRole.UPPER)
        displays.filter { display ->
            display.displayId != 0 &&
                (display.flags and android.view.Display.FLAG_PRIVATE) == 0 &&
                display.name?.let { it.contains("virtual", true) || it.contains("overlay", true) } != true
        }.forEach { roles[it.displayId] = ScreenRole.LOWER }
        return roles
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) { packageName }

    private var lastStatus = ""
    private fun writeStatus(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply()
    }

    private fun closeBridge() {
        val active = bridge
        bridge = null
        if (active != null) try { active.close() } catch (_: Exception) { }
    }

    private fun restorePlayerGains() {
        val active = bridge
        if (active != null) {
            try {
                val snapshot = active.listActive()
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                for ((uid, playerIds) in snapshot.playersByUid) {
                    val appGain = prefs.getInt("per_app_volume_$uid", 100).coerceIn(0, 100) / 100f
                    for (playerId in playerIds) {
                        if (controlledPlayers[playerId]?.uid == uid) {
                            try { active.setPlayerVolume(uid, playerId, appGain) } catch (_: Exception) { }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not restore player gains while stopping", error)
            }
        }
        controlledPlayers.clear()
        lastPlayerGain.clear()
        restoreSystemMediaVolume()
    }

    private fun applyRoleGain(roleName: String) {
        // "master" changes every screen at once.
        val only = when (roleName) { "upper" -> ScreenRole.UPPER; "lower" -> ScreenRole.LOWER; "master" -> null; else -> return }
        val active = bridge ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val master = prefs.getInt(KEY_MASTER, 100).coerceIn(0, 100) / 100f
        val ampBefore = mediaAmp
        val raiseMediaTo = syncMediaBeforeGains()
        // A new media level changes every player's gain, not only this screen's.
        val all = only == null || kotlin.math.abs(ampBefore - mediaAmp) > 0.0001f
        for ((playerId, control) in controlledPlayers.toMap()) {
            if (!all && control.role != only) continue
            val percent = prefs.getInt(if (control.role == ScreenRole.UPPER) KEY_UPPER else KEY_LOWER, 100).coerceIn(0, 100)
            val gain = playerGain(master * percent / 100f * control.appGain)
            try {
                if (active.setPlayerVolume(control.uid, playerId, gain)) {
                    lastPlayerGain[playerId] = gain
                    lastUnsupportedLogAt.remove(playerId)
                } else {
                    lastPlayerGain.remove(playerId)
                    logUnsupportedPlayer(playerId, System.currentTimeMillis())
                }
            } catch (error: Exception) {
                Log.w(TAG, "Immediate screen gain update failed for player $playerId", error)
                lastPlayerGain.remove(playerId)
            }
        }
        if (raiseMediaTo >= 0) setMediaIndex(raiseMediaTo)
    }

    private fun logUnsupportedPlayer(playerId: Int, now: Long) {
        val last = lastUnsupportedLogAt[playerId] ?: 0L
        if (now - last >= UNSUPPORTED_LOG_INTERVAL_MS) {
            Log.w(TAG, "Player $playerId did not accept PlayerProxy.setVolume; screen gain cannot be applied to this audio pipeline")
            lastUnsupportedLogAt[playerId] = now
        }
    }

    private fun rememberPreviousMediaVolume() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS_MEDIA_VOLUME)) {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            prefs.edit().putInt(KEY_PREVIOUS_MEDIA_VOLUME, audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
        }
    }

    /**
     * Loudness the media stream currently passes through, 0..1 of its maximum (linear).
     * Player gains are set relative to it.
     */
    @Volatile private var mediaAmp = 1f
    private var ampTable: FloatArray? = null
    private var ampTableDevice = -1

    /**
     * The media stream is no longer forced to maximum. It is set to the louder of the two
     * screens' levels, and the quieter screen is turned down per player. A player that has
     * just started (new song, seek, new video) and is not adjusted yet therefore plays at
     * the louder screen's level at most, never at full volume.
     */
    private fun desiredPeak(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val master = prefs.getInt(KEY_MASTER, 100).coerceIn(0, 100) / 100f
        val upper = prefs.getInt(KEY_UPPER, 100).coerceIn(0, 100) / 100f
        val lower = prefs.getInt(KEY_LOWER, 100).coerceIn(0, 100) / 100f
        return master * maxOf(upper, lower)
    }

    private fun playerGain(desired: Float): Float =
        if (mediaAmp <= 0.0001f) 0f else (desired / mediaAmp).coerceIn(0f, 1f)

    private fun outputDeviceType(audio: AudioManager): Int {
        val types = try { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } } catch (_: Exception) { emptyList() }
        @Suppress("DEPRECATION")
        return when {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types && audio.isBluetoothA2dpOn -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_USB_HEADSET in types -> AudioDeviceInfo.TYPE_USB_HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_WIRED_HEADSET in types -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    /** Linear loudness of every media volume step for the current output, or null if unknown. */
    private fun amplitudes(audio: AudioManager): FloatArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val device = outputDeviceType(audio)
        ampTable?.takeIf { ampTableDevice == device }?.let { return it }
        return try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val dbMax = audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, max, device)
            if (dbMax.isNaN() || dbMax.isInfinite()) return null
            FloatArray(max + 1) { index ->
                val db = audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, device)
                if (index == 0 || db.isNaN() || db.isInfinite()) 0f
                else Math.pow(10.0, ((db - dbMax) / 20f).toDouble()).toFloat().coerceIn(0f, 1f)
            }.also { ampTable = it; ampTableDevice = device }
        } catch (error: Exception) {
            Log.w(TAG, "Volume curve unavailable, media stays at maximum", error); null
        }
    }

    /**
     * Picks the media step for the current levels and updates mediaAmp. Lowering happens
     * now; raising is returned (step index) so the caller applies it after turning gains down.
     * Returns -1 when nothing has to be raised.
     */
    private fun syncMediaBeforeGains(): Int {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val table = amplitudes(audio)
        val target: Int
        val amp: Float
        if (table == null) {
            target = max; amp = 1f
        } else {
            val peak = desiredPeak()
            target = if (peak <= 0f) 0 else (0..max).firstOrNull { table[it] >= peak * 0.999f } ?: max
            amp = table[target]
        }
        mediaAmp = amp
        return when {
            target < current -> { setMediaIndex(target); -1 }
            target > current -> target
            else -> -1
        }
    }

    private fun setMediaIndex(index: Int) {
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (error: Exception) { Log.w(TAG, "Could not set media volume", error) }
    }

    /** Hands control back to Android: media volume takes over the PocketHub master level. */
    private fun restoreSystemMediaVolume() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS_MEDIA_VOLUME) && !prefs.contains(KEY_MASTER)) return
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = if (prefs.contains(KEY_MASTER)) {
                Math.round(prefs.getInt(KEY_MASTER, 100).coerceIn(0, 100) / 100f * max)
            } else prefs.getInt(KEY_PREVIOUS_MEDIA_VOLUME, -1)
            if (target >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), 0)
        } catch (error: Exception) { Log.w(TAG, "Could not restore global media volume", error) }
        prefs.edit().remove(KEY_PREVIOUS_MEDIA_VOLUME).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen audio control", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("PocketHub screen audio")
            .setContentText("Separating app volume by display")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.ayaneooverlay.action.START_SCREEN_AUDIO"
        const val ACTION_STOP = "com.example.ayaneooverlay.action.STOP_SCREEN_AUDIO"
        const val ACTION_GAIN_CHANGED = "com.example.ayaneooverlay.action.SCREEN_AUDIO_GAIN_CHANGED"
        const val KEY_ENABLED = "screen_audio_enabled"
        const val KEY_UPPER = "screen_audio_upper"
        const val KEY_LOWER = "screen_audio_lower"
        /** Overall level (0-100) used while per-screen volume is on (media follows the louder screen). */
        const val KEY_MASTER = "screen_audio_master"
        /** Hardware volume keys open the PocketHub volume panel on the upper screen. */
        const val KEY_VOLUME_KEYS = "volume_keys_panel"
        /** Where the volume panel appears: "upper" (default), "lower" or "both". */
        const val KEY_VOLUME_PANEL_DISPLAY = "volume_panel_display"
        /** What the volume keys change by default: "master" (default), "upper" or "lower". */
        const val KEY_VOLUME_KEYS_TARGET = "volume_keys_target"
        /** Live status for the Sound dialog: "starting", "off", "root_failed|msg", "scan_failed|msg" or "ok|upper apps|lower apps|failed apps|unassigned count". */
        const val KEY_STATUS = "screen_audio_status"
        const val PREFS_NAME = "MainActivity"
        private const val EXTRA_ROLE = "role"
        private const val KEY_PREVIOUS_MEDIA_VOLUME = "screen_audio_previous_media_volume"
        private const val TAG = "PocketHubScreenAudio"
        private const val CHANNEL_ID = "pockethub_screen_audio"
        private const val NOTIFICATION_ID = 8204
        private const val POLL_MS = 1200L
        private const val BACKGROUND_GRACE_MS = 30_000L
        private const val UNSUPPORTED_LOG_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, ScreenAudioVolumeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        /**
         * Turns per-screen volume on. The current media level becomes the PocketHub master
         * level, so the sound does not jump to full when media is raised to maximum.
         */
        fun enable(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, false) || !prefs.contains(KEY_MASTER)) {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                prefs.edit().putInt(KEY_MASTER, audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).apply()
            }
            prefs.edit().putBoolean(KEY_ENABLED, true).putString(KEY_STATUS, "starting").apply()
            start(context)
        }

        fun disable(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false).putString(KEY_STATUS, "off").apply()
            stop(context)
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenAudioVolumeService::class.java).setAction(ACTION_STOP))
        }

        fun gainChanged(context: Context, preferenceKey: String) {
            val role = when (preferenceKey) { KEY_UPPER -> "upper"; KEY_MASTER -> "master"; else -> "lower" }
            context.startService(Intent(context, ScreenAudioVolumeService::class.java)
                .setAction(ACTION_GAIN_CHANGED).putExtra(EXTRA_ROLE, role))
        }
    }
}
