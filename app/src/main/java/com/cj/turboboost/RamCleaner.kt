package com.cj.turboboost

/**
 * Outcome of a boost or a restore, named step by step.
 *
 * Every sub-step result used to be discarded, so a boost in which nothing at all applied was
 * indistinguishable from a complete one, and restore reported success off the back of a
 * single unrelated call (M1/M2). Callers surface this verbatim.
 */
data class StepReport(
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    /** The Shizuku reflection entry point is gone — distinct from a command failing (M10). */
    val apiUnsupported: Boolean = false,
    /** The Shizuku binder was not alive, so nothing was attempted. */
    val unavailable: Boolean = false,
    /** How many packages the RAM purge stopped. */
    val stoppedCount: Int = 0,
    /** Packages the RAM purge tried and failed to stop. */
    val killFailed: List<String> = emptyList()
) {
    val allOk: Boolean get() = failed.isEmpty() && !apiUnsupported && !unavailable

    /** A one-line summary fit for a toast. */
    fun summary(okText: String): String = when {
        apiUnsupported -> "Shizuku API not supported"
        unavailable -> "Shizuku not connected"
        failed.isEmpty() && applied.isEmpty() -> "Nothing to do"
        failed.isEmpty() -> okText
        applied.isEmpty() -> "Failed: ${failed.joinToString(", ")}"
        else -> "Partly done - failed: ${failed.joinToString(", ")}"
    }
}

/** Accumulates named step results into a [StepReport]. */
private class ReportBuilder {
    private val applied = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    var apiUnsupported = false
        private set
    var stoppedCount = 0
    var killFailed: List<String> = emptyList()

    fun record(name: String, result: ShellResult) {
        if (result.apiUnsupported) apiUnsupported = true
        if (result.ok) applied += name else failed += name
    }

    fun record(name: String, ok: Boolean) {
        if (ok) applied += name else failed += name
    }

    fun build(sequenceHitMissingApi: Boolean = false) = StepReport(
        applied = applied.toList(),
        failed = failed.toList(),
        apiUnsupported = apiUnsupported || sequenceHitMissingApi,
        stoppedCount = stoppedCount,
        killFailed = killFailed
    )
}

/** Outcome of the background purge. */
data class KillResult(
    val stopped: Int = 0,
    val failed: List<String> = emptyList(),
    /** The runtime list could not be built, so the static fallback list was used. */
    val usedFallback: Boolean = false,
    val unavailable: Boolean = false
) {
    /** Nothing failed, or at least something was stopped (a stale hog failing is not fatal). */
    val ok: Boolean get() = !unavailable && (failed.isEmpty() || stopped > 0)
}

object RamCleaner {

    /**
     * How privileged commands actually run. Swappable so the boost/restore logic can be
     * exercised in unit tests against a fake executor.
     */
    @Volatile
    var shell: ShellExecutor = ShizukuShell

    /** Master auto-sync access, swappable for the same reason as [shell]. */
    @Volatile
    var syncSettings: SyncSettings = PlatformSyncSettings

    /**
     * Sticky "the Shizuku reflection entry point is gone" flag for the sequence currently
     * running. Most sub-steps collapse their results to a Boolean before the report sees
     * them, so without this latch an API break would be indistinguishable from every
     * command simply failing (M10).
     *
     * Thread-local: a sequence runs start to finish on one dispatcher thread, and the flag
     * must not leak between callers.
     */
    private val apiUnsupportedSeen = ThreadLocal.withInitial { false }

    private fun beginSequence() = apiUnsupportedSeen.set(false)

    private fun sequenceHitMissingApi(): Boolean = apiUnsupportedSeen.get() == true

    /**
     * Runs one command. Each invocation is bounded by the executor's own per-command
     * timeout and always reaps the remote process (H1/H2).
     */
    private fun runShizukuCommand(command: String): ShellResult {
        val result = shell.run(command)
        if (result.apiUnsupported) apiUnsupportedSeen.set(true)
        return result
    }

