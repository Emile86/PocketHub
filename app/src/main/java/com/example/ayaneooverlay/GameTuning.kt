package com.example.ayaneooverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Game tuning: user profiles bound to an app or to one game inside an app (an .exe in
 * GameHub / Winlator, a ROM file in an emulator), automatic search for the lowest CPU / GPU
 * limits that keep the frame rate, and an adaptive mode that keeps a target frame rate.
 *
 * Tuned profiles are ordinary PocketHub profiles named "tune:<id>", so their CPU, GPU, fan
 * and Turbo values live in the same preference keys as every other profile and are edited
 * with the same profile editor.
 */

/** Small root helper used by the tuning code (independent of the Activity). */
object TuneRoot {
    /** Through the shared "tuning" root shell (no new su per call); own su -c if it is busy or unavailable. */
    fun run(command: String): String? {
        RootShell.tuning.run(command, mergeStderr = true, timeoutMs = 60_000L, waitMs = 1_500L)?.let { return it.output }
        return runOwnSu(command)
    }

    private fun runOwnSu(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    } catch (_: Exception) { null }
}

/** A user profile from the Game tuning page. */
data class TunedProfile(
    val id: String,
    var name: String,
    /** App the profile belongs to (null = only applied by hand). */
    var pkg: String?,
    /** One game inside that app: lower-case file name of the .exe or ROM (null = the whole app). */
    var gameKey: String?,
    var gameLabel: String?,
    /** "fixed" = the saved limits, "adaptive" = PocketHub moves the limits to hold [targetFps]. */
    var mode: String = MODE_FIXED,
    /** Frame rate to hold in adaptive mode (0 = the rate found by auto-tune / 60). */
    var targetFps: Int = 0,
    var autoApply: Boolean = true,
    /** Frame rate measured at full speed during the last auto-tune (0 = never tuned). */
    var tunedBaseline: Int = 0,
    /** Noise level (NoiseLevel.*) the profile was tuned for; "" = follow its fan mode only. */
    var noise: String = "",
    /** What the profile keeps besides the frame rate: TuneGoal.KIND_* ("" = older profile, see [goal]). */
    var goalKind: String = "",
    /** Temperature target for a temperature goal, and the sensor it is read from (Thermal.SENSOR_*). */
    var tempTarget: Double = 0.0,
    var sensor: String = Thermal.SENSOR_MAX
) {
    /** The profile's heat / noise goal, or null when it has none (fan mode only). */
    fun goal(): TuneGoal? = when (goalKind) {
        TuneGoal.KIND_NOISE -> TuneGoal(TuneGoal.KIND_NOISE, noise = noise.ifBlank { NoiseLevel.BALANCED })
        TuneGoal.KIND_TEMP -> TuneGoal(TuneGoal.KIND_TEMP, tempTarget = tempTarget, sensor = sensor)
        TuneGoal.KIND_FPS -> TuneGoal(TuneGoal.KIND_FPS)
        else -> if (noise.isNotBlank()) TuneGoal(TuneGoal.KIND_NOISE, noise = noise) else null
    }

    fun setGoal(goal: TuneGoal?) {
        goalKind = goal?.kind ?: ""
        noise = if (goal != null && goal.kind == TuneGoal.KIND_NOISE) goal.noise else ""
        tempTarget = if (goal != null && goal.kind == TuneGoal.KIND_TEMP) goal.tempTarget else 0.0
        sensor = goal?.sensor ?: Thermal.SENSOR_MAX
    }

    val profileKey: String get() = PREFIX + id

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("pkg", pkg ?: JSONObject.NULL); put("gameKey", gameKey ?: JSONObject.NULL); put("gameLabel", gameLabel ?: JSONObject.NULL)
        put("mode", mode); put("targetFps", targetFps); put("autoApply", autoApply); put("tunedBaseline", tunedBaseline); put("noise", noise)
        put("goalKind", goalKind); put("tempTarget", tempTarget); put("sensor", sensor)
    }

    companion object {
        const val PREFIX = "tune:"
        const val MODE_FIXED = "fixed"
        const val MODE_ADAPTIVE = "adaptive"

        fun fromJson(o: JSONObject) = TunedProfile(
            id = o.getString("id"),
            name = o.optString("name", "Profile"),
            pkg = o.optString("pkg").takeIf { !o.isNull("pkg") && it.isNotBlank() },
            gameKey = o.optString("gameKey").takeIf { !o.isNull("gameKey") && it.isNotBlank() },
            gameLabel = o.optString("gameLabel").takeIf { !o.isNull("gameLabel") && it.isNotBlank() },
            mode = o.optString("mode", MODE_FIXED),
            targetFps = o.optInt("targetFps", 0),
            autoApply = o.optBoolean("autoApply", true),
            tunedBaseline = o.optInt("tunedBaseline", 0),
            noise = o.optString("noise", ""),
            goalKind = o.optString("goalKind", ""),
            tempTarget = o.optDouble("tempTarget", 0.0),
            sensor = o.optString("sensor", Thermal.SENSOR_MAX)
        )
    }
}

object TunedProfiles {
    private const val PREFS = "MainActivity"
    private const val KEY = "tuned_profiles"
    /** Master switch: switch to a matching tuned profile when its game is on the top screen. */
    const val KEY_AUTO_SWITCH = "tuned_auto_switch"
    /** Quiet mode: every profile runs one noise level quieter, with a temperature guard. */
    const val KEY_QUIET_MODE = "quiet_mode"
    const val MAX_PROFILES = 40

    fun isTuned(profile: String) = profile.startsWith(TunedProfile.PREFIX)

