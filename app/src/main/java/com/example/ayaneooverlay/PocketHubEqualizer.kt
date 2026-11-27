package com.example.ayaneooverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * System-wide equalizer on the global output mix (audio session 0).
 *
 * Preferred engine: DynamicsProcessing (Android 9+). It does the band EQ, an adjustable
 * pre-amp (the plain Equalizer on this firmware lowers loudness a lot) and a limiter so
 * boosting never clips. Fallback: the classic Equalizer plus LoudnessEnhancer for the gain.
 * BassBoost is used with both. Settings live in the "MainActivity" preferences; the effect
 * objects only exist while EqualizerService runs.
 */
object PocketHubEqualizer {
    private const val TAG = "PocketHubEqualizer"
    private const val PREFS = "MainActivity"
    const val KEY_ENABLED = "eq_enabled"
    const val KEY_LEVELS = "eq_levels"
    const val KEY_PRESET = "eq_preset"
    const val KEY_BASS = "eq_bass"
    /** Pre-amp in whole dB, 0..MAX_PREAMP_DB. */
    const val KEY_PREAMP = "eq_preamp_db"
    const val MAX_PREAMP_DB = 15
    const val PRESET_CUSTOM = "custom"
    private const val PRIORITY = 1000

    private var dynamics: android.media.audiofx.DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null

    /** "dp", "eq" or "" while off; shown in the dialog. */
    var engine: String = ""
        private set

    /** Band layout of this device, read once. */
    class Bands(val count: Int, val minLevel: Int, val maxLevel: Int, val centerHz: IntArray, val presets: List<String>)

    @Volatile private var cachedBands: Bands? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The device's band layout, or a standard 5-band layout if the Equalizer can't be probed. */
    fun bands(): Bands? {
        cachedBands?.let { return it }
        val probed = try {
            val probe = equalizer ?: Equalizer(0, 0)
            val range = probe.bandLevelRange
            val count = probe.numberOfBands.toInt()
            val centers = IntArray(count) { probe.getCenterFreq(it.toShort()) / 1000 }
            val presets = (0 until probe.numberOfPresets).map { probe.getPresetName(it.toShort()) }
            if (probe !== equalizer) probe.release()
            Bands(count, range[0].toInt(), range[1].toInt(), centers, presets)
        } catch (error: Throwable) {
            Log.w(TAG, "Equalizer probe failed, using a standard band layout", error)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                Bands(5, -1500, 1500, intArrayOf(60, 230, 910, 3600, 14000), emptyList())
            else null
        }
        return probed?.also { cachedBands = it }
    }

    fun levels(context: Context, bands: Bands): IntArray {
        val saved = prefs(context).getString(KEY_LEVELS, null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        return IntArray(bands.count) { (saved.getOrNull(it) ?: 0).coerceIn(bands.minLevel, bands.maxLevel) }
    }

    fun saveLevels(context: Context, levels: IntArray) {
        prefs(context).edit().putString(KEY_LEVELS, levels.joinToString(",")).apply()
    }

    /** 0..1000, BassBoost strength. */
    fun bass(context: Context) = prefs(context).getInt(KEY_BASS, 0).coerceIn(0, 1000)

    fun preamp(context: Context) = prefs(context).getInt(KEY_PREAMP, 0).coerceIn(0, MAX_PREAMP_DB)

    /** Fills levels from one of the device's built-in presets. */
    fun presetLevels(presetIndex: Int, bands: Bands): IntArray? = try {
        val eq = Equalizer(0, 0)
        eq.usePreset(presetIndex.toShort())
        val result = IntArray(bands.count) { eq.getBandLevel(it.toShort()).toInt() }
        eq.release()
        result
    } catch (error: Throwable) {
        Log.w(TAG, "Could not read preset $presetIndex", error); null
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) EqualizerService.start(context, restartPlayback = true) else EqualizerService.stop(context)
    }

