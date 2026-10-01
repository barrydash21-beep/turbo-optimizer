package com.cj.turboboost

import android.content.Context

/**
 * How aggressive a boost should be.
 *
 * Each profile is a declarative description of which optimizations run — [RamCleaner.applyProfile]
 * is the single place that reads it, so the matrix lives here rather than being scattered
 * across call sites.
 *
 * The booster never touches thermal throttling on any profile: the phone's own thermal
 * governor always stays in charge.
 */
enum class BoostProfile(
    val label: String,
    val blurb: String,
    /** Argument to `cmd game mode` — battery | standard | performance. */
    val gameMode: String,
    val dropCaches: Boolean,
    val suppressInterruptions: Boolean,
    val trimAnimations: Boolean
) {
    POWER_SAVING(
        label = "SAVER",
        blurb = "Cools and conserves. Runs the game in battery mode.",
        gameMode = "battery",
        dropCaches = false,
        suppressInterruptions = false,
        trimAnimations = false
    ),
    BALANCED(
        label = "BALANCED",
        blurb = "Everyday gaming. Frees cached RAM and turns off system animations. " +
            "Leaves your notifications and sync alone.",
        gameMode = "standard",
        dropCaches = true,
        // Silencing the phone is far too large a side effect for the default profile to
        // apply without saying so; TURBO keeps it and names it in its blurb (H5).
        suppressInterruptions = false,
        trimAnimations = true
    ),
    PERFORMANCE(
        label = "TURBO",
        blurb = "Max performance game mode. Silences notifications (Do Not Disturb) and " +
            "pauses auto-sync, frees cached RAM and turns off system animations.",
        gameMode = "performance",
        dropCaches = true,
        suppressInterruptions = true,
        trimAnimations = true
    );

    companion object {
        /** The profile a stored string names, or [BALANCED] for anything unrecognised. */
        fun fromStored(raw: String?): BoostProfile =
            runCatching { valueOf(raw ?: "") }.getOrDefault(BALANCED)
    }
}

/**
 * Persists the user's boost options across launches.
 *
 * Backed by a [KeyValueStore] rather than `SharedPreferences` directly, so the round-trip
 * and the corrupt-value fallback are unit-testable without a device (L15).
 */
class BoosterPrefs(private val store: KeyValueStore) {

    constructor(context: Context) : this(Stores.options(context))

    var profile: BoostProfile
        get() = BoostProfile.fromStored(store.getString(KEY_PROFILE))
        set(value) = store.putString(KEY_PROFILE, value.name)

    /** Whether the boost should also bring up the traffic-blocking VPN. */
    var vpnEnabled: Boolean
        get() = store.getBoolean(KEY_VPN, true) // default on: preserves prior behaviour
        set(value) = store.putBoolean(KEY_VPN, value)

    /** Packages the user has added as games, in the order they were added. */
    var games: List<String>
        // Comma-separated: a package name can never contain one.
        get() = store.getString(KEY_GAMES)
            ?.split(',')?.map { it.trim() }?.filter { GameRegistry.isValidPackageName(it) }?.distinct()
            .orEmpty()
        set(value) = store.putString(
            KEY_GAMES,
            value.filter { GameRegistry.isValidPackageName(it) }.distinct().joinToString(",")
        )

    /** Package of the game the user picked, or null before the first pick. */
    var selectedGame: String?
        get() = store.getString(KEY_GAME)
        set(value) = store.putString(KEY_GAME, value)

    /** The refresh rate the user picked for [packageName], or null if never picked. */
    fun rateFor(packageName: String): Float? =
        store.getString(PREFIX_RATE + packageName)?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }

    fun setRate(packageName: String, rate: Float) =
        store.putString(PREFIX_RATE + packageName, RefreshRate.format(rate))

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_VPN = "vpn_enabled"
        const val KEY_GAME = "selected_game"
        const val KEY_GAMES = "games"
        const val PREFIX_RATE = "rate_"
    }
}
