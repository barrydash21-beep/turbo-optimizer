package com.cj.turboboost

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import java.util.Locale

/**
 * Memory figures for the MEMORY tile.
 *
 * Structured rather than one pre-formatted string: the tile used to `substringBefore(" /")`
 * a formatted string, which silently rendered the whole placeholder in both the 22 sp and
 * the 9 sp slot on the first frame (L2).
 */
data class RamInfo(val fraction: Float, val used: String, val total: String) {
    companion object {
        fun from(totalBytes: Long, availableBytes: Long): RamInfo {
            if (totalBytes <= 0L) return RamInfo(0f, "--", "-- GB")
            val usedBytes = (totalBytes - availableBytes).coerceIn(0L, totalBytes)
            val gb = 1024f * 1024f * 1024f
            return RamInfo(
                fraction = usedBytes.toFloat() / totalBytes.toFloat(),
                used = String.format(Locale.US, "%.1f", usedBytes / gb),
                total = String.format(Locale.US, "%.1f GB", totalBytes / gb)
            )
        }
    }
}

object SystemTelemetry {

    /**
     * Probed in order. A single hardcoded host meant a permanent "--" — read by the user as
     * "no internet" — anywhere that host is blocked (L4).
     */
    private val PROBE_HOSTS = listOf("8.8.8.8", "1.1.1.1")

    private const val PROBE_TIMEOUT_MS = 1500
    private const val PROBE_INTERVAL_MS = 2000L

    fun getRamInfo(context: Context): RamInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            RamInfo.from(memoryInfo.totalMem, memoryInfo.availMem)
        } catch (e: Exception) {
            RamInfo.from(0L, 0L)
        }
    }

    /**
     * Round-trip time to the first reachable DNS host, in milliseconds, or -1.
     *
     * This is a reachability measurement, not a ping: it includes socket setup, and the
     * booster is in the VPN's disallowed list, so it is measured outside the tunnel and can
     * never reflect what the stabilizer does. Labelled accordingly in the UI, and no longer
     * floored at a fabricated 12 ms (L3).
     */
    fun getReachabilityMs(): Flow<Int> = flow {
        while (true) {
            emit(probeOnce())
            delay(PROBE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun probeOnce(): Int {
        for (host in PROBE_HOSTS) {
            try {
                val start = System.currentTimeMillis()
                if (InetAddress.getByName(host).isReachable(PROBE_TIMEOUT_MS)) {
                    return (System.currentTimeMillis() - start).toInt()
                }
            } catch (e: Exception) {
                // Try the next host.
            }
        }
        return -1
    }

    /**
     * Every refresh rate the default display advertises in `Display.getSupportedModes()`, as
     * the panel reports it (e.g. 120.00001), or empty if the display cannot be read.
     *
     * Goes through DisplayManager so an Application context is enough.
     */
    fun getSupportedRefreshRates(context: Context): List<Float> = try {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes
            ?.map { it.refreshRate }
            ?.distinct()
            .orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** Refresh rate the display is currently running at. */
    @Suppress("DEPRECATION") // defaultDisplay on the pre-R branch; minSdk 26 predates context.display
    fun getRefreshRate(context: Context): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.refreshRate ?: RefreshRate.FALLBACK
            } else {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.defaultDisplay?.refreshRate ?: RefreshRate.FALLBACK
            }
        } catch (e: Exception) {
            RefreshRate.FALLBACK
        }
    }
}

/** Pure refresh-rate arithmetic, split out so it is unit-testable without a Display. */
object RefreshRate {

    /** Used whenever the panel advertises nothing usable. */
    const val FALLBACK = 60f

    /** The highest rate the panel advertises, or [FALLBACK] when the list is unusable. */
    fun selectBest(advertised: List<Float>): Float =
        advertised.filter { it > 0f && it.isFinite() }.maxOrNull() ?: FALLBACK

    /** The value written to `min_refresh_rate` / `peak_refresh_rate`. */
    fun format(rate: Float): String = String.format(Locale.US, "%.1f", rate)

    /** The value shown in the DISPLAY tile: rounded, never truncated. */
    fun display(rate: Float): Int = Math.round(rate)

    /** How far an advertised mode may be from a nominal rate and still count as that rate. */
    private const val MODE_TOLERANCE_HZ = 0.5f

    /**
     * The [allowed] rates the panel actually has a mode for. Panels advertise 120.00001 or
     * 89.96, so matching is within [MODE_TOLERANCE_HZ]. If the panel's modes could not be
     * read, only [FALLBACK] is offered: offering a rate the panel lacks makes the display
     * controller flap between modes.
     */
    fun supported(allowed: List<Float>, panelModes: List<Float>): List<Float> {
        if (panelModes.none { it > 0f && it.isFinite() }) return listOf(FALLBACK)
        return allowed.filter { rate -> panelModes.any { kotlin.math.abs(it - rate) <= MODE_TOLERANCE_HZ } }
    }

    /**
     * The rate to use: the user's [stored] pick if it is still supported, else [default] if
     * supported, else the highest supported rate below [default], else the lowest supported.
     */
    fun resolve(supported: List<Float>, stored: Float?, default: Float): Float? {
        if (supported.isEmpty()) return null
        stored?.let { s -> supported.firstOrNull { it == s }?.let { return it } }
        supported.firstOrNull { it == default }?.let { return it }
        return supported.filter { it < default }.maxOrNull() ?: supported.min()
    }

    /** The next supported rate below [current], or null when [current] is already the lowest. */
    fun nextLower(current: Float, supported: List<Float>): Float? =
        supported.filter { it < current }.maxOrNull()
}
