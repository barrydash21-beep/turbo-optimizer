package com.cj.turboboost

import android.util.Log

/** What one apply or restore did. [state] is the read-back, or null if it could not be read. */
data class ResolutionResult(
    val ok: Boolean,
    val detail: String,
    val state: DisplayState? = null,
    val unavailable: Boolean = false
)

/**
 * Applies and restores a render resolution override with `wm size` and `wm density`.
 *
 * The original override state (including "no override") is persisted, and `pending` is set,
 * before any `wm` command runs. `pending` is cleared only once a restore has been verified by
 * read-back, so a crash, a force-close or a reboot with the override still applied is always
 * undone on the next start. Same contract as [PerformanceTuner]'s `tweaks_active`.
 *
 * Kept in its own store: [RamCleaner.restoreSystemState] clears the whole snapshot file.
 */
object ResolutionLever {

    private const val TAG = "TurboBooster"

    private const val KEY_PENDING = "pending_restore"
    private const val KEY_SNAPSHOT = "original_present"
    private const val KEY_ORIG_SIZE = "original_override_size"
    private const val KEY_ORIG_DENSITY = "original_override_density"
    private const val KEY_TARGET_SIZE = "target_size"
    private const val KEY_TARGET_DENSITY = "target_density"

    /** Writable by the shell uid Shizuku runs as (2000), and by root. */
    private const val WATCHDOG_PID = "/data/local/tmp/turboboost_resolution_watchdog.pid"

    /**
     * Whether this process applied the override now pending. False after a process restart,
     * which is how a fresh start recognises an override left behind by a crash or force-close.
     */
    @Volatile
    var appliedInThisProcess = false
        private set

    fun isPending(store: KeyValueStore): Boolean = store.getBoolean(KEY_PENDING, false)

    /** The override this app meant to hold, or null when nothing is pending. */
    fun intended(store: KeyValueStore): ResolutionTarget? {
        if (!isPending(store)) return null
        val size = DisplaySize.parse(store.getString(KEY_TARGET_SIZE)) ?: return null
        val density = store.getString(KEY_TARGET_DENSITY)?.toIntOrNull() ?: return null
        return ResolutionTarget(size, density)
    }

    /** Current `wm size` / `wm density`, or null when either cannot be read. */
    fun read(shell: ShellExecutor): DisplayState? =
        DisplayResolution.parseState(shell.capture("wm size"), shell.capture("wm density"))

    /**
     * Applies [target], reads it back, and restores straight away if the system does not hold
     * exactly what was asked for.
     */
    @Synchronized
    fun apply(shell: ShellExecutor, store: KeyValueStore, target: ResolutionTarget): ResolutionResult {
        if (!shell.isAvailable()) return ResolutionResult(false, "Shizuku not connected", unavailable = true)
        val before = read(shell) ?: return ResolutionResult(false, "Could not read wm size / wm density")
        if (!DisplayResolution.isAllowed(target, before.physicalSize)) {
            return ResolutionResult(false, "$target is outside 50-100% of ${before.physicalSize}", before)
        }

        // Only the first apply records the original: a second preset on top of the first must
        // not save our own override as the user's.
        if (!store.getBoolean(KEY_SNAPSHOT, false)) {
            store.putString(KEY_ORIG_SIZE, before.overrideSize?.toString())
            store.putString(KEY_ORIG_DENSITY, before.overrideDensity?.toString())
            store.putBoolean(KEY_SNAPSHOT, true)
        }
        store.putString(KEY_TARGET_SIZE, target.size.toString())
        store.putString(KEY_TARGET_DENSITY, target.density.toString())
        store.putBoolean(KEY_PENDING, true)
        appliedInThisProcess = true

        val sizeOk = shell.run("wm size ${target.size}").ok
        val densityOk = shell.run("wm density ${target.density}").ok
        val after = read(shell)
        log { Log.i(TAG, "Resolution -> $target, read back: $after") }
        if (sizeOk && densityOk && after != null && DisplayResolution.matches(after, target)) {
            return ResolutionResult(true, "Applied $target", after)
        }

        val asked = "asked for $target"
        val got = after?.let { "system reports ${it.size} @ ${it.density} dpi" } ?: "read-back failed"
        val restore = restoreLocked(shell, store)
        val outcome = if (restore.ok) "restored" else "restore failed: ${restore.detail}"
        return ResolutionResult(false, "Mismatch: $asked, $got - $outcome", restore.state)
    }

