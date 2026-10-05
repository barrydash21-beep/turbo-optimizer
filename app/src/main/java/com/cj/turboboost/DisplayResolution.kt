package com.cj.turboboost

import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

/** A size and density applied together. */
data class ResolutionTarget(val size: DisplaySize, val density: Int) {
    override fun toString() = "$size @ $density dpi"
}

/** One choice on the lever. [native] means "no override". */
data class ResolutionPreset(val label: String, val percent: Int, val target: ResolutionTarget, val native: Boolean)

/**
 * Parsing and preset math for the render resolution lever. Pure, so it is unit-tested.
 *
 * Everything is derived from the physical size `wm size` reports, never from a hardcoded panel,
 * so the same code gives 900x2050 on a 1080x2460 panel and 900x2030 on a 1080x2436 one.
 */
object DisplayResolution {

    /**
     * Width fractions offered, as exact ratios: "about 83%" is 5/6 and "about 67%" is 2/3,
     * which land on whole even pixels on 1080-wide panels.
     */
    private val PRESETS = listOf(
        Triple("NATIVE", 1, 1),
        Triple("83%", 5, 6),
        Triple("67%", 2, 3)
    )

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

    /**
     * [physical] scaled to [numerator]/[denominator] of its width. Width is rounded to an even
     * number, height follows the same ratio (aspect ratio kept) and is rounded to even, and the
     * density is [physicalDensity] times the resulting width ratio, rounded.
     *
     * Null when the result would fall below the 50% floor or above native.
     */
    fun scaled(physical: DisplaySize, physicalDensity: Int, numerator: Int, denominator: Int): ResolutionTarget? {
        if (numerator <= 0 || denominator <= 0) return null
        val width = roundEven(physical.width.toDouble() * numerator / denominator)
        val height = roundEven(physical.height.toDouble() * width / physical.width)
        val density = (physicalDensity.toDouble() * width / physical.width).roundToInt()
        val target = ResolutionTarget(DisplaySize(width, height), density)
        return target.takeIf { isAllowed(it, physical) }
    }

    /** Native plus every preset that clears the floor on this panel. */
    fun presets(state: DisplayState): List<ResolutionPreset> = PRESETS.mapNotNull { (label, n, d) ->
        val native = n == d
        val target = if (native) {
            ResolutionTarget(state.physicalSize, state.physicalDensity)
        } else {
            scaled(state.physicalSize, state.physicalDensity, n, d) ?: return@mapNotNull null
        }
        ResolutionPreset(label, (100.0 * n / d).roundToInt(), target, native)
    }

    /** At least 50% of the physical width and height, never above them, and a usable density. */
    fun isAllowed(target: ResolutionTarget, physical: DisplaySize): Boolean =
        target.size.width * 2 >= physical.width &&
            target.size.height * 2 >= physical.height &&
            target.size.width <= physical.width &&
            target.size.height <= physical.height &&
            target.density > 0

    /** Whether [state] holds [target] as its override on both axes. */
    fun matches(state: DisplayState, target: ResolutionTarget): Boolean =
        state.overrideSize == target.size && state.overrideDensity == target.density

    /**
     * Whether [state]'s override is exactly one this app's preset math produces. Used on app
     * start to recognise an override this app left behind after its own records were lost.
     */
    fun isOwnPreset(state: DisplayState): Boolean =
        presets(state).any { !it.native && matches(state, it.target) }

    /** For the session record: `900x2050@400` with an override, `native` without, null if unread. */
    fun summaryValue(state: DisplayState?): String? = when {
        state == null -> null
        state.hasOverride -> "${state.size}@${state.density}"
        else -> NATIVE
    }

    const val NATIVE = "native"

    private fun roundEven(value: Double): Int = ((value / 2.0).roundToLong() * 2).toInt()
}