    fun load(context: Context): MutableList<TunedProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> try { TunedProfile.fromJson(array.getJSONObject(i)) } catch (_: Exception) { null } }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(context: Context, list: List<TunedProfile>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun byKey(context: Context, profileKey: String): TunedProfile? =
        if (!isTuned(profileKey)) null else load(context).firstOrNull { it.profileKey == profileKey }

    fun upsert(context: Context, profile: TunedProfile) {
        val list = load(context)
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) list[index] = profile else list.add(0, profile)
        save(context, list.take(MAX_PROFILES))
    }

    fun delete(context: Context, id: String) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
        val key = (TunedProfile.PREFIX + id).lowercase(Locale.ROOT)
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        listOf("tune_fan_", "tune_gpu_", "tune_gpu_lock_", "tune_turbo_", "tune_cpu_", "tune_cpu_policy0_", "tune_cpu_policy3_", "tune_cpu_policy7_")
            .forEach { edit.remove(it + key) }
        edit.apply()
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36).lowercase(Locale.ROOT)

    /** Most specific automatic profile for what runs: same app and game first, then the app alone. */
    fun match(context: Context, game: DetectedGame?): TunedProfile? {
        game ?: return null
        val list = load(context).filter { it.autoApply && it.pkg == game.pkg }
        if (game.gameKey != null) list.firstOrNull { it.gameKey == game.gameKey }?.let { return it }
        return list.firstOrNull { it.gameKey == null }
    }
}

/** What runs on the top screen: the app and, when PocketHub can tell, the game inside it. */
data class DetectedGame(val pkg: String, val gameKey: String?, val gameLabel: String?, val source: String?)

object GameDetector {
    private const val TAG = "PocketHubGameDetect"

    /** Windows helpers that run in every Wine prefix and are never "the game". */
    private val EXE_IGNORE = setOf(
        "wineserver.exe", "explorer.exe", "services.exe", "winedevice.exe", "plugplay.exe", "svchost.exe",
        "rpcss.exe", "conhost.exe", "tabtip.exe", "start.exe", "winhandler.exe", "wfm.exe", "cmd.exe",
        "rundll32.exe", "winemenubuilder.exe", "steamwebhelper.exe", "steamservice.exe", "crashpad_handler.exe",
        "unitycrashhandler64.exe", "unitycrashhandler32.exe", "easyanticheat.exe", "easyanticheat_eos.exe",
        "msiexec.exe", "regedit.exe", "wineboot.exe", "taskmgr.exe", "control.exe", "dxsetup.exe", "vcredist_x64.exe",
        "vcredist_x86.exe", "dotnetfx.exe", "update.exe", "launcher.exe", "steam.exe", "epicgameslauncher.exe",
        "gogwebhelper.exe", "galaxyclient.exe", "uplay.exe", "upc.exe", "ubisoftconnect.exe", "eadesktop.exe", "origin.exe"
    )
    private val ROM_EXTENSIONS = listOf(
        "nsp", "xci", "nsz", "xcz", "nca", "iso", "cso", "chd", "rvz", "gcm", "gcz", "wbfs", "wia", "wua", "wux", "rpx",
        "3ds", "cia", "cci", "cxi", "3dsx", "nds", "gba", "gbc", "gb", "sfc", "smc", "nes", "n64", "z64", "v64",
        "pbp", "cue", "bin", "img", "mdf", "ecm", "vpk", "elf", "xex", "md", "gen", "sms", "gg", "pce", "ws", "wsc",
        "ngp", "ngc", "a26", "lnx", "jag", "32x", "dsk", "adf", "zip", "7z"
    )

    /** Package of the focused app on the top screen (display 0), or null. */
    fun foregroundPackage(): String? {
        val dump = TuneRoot.run("dumpsys window displays 2>/dev/null | grep -E 'Display: mDisplayId=|mCurrentFocus=|mFocusedApp='") ?: return null
        var onTop = false
        val pkgRegex = Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
        for (line in dump.lineSequence()) {
            if (line.contains("Display: mDisplayId=")) { onTop = line.contains("mDisplayId=0 ") || line.trim().endsWith("mDisplayId=0"); continue }
            if (!onTop) continue
            val pkg = pkgRegex.find(line)?.groupValues?.getOrNull(1) ?: continue
            if (pkg == "com.example.ayaneooverlay" || pkg == "com.android.systemui") continue
            // A home screen (Android launcher, AYA Home, PocketHub Launcher…) is never a game.
            if (isLauncher(pkg)) return null
            return pkg
        }
        return null
    }

    @Volatile private var launchers: Set<String> = emptySet()
    @Volatile private var launchersReadAt = 0L

