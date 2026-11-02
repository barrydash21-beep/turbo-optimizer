package com.cj.turboboost

import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Cumulative CPU time from the first line of `/proc/stat`, in clock ticks. */
data class CpuTicks(val busy: Long, val total: Long)

/**
 * CPU load from two samples of `/proc/stat`. Pure arithmetic, so it is unit-tested without a
 * device. One sample alone says nothing about load; [CpuSampler] keeps the previous one.
 */
object CpuUsage {

    /** The command whose output [parse] reads, for when the app itself may not read the file. */
    const val COMMAND = "head -n 1 /proc/stat"

    /**
     * `cpu  user nice system idle iowait irq softirq steal ...` -> busy and total ticks.
     * Total is the first eight fields (guest time is already inside user); idle is idle plus
     * iowait. Null for anything that is not the aggregate `cpu` line.
     */
    fun parse(raw: String?): CpuTicks? {
        val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.split(Regex("\\s+"))
        if (parts.firstOrNull() != "cpu") return null
        val fields = parts.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        if (fields.size < 4 || fields.any { it < 0 }) return null
        val total = fields.sum()
        val idle = fields[3] + fields.getOrElse(4) { 0L }
        return CpuTicks(busy = total - idle, total = total)
    }

    /** Whole-number load between two samples, or null when no time passed or the counters reset. */
    fun percent(previous: CpuTicks, now: CpuTicks): Int? {
        val totalDelta = now.total - previous.total
        val busyDelta = now.busy - previous.busy
        if (totalDelta <= 0L || busyDelta < 0L) return null
        return (busyDelta.coerceAtMost(totalDelta) * 100.0 / totalDelta).roundToInt()
    }

    /** The first line of `/proc/stat` read by this app, or null: since Android 8 only the shell may read it. */
    fun readDirect(): String? =
        runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull()
}

/** One tick of CPU: [readable] says whether `/proc/stat` could be read at all, [percent] is null until two samples exist. */
data class CpuReading(val readable: Boolean, val percent: Int? = null)

/**
 * Reads `/proc/stat` and turns consecutive reads into a load. The app's own read is tried
 * first; once it fails the shell is used from then on. Whatever cannot be read is reported as
 * unreadable, never as a number.
 */
class CpuSampler(
    private val shell: ShellExecutor,
    private val direct: () -> String? = CpuUsage::readDirect
) {
    private var previous: CpuTicks? = null
    private var useDirect = true

    /** Who read the last line: "the app" or "Shizuku". Null before the first read. */
    var via: String? = null
        private set

    /** The raw first line from whichever route works, or null. Also what the diagnostics row probes. */
    fun readRaw(): String? {
        if (useDirect) {
            direct()?.takeIf { CpuUsage.parse(it) != null }?.let {
                via = "the app"
                return it
            }
            useDirect = false
        }
        via = "Shizuku"
        return shell.capture(CpuUsage.COMMAND)
    }

    fun sample(): CpuReading {
        val ticks = CpuUsage.parse(readRaw())
        if (ticks == null) {
            // A load across a gap of failed reads would not be the last second's.
            previous = null
            return CpuReading(readable = false)
        }
        val load = previous?.let { CpuUsage.percent(it, ticks) }
        previous = ticks
        return CpuReading(readable = true, percent = load)
    }
}

object RamUsage {

    /** Used share of memory as a whole number, or null when the total is unknown. */
    fun percent(totalBytes: Long, availableBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        val used = (totalBytes - availableBytes).coerceIn(0L, totalBytes)
        return (used * 100.0 / totalBytes).roundToInt()
    }
}

/**
 * The game's frame rate from the frames each poll returns. Keeps the newest frame already
 * counted so no frame counts twice, and chains it in front of the next poll so the gap between
 * polls is measured too. Gaps of [Diagnostics.PAUSE_NS] or more (loading, menus) are not frames
 * and are left out, as in a session's own average.
 */
class OverlayFps {

    /** The newest presented frame already counted; the next poll reads from after it. */
    var lastPresentNs = 0L
        private set

    /**
     * Whole frames per second over this poll's frames, or null when there are too few to call
     * one. A poll that is too short is not consumed: the next one re-reads its frames.
     */
    fun consume(presentsNs: List<Long>): Int? {
        if (presentsNs.isEmpty()) return null
        val chain = if (lastPresentNs > 0L) listOf(lastPresentNs) + presentsNs else presentsNs
        val intervals = Diagnostics.intervals(chain).filter { it < Diagnostics.PAUSE_NS }
        val fps = Diagnostics.averageFps(intervals) ?: return null
        lastPresentNs = max(lastPresentNs, presentsNs.last())
        return fps.roundToInt()
    }
}

/** What the overlay prints. A figure that could not be read says so; nothing is estimated. */
object OverlayText {

    const val UNAVAILABLE = "N/A"

    fun format(fps: Int?, cpu: CpuReading, ramPercent: Int?): String =
        "FPS ${fps?.toString() ?: UNAVAILABLE}  " +
            "CPU ${cpuText(cpu)}  " +
            "RAM ${ramPercent?.let { "$it%" } ?: UNAVAILABLE}"

    /** "--" only for the one tick before a first delta exists; unreadable is "N/A". */
    private fun cpuText(cpu: CpuReading): String = when {
        !cpu.readable -> UNAVAILABLE
        cpu.percent == null -> "--"
        else -> "${cpu.percent}%"
    }
}