    /**
     * Force-stops every running third-party app plus the running Transsion bloat, except the
     * protected set (this app, [selectedGame], Shizuku, the current launcher, keyboard, SMS app
     * and dialer, and anything whose name contains a [AppPackages.PROTECTED_NAME_PARTS] entry). The other
     * games the user added are stopped like any other app.
     *
     * The list is built at runtime from `pm list packages -3` and `ps -A`. If it cannot be
     * built safely (no third-party list, or the launcher or keyboard cannot be resolved), it
     * falls back to the static [AppPackages.BACKGROUND_HOGS] rather than risk stopping either.
     *
     * NOTE: `am force-stop` also puts a package into the *stopped* state — its alarms and
     * jobs are cancelled and it receives no broadcasts until the user launches it by hand.
     * That is disclosed in the UI (M12).
     */
    fun killBackgroundApps(selectedGame: String): KillResult {
        if (!shell.isAvailable()) return KillResult(unavailable = true)

        val targets = runtimeKillTargets(selectedGame)
        val usedFallback = targets == null
        val toStop = targets ?: AppPackages.BACKGROUND_HOGS.filter { it != selectedGame }

        val failed = mutableListOf<String>()
        var stopped = 0
        for (pkg in toStop) {
            if (runShizukuCommand("am force-stop $pkg").ok) stopped++ else failed += pkg
        }
        // Final sweep of cached background processes; its outcome does not change the count.
        runShizukuCommand("am kill-all")
        return KillResult(stopped = stopped, failed = failed, usedFallback = usedFallback)
    }

    /** The packages to stop, or null when the runtime list cannot be built safely. */
    private fun runtimeKillTargets(selectedGame: String): List<String>? {
        val thirdParty = parsePackageList(shell.capture("pm list packages -3")) ?: return null
        val running = parseProcessNames(shell.capture("ps -A -o NAME")) ?: return null
        val launcher = parseComponentPackage(
            lastLine(
                shell.capture(
                    "cmd package resolve-activity --brief " +
                        "-a android.intent.action.MAIN -c android.intent.category.HOME"
                )
            )
        )?.takeIf { it != "android" } ?: return null
        val keyboard = parseComponentPackage(shell.capture("settings get secure default_input_method"))
            ?: return null

        // The default SMS app and dialer: force-stopping them left SMS/OTPs unnotified until
        // opened by hand (seen on-device with Google Messages). Best effort: none resolved
        // just adds nothing, it does not trigger the fallback.
        val roleHolders = PROTECTED_ROLES.flatMap { role ->
            parseRoleHolders(shell.capture("cmd role get-role-holders $role"))
        }

        return selectKillTargets(
            running = running,
            thirdParty = thirdParty,
            bloat = AppPackages.TRANSSION_BLOAT.toSet(),
            protected = AppPackages.PROTECTED.toSet() + selectedGame + launcher + keyboard + roleHolders
        )
    }

    private val PROTECTED_ROLES = listOf("android.app.role.SMS", "android.app.role.DIALER")

    /** `cmd role get-role-holders` prints the holders separated by `;` or newlines. */
    internal fun parseRoleHolders(raw: String?): Set<String> =
        raw?.split(';', '\n', '\r')
            ?.map { it.trim() }
            ?.filter { it.contains('.') && it.none { c -> c.isWhitespace() } }
            ?.toSet()
            .orEmpty()

    /** kill = running AND (third-party OR bloat), minus everything protected. */
    internal fun selectKillTargets(
        running: Set<String>,
        thirdParty: Set<String>,
        bloat: Set<String>,
        protected: Set<String>
    ): List<String> = running
        .filter { it in thirdParty || it in bloat }
        .filter { pkg ->
            pkg !in protected && AppPackages.PROTECTED_NAME_PARTS.none { pkg.contains(it, ignoreCase = true) }
        }
        .sorted()

    /** `package:com.foo` lines; null when there is nothing to parse. */
    internal fun parsePackageList(raw: String?): Set<String>? {
        val packages = raw?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.startsWith("package:") }
            ?.map { it.removePrefix("package:").trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        return packages?.takeIf { it.isNotEmpty() }
    }