    /** Every installed home-screen app, from the HOME intent (refreshed every few minutes). */
    private fun launcherPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (launchers.isNotEmpty() && now - launchersReadAt < 5 * 60_000L) return launchers
        val out = TuneRoot.run("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null") ?: ""
        val found = Regex("""\b([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""").findAll(out).map { it.groupValues[1] }.toSet()
        launchers = found
        launchersReadAt = now
        return found
    }

    fun isLauncher(pkg: String): Boolean {
        val lower = pkg.lowercase()
        if (lower.contains("launcher") || lower.contains("ayahome") || lower.contains("ayaneo.home") ||
            lower.contains("quickstep") || lower.contains("pockethub")) return true
        return pkg in launcherPackages()
    }

    /** Foreground app on the top screen plus, when found, the .exe or ROM it is running. */
    fun detect(): DetectedGame? {
        val pkg = foregroundPackage() ?: return null
        return detectIn(pkg)
    }

    fun detectIn(pkg: String): DetectedGame {
        val safe = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val extensions = ROM_EXTENSIONS.joinToString("|")
        // One root shell: the app's processes (user name taken from its data folder) with their
        // command lines, and the files they hold open that look like game images.
        val script = """
            # The app's user id. Not from /data/data: a root shell started by an app only sees its
            # own data folder there (app data isolation). Take it from the running process instead.
            uid=
            for p in ${'$'}(pidof $safe 2>/dev/null); do uid=${'$'}(stat -c %u /proc/${'$'}p 2>/dev/null); [ -n "${'$'}uid" ] && break; done
            [ -n "${'$'}uid" ] || uid=${'$'}(pm list packages -U $safe 2>/dev/null | grep "package:$safe uid:" | head -n 1 | sed 's/.*uid://; s/[^0-9].*//')
            [ -n "${'$'}uid" ] || exit 0
            ps -A -o UID=,PID=,RSS= 2>/dev/null | while read u pid rss; do
              [ "${'$'}u" = "${'$'}uid" ] || continue
              # Full command line from /proc: ps cuts long ones (paths with spaces) without a terminal.
              args=${'$'}(tr '\000' ' ' < /proc/${'$'}pid/cmdline 2>/dev/null)
              [ -n "${'$'}args" ] || continue
              # printf, not echo: echo treats the backslashes of Windows paths as escapes (\t, \r, \c…).
              printf 'P|%s|%s\n' "${'$'}rss" "${'$'}args"
              [ "${'$'}rss" -gt 40000 ] 2>/dev/null || continue
              ls -l /proc/${'$'}pid/fd 2>/dev/null | grep -iE '\.($extensions)${'$'}' | sed 's/.*-> /F|/'
            done
        """.trimIndent()
        val output = TuneRoot.run(script).orEmpty()
        var bestExe: String? = null
        var bestArgs = ""
        var bestRss = -1L
        val exeRegex = Regex("""([^\\/:"*?<>|\s]+\.exe)\b""", RegexOption.IGNORE_CASE)
        val roms = mutableListOf<String>()
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("P|") -> {
                    val parts = line.split("|", limit = 3)
                    val rss = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                    val args = parts.getOrNull(2).orEmpty()
                    exeRegex.findAll(args).map { it.groupValues[1] }
                        .filter { it.lowercase(Locale.ROOT) !in EXE_IGNORE }
                        .lastOrNull()?.let { exe ->
                            if (rss > bestRss) { bestRss = rss; bestExe = exe; bestArgs = args }
                        }
                }
                line.startsWith("F|") -> {
                    val path = line.removePrefix("F|").trim()
                    if (!path.contains("/cache/", true) && !path.contains("/tmp/", true)) roms += path
                }
            }
        }
        bestExe?.let { exe ->
            // Steam games: the folder under steamapps\common is the real name ("Tomb Raider").
            val folder = Regex("""steamapps[\\/]common[\\/]([^\\/]+)[\\/]""", RegexOption.IGNORE_CASE)
                .find(bestArgs)?.groupValues?.getOrNull(1)?.trim()
            logChange(pkg, exe)
            return DetectedGame(pkg, exe.lowercase(Locale.ROOT), folder?.takeIf { it.isNotBlank() } ?: prettyName(exe), "exe")
        }
        // Prefer real disc / cartridge images over archives when both are open.
        val rom = roms.firstOrNull { !it.endsWith(".zip", true) && !it.endsWith(".7z", true) } ?: roms.firstOrNull()
        if (rom != null) {
            val file = rom.substringAfterLast('/')
            logChange(pkg, file)
            return DetectedGame(pkg, file.lowercase(Locale.ROOT), prettyName(file), "rom")
        }
        logChange(pkg, null)
        return DetectedGame(pkg, null, null, null)
    }

    @Volatile private var lastLogged: String? = null

    /** Logs only when what runs changes, not on every check. */
    private fun logChange(pkg: String, game: String?) {
        val key = "$pkg|$game"
        if (key == lastLogged) return
        lastLogged = key
        Log.i(TAG, if (game != null) "$pkg → $game" else "$pkg: no separate game")
    }

    /** "The_Legend_of_Zelda [0100F2C0115B6000][v0].nsp" → "The Legend of Zelda". */
    fun prettyName(file: String): String {
        var name = file.substringBeforeLast('.')
        name = name.replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
        name = name.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        return name.ifBlank { file }
    }
}

/** Frame rate of the focused app on the top screen, read from SurfaceFlinger (needs root). */
object FpsProbe {
    @Volatile private var layer: String? = null
    @Volatile private var layerPkg: String? = null
    @Volatile private var layerCheckedAt = 0L

    /** Average FPS over the last [windowMs]; null when no game layer is found. */
    fun read(windowMs: Long = 1000L): Int? {
        val now = SystemClock.elapsedRealtime()
        var current = layer
        if (current == null || now - layerCheckedAt > 6000L) {
            layerCheckedAt = now
            current = resolveLayer()
            layer = current
        }
        current ?: return null
        val fps = fromLayer(current, windowMs * 1_000_000L)
        if (fps == null) layer = null
        return fps
    }

    fun currentPackage(): String? = layerPkg

    fun reset() { layer = null; layerPkg = null }

    private fun resolveLayer(): String? {
        val pkg = GameDetector.foregroundPackage() ?: return null
        layerPkg = pkg
        val layers = TuneRoot.run("dumpsys SurfaceFlinger --list 2>/dev/null")?.lineSequence()
            ?.filter { it.contains(pkg) && !it.contains("com.example.ayaneooverlay") }?.toList().orEmpty()
        if (layers.isEmpty()) return null
        val preferred = layers.filter { it.contains("SurfaceView", true) || it.contains("BLAST", true) || it.contains("BufferQueue", true) }.ifEmpty { layers }
        return preferred.take(5).maxByOrNull { fromLayer(it, 2_000_000_000L) ?: -1 }
    }

