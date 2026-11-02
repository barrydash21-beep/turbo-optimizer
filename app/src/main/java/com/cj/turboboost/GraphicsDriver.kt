package com.cj.turboboost

import android.os.Build

/** What one driver-list write or restore did, for the boost report and the session end. */
data class DriverOutcome(val ok: Boolean, val detail: String)

/** How far the opt-in is known to work, kept from the last write and restore; see [GraphicsDriver.lastResult]. */
data class DriverResult(val status: CheckStatus, val note: String)

/**
 * The game's package on the system's list of apps that opt in to the updatable graphics
 * driver's production build: the global setting [KEY], a comma-separated package list. Off by
 * default and gated to Android 10 (SDK 29) and later.
 *
 * A session adds its game to the list and puts the list back exactly as it was. Before the
 * first write the raw prior value is saved, on disk before the write runs (a later boost on top
 * keeps that first one). The write is read back, and a list that does not read back as written
 * is reported as unsupported and put straight back. The restore is read back too. A restore
 * that does not read back leaves the value held, so the next session end or app start retries.
 *
 * Nothing here says the driver is used or that the game runs any differently: that is not
 * measured, and the UI says so. Whatever the system ignores is reported, never worked around.
 */
object GraphicsDriver {

    const val KEY = "updatable_driver_production_opt_in_apps"
    const val MIN_SDK = Build.VERSION_CODES.Q

    /** `settings get` prints this for a key that is not set. */
    private const val UNSET = "null"

    /** In the game-resolution store, beside [ProfileGameMode]'s keys; no prefix shared with them. */
    private const val KEY_HELD = "driver_held"
    private const val KEY_PRIOR = "driver_prior"
    private const val KEY_WRITTEN = "driver_written"
    private const val KEY_PACKAGE = "driver_package"
    private const val KEY_WRITE_OK = "driver_write_ok"
    private const val KEY_RESULT = "driver_result"

    fun held(store: KeyValueStore): Boolean = store.getBoolean(KEY_HELD, false)

    /** What the last write and restore showed, or null before the opt-in has ever run. */
    fun lastResult(store: KeyValueStore): DriverResult? {
        val raw = store.getString(KEY_RESULT) ?: return null
        val status = raw.substringBefore('|').let { name -> CheckStatus.entries.firstOrNull { it.name == name } } ?: return null
        return DriverResult(status, raw.substringAfter('|', ""))
    }

    private fun note(store: KeyValueStore, status: CheckStatus, note: String) =
        store.putStringNow(KEY_RESULT, "${status.name}|$note")

    /** The raw setting: its text, `null` when unset, or Kotlin null when it could not be read. */
    private fun read(shell: ShellExecutor): String? = shell.capture("settings get global $KEY")?.trim()

    /** The entries of a raw value; an unset or blank value has none. Order kept, as the system keeps it. */
    internal fun entries(raw: String): List<String> =
        if (raw == UNSET || raw.isBlank()) emptyList() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * [raw] with [pkg] added: every existing entry kept, in its place, and [pkg] once at the
     * end. Null when [raw] holds an entry that is not a package name, since it could not be
     * written back verbatim and nothing is ever dropped.
     */
    internal fun merged(raw: String, pkg: String): List<String>? {
        val existing = entries(raw)
        if (existing.any { !GameRegistry.isValidPackageName(it) }) return null
        return (existing + pkg).distinct()
    }

    private fun write(shell: ShellExecutor, value: String): Boolean =
        shell.run("settings put global $KEY '$value'").ok

    private fun delete(shell: ShellExecutor): Boolean =
        shell.run("settings delete global $KEY").ok