    /** Pushes the saved settings to the live effect (no-op while it is off). */
    fun applySaved(context: Context) {
        val info = bands() ?: return
        val levels = levels(context, info)
        val preampDb = preamp(context)
        dynamics?.let { dp ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val eq = android.media.audiofx.DynamicsProcessing.Eq(true, true, info.count)
                    for (band in 0 until info.count) {
                        eq.getBand(band).apply {
                            isEnabled = true
                            cutoffFrequency = bandCutoff(info, band)
                            gain = levels[band] / 100f
                        }
                    }
                    dp.setPreEqAllChannelsTo(eq)
                    dp.setInputGainAllChannelsTo(preampDb.toFloat())
                    dp.enabled = true
                }
            } catch (error: Throwable) { Log.w(TAG, "Could not apply dynamics settings", error) }
        }
        equalizer?.let { eq ->
            try {
                levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level.toShort()) }
                eq.enabled = true
            } catch (error: Throwable) { Log.w(TAG, "Could not apply equalizer levels", error) }
            try {
                val enhancer = loudness ?: if (preampDb > 0) android.media.audiofx.LoudnessEnhancer(0).also { loudness = it } else null
                enhancer?.setTargetGain(preampDb * 100)
                enhancer?.enabled = preampDb > 0
            } catch (error: Throwable) { Log.w(TAG, "Could not apply loudness gain", error) }
        }
        try {
            val strength = bass(context)
            val boost = bassBoost ?: if (strength > 0) BassBoost(PRIORITY, 0).also { bassBoost = it } else null
            if (boost != null) {
                if (boost.strengthSupported) boost.setStrength(strength.toShort())
                boost.enabled = strength > 0
            }
        } catch (error: Throwable) { Log.w(TAG, "Could not apply bass boost", error) }
    }

    /** Upper edge of a band for DynamicsProcessing: halfway (geometric) to the next centre. */
    private fun bandCutoff(info: Bands, band: Int): Float =
        if (band >= info.count - 1) 20000f
        else Math.sqrt(info.centerHz[band].toDouble() * info.centerHz[band + 1].toDouble()).toFloat()

    internal fun attach(context: Context): Boolean {
        if (dynamics == null && equalizer == null) {
            val info = bands()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info != null) {
                dynamics = try {
                    val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                        android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2, true, info.count, false, 0, false, 0, true
                    ).setPreferredFrameDuration(10f).build()
                    android.media.audiofx.DynamicsProcessing(PRIORITY, 0, config).also { dp ->
                        // Limiter just under 0 dBFS so the pre-amp can't make the sound clip.
                        dp.setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(
                            true, true, 0, 1f, 60f, 10f, -1f, 0f))
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "DynamicsProcessing unavailable, falling back to Equalizer", error); null
                }
            }
            if (dynamics == null) {
                equalizer = try { Equalizer(PRIORITY, 0) } catch (error: Throwable) {
                    Log.w(TAG, "Could not attach the global equalizer", error); null
                }
            }
            engine = when { dynamics != null -> "dp"; equalizer != null -> "eq"; else -> "" }
        }
        applySaved(context)
        return dynamics != null || equalizer != null
    }

    internal fun detach() {
        try { dynamics?.enabled = false; dynamics?.release() } catch (_: Throwable) { }
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Throwable) { }
        try { loudness?.enabled = false; loudness?.release() } catch (_: Throwable) { }
        try { bassBoost?.enabled = false; bassBoost?.release() } catch (_: Throwable) { }
        dynamics = null
        equalizer = null
        loudness = null
        bassBoost = null
        engine = ""
    }

    /**
     * A global effect is only picked up by sound that starts after it was attached. Taking
     * transient audio focus for a moment makes music apps pause and resume by themselves,
     * so the song that is already playing gets the equalizer straight away.
     */
    internal fun restartPlayback(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (!audio.isMusicActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                if (audio.requestAudioFocus(request) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ audio.abandonAudioFocusRequest(request) }, 450L)
                }
            }
        } catch (error: Throwable) { Log.w(TAG, "Could not restart playback", error) }
    }
}

/** Keeps the global equalizer alive while it is switched on. */
class EqualizerService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Equalizer", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        startForeground(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PocketHub equalizer").setContentText("Equalizer is on").setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !PocketHubEqualizer.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PocketHubEqualizer.attach(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_RESTART_PLAYBACK, false) == true) PocketHubEqualizer.restartPlayback(this)
        return START_STICKY
    }

    override fun onDestroy() {
        PocketHubEqualizer.detach()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pockethub_equalizer"
        private const val NOTIFICATION_ID = 8211
        private const val ACTION_STOP = "com.example.ayaneooverlay.action.STOP_EQUALIZER"

        private const val EXTRA_RESTART_PLAYBACK = "restart_playback"

        fun start(context: Context, restartPlayback: Boolean = false) {
            val intent = Intent(context, EqualizerService::class.java).putExtra(EXTRA_RESTART_PLAYBACK, restartPlayback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            try { context.startService(Intent(context, EqualizerService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {
                context.stopService(Intent(context, EqualizerService::class.java))
            }
        }
    }
}