    private fun fromLayer(name: String, windowNs: Long): Int? {
        val escaped = name.replace("'", "'\\''")
        val dump = TuneRoot.run("dumpsys SurfaceFlinger --latency '$escaped' 2>/dev/null") ?: return null
        val stamps = dump.lineSequence().drop(1).mapNotNull { line ->
            val ts = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (ts <= 0L || ts >= Long.MAX_VALUE / 2) null else ts
        }.distinct().sorted().toList()
        if (stamps.size < 2) return 0
        val newest = stamps.last()
        if (System.nanoTime() - newest > windowNs) return 0
        val recent = stamps.filter { it >= newest - windowNs }
        if (recent.size < 2) return 0
        val span = recent.last() - recent.first()
        if (span <= 0L) return null
        val fps = (recent.size - 1) * 1_000_000_000.0 / span
        return fps.takeIf { it in 0.0..240.0 }?.roundToInt()
    }
}

/**
 * Noise targets for auto-tune and tuned profiles: the fan mode that level uses and the
 * temperature PocketHub keeps the chip under at that level (0 = no limit, only the frame rate).
 * "Silent" stays under the 70 °C point where overheat protection would start the fan.
 */
object NoiseLevel {
    const val SILENT = "silent"
    const val QUIET = "quiet"
    const val BALANCED = "balanced"
    const val COOLING = "cooling"
    val ORDER = listOf(SILENT, QUIET, BALANCED, COOLING)

    fun fanMode(level: String): String = when (level) {
        SILENT -> "FAN_MODE_OFF"
        QUIET -> "FAN_MODE_MUTE"
        BALANCED -> "FAN_MODE_BALANCE"
        else -> "FAN_MODE_TURBO"
    }

    fun tempLimit(level: String): Double = when (level) {
        SILENT -> 66.0
        QUIET -> 78.0
        BALANCED -> 86.0
        else -> 0.0
    }

    fun quieter(level: String): String = ORDER.getOrElse(ORDER.indexOf(level) - 1) { SILENT }
    fun louder(level: String): String? = ORDER.getOrNull(ORDER.indexOf(level) + 1)

    /** Noise level matching a fan mode (custom curve / OEM auto count as balanced). */
    fun fromFanMode(mode: String?): String = when (mode) {
        "FAN_MODE_OFF" -> SILENT
        "FAN_MODE_MUTE" -> QUIET
        "FAN_MODE_TURBO" -> COOLING
        else -> BALANCED
    }
}

/**
 * What auto-tune keeps besides the frame rate. Frame rate, fan noise and temperature are tied
 * together (the chip's power is the heat, the fan takes it away), so only one of noise or
 * temperature is chosen; the other follows.
 *  - noise: the fan stays at a noise level; PocketHub keeps that level's safety temperature.
 *  - temp: a temperature target on one sensor; the fan may work as hard as it needs.
 *  - fps: only the frame rate counts; strongest fan, temperature within overheat protection.
 */
data class TuneGoal(
    val kind: String,
    val noise: String = NoiseLevel.BALANCED,
    val tempTarget: Double = 0.0,
    val sensor: String = Thermal.SENSOR_MAX
) {
    fun fanMode(): String = if (kind == KIND_NOISE) NoiseLevel.fanMode(noise) else "FAN_MODE_TURBO"

    fun tempLimit(): Double = when (kind) {
        KIND_NOISE -> NoiseLevel.tempLimit(noise)
        KIND_TEMP -> tempTarget
        else -> 0.0
    }

    /** Sensor the limit is read from (noise levels use the hottest CPU / GPU zone). */
    fun limitSensor(): String = if (kind == KIND_TEMP) sensor else Thermal.SENSOR_MAX

    companion object {
        const val KIND_NOISE = "noise"
        const val KIND_TEMP = "temp"
        const val KIND_FPS = "fps"
    }
}

/** Temperatures from the thermal zones in °C (needs root). */
object Thermal {
    const val SENSOR_MAX = "max"
    const val SENSOR_CPU = "cpu"
    const val SENSOR_GPU = "gpu"
    /** Body / skin sensor: what your hands feel (only on devices that have one). */
    const val SENSOR_SKIN = "skin"

    @Volatile private var skinChecked = false
    @Volatile private var skinPresent = false

    private fun pattern(sensor: String): String = when (sensor) {
        SENSOR_CPU -> "*cpu*|*cluster*|*silver*|*gold*"
        SENSOR_GPU -> "*gpu*|*gpuss*"
        SENSOR_SKIN -> "*skin*|*quiet*"
        else -> "*cpu*|*cluster*|*silver*|*gold*|*gpu*|*gpuss*"
    }

    /** Hottest zone of [sensor], or null when none can be read. */
    fun read(sensor: String = SENSOR_MAX): Double? {
        val raw = TuneRoot.run("""
            m=0
            for z in /sys/class/thermal/thermal_zone*; do
              IFS= read -r t < "${'$'}z/type" 2>/dev/null || continue
              case "${'$'}t" in ${pattern(sensor)}) ;; *) continue ;; esac
              IFS= read -r v < "${'$'}z/temp" 2>/dev/null || continue
              [ "${'$'}v" -gt "${'$'}m" ] 2>/dev/null && m=${'$'}v
            done
            echo ${'$'}m
        """.trimIndent())?.trim()?.toDoubleOrNull() ?: return null
        val celsius = if (raw > 1000.0) raw / 1000.0 else raw
        return celsius.takeIf { it in 15.0..130.0 }
    }

    fun maxCelsius(): Double? = read(SENSOR_MAX)

    /** Body sensor known to exist (without a new root call; false until checked once). */
    fun skinKnown(): Boolean = skinChecked && skinPresent