    /**
     * Process names from `ps -A -o NAME`, reduced to package names (`com.foo:svc` -> `com.foo`).
     * Kernel threads and native daemons have no dot and are dropped.
     */
    internal fun parseProcessNames(raw: String?): Set<String>? {
        val names = raw?.lineSequence()
            ?.map { it.trim().substringBefore(':') }
            ?.filter { it.contains('.') && it.none { c -> c.isWhitespace() || c == '/' || c == '[' } }
            ?.toSet()
        return names?.takeIf { it.isNotEmpty() }
    }

    /** `com.foo/.Bar` -> `com.foo`; null for blank, "null" or anything that is not a component. */
    internal fun parseComponentPackage(raw: String?): String? {
        val value = raw?.trim()
        if (value.isNullOrEmpty() || value == "null" || !value.contains('/')) return null
        return value.substringBefore('/').trim().takeIf { it.isNotEmpty() }
    }

    private fun lastLine(raw: String?): String? =
        raw?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }

    /**
     * Frees pagecache, dentries and inodes.
     *
     * Requires root-mode Shizuku (uid 0); under ADB-mode Shizuku (uid 2000) the write to
     * /proc/sys/vm/drop_caches is denied and this returns false. Failure is non-fatal.
     */
    fun dropKernelCaches(): Boolean {
        if (!shell.isAvailable()) return false
        // Flush dirty pages first so they are actually reclaimable.
        runShizukuCommand("sync")
        return runShizukuCommand("echo 3 > /proc/sys/vm/drop_caches").ok
    }

    /**
     * Opts [gamePackage] into one of Android's native GameManager scheduling profiles.
     *
     * @param mode one of battery | standard | performance.
     *
     * `cmd game mode` is API 31+; older shells return "Unknown service".
     */
    fun applyGameMode(mode: String, gamePackage: String): Boolean {
        if (!shell.isAvailable()) return false
        return runShizukuCommand("cmd game mode $mode $gamePackage").ok
    }

    /**
     * Silences heads-up notifications (which composite over the game surface on the UI
     * thread) and suspends master auto-sync.
     *
     * TURBO only. This stops the phone ringing for anyone not on the priority list and halts
     * email/calendar/contact sync device-wide, which is far too large a side effect for the
     * default profile to apply unannounced (H5). It is named in the TURBO blurb.
     *
     * Uses priority-only rather than Total Silence so the volume UI stays usable;
     * [restoreSystemState] undoes this.
     */
    fun suppressSystemInterruptions(): Boolean {
        if (!shell.isAvailable()) return false

        // zen_mode is a hidden key; writing it alone bypasses ZenModeHelper and may not
        // stick, so follow up with the supported shell path.
        //
        // Priority-only (1), NOT Total Silence (2): Total Silence forces the ringer stream
        // silent and locks the volume UI, so the user cannot adjust game volume mid-match.
        // Priority-only still suppresses the heads-up notifications we care about.
        val zen = runShizukuCommand("settings put global zen_mode 1").ok // 1 = priority only
        val dnd = runShizukuCommand("cmd notification set_dnd priority").ok

        val sync = syncSettings.setMasterSyncEnabled(false)

        // Either DND path landing is enough for the filter to be active.
        return (zen || dnd) && sync
    }

    /**
     * Animation scales applied during a boost.
     *
     * window/transition go to 0 -- that is what actually cuts app-launch delay and is
     * visually safe. animator_duration_scale stays at 1 on purpose: zeroing it makes
     * Compose spring()/animateFloatAsState snap to their end value, which reads as UI
     * flicker on the booster screen.
     */
    private val ANIMATION_SCALES_BOOST = mapOf(
        "window_animation_scale" to "0",
        "transition_animation_scale" to "0",
        "animator_duration_scale" to "1"
    )

    /**
     * Cuts perceived input-to-display delay by zeroing the system animation scales, and
     * locks pointer speed to maximum (valid range -7..7).
     *
     * The animation scales are the part that actually helps: at 0 the window, transition
     * and animator durations collapse, so app switching and the game launch stop waiting
     * on animation frames.
     *
     * NOTE: Settings.System.pointer_speed governs the *mouse/trackpad* cursor, not the
     * touchscreen -- per AOSP InputSettings it has no effect on touch latency or sampling.
     * Kept because it is explicitly specified and harmless; see [applyGameMode]
     * for the scheduling lever that does affect gameplay responsiveness.
     */
    fun optimizeTouchInput(): Boolean {
        if (!shell.isAvailable()) return false

        var scaled = true
        for ((key, value) in ANIMATION_SCALES_BOOST) {
            if (!runShizukuCommand("settings put global $key $value").ok) scaled = false
        }

        val pointer = runShizukuCommand("settings put system pointer_speed 7").ok
        return scaled && pointer
    }

    /**
     * Returns the device to normal: undoes every change the booster makes, writing back the
     * user's own pre-boost values from [store] rather than guessing platform defaults (H4).
     *
     * Falls back to platform defaults only where the snapshot has no value. Clears the
     * snapshot only on a fully successful restore, so a partial failure can be retried
     * against the original values.
     */
    fun restoreSystemState(store: KeyValueStore): StepReport {
        if (!shell.isAvailable()) return StepReport(unavailable = true)

        beginSequence()
        val report = ReportBuilder()

        // Notifications: put the user's own DND mode back, not an unconditional "off".
        val zen = SystemSnapshot.zenMode(store)
        report.record("Do Not Disturb", runShizukuCommand("settings put global zen_mode $zen"))
        runShizukuCommand("cmd notification set_dnd ${SystemSnapshot.dndArgumentFor(zen)}")

        report.record(
            "Pointer speed",
            runShizukuCommand("settings put system pointer_speed ${SystemSnapshot.pointerSpeed(store)}")
        )

        var scalesOk = true
        for (key in ANIMATION_SCALES_BOOST.keys) {
            val value = SystemSnapshot.animationScale(store, key)
            if (!runShizukuCommand("settings put global $key $value").ok) scalesOk = false
        }
        report.record("Animations", scalesOk)

        // Refresh rate is not touched here: PerformanceTuner owns it and writes back the
        // user's exact saved values. Deleting the keys here used to wipe the user's own rate
        // whenever Restore was tapped with no tweak session held.

        // Only touched when we know what it was. With no snapshot there is nothing to
        // restore, and defaulting to "on" is the metered-data bill H4 is about — turning
        // auto-sync on for a user who deliberately keeps it off starts a device-wide sync.
        if (SystemSnapshot.exists(store)) {
            report.record(
                "Auto-sync",
                syncSettings.setMasterSyncEnabled(SystemSnapshot.masterSync(store))
            )
        }

        val result = report.build(sequenceHitMissingApi())
        // Keep the snapshot if anything failed, so a retry still has the original values.
        if (result.allOk) SystemSnapshot.clear(store)
        return result
    }

    /**
     * Runs one boost according to [profile] for [gamePackage]. Single reader of the profile
     * matrix. The refresh rate is not part of it: [PerformanceTuner] applies the per-game rate.
     *
     * Snapshots the user's current settings first, so [restoreSystemState] has something
     * truthful to write back.
     */
    fun applyProfile(profile: BoostProfile, gamePackage: String, store: KeyValueStore): StepReport {
        if (!shell.isAvailable()) return StepReport(unavailable = true)

        beginSequence()

        // Before anything is mutated, and only if we are not already holding a snapshot
        // from an earlier un-restored boost.
        SystemSnapshot.captureIfAbsent(shell, store, syncSettings)

        val report = ReportBuilder()

        // Always: reclaiming background RAM is the cheapest win and carries no risk.
        val kill = killBackgroundApps(gamePackage)
        report.record("RAM purge", kill.ok)
        report.stoppedCount = kill.stopped
        report.killFailed = kill.failed

        // After the force-stops, so the memory they released is reclaimable.
        if (profile.dropCaches) report.record("Kernel caches", dropKernelCaches())

        report.record("Game mode", applyGameMode(profile.gameMode, gamePackage))

        if (profile.suppressInterruptions) report.record("Do Not Disturb", suppressSystemInterruptions())
        if (profile.trimAnimations) report.record("Animations", optimizeTouchInput())

        return report.build(sequenceHitMissingApi())
    }
}
