package com.cj.turboboost

/**
 * A monitor-only session: the game is launched and measured exactly as a boosted session is,
 * with nothing changed. No restriction, no RAM purge, no profile, no refresh rate, no
 * stabilizer, no resolution preset set again. It is what a boost's numbers are compared with.
 */
object Baseline {

    /** What the phone holds right now that would make a baseline measure something else. */
    data class Held(
        val tweaks: Boolean,
        val restriction: Boolean,
        /** The snapshot of the user's own settings: a boost's DND, animations or sync still changed. */
        val systemSnapshot: Boolean,
        /** A render resolution preset or FPS cap this app holds for the game. */
        val gamePreset: Boolean,
        val sessionOpen: Boolean,
        val stabilizer: Boolean
    )

    /** Why a baseline cannot start now, or null when it can. */
    fun refusal(held: Held): String? = when {
        held.sessionOpen -> "A session is still running - wait for it to end or tap RESTORE"
        held.tweaks || held.restriction || held.systemSnapshot ->
            "A boost's changes are still on - tap RESTORE NORMAL SETTINGS first"
        held.gamePreset -> "The game holds a resolution preset or FPS cap - set it to DEFAULT and OFF first"
        held.stabilizer -> "The stabilizer is on - stop it first"
        else -> null
    }
}