    /** True when the device reports a body / skin temperature sensor (checked once). */
    fun hasSkinSensor(): Boolean {
        if (!skinChecked) {
            skinPresent = read(SENSOR_SKIN) != null
            skinChecked = true
        }
        return skinPresent
    }
}

/** Writes CPU / GPU limits straight to sysfs (used while tuning and by the adaptive mode). */
object FreqControl {
    val FALLBACK_GPU_HZ = listOf(124800000L, 220000000L, 295000000L, 348000000L, 401000000L, 475000000L, 550000000L,
        615000000L, 680000000L, 719000000L, 746000000L, 794000000L, 827000000L, 860000000L, 1000000000L)

    fun gpuSteps(): List<Long> {
        val raw = TuneRoot.run("for d in /sys/class/devfreq/*; do case \"\$d\" in *kgsl*|*3d00000*) cat \"\$d/available_frequencies\" 2>/dev/null; break;; esac; done").orEmpty()
        val values = raw.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.distinct().sorted()
        return if (values.size >= 3) values else FALLBACK_GPU_HZ
    }

    fun cpuSteps(policies: Collection<String>, fallback: Map<String, List<Int>>): Map<String, List<Int>> = policies.associateWith { policy ->
        val raw = TuneRoot.run("cat /sys/devices/system/cpu/cpufreq/$policy/scaling_available_frequencies 2>/dev/null").orEmpty()
        val values = raw.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.distinct().sorted()
        if (values.size >= 3) values else fallback[policy].orEmpty()
    }

    fun apply(cpu: Map<String, Int>, gpuHz: Long?) {
        val script = buildString {
            cpu.forEach { (policy, target) ->
                appendLine("d=/sys/devices/system/cpu/cpufreq/$policy")
                appendLine("read curmin < \$d/scaling_min_freq 2>/dev/null; read floor < \$d/cpuinfo_min_freq 2>/dev/null")
                appendLine("[ -n \"\$curmin\" ] && [ \"\$curmin\" -gt $target ] 2>/dev/null && echo \"\$floor\" > \$d/scaling_min_freq 2>/dev/null")
                appendLine("echo $target > \$d/scaling_max_freq 2>/dev/null")
            }
            if (gpuHz != null) {
                appendLine("for d in /sys/class/devfreq/*; do case \"\$d\" in *kgsl*|*3d00000*) ;; *) continue ;; esac")
                appendLine("  low=\$(cat \$d/available_frequencies 2>/dev/null | tr ' ' '\\n' | grep -E '^[0-9]+\$' | sort -n | head -n 1)")
                appendLine("  [ -n \"\$low\" ] && echo \$low > \$d/min_freq 2>/dev/null")
                appendLine("  echo $gpuHz > \$d/max_freq 2>/dev/null")
                appendLine("done")
                appendLine("echo $gpuHz > /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null")
            }
            appendLine("true")
        }
        TuneRoot.run(script)
    }
}

/**
 * Finds the lowest CPU / GPU limits that keep the frame rate of the game on the top screen.
 * Runs on its own thread; the caller stops PocketHub's own limit keepers first and applies a
 * profile again afterwards (the saved result, or the previous profile when cancelled).
 */
object AutoTuner {
    private const val TAG = "PocketHubAutoTune"
    private val CAPS = listOf(30, 40, 45, 60, 72, 90, 120, 144, 165)

    data class Result(
        val baselineFps: Int,
        val targetFps: Int,
        val capped: Boolean,
        val cpu: Map<String, Int>,
        val gpuHz: Long,
        val maxCpu: Map<String, Int>,
        val maxGpuHz: Long,
        val pkg: String?,
        /** Goal the run was made for and the temperature limit it used (0 = none). */
        val goal: TuneGoal = TuneGoal(TuneGoal.KIND_FPS),
        val tempLimit: Double = 0.0,
        /** Hottest temperature seen while settling, and the frame rate at the end. */
        val maxTemp: Double = 0.0,
        val achievedFps: Int = 0,
        /** True when the goal could only be kept by giving up some frame rate. */
        val short: Boolean = false,
        /** Limits that kept the frame rate before the temperature step (used for "louder, full FPS"). */
        val fpsCpu: Map<String, Int> = cpu,
        val fpsGpuHz: Long = gpuHz
    )

    interface Listener {
        fun onProgress(stage: String, detail: String, fps: Int?, percent: Int)
        fun onFinished(result: Result?, error: String?)
    }

    @Volatile var running = false
        private set
    @Volatile private var cancelled = false
    @Volatile var listener: Listener? = null
    /** Last finished result, kept so a reopened window can still save it. */
    @Volatile var lastResult: Result? = null
    private val main = Handler(Looper.getMainLooper())

    fun cancel() { cancelled = true }

