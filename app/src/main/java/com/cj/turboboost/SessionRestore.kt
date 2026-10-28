package com.cj.turboboost

/** What putting a session's changes back did, one report per kind of record. */
data class SessionRestoreResult(
    val tweaks: TuneReport,
    /** Null when no snapshot of the user's own settings was held. */
    val system: StepReport?,
    val restrictions: RestrictionReport
) {
    /**
     * Every held record was put back. Anything less leaves the failed records held, and the
     * caller must keep retrying rather than end the session.
     */
    val allRestored: Boolean
        get() = tweaks.ok && (system == null || system.allOk) && restrictions.ok
}

/**
 * Puts back everything a boost holds: the gaming tweaks, the snapshot of the user's own
 * settings (animation scales, Do Not Disturb, pointer speed, auto-sync) and the background
 * restriction. [SessionEnd.finish] calls this for the game exit, RESTORE and the next app start,
 * so they cannot disagree on what "restored" means. Each part does nothing when it holds nothing,
 * and the snapshot part never writes over a setting changed since ([RamCleaner.restoreHeldSnapshot]).
 */
object SessionRestore {

    fun restoreHeld(
        shell: ShellExecutor,
        tuner: KeyValueStore,
        snapshot: KeyValueStore,
        restrictions: KeyValueStore
    ): SessionRestoreResult = SessionRestoreResult(
        tweaks = PerformanceTuner.revertGamingTweaks(tuner),
        system = RamCleaner.restoreHeldSnapshot(snapshot),
        restrictions = BackgroundRestrictor.restore(shell, restrictions)
    )
}
