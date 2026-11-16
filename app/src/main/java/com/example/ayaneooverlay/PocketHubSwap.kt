package com.example.ayaneooverlay

import android.content.Context
import java.io.File

data class PocketHubSwapResult(val ok: Boolean, val output: String, val exitCode: Int)

object PocketHubSwap {
    const val PREFERENCE_KEY = "virtual_memory_swap_gb"
    private const val SCRIPT_NAME = "pockethub_swap.sh"
    private const val ROOT_CLIENT = "/product/bin/xsu"
    private val scriptLock = Any()

    fun run(context: Context, action: String, sizeGb: Int = 0): PocketHubSwapResult {
        return try {
            val script = File(context.filesDir, SCRIPT_NAME)
            synchronized(scriptLock) {
                val packaged = context.assets.open(SCRIPT_NAME).use { it.readBytes() }
                val current = if (script.exists()) script.readBytes() else byteArrayOf()
                if (!packaged.contentEquals(current)) {
                    script.writeBytes(packaged)
                    script.setReadable(true, false)
                }
            }
            if (!File(ROOT_CLIENT).canExecute()) {
                return PocketHubSwapResult(false, "AYANEO xsu root client is unavailable", -1)
            }
            val process = ProcessBuilder(ROOT_CLIENT, "sh ${script.absolutePath} $action $sizeGb")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()
            val successful = output.lineSequence().any { it.trim() == "RESULT=OK" }
            PocketHubSwapResult(successful, output, if (successful) 0 else exitCode.takeIf { it != 0 } ?: 1)
        } catch (error: Exception) {
            PocketHubSwapResult(false, error.message.orEmpty(), -1)
        }
    }
}