    /**
     * @param tolerancePct how much lower the average may be for uncapped games.
     * @param forcedTarget frame rate to hold (0 = detect from the full-speed baseline).
     * @param labels localized stage names: baseline, gpu, prime, big, little, verify.
     */
    fun start(cpuPolicies: Map<String, List<Int>>, tolerancePct: Int, forcedTarget: Int, labels: Map<String, String>, goal: TuneGoal = TuneGoal(TuneGoal.KIND_FPS)) {
        if (running) return
        running = true
        cancelled = false
        lastResult = null
        Thread({
            var result: Result? = null
            var error: String? = null
            try {
                result = runTune(cpuPolicies, tolerancePct, forcedTarget, labels, goal)
            } catch (e: CancelledException) {
                error = "cancelled"
            } catch (e: TuneException) {
                error = e.message
            } catch (e: Exception) {
                Log.w(TAG, "auto-tune failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
            running = false
            lastResult = result
            main.post { listener?.onFinished(result, error) }
        }, "PocketHub-autotune").apply { isDaemon = true; start() }
    }

    private class CancelledException : Exception()
    class TuneException(message: String) : Exception(message)

    private fun progress(stage: String, detail: String, fps: Int?, percent: Int) {
        main.post { listener?.onProgress(stage, detail, fps, percent) }
    }

    private fun sleepChecked(ms: Long) {
        var left = ms
        while (left > 0) {
            if (cancelled) throw CancelledException()
            val step = minOf(left, 200L)
            Thread.sleep(step)
            left -= step
        }
    }

    /** Samples once a second; returns (average, low) or throws when the game stopped drawing. */
    private fun measure(seconds: Int, stage: String, detail: String, percent: Int, pkg: String?): Pair<Double, Int> {
        val samples = mutableListOf<Int>()
        var empty = 0
        repeat(seconds) {
            sleepChecked(1000L)
            val fps = FpsProbe.read(1000L)
            if (pkg != null && FpsProbe.currentPackage() != null && FpsProbe.currentPackage() != pkg) {
                throw TuneException("game_changed")
            }
            if (fps == null || fps <= 0) empty++ else samples += fps
            progress(stage, detail, fps, percent)
        }
        if (samples.size < (seconds + 1) / 2 || empty > seconds / 2) throw TuneException("no_frames")
        val avg = samples.average()
        // Second-lowest sample: one hiccup (a load, a GC) does not fail a step.
        val low = samples.sorted().let { if (it.size >= 3) it[1] else it.first() }
        return avg to low
    }

    private fun runTune(policies: Map<String, List<Int>>, tolerancePct: Int, forcedTarget: Int, labels: Map<String, String>, goal: TuneGoal): Result {
        FpsProbe.reset()
        val gpuSteps = FreqControl.gpuSteps()
        val cpuSteps = FreqControl.cpuSteps(policies.keys, policies).filterValues { it.isNotEmpty() }
        val maxCpu = cpuSteps.mapValues { it.value.last() }
        val maxGpu = gpuSteps.last()
        val pkg = GameDetector.foregroundPackage()

        // 1. Full speed: warm up, then measure the reference frame rate.
        FreqControl.apply(maxCpu, maxGpu)
        progress(labels["baseline"].orEmpty(), "", null, 2)
        sleepChecked(3000L)
        val (baseAvg, baseLow) = measure(8, labels["baseline"].orEmpty(), "", 6, pkg)
        val baseline = baseAvg.roundToInt()
        if (baseline < 10) throw TuneException("no_frames")
        val cap = CAPS.firstOrNull { abs(baseAvg - it) <= 2.0 && baseLow >= it - 5 }
        val capped = forcedTarget > 0 || cap != null
        val target = when {
            forcedTarget > 0 -> forcedTarget.coerceAtMost(baseline + 2)
            cap != null -> cap
            else -> baseline
        }
        val allowance = if (capped) 1.5 else maxOf(1.0, target * tolerancePct / 100.0)
        fun passes(avg: Double, low: Int): Boolean = avg >= target - allowance && low >= target - allowance * 2 - 3

        // 2. Lower one knob at a time with a binary search; the others stay where they are.
        val chosenCpu = maxCpu.toMutableMap()
        var chosenGpu = maxGpu
        val knobs = mutableListOf<Pair<String, List<Long>>>()
        knobs += "gpu" to gpuSteps
        listOf("policy7" to "prime", "policy3" to "big", "policy0" to "little").forEach { (policy, _) ->
            cpuSteps[policy]?.let { steps -> knobs += policy to steps.map { it.toLong() } }
        }
        cpuSteps.keys.filter { it !in setOf("policy7", "policy3", "policy0") }.forEach { policy ->
            knobs += policy to cpuSteps.getValue(policy).map { it.toLong() }
        }
        val totalTests = knobs.sumOf { (_, steps) -> kotlin.math.ceil(kotlin.math.log2(steps.size.toDouble())).toInt() + 1 }.coerceAtLeast(1)
        var testsDone = 0
        val tempLimit = goal.tempLimit()
        val sensor = goal.limitSensor()
        val searchEnd = if (tempLimit > 0) 72 else 90
        fun percent() = (10 + (searchEnd - 10) * testsDone / totalTests).coerceIn(10, searchEnd)

        for ((knob, steps) in knobs) {
            val stage = when (knob) {
                "gpu" -> labels["gpu"]
                "policy7" -> labels["prime"]
                "policy3" -> labels["big"]
                "policy0" -> labels["little"]
                else -> knob
            }.orEmpty()
            var lo = 0
            var hi = steps.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) / 2
                val value = steps[mid]
                if (knob == "gpu") FreqControl.apply(chosenCpu, value)
                else FreqControl.apply(chosenCpu + (knob to value.toInt()), chosenGpu)
                val detail = if (knob == "gpu") "${value / 1_000_000} MHz" else "${value / 1000} MHz"
                sleepChecked(1500L)
                val (avg, low) = measure(5, stage, detail, percent(), pkg)
                testsDone++
                if (passes(avg, low)) hi = mid else lo = mid + 1
            }
            // One step of headroom so heavier scenes keep the frame rate too.
            val pick = (hi + 1).coerceAtMost(steps.lastIndex)
            if (knob == "gpu") chosenGpu = steps[pick] else chosenCpu[knob] = steps[pick].toInt()
            FreqControl.apply(chosenCpu, chosenGpu)
            testsDone++
        }

        // 3. Check everything together; if it falls short, give every knob one more step.
        val verifyAt = if (tempLimit > 0) 75 else 92
        progress(labels["verify"].orEmpty(), "", null, verifyAt)
        sleepChecked(1500L)
        val (avg, low) = measure(8, labels["verify"].orEmpty(), "", verifyAt + 3, pkg)
        if (!passes(avg, low)) {
            chosenGpu = gpuSteps.firstOrNull { it > chosenGpu } ?: maxGpu
            cpuSteps.forEach { (policy, steps) ->
                chosenCpu[policy] = steps.firstOrNull { it > (chosenCpu[policy] ?: 0) } ?: steps.last()
            }
            FreqControl.apply(chosenCpu, chosenGpu)
        }
        val fpsCpu = chosenCpu.toMap()
        val fpsGpu = chosenGpu
        if (tempLimit <= 0) {
            progress(labels["verify"].orEmpty(), "", avg.roundToInt(), 100)
            return Result(baseline, target, capped, chosenCpu, chosenGpu, maxCpu, maxGpu, pkg, goal, 0.0, 0.0, avg.roundToInt(), false, fpsCpu, fpsGpu)
        }

        // 4. Heat: a 7-second step is too short for the temperature to settle. Keep the limits
        // for a while at the chosen fan level and lower GPU / big cores while the chip heads for
        // the level's limit, until it holds steady below it.
        val soak = labels["soak"].orEmpty()
        val bigPolicies = listOf("policy7", "policy3").filter { cpuSteps.containsKey(it) }
        val temps = ArrayDeque<Pair<Long, Double>>()
        val fpsTail = ArrayDeque<Double>()
        var maxTemp = 0.0
        var lowerGpuNext = true
        val soakStart = SystemClock.elapsedRealtime()
        var steadySince = soakStart
        var atFloor = false
        val slowSensor = sensor == Thermal.SENSOR_SKIN
        val maxSoakMs = if (slowSensor) 360_000L else 240_000L
        val steadyNeedMs = if (slowSensor) 120_000L else 75_000L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - soakStart
            val pct = (80 + 19 * elapsed / maxSoakMs).toInt().coerceIn(80, 99)
            val temp = Thermal.read(sensor)
            val detail = (temp?.let { String.format(Locale.US, "%.0f °C", it) } ?: "— °C") + String.format(Locale.US, " / %.0f °C", tempLimit)
            val (fAvg, _) = measure(5, soak, detail, pct, pkg)
            fpsTail.addLast(fAvg); while (fpsTail.size > 3) fpsTail.removeFirst()
            if (temp == null) {
                if (elapsed > 60_000L) break
                continue
            }
            maxTemp = maxOf(maxTemp, temp)
            temps.addLast(now to temp)
            while (temps.size > 1 && now - temps.first().first > 30_000L) temps.removeFirst()
            val slopePerMin = if (temps.size >= 2) {
                val (t0, v0) = temps.first()
                val span = (now - t0).coerceAtLeast(1L)
                (temp - v0) * 60_000.0 / span
            } else 0.0
            val heading = temp >= tempLimit || (elapsed > 20_000L && temp + slopePerMin * 1.0 >= tempLimit + 1.0)
            if (heading) {
                // Lower one knob a step; alternate GPU and the big cores.
                var lowered = false
                if (lowerGpuNext) {
                    val i = gpuSteps.indexOf(chosenGpu)
                    if (i > 0) { chosenGpu = gpuSteps[i - 1]; lowered = true }
                }
                if (!lowered) {
                    bigPolicies.forEach { p ->
                        val steps = cpuSteps.getValue(p)
                        val i = steps.indexOf(chosenCpu[p] ?: steps.last())
                        if (i > 0) { chosenCpu[p] = steps[i - 1]; lowered = true }
                    }
                }
                if (!lowered && !lowerGpuNext) {
                    val i = gpuSteps.indexOf(chosenGpu)
                    if (i > 0) { chosenGpu = gpuSteps[i - 1]; lowered = true }
                }
                lowerGpuNext = !lowerGpuNext
                if (!lowered) { atFloor = true; break }
                FreqControl.apply(chosenCpu, chosenGpu)
                steadySince = now
                temps.clear()
            } else if (now - steadySince >= steadyNeedMs && slopePerMin < 1.5) {
                break
            }
            if (elapsed > maxSoakMs) break
        }
        val achieved = if (fpsTail.isEmpty()) avg else fpsTail.average()
        val short = achieved < target - allowance || atFloor
        progress(soak, String.format(Locale.US, "%.0f °C", maxTemp), achieved.roundToInt(), 100)
        return Result(baseline, target, capped, chosenCpu, chosenGpu, maxCpu, maxGpu, pkg, goal, tempLimit, maxTemp, achieved.roundToInt(), short, fpsCpu, fpsGpu)
    }
}

