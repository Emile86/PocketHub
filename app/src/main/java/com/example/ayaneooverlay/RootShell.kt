package com.example.ayaneooverlay

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One long-lived root shell shared by the frequent reads (live dashboard values, FPS, game
 * detection, auto-tune). Before, every read started its own `su -c …` process: several new
 * root processes per second, and Magisk logged / notified each of them. Now `su` is started
 * once and the commands are written to it one after another.
 *
 * Each command runs in a subshell with stdin from /dev/null (so it can never eat the next
 * command, and `exit` only ends that subshell), followed by an end marker with its exit code.
 * Calls are serialized. A command that takes longer than the timeout kills the shell; the next
 * call starts a new one. If the shell cannot be used, [run] returns null and the caller falls
 * back to the old one-process-per-command way, so nothing stops working.
 *
 * Not for commands that leave background jobs running (`… &`): those keep using their own
 * `su -c` process, because a background job would hold on to this shell's output.
 */
class RootShell private constructor(private val name: String) {
    companion object {
        private const val TAG = "PocketHubRootShell"
        private const val TIMEOUT_MS = 15_000L

        /** Dashboard values, FPS, settings reads in the main screen. */
        val main = RootShell("main")
        /** Game detection, auto-tune and the adaptive governor (can run longer commands). */
        val tuning = RootShell("tuning")

        private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "PocketHubRootShell-watchdog").apply { isDaemon = true }
        }
    }

    class Result(val output: String, val exitCode: Int) {
        val ok get() = exitCode == 0
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var process: Process? = null
    private var input: OutputStream? = null
    private var reader: BufferedReader? = null
    private val counter = AtomicLong(0)
    /** After repeated start failures (root denied), stop trying for a while. */
    private var disabledUntil = 0L
    private var startFailures = 0

    /**
     * Runs [command] in the shared root shell. stderr is merged into the output when
     * [mergeStderr] is true, otherwise dropped. Returns null if the shell is not available
     * (the caller should fall back to its own `su -c`). If the shell is busy with another
     * command for more than [waitMs], it also returns null, so a slow command never holds up
     * a quick read: that read then simply uses its own `su -c` as before.
     */
    fun run(command: String, mergeStderr: Boolean = false, timeoutMs: Long = TIMEOUT_MS, waitMs: Long = 300L): Result? {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            if (System.currentTimeMillis() < disabledUntil) return null
            if (!ensureStarted()) return null
            val p = process ?: return null
            val out = input ?: return null
            val inReader = reader ?: return null
            val id = counter.incrementAndGet()
            val marker = "__PH_END_${id}__"
            val err = if (mergeStderr) "2>&1" else "2>/dev/null"
            val timedOut = AtomicBoolean(false)
            var timer: ScheduledFuture<*>? = null
            return try {
                val text = "(\n$command\n) </dev/null $err\n__ph_rc=\$?\nprintf '\\n%s %s\\n' '$marker' \"\$__ph_rc\"\n"
                out.write(text.toByteArray())
                out.flush()
                timer = watchdog.schedule({
                    timedOut.set(true)
                    Log.w(TAG, "Command timed out, restarting the root shell")
                    try { p.destroy() } catch (_: Exception) { }
                }, timeoutMs, TimeUnit.MILLISECONDS)
                val buffer = StringBuilder()
                var exitCode = -1
                while (true) {
                    val line = inReader.readLine() ?: throw java.io.IOException("root shell closed")
                    if (line.startsWith(marker)) {
                        exitCode = line.substring(marker.length).trim().toIntOrNull() ?: -1
                        break
                    }
                    buffer.append(line).append('\n')
                }
                timer.cancel(false)
                // The marker is printed after a newline of its own: drop that extra line break.
                var output = buffer.toString()
                if (output.endsWith("\n\n")) output = output.dropLast(1)
                Result(output.trimEnd('\n'), exitCode)
            } catch (error: Exception) {
                timer?.cancel(false)
                if (!timedOut.get()) Log.w(TAG, "Root shell failed: ${error.message}")
                closeLocked()
                null
            }
        } finally {
            lock.unlock()
        }
    }

    /** Ends the shared shell (it is started again on the next call). */
    fun close() {
        lock.lock()
        try { closeLocked() } finally { lock.unlock() }
    }

    private fun ensureStarted(): Boolean {
        val p = process
        if (p != null && isAlive(p)) return true
        closeLocked()
        return try {
            val started = ProcessBuilder("su").start()
            // stderr of the shell itself is not used; drain it so it can never block.
            Thread({
                try { started.errorStream.use { s -> val b = ByteArray(1024); while (s.read(b) >= 0) { } } } catch (_: Exception) { }
            }, "PocketHubRootShell-$name-stderr").apply { isDaemon = true; start() }
            process = started
            input = started.outputStream
            reader = BufferedReader(InputStreamReader(started.inputStream))
            // Check that this really is a root shell before using it.
            val probe = "echo __PH_READY__ \$(id -u)\n"
            input!!.write(probe.toByteArray()); input!!.flush()
            // Skip anything su may print first; the probe line decides.
            var ready: String? = null
            for (i in 0 until 5) {
                val line = readLineWithTimeout(5_000L) ?: break
                if (line.startsWith("__PH_READY__")) { ready = line; break }
            }
            if (ready == null || ready.trim() != "__PH_READY__ 0") {
                throw IllegalStateException("not a root shell: $ready")
            }
            startFailures = 0
            Log.i(TAG, "Shared root shell '$name' started")
            true
        } catch (error: Exception) {
            closeLocked()
            startFailures++
            if (startFailures >= 3) {
                // Root is denied or unavailable: let callers use their own su for a minute.
                disabledUntil = System.currentTimeMillis() + 60_000L
                startFailures = 0
            }
            Log.w(TAG, "Could not start the shared root shell: ${error.message}")
            false
        }
    }

    private fun readLineWithTimeout(timeoutMs: Long): String? {
        val p = process ?: return null
        val timer = watchdog.schedule({ try { p.destroy() } catch (_: Exception) { } }, timeoutMs, TimeUnit.MILLISECONDS)
        return try { reader?.readLine() } catch (_: Exception) { null } finally { timer.cancel(false) }
    }

    private fun isAlive(p: Process): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun closeLocked() {
        val p = process
        process = null
        try { input?.close() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        input = null
        reader = null
        try { p?.destroy() } catch (_: Exception) { }
    }
}
