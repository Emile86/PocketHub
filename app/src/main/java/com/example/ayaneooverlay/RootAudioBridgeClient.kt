package com.example.ayaneooverlay

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter

internal data class RootAudioSnapshot(
    val uids: List<Int>,
    val playerCounts: Map<Int, Int>,
    val playersByUid: Map<Int, Set<Int>>,
    val packageDisplayIds: Map<String, Set<Int>>
)

/**
 * Owns the root-side app_process bridge. The helper is started only when the user
 * opens the per-app mixer, so granting su is explicit and Shizuku is not required.
 */
internal class RootAudioBridgeClient(context: Context) : Closeable {
    private val process: Process
    private val reader: BufferedReader
    private val writer: BufferedWriter
    private val lock = Any()
    @Volatile private var closed = false
    val isAlive: Boolean get() = !closed && process.isAlive

    init {
        val apkPath = context.applicationInfo.sourceDir
        val command = "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin com.example.ayaneooverlay.RootAudioBridge"
        process = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { Log.w("AyaneoRootAudio", it) }
                }
            } catch (_: Exception) { }
        }, "AyaneoRootAudio-stderr").apply { isDaemon = true; start() }

        val hello = request(JSONObject().put("cmd", "ping"))
        check(hello.optBoolean("ok", false) && hello.optString("result") == "pong") {
            hello.optString("error", "The root audio service did not answer")
        }
    }

    @Throws(Exception::class)
    fun listActive(): RootAudioSnapshot {
        val response = request(JSONObject().put("cmd", "list"))
        if (!response.optBoolean("ok", false)) {
            throw IllegalStateException(response.optString("error", "Unable to list audio players"))
        }
        val uids = ArrayList<Int>()
        val uidArray = response.optJSONArray("uids") ?: JSONArray()
        for (index in 0 until uidArray.length()) {
            val uid = uidArray.optInt(index, -1)
            if (uid >= 10000) uids.add(uid)
        }
        val counts = LinkedHashMap<Int, Int>()
        val countObject = response.optJSONObject("playerCounts") ?: JSONObject()
        val keys = countObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            key.toIntOrNull()?.let { counts[it] = countObject.optInt(key, 0) }
        }
        val playersByUid = LinkedHashMap<Int, MutableSet<Int>>()
        val players = response.optJSONArray("players") ?: JSONArray()
        for (index in 0 until players.length()) {
            val row = players.optJSONObject(index) ?: continue
            val uid = row.optInt("uid", -1)
            val playerId = row.optInt("playerId", -1)
            if (uid >= 10000 && playerId >= 0) playersByUid.getOrPut(uid) { linkedSetOf() }.add(playerId)
        }
        val packageDisplays = LinkedHashMap<String, MutableSet<Int>>()
        val displayArray = response.optJSONArray("packageDisplays") ?: JSONArray()
        for (index in 0 until displayArray.length()) {
            val row = displayArray.optJSONObject(index) ?: continue
            val packageName = row.optString("package")
            val displayId = row.optInt("displayId", -1)
            if (packageName.isBlank() || displayId < 0) continue
            packageDisplays.getOrPut(packageName) { linkedSetOf() }.add(displayId)
        }
        return RootAudioSnapshot(uids.distinct().sorted(), counts, playersByUid.mapValues { it.value.toSet() }, packageDisplays.mapValues { it.value.toSet() })
    }

    @Throws(Exception::class)
    fun setVolume(uid: Int, volume: Float): Boolean {
        val response = request(JSONObject().put("cmd", "set").put("uid", uid).put("volume", volume.coerceIn(0f, 1f).toDouble()))
        if (!response.optBoolean("ok", false)) throw IllegalStateException(response.optString("error", "Volume update failed"))
        return response.optBoolean("changed", false)
    }

    @Throws(Exception::class)
    fun setPlayerVolume(uid: Int, playerId: Int, volume: Float): Boolean {
        val response = request(JSONObject().put("cmd", "setPlayer").put("uid", uid).put("playerId", playerId)
            .put("volume", volume.coerceIn(0f, 1f).toDouble()))
        if (!response.optBoolean("ok", false)) throw IllegalStateException(response.optString("error", "Player volume update failed"))
        return response.optBoolean("changed", false)
    }

    private fun request(request: JSONObject): JSONObject = synchronized(lock) {
        check(!closed) { "Root audio service is closed" }
        if (!process.isAlive) throw IllegalStateException("Root audio process exited (code ${process.exitValue()})")
        writer.write(request.toString())
        writer.newLine()
        writer.flush()
        val line = reader.readLine() ?: throw IllegalStateException("Root audio process closed its output")
        JSONObject(line)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            try {
                if (process.isAlive) {
                    writer.write(JSONObject().put("cmd", "stop").toString())
                    writer.newLine()
                    writer.flush()
                    reader.readLine()
                }
            } catch (_: Exception) { }
            closed = true
            try { writer.close() } catch (_: Exception) { }
            try { reader.close() } catch (_: Exception) { }
            try { process.destroy() } catch (_: Exception) { }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