/**
 * Adaptive mode: keeps a target frame rate with as little CPU / GPU as possible. Every two
 * seconds it raises the limits when the game falls short and slowly lowers them while the
 * target is held. Works between a floor (lowest steps it may use) and the full-speed ceiling.
 */
object AdaptiveGovernor {
    private const val TAG = "PocketHubAdaptive"
    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile var activeKey: String? = null
        private set
    @Volatile var status: String = ""
        private set

    fun isRunning() = thread?.isAlive == true

    fun stop() {
        stopFlag = true
        thread?.interrupt()
        thread = null
        activeKey = null
    }

    /**
     * @param start limits to begin with (the profile's saved / tuned values).
     * @param cpuSteps available frequencies per policy; @param gpuSteps available GPU frequencies.
     */
    /**
     * @param targetFps frame rate to hold; 0 = temperature guard only (never above [start]).
     * @param tempLimit keep the chip under this temperature (0 = no limit).
     */
    fun start(profileKey: String, targetFps: Int, start: Map<String, Int>, startGpu: Long, cpuSteps: Map<String, List<Int>>, gpuSteps: List<Long>, tempLimit: Double = 0.0, sensor: String = Thermal.SENSOR_MAX) {
        stop()
        stopFlag = false
        activeKey = profileKey
        val guardOnly = targetFps <= 0
        val target = if (guardOnly) 0 else targetFps.coerceIn(20, 165)
        val t = Thread({
            try {
                FpsProbe.reset()
                // Big cores (policy3 + policy7) move together as one knob; little cores keep their value.
                var gpuIdx = gpuSteps.indexOfFirst { it >= startGpu }.let { if (it < 0) gpuSteps.lastIndex else it }
                val bigPolicies = listOf("policy3", "policy7").filter { cpuSteps.containsKey(it) }
                fun cpuIndexFor(level: Float): Map<String, Int> = cpuSteps.mapValues { (policy, steps) ->
                    if (policy in bigPolicies) steps[(level * steps.lastIndex).roundToInt().coerceIn(0, steps.lastIndex)]
                    else start[policy] ?: steps.last()
                }
                var cpuLevel = bigPolicies.map { p ->
                    val steps = cpuSteps.getValue(p)
                    val idx = steps.indexOfFirst { it >= (start[p] ?: steps.last()) }.let { if (it < 0) steps.lastIndex else it }
                    idx.toFloat() / steps.lastIndex.coerceAtLeast(1)
                }.maxOrNull() ?: 1f
                val minGpuIdx = (gpuSteps.size * 0.15f).toInt()
                val minCpuLevel = 0.25f
                // Guard only: the profile's own limits are the ceiling.
                val maxGpuIdx = if (guardOnly) gpuIdx else gpuSteps.lastIndex
                val maxCpuLevel = if (guardOnly) cpuLevel else 1f
                var temp: Double? = null
                var tempCheckedAt = 0L
                var goodTicks = 0
                var lowerGpuNext = true
                var holdUntil = 0L
                var lastApplied = 0L
                fun apply() {
                    FreqControl.apply(cpuIndexFor(cpuLevel), gpuSteps[gpuIdx])
                    lastApplied = SystemClock.elapsedRealtime()
                }
                apply()
                val recent = ArrayDeque<Int>()
                while (!stopFlag) {
                    Thread.sleep(2000L)
                    if (tempLimit > 0 && SystemClock.elapsedRealtime() - tempCheckedAt >= 4000L) {
                        tempCheckedAt = SystemClock.elapsedRealtime()
                        temp = Thermal.read(sensor)
                    }
                    val hot = tempLimit > 0 && (temp ?: 0.0) >= tempLimit
                    val warm = tempLimit > 0 && (temp ?: 0.0) >= tempLimit - 3.0
                    if (hot) {
                        // Over the noise level's limit: step down whatever the frame rate says.
                        if (gpuIdx > minGpuIdx) gpuIdx--
                        if (cpuLevel > minCpuLevel) cpuLevel = (cpuLevel - 0.05f).coerceAtLeast(minCpuLevel)
                        apply()
                        goodTicks = 0
                        holdUntil = SystemClock.elapsedRealtime() + 20_000L
                        status = String.format(Locale.US, "%.0f °C ≥ %.0f °C", temp ?: 0.0, tempLimit)
                        continue
                    }
                    if (guardOnly) {
                        // Cool again: move back up toward the profile's limits.
                        if (!warm && SystemClock.elapsedRealtime() >= holdUntil && (gpuIdx < maxGpuIdx || cpuLevel < maxCpuLevel)) {
                            if (gpuIdx < maxGpuIdx) gpuIdx++
                            if (cpuLevel < maxCpuLevel) cpuLevel = (cpuLevel + 0.05f).coerceAtMost(maxCpuLevel)
                            apply()
                            holdUntil = SystemClock.elapsedRealtime() + 10_000L
                        }
                        status = temp?.let { String.format(Locale.US, "%.0f / %.0f °C", it, tempLimit) } ?: ""
                        if (SystemClock.elapsedRealtime() - lastApplied > 10_000L) apply()
                        continue
                    }
                    val fps = FpsProbe.read(2000L)
                    if (fps == null || fps <= 0) { status = "—"; continue }  // menu / paused: leave limits alone
                    recent.addLast(fps); while (recent.size > 3) recent.removeFirst()
                    val avg = recent.average()
                    val now = SystemClock.elapsedRealtime()
                    status = "$fps / $target FPS · GPU ${gpuSteps[gpuIdx] / 1_000_000} MHz · CPU ${(cpuLevel * 100).roundToInt()}%" +
                        (temp?.let { String.format(Locale.US, " · %.0f °C", it) } ?: "")
                    if ((fps < target - 3 || avg < target - 2) && !warm) {
                        // Falling short: raise both knobs a step and stop lowering for a while.
                        var changed = false
                        if (gpuIdx < gpuSteps.lastIndex) { gpuIdx++; changed = true }
                        if (cpuLevel < 1f) { cpuLevel = (cpuLevel + 0.1f).coerceAtMost(1f); changed = true }
                        if (changed) apply()
                        goodTicks = 0
                        holdUntil = now + 20_000L
                    } else if (avg >= target - 1) {
                        goodTicks++
                        if (goodTicks >= 5 && now >= holdUntil) {
                            goodTicks = 0
                            if (lowerGpuNext && gpuIdx > minGpuIdx) gpuIdx--
                            else if (cpuLevel > minCpuLevel) cpuLevel = (cpuLevel - 0.05f).coerceAtLeast(minCpuLevel)
                            else if (gpuIdx > minGpuIdx) gpuIdx--
                            lowerGpuNext = !lowerGpuNext
                            apply()
                        }
                    }
                    // Something else (the OEM service) may restore its caps: reassert every 10 s.
                    if (now - lastApplied > 10_000L) apply()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "adaptive mode stopped", e)
            }
        }, "PocketHub-adaptive").apply { isDaemon = true }
        thread = t
        t.start()
    }
}
