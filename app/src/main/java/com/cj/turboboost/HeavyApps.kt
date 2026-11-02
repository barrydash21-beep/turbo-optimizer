package com.cj.turboboost

import kotlin.math.roundToInt

/** A running third-party app and the memory its processes hold. */
data class HeavyApp(val packageName: String, val megabytes: Int)

/** The heavy-apps card: its rows once read, whether a read is running, and what to tell the user. */
data class HeavyAppsUi(
    val apps: List<HeavyApp>? = null,
    val loading: Boolean = false,
    /** Why there is no list, or what the last action did. */
    val note: String? = null,
    /** Packages stopped from the card: shown with their result until a REFRESH finds them not running. */
    val stopped: Set<String> = emptySet(),
    /** Packages put on the restriction list from the card. */
    val listed: Set<String> = emptySet()
)

/**
 * The few running third-party apps that hold the most memory, for the user to stop or restrict
 * one at a time. Read-only: stopping goes through [RamCleaner.stopApp] and restricting adds to
 * the existing background restriction list, so nothing here changes the device.
 */
object HeavyApps {

    const val LIMIT = 5

    const val STATUS_STOPPED = "Stopped"
    const val STATUS_RESTRICTED = "Restricted"
    const val STATUS_BUCKET_IGNORED = "Restricted: bucket ignored by system"
    const val STATUS_CAME_BACK = "Came back"
    const val STATUS_LISTED = "On the list: applies at the next boost"
    private const val LAST_SESSION = " (last session)"

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

    /**
     * What the card says about one row, from reads this app already makes; nothing is polled.
     *
     * - Stopped from the card: "Stopped", or "Came back" when a refresh finds it running again.
     * - A restriction has run for it ([notes], what the system ignored of that restriction):
     *   "Came back" if it was alive at the check [BackgroundRestrictor.ALIVE_CHECK_DELAY_MS] into
     *   that session, else "Restricted: bucket ignored by system" when the bucket read-back
     *   differed, else "Restricted". Once the session has put the restriction back ([held] false)
     *   these say "(last session)": they are what happened, not what holds now.
     * - Only put on the list from the card: that it applies at the next boost, as nothing is
     *   restricted until then.
     */
    internal fun status(
        stopped: Boolean,
        running: Boolean,
        listed: Boolean,
        notes: List<String>?,
        aliveEarly: Boolean,
        held: Boolean
    ): String? = when {
        stopped -> if (running) STATUS_CAME_BACK else STATUS_STOPPED
        notes != null -> when {
            aliveEarly -> STATUS_CAME_BACK
            notes.any { it.startsWith("bucket ignored") } -> STATUS_BUCKET_IGNORED
            else -> STATUS_RESTRICTED
        } + if (held) "" else LAST_SESSION
        listed -> STATUS_LISTED
        else -> null
    }

    /** The restricted apps alive at the early check of the session that ran the restriction, or null. */
    internal fun aliveEarlyFor(last: SessionRecord?, restrictedAtMs: Long): List<String>? =
        last?.takeIf { it.startedAtMs >= restrictedAtMs }?.restrictedAliveEarly

    /**
     * The stopped marks after a read: kept while [keepStopped] (the read straight after a stop),
     * else only those found running again, which then show as having come back.
     */
    internal fun stoppedAfterRefresh(stopped: Set<String>, running: Set<String>, keepStopped: Boolean): Set<String> =
        if (keepStopped) stopped else stopped.filter { it in running }.toSet()

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
