package com.cj.turboboost

/** A display size as `wm size` prints it: width x height in the panel's natural orientation. */
data class DisplaySize(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"

    companion object {
        /** `1080x2460` -> [DisplaySize]; null for anything else. */
        fun parse(raw: String?): DisplaySize? {
            val match = Regex("""^\s*(\d+)\s*x\s*(\d+)\s*$""").find(raw ?: return null) ?: return null
            val w = match.groupValues[1].toIntOrNull() ?: return null
            val h = match.groupValues[2].toIntOrNull() ?: return null
            return if (w > 0 && h > 0) DisplaySize(w, h) else null
        }
    }
}

/**
 * What `wm size` and `wm density` report. A null override means the line was absent: the
 * display is at its physical value on that axis.
 */
data class DisplayState(
    val physicalSize: DisplaySize,
    val overrideSize: DisplaySize?,
    val physicalDensity: Int,
    val overrideDensity: Int?
) {
    val size: DisplaySize get() = overrideSize ?: physicalSize
    val density: Int get() = overrideDensity ?: physicalDensity
    val hasOverride: Boolean get() = overrideSize != null || overrideDensity != null
}

/**
 * The display-wide `wm size` / `wm density` override, which this app no longer sets. Earlier
 * builds did, and silently undid any override matching their presets on start. This build
 * never changes the display on its own: on its first launch it asks once whether to reset an
 * override it finds, and only [reset] — run when the user says so — touches it.
 */
object DisplayOverride {

    /** Options-store flag: the one-time check of this build has been answered. */
    private const val KEY_CHECKED = "display_override_checked_v2"

    /** The removed lever's shell watchdog, which reset the display after 15 s. */
    private const val LEGACY_WATCHDOG_PID = "/data/local/tmp/turboboost_resolution_watchdog.pid"

    private val SIZE_LINE = Regex("""^(Physical|Override) size:\s*(\d+\s*x\s*\d+)\s*$""")
    private val DENSITY_LINE = Regex("""^(Physical|Override) density:\s*(\d+)\s*$""")

    /** Physical and override sizes from `wm size`; either is null when its line is missing. */
    fun parseSize(output: String?): Pair<DisplaySize?, DisplaySize?> {
        var physical: DisplaySize? = null
        var override: DisplaySize? = null
        output?.lineSequence()?.map { it.trim() }?.forEach { line ->
            val m = SIZE_LINE.find(line) ?: return@forEach
            val size = DisplaySize.parse(m.groupValues[2])
            if (m.groupValues[1] == "Physical") physical = size else override = size
        }
        return physical to override
    }

    /** Physical and override densities from `wm density`; either is null when its line is missing. */
    fun parseDensity(output: String?): Pair<Int?, Int?> {
        var physical: Int? = null
        var override: Int? = null
        output?.lineSequence()?.map { it.trim() }?.forEach { line ->
            val m = DENSITY_LINE.find(line) ?: return@forEach
            val value = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
            if (m.groupValues[1] == "Physical") physical = value else override = value
        }
        return physical to override
    }

    /** Both outputs combined, or null when either physical value is missing. */
    fun parseState(sizeOutput: String?, densityOutput: String?): DisplayState? {
        val (physicalSize, overrideSize) = parseSize(sizeOutput)
        val (physicalDensity, overrideDensity) = parseDensity(densityOutput)
        return DisplayState(
            physicalSize = physicalSize ?: return null,
            overrideSize = overrideSize,
            physicalDensity = physicalDensity ?: return null,
            overrideDensity = overrideDensity
        )
    }

    fun read(shell: ShellExecutor): DisplayState? =
        parseState(shell.capture("wm size"), shell.capture("wm density"))

    fun isChecked(store: KeyValueStore): Boolean = store.getBoolean(KEY_CHECKED, false)

    fun markChecked(store: KeyValueStore) = store.putBoolean(KEY_CHECKED, true)

    /**
     * The one-time check. Returns the override to ask about, or null when there is nothing to
     * ask (no override: the check is done) or nothing could be read (asked again next start).
     * Changes nothing on the display.
     */
    fun check(shell: ShellExecutor, options: KeyValueStore, legacy: KeyValueStore): DisplayState? {
        if (isChecked(options) || !shell.isAvailable()) return null
        // A countdown left by the removed lever must not reset the display behind the prompt.
        shell.run("[ -f $LEGACY_WATCHDOG_PID ] && kill \$(cat $LEGACY_WATCHDOG_PID) 2>/dev/null; rm -f $LEGACY_WATCHDOG_PID")
        val state = read(shell) ?: return null
        legacy.clear()
        if (!state.hasOverride) markChecked(options)
        return state.takeIf { it.hasOverride }
    }

    /** The user chose Reset: clears both overrides and reports what the display now shows. */
    fun reset(shell: ShellExecutor): DisplayState? {
        shell.run("wm size reset")
        shell.run("wm density reset")
        return read(shell)
    }
}
