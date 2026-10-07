package com.cj.turboboost

import android.content.ContentResolver
import android.content.Context
import androidx.core.content.edit

/**
 * A tiny persistent key/value store. Behind an interface so the snapshot, restore and
 * preferences logic can all be unit-tested without Android (H4, L15).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun clear()
}

/** The real store: one named `SharedPreferences` file. */
class SharedPrefsStore(context: Context, name: String) : KeyValueStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit { putString(key, value) }
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    override fun clear() {
        prefs.edit { clear() }
    }
}

/** The SharedPreferences files this app owns. */
object Stores {
    fun options(context: Context) = SharedPrefsStore(context, "booster_options")
    fun snapshot(context: Context) = SharedPrefsStore(context, "booster_snapshot")

    /** Separate from the snapshot: restoreSystemState clears that whole file on success. */
    fun tuner(context: Context) = SharedPrefsStore(context, "booster_tuner")

    /** Session diagnostics: the last 20 sessions. */
    fun sessions(context: Context) = SharedPrefsStore(context, "booster_sessions")

    /** Per-game downscale: each game's snapshot, the preset applied, and its measurements. */
    fun gameResolution(context: Context) = SharedPrefsStore(context, "booster_game_resolution")

    /** The removed `wm size` lever's records; cleared by the one-time display override check. */
    fun legacyResolution(context: Context) = SharedPrefsStore(context, "booster_resolution")
}

/** Master auto-sync, behind an interface for the same reason as [KeyValueStore]. */
interface SyncSettings {
    fun isMasterSyncEnabled(): Boolean
    fun setMasterSyncEnabled(enabled: Boolean): Boolean
}

object PlatformSyncSettings : SyncSettings {
    override fun isMasterSyncEnabled(): Boolean =
        runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)

    override fun setMasterSyncEnabled(enabled: Boolean): Boolean =
        runCatching { ContentResolver.setMasterSyncAutomatically(enabled) }.isSuccess
}

/**
 * The user's settings as they were before the first boost.
 *
 * Restore used to write hardcoded platform defaults, which destroyed deliberate user
 * configuration — a DND schedule, a custom pointer speed, animations the user had turned
 * off, and most damagingly master auto-sync, where forcing it back on can trigger a
 * device-wide sync on a metered plan (H4).
 *
 * The snapshot is taken **only when none exists**. Two boosts in a row must not overwrite it
 * with the already-boosted values, which would make the original settings unrecoverable.
 */
object SystemSnapshot {

    /** Animation scales the boost touches, with their platform defaults. */
    val ANIMATION_SCALE_DEFAULTS = mapOf(
        "window_animation_scale" to "1",
        "transition_animation_scale" to "1",
        "animator_duration_scale" to "1"
    )

    private const val KEY_PRESENT = "present"
    private const val KEY_ZEN = "zen_mode"
    private const val KEY_POINTER = "pointer_speed"
    private const val KEY_SYNC = "master_sync"
    private const val PREFIX_SCALE = "scale_"

    fun exists(store: KeyValueStore): Boolean = store.getBoolean(KEY_PRESENT, false)

    /**
     * Reads and stores the current values. No-op when a snapshot is already held.
     *
     * @return true if a snapshot was taken by this call.
     */
    fun captureIfAbsent(
        shell: ShellExecutor,
        store: KeyValueStore,
        sync: SyncSettings
    ): Boolean {
        if (exists(store)) return false

        store.putString(KEY_ZEN, normalize(shell.capture("settings get global zen_mode")))
        store.putString(KEY_POINTER, normalize(shell.capture("settings get system pointer_speed")))
        for (key in ANIMATION_SCALE_DEFAULTS.keys) {
            store.putString(PREFIX_SCALE + key, normalize(shell.capture("settings get global $key")))
        }
        store.putBoolean(KEY_SYNC, sync.isMasterSyncEnabled())
        store.putBoolean(KEY_PRESENT, true)
        return true
    }

    /** The user's zen_mode before the boost, or the platform default "0" (off). */
    fun zenMode(store: KeyValueStore): String = store.getString(KEY_ZEN) ?: "0"

    /** The user's pointer_speed before the boost, or the platform default "0". */
    fun pointerSpeed(store: KeyValueStore): String = store.getString(KEY_POINTER) ?: "0"

    /** The user's value for [key], or its platform default. */
    fun animationScale(store: KeyValueStore, key: String): String =
        store.getString(PREFIX_SCALE + key) ?: ANIMATION_SCALE_DEFAULTS[key] ?: "1"

    /** Whether master auto-sync was on before the boost. Defaults to on when unknown. */
    fun masterSync(store: KeyValueStore): Boolean = store.getBoolean(KEY_SYNC, true)

    fun clear(store: KeyValueStore) = store.clear()

    /**
     * Maps a `zen_mode` value to the matching `cmd notification set_dnd` argument, so
     * restore puts the user's own DND mode back rather than always forcing it off.
     */
    fun dndArgumentFor(zenMode: String): String = when (zenMode.trim()) {
        "1" -> "priority"
        "2" -> "none"   // total silence
        "3" -> "alarms"
        else -> "off"
    }

    /**
     * `settings get` prints the literal string "null" for an unset key; treat that, blanks
     * and read failures alike as "no stored value, use the platform default".
     */
    private fun normalize(raw: String?): String? {
        val value = raw?.trim()
        return if (value.isNullOrEmpty() || value == "null") null else value
    }
}
