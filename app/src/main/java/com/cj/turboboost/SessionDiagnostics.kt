package com.cj.turboboost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * One finished game session, as sampled by [GameMonitorService].
 *
 * Every measured value is nullable: null means it could not be measured, and the UI says
 * "unavailable". Nothing here is ever estimated or filled in.
 */
data class SessionRecord(
    val packageName: String,
    val refreshRate: Float,
    val startedAtMs: Long,
    val durationMs: Long,
    val avgFps: Float?,
    val onePercentLowFps: Float?,
    /** Time from session start to the first sample with thermal status >= 1; null if never seen. */
    val msToThermal1: Long?,
    /** Highest sampled thermal status, or null if no thermal sample was readable. */
    val maxThermal: Int?,
    val samples: Int,
    val fpsSamples: Int,
    val thermalSamples: Int,
    /**
     * Highest `peak_refresh_rate` read back during the session, or null if never readable.
     * HiOS rewrites the key to its own setting ~3 s after a game launches, so this can differ
     * from [refreshRate]; the summary shows both.
     */
    val observedPeak: Float? = null,
    /**
     * Render resolution when the game was first seen: `900x2050@400` with an override,
     * [DisplayResolution.NATIVE] without one, null if unreadable or recorded before this existed.
     */
    val renderResolution: String? = null
) {
    /** Average FPS is more than 15% below the chosen rate, or the device reached "moderate". */
    val struggled: Boolean
        get() = (avgFps != null && avgFps < refreshRate * 0.85f) || (maxThermal != null && maxThermal >= 2)

    fun encode(): String = listOf(
        packageName, RefreshRate.format(refreshRate), startedAtMs, durationMs,
        avgFps?.let { fmt(it) }, onePercentLowFps?.let { fmt(it) }, msToThermal1, maxThermal,
        samples, fpsSamples, thermalSamples, observedPeak?.let { RefreshRate.format(it) }, renderResolution
    ).joinToString(SEP) { it?.toString() ?: "" }

    companion object {
        private const val SEP = "|"
        private const val FIELDS = 13

        /** Records saved before [observedPeak] existed. */
        private const val FIELDS_V1 = 11

        /** Records saved before [renderResolution] existed. */
        private const val FIELDS_V2 = 12

        private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

        /** Null for a line that is not a complete record. */
        fun decode(line: String): SessionRecord? {
            val f = line.split(SEP)
            if ((f.size != FIELDS && f.size != FIELDS_V2 && f.size != FIELDS_V1) || f[0].isBlank()) return null
            return runCatching {
                SessionRecord(
                    packageName = f[0],
                    refreshRate = f[1].toFloat(),
                    startedAtMs = f[2].toLong(),
                    durationMs = f[3].toLong(),
                    avgFps = f[4].toFloatOrNull(),
                    onePercentLowFps = f[5].toFloatOrNull(),
                    msToThermal1 = f[6].toLongOrNull(),
                    maxThermal = f[7].toIntOrNull(),
                    samples = f[8].toInt(),
                    fpsSamples = f[9].toInt(),
                    thermalSamples = f[10].toInt(),
                    observedPeak = f.getOrNull(11)?.toFloatOrNull(),
                    renderResolution = f.getOrNull(12)?.takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }
}

/** Parsing of the privileged dumps the sampler reads. Pure, so it is unit-tested. */
object Diagnostics {

    /** `actualPresentTime` for a frame whose fence has not signalled yet. */
    private const val PENDING = Long.MAX_VALUE

    /** Fewer frame intervals than this in one sample is not enough to call an FPS. */
    const val MIN_INTERVALS_PER_SAMPLE = 10

    /** A 1% low needs at least 100 intervals to mean anything. */
    const val MIN_INTERVALS_FOR_LOW = 100

    /**
     * The "Thermal Status: N" value from `dumpsys thermalservice`, or null when it is
     * missing or when a status override is active (`cmd thermalservice override-status`,
     * which TURBO uses): the reported value is then forced, not measured.
     */
    fun parseThermalStatus(dump: String?): Int? {
        if (dump.isNullOrBlank()) return null
        val lines = dump.lineSequence().map { it.trim() }
        if (lines.any { it.equals("IsStatusOverride: true", ignoreCase = true) }) return null
        return lines.firstOrNull { it.startsWith("Thermal Status:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
    }

    /** True when the dump shows a forced status, so the UI can say why thermal is unavailable. */
    fun thermalOverridden(dump: String?): Boolean =
        dump?.lineSequence()?.any { it.trim().equals("IsStatusOverride: true", ignoreCase = true) } == true

    /**
     * Layer names from `dumpsys SurfaceFlinger --list`. Android 14+ wraps each one as
     * `RequestedLayerState{<name> parentId=… z=…}`; older releases print the bare name.
     * The name runs up to and including its `#<id>` suffix.
     */
    fun layerNames(list: String?): List<String> {
        if (list.isNullOrBlank()) return emptyList()
        val nameWithId = Regex("""^(.*?#\d+)(?:\s|$)""")
        return list.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val inner = if (line.startsWith("RequestedLayerState{") && line.endsWith("}")) {
                    line.removePrefix("RequestedLayerState{").removeSuffix("}")
                } else {
                    line
                }
                nameWithId.find(inner)?.groupValues?.get(1) ?: inner
            }
            .distinct()
            .toList()
    }

    /**
     * Layers that may carry [packageName]'s frames, best first: a BLAST SurfaceView (Unity
     * games render there), any SurfaceView, then the app window itself (NativeActivity games
     * render into it). Container layers that never hold buffers are dropped.
     */
    fun candidateLayers(list: String?, packageName: String): List<String> {
        val containers = listOf("ActivityRecord{", "Surface(name=", "leash", "Bounds for", "Background for", "Task=", "Splash Screen")
        return layerNames(list)
            .filter { name -> name.contains("$packageName/") || name.contains("[$packageName/") }
            .filter { name -> containers.none { name.contains(it) } }
            .sortedBy { name ->
                when {
                    name.contains("SurfaceView[") && name.contains("(BLAST)") -> 0
                    name.contains("SurfaceView[") -> 1
                    else -> 2
                }
            }
    }

    /**
     * Presented-frame timestamps (ns) from `dumpsys SurfaceFlinger --latency <layer>`: the
     * second column (actual present time) of each frame row, skipping unfilled rows (0) and
     * frames still pending, keeping only those after [afterNs] so no frame is counted twice.
     */
    fun presentTimes(latency: String?, afterNs: Long = 0L): List<Long> {
        if (latency.isNullOrBlank()) return emptyList()
        return latency.lineSequence()
            .drop(1) // refresh period
            .mapNotNull { row ->
                val cols = row.trim().split(Regex("\\s+"))
                if (cols.size < 3) null else cols[1].toLongOrNull()
            }
            .filter { it in 1 until PENDING && it > afterNs }
            .sorted()
            .toList()
    }

    /** Intervals between consecutive presents. */
    fun intervals(presents: List<Long>): List<Long> =
        presents.zipWithNext { a, b -> b - a }.filter { it > 0 }

    /** Frames per second over [intervals]: frame count over elapsed time. Null if too few. */
    fun averageFps(intervals: List<Long>): Float? {
        if (intervals.size < MIN_INTERVALS_PER_SAMPLE) return null
        val totalNs = intervals.sum()
        if (totalNs <= 0) return null
        return (intervals.size * 1_000_000_000.0 / totalNs).toFloat()
    }

    /** FPS over the slowest 1% of frames. Null below [MIN_INTERVALS_FOR_LOW] intervals. */
    fun onePercentLow(intervals: List<Long>): Float? {
        if (intervals.size < MIN_INTERVALS_FOR_LOW) return null
        val worst = intervals.sortedDescending().take(maxOf(1, intervals.size / 100))
        val meanNs = worst.average()
        if (meanNs <= 0.0) return null
        return (1_000_000_000.0 / meanNs).toFloat()
    }

    /** The next lower rate to suggest after a session that struggled, or null. */
    fun suggestion(record: SessionRecord, supported: List<Float>): Float? =
        if (record.struggled) RefreshRate.nextLower(record.refreshRate, supported) else null
}

/**
 * Accumulates one session's samples in memory. Nothing is written until [finish], so
 * sampling never touches the disk mid-game.
 */
class SessionAccumulator(
    val packageName: String,
    val refreshRate: Float,
    val startedAtMs: Long,
    private val startedElapsedMs: Long
) {
    private val intervals = mutableListOf<Long>()
    private var lastPresentNs = 0L
    private var samples = 0
    private var fpsSamples = 0
    private var thermalSamples = 0
    private var maxThermal: Int? = null
    private var msToThermal1: Long? = null
    private var observedPeak: Float? = null

    /** Set once by the monitor when the session starts; see [SessionRecord.renderResolution]. */
    var renderResolution: String? = null

    /** The newest frame already counted, so the next latency dump is read from after it. */
    val lastPresent: Long get() = lastPresentNs

    fun addSample(nowElapsedMs: Long, thermal: Int?, presents: List<Long>, peak: Float? = null) {
        samples++
        if (peak != null) observedPeak = maxOf(observedPeak ?: peak, peak)
        if (thermal != null) {
            thermalSamples++
            maxThermal = maxOf(maxThermal ?: thermal, thermal)
            if (thermal >= 1 && msToThermal1 == null) msToThermal1 = nowElapsedMs - startedElapsedMs
        }
        val sampleIntervals = Diagnostics.intervals(presents)
        if (sampleIntervals.size >= Diagnostics.MIN_INTERVALS_PER_SAMPLE) {
            fpsSamples++
            intervals += sampleIntervals
        }
        presents.lastOrNull()?.let { lastPresentNs = maxOf(lastPresentNs, it) }
    }

    fun finish(nowElapsedMs: Long): SessionRecord = SessionRecord(
        packageName = packageName,
        refreshRate = refreshRate,
        startedAtMs = startedAtMs,
        durationMs = (nowElapsedMs - startedElapsedMs).coerceAtLeast(0L),
        avgFps = Diagnostics.averageFps(intervals),
        onePercentLowFps = Diagnostics.onePercentLow(intervals),
        msToThermal1 = msToThermal1,
        maxThermal = maxThermal,
        samples = samples,
        fpsSamples = fpsSamples,
        thermalSamples = thermalSamples,
        observedPeak = observedPeak,
        renderResolution = renderResolution
    )
}

/**
 * Takes one sample through Shizuku: the thermal status, the game's newly presented frames,
 * and the `peak_refresh_rate` actually held. Three short dumps at most per sample, and the layer list only when the cached
 * layer stops producing frames (the game recreated its surface, or this is the first sample).
 */
class SessionSampler(private val shell: ShellExecutor, private val packageName: String) {

    private companion object {
        /** Latency dumps tried per re-resolve, so a sample stays a handful of commands. */
        const val MAX_CANDIDATES = 3
    }

    /** The SurfaceFlinger layer frames were last read from, or null if none was found. */
    var layer: String? = null
        private set

    /** Whether the last sample's thermal status was forced by an override, not measured. */
    var thermalOverridden = false
        private set

    data class Sample(val thermal: Int?, val frames: Int, val peak: Float?)

    fun sample(session: SessionAccumulator, nowElapsedMs: Long): Sample {
        val dump = shell.capture("dumpsys thermalservice")
        thermalOverridden = Diagnostics.thermalOverridden(dump)
        val thermal = Diagnostics.parseThermalStatus(dump)
        val frames = readFrames(session.lastPresent)
        val peak = shell.capture("settings get system peak_refresh_rate")?.trim()?.toFloatOrNull()
        session.addSample(nowElapsedMs, thermal, frames, peak)
        return Sample(thermal, frames.size, peak)
    }

    private fun readFrames(afterNs: Long): List<Long> {
        layer?.let { cached ->
            val frames = latency(cached, afterNs)
            if (frames.size > Diagnostics.MIN_INTERVALS_PER_SAMPLE) return frames
        }
        val candidates = Diagnostics.candidateLayers(
            shell.capture("dumpsys SurfaceFlinger --list"),
            packageName
        ).take(MAX_CANDIDATES)
        var best: String? = null
        var bestFrames = emptyList<Long>()
        for (candidate in candidates) {
            val frames = latency(candidate, afterNs)
            if (frames.size > bestFrames.size) {
                best = candidate
                bestFrames = frames
            }
        }
        layer = best
        return bestFrames
    }

    private fun latency(layer: String, afterNs: Long): List<Long> =
        Diagnostics.presentTimes(shell.capture("dumpsys SurfaceFlinger --latency ${quote(layer)}"), afterNs)

    /** Layer names carry spaces, brackets and parentheses; single-quote them for `sh -c`. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * The last [MAX_SESSIONS] sessions, one encoded line each. Saving is a single write; which
 * summary was last shown is recorded when the user dismisses it, not at save time.
 */
class SessionStore(private val store: KeyValueStore) {

    companion object {
        const val MAX_SESSIONS = 20
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_SEEN = "seen_started_at"

        private val _saved = MutableStateFlow(0L)

        /** Bumped on every save, so an open screen can show the summary without a restart. */
        val saved: StateFlow<Long> = _saved.asStateFlow()
    }

    fun all(): List<SessionRecord> =
        store.getString(KEY_SESSIONS)?.lineSequence()?.mapNotNull { SessionRecord.decode(it) }?.toList().orEmpty()

    /** Appends [record] and keeps the newest [MAX_SESSIONS]. One write. */
    fun save(record: SessionRecord) {
        val kept = (all() + record).takeLast(MAX_SESSIONS)
        store.putString(KEY_SESSIONS, kept.joinToString("\n") { it.encode() })
        _saved.value = _saved.value + 1
    }

    /** The newest session if its summary has not been dismissed yet. */
    fun unseen(): SessionRecord? {
        val last = all().lastOrNull() ?: return null
        return if (store.getString(KEY_SEEN)?.toLongOrNull() == last.startedAtMs) null else last
    }

    fun markSeen(record: SessionRecord) = store.putString(KEY_SEEN, record.startedAtMs.toString())
}
