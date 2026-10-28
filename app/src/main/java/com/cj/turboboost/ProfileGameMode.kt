package com.cj.turboboost

/** What one profile game-mode switch or restore did, for the boost report and the session end. */
data class GameModeOutcome(val ok: Boolean, val detail: String)

/**
 * The profile's `cmd game mode` switch (battery, standard or performance), saved and put back
 * like [GameResolution] does for its presets. Before this, the switch was never undone: a game
 * held in custom mode by another app's config left it when a BALANCED boost set standard, and
 * stayed there.
 *
 * Before the first switch the game's mode is saved, on disk before the switch runs. A later
 * boost on top keeps that first one. [restore] puts it back only while the game still holds the
 * mode this app set; if something else changed it since (another app, or a phone restart, which
 * resets every game to standard), the game is left as it is and the saved mode is dropped.
 */
object ProfileGameMode {

    /** In the game-resolution store, beside [GameResolution]'s keys; no prefix shared with them. */
    private const val KEY_SAVED = "profile_mode_saved_"
    private const val KEY_SET = "profile_mode_set_"
    private const val KEY_PACKAGES = "profile_mode_packages"

    /** The game's mode and the modes it offers, or null when unreadable. Plain `capture`: no redirection in the command. */
    private fun read(shell: ShellExecutor, pkg: String): Pair<String, List<String>>? =
        GameResolution.parseModes(shell.capture("cmd game list-modes $pkg"))

    fun held(store: KeyValueStore): Boolean = packages(store).isNotEmpty()

    private fun packages(store: KeyValueStore): List<String> =
        store.getString(KEY_PACKAGES).orEmpty().split(',').filter { GameRegistry.isValidPackageName(it) }

    private fun setPackages(store: KeyValueStore, packages: List<String>) =
        store.putStringNow(KEY_PACKAGES, packages.distinct().joinToString(",").ifEmpty { null })

    /**
     * Switches [pkg] to [mode], saving its own mode first. Nothing runs when the mode cannot be
     * read (nothing could be put back), when the game does not offer [mode], or when it is
     * already in it.
     */
    fun apply(shell: ShellExecutor, store: KeyValueStore, pkg: String, mode: String): GameModeOutcome {
        if (!GameRegistry.isValidPackageName(pkg)) return GameModeOutcome(false, "not a package name")
        val (current, offered) = read(shell, pkg) ?: return GameModeOutcome(false, "mode unreadable, left as it is")
        if (mode !in offered) return GameModeOutcome(false, "$mode not offered, stays $current")
        if (current == mode) return GameModeOutcome(true, "already $mode")

        val fresh = store.getString(KEY_SAVED + pkg) == null
        // On disk before the switch: a process killed right after it must still find them.
        if (fresh) store.putStringNow(KEY_SAVED + pkg, current)
        store.putStringNow(KEY_SET + pkg, mode)
        setPackages(store, packages(store) + pkg)

        val ran = shell.run("cmd game mode $mode $pkg").ok
        val back = read(shell, pkg)?.first
        if (ran && back == mode) return GameModeOutcome(true, "$current to $mode")
        // Nothing changed: drop what this call saved, so the next try starts clean.
        if (fresh && back == current) forget(store, pkg)
        return GameModeOutcome(false, "wanted $mode, reads ${back ?: "unreadable"}")
    }

    /**
     * Puts every saved mode back where the game still holds the one this app set. A game left
     * in another mode is not touched and its saved mode is dropped. Not ok while a mode cannot
     * be read or a switch back does not read back, so the next session end or app start retries.
     */
    fun restore(shell: ShellExecutor, store: KeyValueStore): GameModeOutcome {
        val held = packages(store)
        if (held.isEmpty()) return GameModeOutcome(true, "nothing held")
        if (!shell.isAvailable()) return GameModeOutcome(false, "Shizuku not connected")
        val notes = mutableListOf<String>()
        var ok = true
        for (pkg in held) {
            val saved = store.getString(KEY_SAVED + pkg)
            val set = store.getString(KEY_SET + pkg)
            val now = read(shell, pkg)?.first
            when {
                saved == null || set == null -> forget(store, pkg)
                now == null -> {
                    ok = false
                    notes += "$pkg mode unreadable"
                }
                now == saved -> {
                    forget(store, pkg)
                    notes += "$pkg already $saved"
                }
                now != set -> {
                    // Changed since by someone else, or reset by a restart: theirs, not ours to undo.
                    forget(store, pkg)
                    notes += "$pkg changed outside to $now, left as it is"
                }
                else -> {
                    shell.run("cmd game mode $saved $pkg")
                    val back = read(shell, pkg)?.first
                    if (back == saved) {
                        forget(store, pkg)
                        notes += "$pkg back to $saved"
                    } else {
                        ok = false
                        notes += "$pkg wanted $saved, reads ${back ?: "unreadable"}"
                    }
                }
            }
        }
        return GameModeOutcome(ok, notes.joinToString("; "))
    }

    private fun forget(store: KeyValueStore, pkg: String) {
        store.putStringNow(KEY_SAVED + pkg, null)
        store.putStringNow(KEY_SET + pkg, null)
        setPackages(store, packages(store) - pkg)
    }
}
