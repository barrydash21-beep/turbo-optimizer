package com.cj.turboboost

import kotlin.math.roundToInt

/** A running third-party app and the memory its processes hold. */
data class HeavyApp(val packageName: String, val megabytes: Int)

/** The heavy-apps card: its rows once read, whether a read is running, and what to tell the user. */
data class HeavyAppsUi(
    val apps: List<HeavyApp>? = null,
    val loading: Boolean = false,
    /** Why there is no list, or what the last action did. */
    val note: String? = null
)

/**
 * The few running third-party apps that hold the most memory, for the user to stop or restrict
 * one at a time. Read-only: stopping goes through [RamCleaner.stopApp] and restricting adds to
 * the existing background restriction list, so nothing here changes the device.
 */
object HeavyApps {

    const val LIMIT = 5

    /**
     * Resident set size per process, in kilobytes. RSS counts shared pages in every process
     * that maps them, so the figures are a ranking, not a sum that matches the RAM total.
     */
    private const val RESIDENT_COMMAND = "ps -A -o RSS,NAME"

    /**
     * `ps -A -o RSS,NAME` as kilobytes per package: a package's processes (`com.foo`,
     * `com.foo:svc`) are added together, and rows without a dotted name are dropped.
     */
    internal fun parseResident(raw: String?): Map<String, Long>? {
        val perPackage = linkedMapOf<String, Long>()
        raw?.lineSequence()?.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@forEach
            val kb = parts[0].toLongOrNull() ?: return@forEach
            val name = parts[1].trim().substringBefore(':')
            if (!name.contains('.') || name.any { it.isWhitespace() || it == '/' || it == '[' }) return@forEach
            perPackage[name] = (perPackage[name] ?: 0L) + kb
        }
        return perPackage.takeIf { it.isNotEmpty() }
    }

    /**
     * The heaviest [limit] of [resident] that are third-party, not [protected] and not one of
     * the user's [games]: the same protection the stop itself applies.
     */
    internal fun select(
        resident: Map<String, Long>,
        thirdParty: Set<String>,
        protected: Set<String>,
        games: Set<String>,
        limit: Int = LIMIT
    ): List<HeavyApp> = resident.entries
        .filter { (pkg, _) -> pkg in thirdParty && pkg !in games && !AppPackages.isProtected(pkg, protected) }
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { (pkg, kb) -> HeavyApp(pkg, (kb / 1024.0).roundToInt()) }

    /** The list a session restricts once [pkg] is on it: the user's own, or else [defaults]. Never twice. */
    internal fun restrictedWith(current: List<String>?, defaults: List<String>, pkg: String): List<String> =
        ((current ?: defaults) + pkg).distinct()

    /** Reads the card's rows through Shizuku. Blocking: call off the main thread. */
    fun load(shell: ShellExecutor, selectedGame: String?, games: Set<String>): HeavyAppsUi {
        if (!shell.isAvailable()) return HeavyAppsUi(note = "Connect Shizuku to list running apps")
        val thirdParty = RamCleaner.parsePackageList(shell.capture("pm list packages -3"))
        val protected = RamCleaner.protectedPackages(shell, selectedGame)
        val resident = parseResident(shell.capture(RESIDENT_COMMAND))
        return when {
            thirdParty == null || resident == null -> HeavyAppsUi(note = "Could not read the running apps")
            protected == null -> HeavyAppsUi(note = "Could not tell which apps are protected, so none are listed")
            else -> HeavyAppsUi(apps = select(resident, thirdParty, protected, games))
        }
    }
}
