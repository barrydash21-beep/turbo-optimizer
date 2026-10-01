package com.cj.turboboost

import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Outcome of one shell command.
 *
 * Structured rather than a bare Boolean so callers can tell "the command ran and said no"
 * apart from "we never got to run it" -- the distinction the restore path needs in order to
 * report honestly (M1/M2), and the one [apiUnsupported] carries for M10.
 */
data class ShellResult(
    val exitCode: Int = -1,
    /** The command was still running when the per-command deadline expired. */
    val timedOut: Boolean = false,
    /** `Shizuku.newProcess` reflection failed -- the Shizuku API changed under us. */
    val apiUnsupported: Boolean = false,
    /** The Shizuku binder is not alive, so nothing was attempted. */
    val unavailable: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !apiUnsupported && !unavailable

    companion object {
        fun success() = ShellResult(exitCode = 0)
        fun timedOut() = ShellResult(timedOut = true)
        fun apiUnsupported() = ShellResult(apiUnsupported = true)
        fun unavailable() = ShellResult(unavailable = true)
    }
}

/**
 * Runs privileged shell commands. Behind an interface purely so the boost/restore logic can
 * be unit-tested against a fake without a device or a live Shizuku server.
 */
interface ShellExecutor {
    fun isAvailable(): Boolean
    fun run(command: String): ShellResult

    /** Runs [command] and returns its trimmed stdout, or null if it did not exit cleanly. */
    fun capture(command: String): String?
}

/** The real executor: `sh -c <command>` at Shizuku's uid (2000 in ADB mode, 0 under root). */
object ShizukuShell : ShellExecutor {

    private const val TAG = "TurboBooster"

    /**
     * Per-command ceiling. A wedged `am force-stop` used to pin the boost coroutine forever
     * (H1); nothing the booster runs legitimately takes longer than this.
     */
    private const val TIMEOUT_SECONDS = 5L

    override fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        Log.w(TAG, "pingBinder threw", e)
        false
    }

    override fun run(command: String): ShellResult = exec(command, captureStdout = false).first

    override fun capture(command: String): String? {
        val (result, stdout) = exec(command, captureStdout = true)
        return if (result.ok) stdout?.trim() else null
    }

    private fun exec(command: String, captureStdout: Boolean): Pair<ShellResult, String?> {
        if (!isAvailable()) return ShellResult.unavailable() to null

        var process: ShizukuRemoteProcess? = null
        try {
            // Reflection: newProcess is private and deprecated. A NoSuchMethodException here
            // means the Shizuku API moved, which is reported distinctly from a command that
            // simply failed (M10).
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true

            process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as ShizukuRemoteProcess

            // Drain both pipes concurrently. A child that fills a pipe buffer blocks on write
            // and never exits, so draining has to happen alongside the wait, not after it.
            var stdout: String? = null
            val outDrain = if (captureStdout) {
                collectAsync(process.inputStream) { stdout = it }
            } else {
                discardAsync("shell-stdout", process.inputStream)
            }
            val errDrain = discardAsync("shell-stderr", process.errorStream)

            val exited = process.waitForTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                if (BuildConfig.DEBUG) Log.d(TAG, "[timeout] $command")
                return ShellResult.timedOut() to null
            }

            // The process has exited, so both pipes are at EOF; these joins return promptly.
            outDrain?.join(500)
            errDrain?.join(500)

            val exit = process.exitValue()
            if (BuildConfig.DEBUG) Log.d(TAG, "[$exit] $command")
            return ShellResult(exitCode = exit) to stdout
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Shizuku.newProcess is gone -- API not supported", e)
            return ShellResult.apiUnsupported() to null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Command failed: $command", e) else Log.w(TAG, "Command failed")
            return ShellResult() to null
        } finally {
            // Always reap: without destroy() the ShizukuRemoteProcess stays pinned in the
            // library's static CACHE along with three pipe fds on the server side (H2).
            runCatching { process?.destroy() }
        }
    }

    private fun discardAsync(name: String, stream: InputStream?): Thread? {
        if (stream == null) return null
        return Thread {
            runCatching {
                stream.use {
                    val buffer = ByteArray(4096)
                    while (it.read(buffer) >= 0) { /* discard */ }
                }
            }
        }.apply { isDaemon = true; this.name = name; start() }
    }

    private fun collectAsync(stream: InputStream?, onDone: (String) -> Unit): Thread? {
        if (stream == null) return null
        return Thread {
            runCatching { stream.use { onDone(it.readBytes().toString(Charsets.UTF_8)) } }
        }.apply { isDaemon = true; name = "shell-capture"; start() }
    }
}