    /**
     * Adds [pkg] to the list. Nothing runs below SDK [MIN_SDK], when the value cannot be read
     * (nothing could be put back), when it holds an entry that cannot be kept verbatim, or when
     * [pkg] is already on it. Not ok when the write does not read back; the value is then
     * already put back.
     */
    fun apply(shell: ShellExecutor, store: KeyValueStore, pkg: String, sdk: Int = Build.VERSION.SDK_INT): DriverOutcome {
        if (sdk < MIN_SDK) return fail(store, "needs Android 10 (SDK $MIN_SDK) or later, this is SDK $sdk")
        if (!GameRegistry.isValidPackageName(pkg)) return fail(store, "not a package name")
        val raw = read(shell) ?: return fail(store, "the list is unreadable, left as it is")
        // A boost on top of one not yet put back keeps the first prior value, not the list it left.
        val prior = if (held(store)) store.getString(KEY_PRIOR) ?: raw else raw
        val list = merged(raw, pkg) ?: return fail(store, "the list holds an entry that is not a package name, left as it is")
        val target = list.joinToString(",")
        if (raw == target) {
            note(store, CheckStatus.UNVERIFIED, "$pkg was already on the list; nothing written")
            return DriverOutcome(true, "$pkg already on the list")
        }

        // On disk before the write: a process killed right after it must still find them.
        store.putStringNow(KEY_PRIOR, prior)
        store.putStringNow(KEY_WRITTEN, target)
        store.putStringNow(KEY_PACKAGE, pkg)
        store.putBooleanNow(KEY_WRITE_OK, false)
        store.putBooleanNow(KEY_HELD, true)

        val ran = write(shell, target)
        val back = read(shell)
        if (ran && back == target) {
            store.putBooleanNow(KEY_WRITE_OK, true)
            note(store, CheckStatus.UNVERIFIED, "written and read back; restore pending")
            return DriverOutcome(true, "$pkg added to the list")
        }

        val reason = if (ran) "wrote \"$target\", reads \"${back ?: "unreadable"}\"" else "the write was refused, reads \"${back ?: "unreadable"}\""
        val undone = restore(shell, store)
        return fail(store, if (undone.ok) "$reason; put back" else "$reason; ${undone.detail}")
    }

    /**
     * Puts the list back exactly as it was: the saved value written back, or the key deleted
     * when it was unset. Not ok while it does not read back, so the next session end or app
     * start retries.
     */
    fun restore(shell: ShellExecutor, store: KeyValueStore): DriverOutcome {
        if (!held(store)) return DriverOutcome(true, "nothing held")
        val prior = store.getString(KEY_PRIOR)
        if (prior == null) {
            // Held with nothing saved cannot be put back and must not be guessed at.
            forget(store)
            note(store, CheckStatus.UNSUPPORTED, "the saved value was lost, so the list was left as it is")
            return DriverOutcome(true, "nothing saved to put back")
        }
        if (!shell.isAvailable()) return DriverOutcome(false, "Shizuku not connected")

        if (prior == UNSET) delete(shell) else write(shell, prior)
        val back = read(shell)
        if (back == prior) {
            val wroteOk = store.getBoolean(KEY_WRITE_OK, false)
            forget(store)
            if (wroteOk) {
                note(store, CheckStatus.SUPPORTED, "written, read back and put back, each matching")
            } else {
                note(store, CheckStatus.UNSUPPORTED, "the write did not read back; put back")
            }
            return DriverOutcome(true, if (prior == UNSET) "key deleted" else "list put back")
        }
        note(store, CheckStatus.UNSUPPORTED, "restore wanted \"$prior\", reads \"${back ?: "unreadable"}\"; retried at the next session end or app start")
        return DriverOutcome(false, "wanted \"$prior\", reads \"${back ?: "unreadable"}\"")
    }

    private fun fail(store: KeyValueStore, why: String): DriverOutcome {
        note(store, CheckStatus.UNSUPPORTED, why)
        return DriverOutcome(false, "Unsupported: $why")
    }

    private fun forget(store: KeyValueStore) {
        store.putStringNow(KEY_PRIOR, null)
        store.putStringNow(KEY_WRITTEN, null)
        store.putStringNow(KEY_PACKAGE, null)
        store.putBooleanNow(KEY_WRITE_OK, false)
        store.putBooleanNow(KEY_HELD, false)
    }
}
