package com.cj.turboboost

import kotlin.math.roundToLong

/**
 * The settings a session ran under; sessions with the same key are one group. [profile] keeps
 * baselines apart from boosted sessions, and [gameState] sessions whose game ran under a
 * different Game Manager mode or config ([GameStateRef.text]).
 */
data class SessionGroupKey(
    val refreshRate: Float,
    val renderResolution: String?,
    val fpsCap: Int?,
    val profile: String? = null,
    val gameState: String? = null
) {
    val label: String
        get() = (profile?.let { "$it, " } ?: "") +
            "${RefreshRate.display(refreshRate)} Hz, ${renderResolution ?: "resolution not recorded"}, " +
            (fpsCap?.let { "cap $it" } ?: "no cap") +
            (gameState?.let { ", mode ${GameStateRef.display(it)}" } ?: "")
}

/**
 * One group's medians. Each median uses only the runs that recorded that value; the matching
 * count says how many did. Nothing is filled in for a run that has no value.
 */
data class GroupStats(
    val key: SessionGroupKey,
    val runs: Int,
    val avgFps: Float?,
    val avgFpsRuns: Int,
    val onePercentLow: Float?,
    val onePercentLowRuns: Int,
    /** Median time to thermal status 1, over the runs that reached it. */
    val msToThermal1: Long?,
    val reachedThermal1: Int,
    /** Runs with a readable thermal status that never reached 1. */
    val neverReachedThermal1: Int,
    val worstThermal: Int?,
    val thermalRuns: Int,
    val newestStartedAtMs: Long
) {
    val enoughRuns: Boolean get() = runs >= SessionComparison.MIN_RUNS
}

/** B minus A, for each median both groups have. */
data class GroupDifference(val avgFps: Float?, val onePercentLow: Float?, val msToThermal1: Long?)

/** Read-only comparison of saved sessions, grouped by the settings they ran under. */
object SessionComparison {

    /** Fewer runs than this in either group shows no difference. */
    const val MIN_RUNS = 3

    fun keyOf(record: SessionRecord) =
        SessionGroupKey(record.refreshRate, record.renderResolution, record.fpsCap, record.profile, record.gameStateStart)

    /**
     * Whether a session is left out of every group: its game restarted mid-way, so it is not one
     * continuous play; another app changed the game's state; or it is a baseline that ran with
     * a Game Manager config present.
     */
    fun excluded(record: SessionRecord): Boolean = record.gameRestarted || record.contaminated || record.baselineInvalid

    /** How many of [packageName]'s sessions [excluded] leaves out. */
    fun excludedCount(records: List<SessionRecord>, packageName: String): Int =
        records.count { it.packageName == packageName && excluded(it) }

    /** [packageName]'s groups, the one played most recently first, without the [excluded] sessions. */
    fun groups(records: List<SessionRecord>, packageName: String): List<GroupStats> =
        records.filter { it.packageName == packageName && !excluded(it) }
            .groupBy(::keyOf)
            .map { (key, runs) -> stats(key, runs) }
            .sortedByDescending { it.newestStartedAtMs }

    fun stats(key: SessionGroupKey, runs: List<SessionRecord>): GroupStats {
        val fps = runs.mapNotNull { it.avgFps }
        val low = runs.mapNotNull { it.onePercentLowFps }
        val reached = runs.mapNotNull { it.msToThermal1 }
        val thermal = runs.mapNotNull { it.maxThermal }
        return GroupStats(
            key = key,
            runs = runs.size,
            avgFps = median(fps.map { it.toDouble() })?.toFloat(),
            avgFpsRuns = fps.size,
            onePercentLow = median(low.map { it.toDouble() })?.toFloat(),
            onePercentLowRuns = low.size,
            msToThermal1 = median(reached.map { it.toDouble() })?.roundToLong(),
            reachedThermal1 = reached.size,
            neverReachedThermal1 = runs.count { it.maxThermal != null && it.msToThermal1 == null },
            worstThermal = thermal.maxOrNull(),
            thermalRuns = thermal.size,
            newestStartedAtMs = runs.maxOf { it.startedAtMs }
        )
    }

    /** The middle value, or the mean of the two middle values; null for none. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** B minus A, only when both groups have [MIN_RUNS] runs; null otherwise. */
    fun difference(a: GroupStats, b: GroupStats): GroupDifference? {
        if (!a.enoughRuns || !b.enoughRuns) return null
        return GroupDifference(
            avgFps = if (a.avgFps != null && b.avgFps != null) b.avgFps - a.avgFps else null,
            onePercentLow = if (a.onePercentLow != null && b.onePercentLow != null) b.onePercentLow - a.onePercentLow else null,
            msToThermal1 = if (a.msToThermal1 != null && b.msToThermal1 != null) b.msToThermal1 - a.msToThermal1 else null
        )
    }
}
