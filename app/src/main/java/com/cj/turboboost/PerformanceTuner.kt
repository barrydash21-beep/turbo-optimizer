package com.cj.turboboost

import android.util.Log

/** What one apply or revert actually did, step by step. */
data class TuneReport(
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val unavailable: Boolean = false,
    /** Raw refresh-rate keys read back after writing: what the system actually holds. */
    val readBack: Map<String, String> = emptyMap(),
    /** Raw output and exit status of the fixed-performance-mode command. */
    val fixedModeOutput: String? = null
) {
    val ok: Boolean get() = failed.isEmpty() && !unavailable
}

/**
 * Display/power tweaks applied before the game starts and undone afterwards.
 *
 * Every original value is persisted before anything is changed, and `tweaks_active` stays
 * set until the revert has actually landed, so a crash, a force-close or a reboot mid-game
 * can always be undone from the next app start.
 */
object PerformanceTuner {

    private const val TAG = "TurboBooster"

    private const val KEY_ACTIVE = "tweaks_active"
    private const val KEY_MIN_SAVED = "saved_min_refresh_rate"
    private const val KEY_PEAK_SAVED = "saved_peak_refresh_rate"

    private val REFRESH_KEYS = listOf("min_refresh_rate", "peak_refresh_rate")

    /** Panels advertise 120.00001 or 89.96; a read-back within this is the rate that was asked for. */
    private const val RATE_TOLERANCE_HZ = 0.5f

    private const val FIXED_MODE_ON = "cmd power set-fixed-performance-mode-enabled true"
    private const val FIXED_MODE_OFF = "cmd power set-fixed-performance-mode-enabled false"

    fun isActive(store: KeyValueStore): Boolean = store.getBoolean(KEY_ACTIVE, false)

    /**
     * Reads and persists the user's refresh-rate settings, then sets `tweaks_active`.
     *
     * A no-op while a capture is already held, so a second boost can never save the
     * already-tweaked rate as the "original".
     *
     * @return false if the originals could not be saved because Shizuku is unavailable.
     */
    fun captureOriginals(store: KeyValueStore): Boolean {
        val shell = RamCleaner.shell
        if (isActive(store)) return true
        if (!shell.isAvailable()) return false

        store.putString(KEY_MIN_SAVED, normalize(shell.capture("settings get system min_refresh_rate")))
        store.putString(KEY_PEAK_SAVED, normalize(shell.capture("settings get system peak_refresh_rate")))
        store.putBoolean(KEY_ACTIVE, true)
        return true
    }

    /**
     * Pins both refresh-rate keys to [refreshRate] and, if [profile] asks for it, turns on
     * fixed performance mode. The originals are saved first. Each command runs on its own:
     * one failing never stops the rest.
     *
     * [refreshRate] must be one the panel advertises; the caller validates it against
     * `Display.getSupportedModes()`.
     */
    fun applyGamingTweaks(store: KeyValueStore, profile: GameProfile, refreshRate: Float): TuneReport {
        val shell = RamCleaner.shell
        if (!shell.isAvailable()) return TuneReport(unavailable = true)
        if (!captureOriginals(store)) return TuneReport(unavailable = true)

        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val target = RefreshRate.format(refreshRate)

        for (key in REFRESH_KEYS) {
            val name = "Refresh rate ($key)"
            if (shell.run("settings put system $key $target").ok) applied += name else failed += name
        }

        val readBack = REFRESH_KEYS.associateWith { key ->
            normalize(shell.capture("settings get system $key")) ?: "null"
        }
        log { Log.i(TAG, "Refresh rate ${profile.packageName} -> $target, read back: $readBack") }
        // The write succeeding is not the same as the value sticking. HiOS 16.3 on the Pova 7
        // accepts `put` for min_refresh_rate (exit 0) but holds it at 10.0 whatever is written;
        // peak_refresh_rate is honoured. Each ignored key is named, never worked around.
        for ((key, value) in readBack) {
            val stuck = value.toFloatOrNull()?.let { kotlin.math.abs(it - refreshRate) <= RATE_TOLERANCE_HZ } == true
            if (!stuck) {
                log { Log.w(TAG, "$key ignored by the system: wrote $target, read back $value") }
                failed += "$key ignored (stays $value)"
            }
        }

        var fixedOutput: String? = null
        if (profile.useFixedPerformanceMode) {
            val fixed = runFixedMode(FIXED_MODE_ON)
            fixedOutput = fixed.output
            // Unsupported is expected on some ROMs: logged and reported, never fatal.
            if (fixed.ok) applied += "Fixed performance mode" else failed += "Fixed performance mode"
            log { Log.i(TAG, "Fixed performance mode on: ${fixed.output}") }
        }

        return TuneReport(applied, failed, readBack = readBack, fixedModeOutput = fixedOutput)
    }

