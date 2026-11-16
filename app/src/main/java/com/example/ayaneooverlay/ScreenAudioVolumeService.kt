package com.example.ayaneooverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioManager
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

    private val poll = object : Runnable {
        override fun run() {
            if (bridge == null && !startingBridge && System.currentTimeMillis() >= nextConnectAllowedAt) connectBridge()
            val current = bridge
            if (current != null) {
                try {
                    applySnapshot(current.listActive())
                } catch (error: Exception) {
                    Log.w(TAG, "Audio scan failed", error)
                    lastPlayerGain.clear()
                    closeBridge()
                }
            }
            handler.postDelayed(this, if (bridge == null) 5000L else POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        worker = HandlerThread("PocketHub-screen-audio").apply { start() }
        handler = Handler(worker.looper)
        handler.post(poll)
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
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        restorePlayerGains()
        closeBridge()
        if (::worker.isInitialized) worker.quitSafely()
        super.onDestroy()
    }

    private fun connectBridge() {
        startingBridge = true
        nextConnectAllowedAt = System.currentTimeMillis() + 15_000L
        Thread({
            val connected = try { RootAudioBridgeClient(applicationContext) } catch (error: Exception) {
                Log.w(TAG, "Root audio bridge unavailable", error)
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
        keepSystemMediaAtMaximum()
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
        for ((uid, playerIds) in snapshot.playersByUid) {
            currentPlayerIds.addAll(playerIds)
            val packages = try { packageManager.getPackagesForUid(uid)?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            val resolvedPackages = packages.map { pkg ->
                if (packageRoles.containsKey(pkg)) packageRoles[pkg]
                else lastPackageRole[pkg]?.takeIf { now - it.second <= BACKGROUND_GRACE_MS }?.first
            }
            if (resolvedPackages.isEmpty() || resolvedPackages.any { it == null }) continue
            val assigned = resolvedPackages.filterNotNull().distinct()
            // Shared UIDs or apps visible on both displays are intentionally left alone.
            if (assigned.size != 1) continue
            val role = assigned.single()
            val screenPercent = prefs.getInt(if (role == ScreenRole.UPPER) KEY_UPPER else KEY_LOWER, 100).coerceIn(0, 100)
            val appPercent = prefs.getInt("per_app_volume_$uid", 100).coerceIn(0, 100)
            val gain = (screenPercent / 100f * appPercent / 100f).coerceIn(0f, 1f)
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
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Could not apply screen gain to player $playerId", error)
                    lastPlayerGain.remove(playerId)
                }
            }
        }
        lastPlayerGain.keys.retainAll(currentPlayerIds)
        controlledPlayers.keys.retainAll(currentPlayerIds)
        // Keep the last known display assignment for an actively playing app. A foreground task
        // is not listed once the app moves behind another window; expiring it after 30 seconds
        // made long background playback impossible to control by screen.
        lastPackageRole.entries.removeAll { now - it.value.second > BACKGROUND_GRACE_MS && it.key !in activePackages }
    }

    private fun screenRoles(): Map<Int, ScreenRole> {
        val displays = try { (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays.toList() } catch (_: Exception) { emptyList() }
        if (displays.isEmpty()) return emptyMap()
        val sorted = displays.sortedBy { it.mode.physicalWidth.toLong() * it.mode.physicalHeight.toLong() }
        if (sorted.size == 1) return mapOf(sorted.first().displayId to ScreenRole.UPPER)
        return mapOf(sorted.last().displayId to ScreenRole.UPPER, sorted.first().displayId to ScreenRole.LOWER)
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
        val role = when (roleName) { "upper" -> ScreenRole.UPPER; "lower" -> ScreenRole.LOWER; else -> return }
        val active = bridge ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val percent = prefs.getInt(if (role == ScreenRole.UPPER) KEY_UPPER else KEY_LOWER, 100).coerceIn(0, 100)
        val screenGain = percent / 100f
        for ((playerId, control) in controlledPlayers.toMap()) {
            if (control.role != role) continue
            val gain = (screenGain * control.appGain).coerceIn(0f, 1f)
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
    }

    private fun logUnsupportedPlayer(playerId: Int, now: Long) {
        val last = lastUnsupportedLogAt[playerId] ?: 0L
        if (now - last >= UNSUPPORTED_LOG_INTERVAL_MS) {
            Log.w(TAG, "Player $playerId did not accept PlayerProxy.setVolume; screen gain cannot be applied to this audio pipeline")
            lastUnsupportedLogAt[playerId] = now
        }
    }

    private fun keepSystemMediaAtMaximum() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS_MEDIA_VOLUME)) {
            prefs.edit().putInt(KEY_PREVIOUS_MEDIA_VOLUME, audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != max) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
            catch (error: Exception) { Log.w(TAG, "Could not raise global media volume", error) }
        }
    }

    private fun restoreSystemMediaVolume() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREVIOUS_MEDIA_VOLUME)) return
        val previous = prefs.getInt(KEY_PREVIOUS_MEDIA_VOLUME, -1)
        try {
            if (previous >= 0) {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }
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

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenAudioVolumeService::class.java).setAction(ACTION_STOP))
        }

        fun gainChanged(context: Context, preferenceKey: String) {
            val role = if (preferenceKey == KEY_UPPER) "upper" else "lower"
            context.startService(Intent(context, ScreenAudioVolumeService::class.java)
                .setAction(ACTION_GAIN_CHANGED).putExtra(EXTRA_ROLE, role))
        }
    }
}