    /**
     * Puts the original override state back: `reset` where there was none, the stored value
     * where there was one. With no snapshot held (an override this app did not record), both
     * axes are reset. Clears `pending` only when the read-back shows the original.
     */
    @Synchronized
    fun restore(shell: ShellExecutor, store: KeyValueStore): ResolutionResult = restoreLocked(shell, store)

    /** [restore] only when this app has an override pending; otherwise a no-op that succeeds. */
    @Synchronized
    fun restoreIfPending(shell: ShellExecutor, store: KeyValueStore): ResolutionResult =
        if (isPending(store)) restoreLocked(shell, store) else ResolutionResult(true, "Nothing to restore")

    /**
     * Starts the keep-or-revert deadline in the Shizuku shell rather than in this app.
     *
     * HiOS freezes this app's uid (Usf_Hiber) a few seconds after it leaves the foreground,
     * foreground service or not, and defers its alarms, so an in-app timer never fires while
     * backgrounded. The shell process is not frozen: it sleeps [seconds] and then runs the same
     * restore commands as [restore]. [cancelWatchdog] (Keep, or any restore) stops it.
     */
    fun startWatchdog(shell: ShellExecutor, store: KeyValueStore, seconds: Int): Boolean {
        cancelWatchdog(shell)
        val (sizeCmd, densityCmd) = restoreCommands(store)
        val script = "echo \$\$ > $WATCHDOG_PID; sleep $seconds; rm -f $WATCHDOG_PID; $sizeCmd; $densityCmd"
        // setsid + no inherited pipes: it outlives the short-lived `sh -c` Shizuku runs it in.
        return shell.run("setsid sh -c '$script' </dev/null >/dev/null 2>&1 &").ok
    }

    /** Stops a pending [startWatchdog] deadline, if any. */
    fun cancelWatchdog(shell: ShellExecutor): Boolean =
        shell.run("[ -f $WATCHDOG_PID ] && kill \$(cat $WATCHDOG_PID) 2>/dev/null; rm -f $WATCHDOG_PID").ok

    /** The `wm` commands that put the recorded original back: `reset` where there was no override. */
    private fun restoreCommands(store: KeyValueStore): Pair<String, String> {
        val (origSize, origDensity) = original(store)
        return (origSize?.let { "wm size $it" } ?: "wm size reset") to
            (origDensity?.let { "wm density $it" } ?: "wm density reset")
    }

    private fun original(store: KeyValueStore): Pair<DisplaySize?, Int?> {
        if (!store.getBoolean(KEY_SNAPSHOT, false)) return null to null
        return DisplaySize.parse(store.getString(KEY_ORIG_SIZE)) to store.getString(KEY_ORIG_DENSITY)?.toIntOrNull()
    }

    private fun restoreLocked(shell: ShellExecutor, store: KeyValueStore): ResolutionResult {
        if (!shell.isAvailable()) return ResolutionResult(false, "Shizuku not connected", unavailable = true)

        // Whatever happens next, a deadline still sleeping must not fire on top of it.
        cancelWatchdog(shell)
        val (origSize, origDensity) = original(store)
        val (sizeCmd, densityCmd) = restoreCommands(store)

        val sizeOk = shell.run(sizeCmd).ok
        val densityOk = shell.run(densityCmd).ok
        val after = read(shell)
        log { Log.i(TAG, "Resolution restore to size=${origSize ?: "reset"} density=${origDensity ?: "reset"}, read back: $after") }

        val verified = after != null && after.overrideSize == origSize && after.overrideDensity == origDensity
        if (!verified) {
            val why = when {
                !sizeOk || !densityOk -> "wm command failed"
                after == null -> "read-back failed"
                else -> "system still reports ${after.size} @ ${after.density} dpi"
            }
            return ResolutionResult(false, "Restore not verified: $why", after)
        }

        for (key in listOf(KEY_ORIG_SIZE, KEY_ORIG_DENSITY, KEY_TARGET_SIZE, KEY_TARGET_DENSITY)) {
            store.putString(key, null)
        }
        store.putBoolean(KEY_SNAPSHOT, false)
        store.putBoolean(KEY_PENDING, false)
        appliedInThisProcess = false
        return ResolutionResult(true, "Restored ${after!!.size} @ ${after.density} dpi", after)
    }

    /** Logging must never fail an apply or restore (android.util.Log is absent in JVM tests). */
    private inline fun log(block: () -> Unit) {
        runCatching { block() }
    }
}