    /**
     * Restores the exact saved values and clears `tweaks_active`. A saved value that was unset
     * ("null" or blank) is deleted rather than written.
     *
     * The flag is cleared only when the refresh-rate restore landed, so a revert that could
     * not run (Shizuku down) is retried on the next app start.
     *
     * A no-op while `tweaks_active` is clear: nothing was captured, so the saved values read
     * as unset and the keys would be deleted. Restore normal settings did exactly that with no
     * session held, and HiOS then refilled peak_refresh_rate with 90 (seen on a Pova 7).
     */
    fun revertGamingTweaks(store: KeyValueStore): TuneReport {
        if (!isActive(store)) return TuneReport()
        val shell = RamCleaner.shell
        if (!shell.isAvailable()) return TuneReport(unavailable = true)

        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val saved = mapOf(
            "min_refresh_rate" to store.getString(KEY_MIN_SAVED),
            "peak_refresh_rate" to store.getString(KEY_PEAK_SAVED)
        )
        var restored = true
        for ((key, value) in saved) {
            val command = if (normalize(value) == null) {
                "settings delete system $key"
            } else {
                "settings put system $key ${value!!.trim()}"
            }
            if (shell.run(command).ok) {
                applied += "Refresh rate ($key)"
            } else {
                failed += "Refresh rate ($key)"
                restored = false
            }
        }

        val fixed = runFixedMode(FIXED_MODE_OFF)
        log { Log.i(TAG, "Refresh rate restored: $saved; fixed performance mode off: ${fixed.output}") }
        if (fixed.ok) applied += "Fixed performance mode"

        if (restored) {
            store.putBoolean(KEY_ACTIVE, false)
            store.putString(KEY_MIN_SAVED, null)
            store.putString(KEY_PEAK_SAVED, null)
        }
        return TuneReport(applied, failed, fixedModeOutput = fixed.output)
    }

    private class FixedModeResult(val ok: Boolean, val output: String)

    /**
     * Runs the command with stderr merged and the exit status echoed, since the executor
     * only hands back stdout on success.
     */
    private fun runFixedMode(command: String): FixedModeResult {
        val out = RamCleaner.shell.capture("$command 2>&1; echo exit=\$?") ?: return FixedModeResult(false, "no output")
        val exit = out.lineSequence().lastOrNull { it.startsWith("exit=") }?.removePrefix("exit=")?.trim()
        // Some services print "Unknown command" / an exception and still exit 0.
        val complained = listOf("unknown", "exception", "error", "not supported", "unsupported")
            .any { out.contains(it, ignoreCase = true) }
        return FixedModeResult(exit == "0" && !complained, out.replace('\n', ' ').trim())
    }

    /** Logging must never fail an apply or revert (android.util.Log is absent in JVM tests). */
    private inline fun log(block: () -> Unit) {
        runCatching { block() }
    }

    /** `settings get` prints the literal "null" for an unset key. */
    private fun normalize(raw: String?): String? {
        val value = raw?.trim()
        return if (value.isNullOrEmpty() || value == "null") null else value
    }
}
