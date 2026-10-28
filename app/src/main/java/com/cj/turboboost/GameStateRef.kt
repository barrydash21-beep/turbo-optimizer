package com.cj.turboboost

/** Whether a game's GameManager state is what this app last saw or set. */
enum class Contamination {
    /** Exactly as last seen, or only its mode reset by a phone restart. */
    CLEAN,

    /** Something other than this app changed the mode or a config since. */
    CONTAMINATED,

    /** Nothing to compare with: no earlier reference, or the state could not be read. */
    UNKNOWN;

    companion object {
        fun fromName(raw: String?): Contamination? = entries.firstOrNull { it.name == raw }

        /** The worse of the checks: any contaminated, else any unknown, else clean. */
        fun worst(vararg checks: Contamination?): Contamination = when {
            checks.any { it == CONTAMINATED } -> CONTAMINATED
            checks.any { it == null || it == UNKNOWN } -> UNKNOWN
            else -> CLEAN
        }
    }
}

/**
 * The game's GameManager state as this app last saw or set it, kept per game, so a session can
 * tell whether another app (a game tuner holding Shizuku, say) changed the game's mode or
 * configs since. Read at session start and end; updated at each session start, after each
 * session end, and after every change this app makes ([GameResolution] apply, restore and
 * reapply), so only a change from outside ever differs.
 *
 * A phone restart puts every game back in standard mode and keeps the configs (seen on a Pova 7),
 * so a mode that differs across a boot change is expected. A config that differs is not, even
 * across a restart.
 */
object GameStateRef {

    /** In the game-resolution store, beside [GameResolution]'s own keys; no prefix shared with them. */
    private const val KEY_REF = "state_ref_"

    /**
     * `standard`, or `custom;4=0.6/60;2=0.8/90`: the mode, then each mode's config as
     * scaling/fps, in mode order. Never holds `|`, `,` or a space, so it fits a session record.
     */
    fun text(mode: String, configs: Map<Int, InterventionConfig>): String =
        mode + configs.toSortedMap().entries.joinToString("") { (id, c) -> ";$id=${c.scaling.orEmpty()}/${c.fps.orEmpty()}" }

    /** Whether [text] holds any config at all, in any mode. */
    fun hasConfigs(text: String): Boolean = text.contains(';')

    private fun mode(text: String): String = text.substringBefore(';')
    private fun configs(text: String): String = text.substringAfter(';', "")

    /**
     * The game's state now, or null when it cannot be read. Read directly rather than through
     * [GameResolution.read], which also needs the game to offer custom mode.
     */
    fun read(shell: ShellExecutor, pkg: String): String? {
        if (!GameRegistry.isValidPackageName(pkg)) return null
        val modes = GameResolution.parseModes(GameResolution.runCaptured(shell, "cmd game list-modes $pkg")?.second) ?: return null
        val configs = GameResolution.parseConfigs(GameResolution.runCaptured(shell, "cmd game list-configs $pkg")?.second) ?: return null
        return text(modes.first, configs).takeIf { safe(it) }
    }

    /** Only what [text] can produce: a word, then `;<id>=<number or blank>/<number or blank>`. */
    private fun safe(text: String): Boolean = Regex("""^\w+(;\d+=[-\d.]*/[-\d]*)*$""").matches(text)

    fun save(store: KeyValueStore, pkg: String, state: String?, bootId: String?) {
        if (state == null || !safe(state)) return
        store.putString(KEY_REF + pkg, "${bootId.orEmpty()} $state")
    }

    fun saveState(store: KeyValueStore, pkg: String, state: GameModeState?, bootId: String?) =
        save(store, pkg, state?.let { text(it.mode, it.configs) }, bootId)

    /** The reference, as (boot id, state); null when there is none. */
    fun reference(store: KeyValueStore, pkg: String): Pair<String?, String>? {
        val raw = store.getString(KEY_REF + pkg) ?: return null
        val boot = raw.substringBefore(' ').takeIf { it.isNotEmpty() }
        val state = raw.substringAfter(' ', "").takeIf { safe(it) } ?: return null
        return boot to state
    }

    /** [now] against the reference: see [Contamination]. */
    fun compare(reference: Pair<String?, String>?, now: String?, currentBoot: String?): Contamination {
        if (now == null || reference == null) return Contamination.UNKNOWN
        val (boot, was) = reference
        if (was == now) return Contamination.CLEAN
        if (configs(was) != configs(now)) return Contamination.CONTAMINATED
        // Only the mode differs: expected across a restart, and only then.
        val restarted = boot != null && currentBoot != null && boot != currentBoot
        return if (restarted && mode(was) != mode(now)) Contamination.CLEAN else Contamination.CONTAMINATED
    }

    /** Reads [pkg]'s state and checks it against its reference: (state, check). */
    fun check(shell: ShellExecutor, store: KeyValueStore, pkg: String): Pair<String?, Contamination> {
        val now = read(shell, pkg)
        return now to compare(reference(store, pkg), now, GameResolution.bootId(shell))
    }

    /** For a summary row: `custom 4=0.6/60, 2=0.8/90`, or the mode alone. */
    fun display(text: String?): String = when {
        text == null -> "unreadable"
        !hasConfigs(text) -> mode(text)
        else -> mode(text) + " " + configs(text).split(';').joinToString(", ")
    }
}
