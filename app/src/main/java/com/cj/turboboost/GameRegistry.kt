package com.cj.turboboost

import android.content.Context

/** One game the user added and the display/power profile a boost applies for it. */
data class GameProfile(
    val packageName: String,
    val displayName: String,
    val defaultRefreshRate: Float,
    val allowedRefreshRates: List<Float>,
    val useFixedPerformanceMode: Boolean
)

/**
 * The booster ships with no built-in game list. The user adds any installed app as a game,
 * and every one of them gets the same generic profile; the rates on offer are then narrowed
 * to what the panel actually supports.
 */
object GameRegistry {

    val DEFAULT_RATES = listOf(60f, 90f, 120f)

    const val DEFAULT_RATE = 60f

    private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    /**
     * Games now come from the user's installed apps, and their package names reach shell
     * commands (`cmd game mode`, the kill list's protected set). A real package name only
     * ever matches this, so anything else is refused rather than passed to the shell (T1).
     */
    fun isValidPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    fun profileFor(packageName: String, displayName: String = packageName) = GameProfile(
        packageName = packageName,
        displayName = displayName,
        defaultRefreshRate = DEFAULT_RATE,
        allowedRefreshRates = DEFAULT_RATES,
        useFixedPerformanceMode = true
    )

    /** The installed app's own label, or null when it is missing or not installed. */
    fun labelFor(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
