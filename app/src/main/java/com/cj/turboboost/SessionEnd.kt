package com.cj.turboboost

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/** How a session ended; [code] is what the watcher sends and the session record keeps. */
enum class EndReason(val code: String) {
    /** The game's process was gone for [SessionWatcher.MISSES_TO_EXIT] checks in a row. */
    EXITED("exited"),

    /** The game's process changed mid-session: not one continuous play of the game. */
    RESTARTED("restarted"),

    /** The game never appeared within the launch grace period. */
    NEVER_STARTED("never_started"),

    /** RESTORE NORMAL SETTINGS. */
    MANUAL("manual"),

    /** The next app start found the session unfinished. */
    APP_START("app_start"),

    /** A new boost started while this session still ran. */
    REPLACED("replaced"),

    /** Shizuku stayed unreachable, so the in-process monitor gave up. */
    SHIZUKU_LOST("shizuku_lost");

    companion object {
        fun fromCode(code: String?): EndReason? = entries.firstOrNull { it.code == code }
    }
}

/** What ending a session needs, so the same step runs from the provider, the monitor and app start. */
class SessionContext(
    val shell: ShellExecutor,
    val session: KeyValueStore,
    val tuner: KeyValueStore,
    val restrictions: KeyValueStore,
    val sessions: SessionStore,
    val elapsedNow: () -> Long,
    val wallNow: () -> Long
) {
    companion object {
        fun of(context: Context): SessionContext = SessionContext(
            shell = RamCleaner.shell,
            session = Stores.session(context),
            tuner = Stores.tuner(context),
            restrictions = Stores.restrictions(context),
            sessions = SessionStore(Stores.sessions(context)),
            elapsedNow = SystemClock::elapsedRealtime,
            wallNow = System::currentTimeMillis
        )
    }
}

/** What one try at ending a session did. */
data class SessionEndOutcome(
    /** Every restore read back and the session is now ended, or there was nothing to end. */
    val ok: Boolean,
    /** The record saved by this try; null when there was none or an earlier try saved it. */
    val record: SessionRecord?,
    val tweaks: TuneReport,
    val restrictions: RestrictionReport
)

/**
 * One session's lifecycle, kept on disk so whichever path ends it ends it the same way: the
 * watcher's provider call while this app is frozen, [GameMonitorService] when it is not, RESTORE,
 * or the next app start after a crash.
 *
 * The end is two-state. [STATE_ENDING] is written before anything is restored; [STATE_ENDED]
 * only after every restore reads back. A session left in "ending" (the app died, or a restore
 * did not stick) is finished by the next call to [finish] from any path, which never saves its
 * record twice.
 */
object SessionEnd {

    private const val TAG = "TurboBooster"

    const val STATE_ACTIVE = "active"
    const val STATE_ENDING = "ending"
    const val STATE_ENDED = "ended"

    private const val KEY_STATE = "state"
    private const val KEY_TOKEN = "token"
    private const val KEY_PACKAGE = "package"
    private const val KEY_RATE = "rate"
    private const val KEY_RESOLUTION = "render_resolution"
    private const val KEY_FPS_CAP = "fps_cap"
    private const val KEY_WATCHER = "watcher"
    private const val KEY_ALIVE_EARLY = "alive_early"
    private const val KEY_RECORD_SAVED = "record_saved"
    private const val KEY_REASON = "end_reason"

    private val SESSION_KEYS = listOf(
        KEY_TOKEN, KEY_PACKAGE, KEY_RATE, KEY_RESOLUTION, KEY_FPS_CAP, KEY_WATCHER, KEY_ALIVE_EARLY, KEY_REASON
    )

    /** The only uids that may end a session: the shell Shizuku runs as, and root. */
    private const val SHELL_UID = 2000
    private const val ROOT_UID = 0

    /** Written for an empty list, as in [SessionRecord]. */
    private const val NONE = "-"

    /**
     * The session the in-process monitor is sampling, when no watcher is. Process memory only:
     * the provider runs in this process, and an app that died lost these samples anyway.
     */
    @Volatile
    var live: SessionAccumulator? = null

    /** A fresh session token: 128 random bits as 32 hex digits. */
    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun state(store: KeyValueStore): String? = store.getString(KEY_STATE)

    /** True once a session has ended; the monitor then stops. */
    fun isEnded(store: KeyValueStore): Boolean = state(store) == STATE_ENDED

    fun watcherHeld(store: KeyValueStore): Boolean = store.getBoolean(KEY_WATCHER, false)

    fun noteWatcher(store: KeyValueStore, started: Boolean) = store.putBooleanNow(KEY_WATCHER, started)

    /** The restricted apps alive [BackgroundRestrictor.ALIVE_CHECK_DELAY_MS] in, null for unreadable. */
    fun noteAliveEarly(store: KeyValueStore, alive: List<String>?) =
        store.putStringNow(KEY_ALIVE_EARLY, alive?.joinToString(",")?.ifEmpty { NONE })

    /**
     * Starts a session for [pkg] under [token]. A session still open is closed first through
     * [replace]: its record is kept, nothing it changed is restored, since the new boost holds
     * the same changes on.
     */
    @Synchronized
    fun begin(ctx: SessionContext, pkg: String, rate: Float, renderResolution: String?, fpsCap: Int?, token: String) {
        val store = ctx.session
        if (state(store) == STATE_ACTIVE || state(store) == STATE_ENDING) replace(ctx)
        SESSION_KEYS.forEach { store.putStringNow(it, null) }
        store.putBooleanNow(KEY_WATCHER, false)
        store.putBooleanNow(KEY_RECORD_SAVED, false)
        store.putStringNow(KEY_TOKEN, token)
        store.putStringNow(KEY_PACKAGE, pkg)
        store.putStringNow(KEY_RATE, RefreshRate.format(rate))
        store.putStringNow(KEY_RESOLUTION, renderResolution)
        store.putStringNow(KEY_FPS_CAP, fpsCap?.toString())
        // Last: the state is what marks a session as present.
        store.putStringNow(KEY_STATE, STATE_ACTIVE)
    }

    /** Closes the open session's record without restoring anything, and stops its watcher. */
    @Synchronized
    fun replace(ctx: SessionContext) {
        val store = ctx.session
        if (state(store) != STATE_ACTIVE && state(store) != STATE_ENDING) return
        if (!saveRecordOnce(ctx, EndReason.REPLACED)) return
        SessionWatcher.stop(ctx.shell)
        SessionWatcher.clearData(ctx.shell)
        SESSION_KEYS.forEach { store.putStringNow(it, null) }
        store.putStringNow(KEY_STATE, STATE_ENDED)
    }

    /**
     * Whether a provider call may end the session: from the shell uid or root, while a session
     * is open, with its token. Other Shizuku apps run as the shell uid too, so the uid alone
     * is not enough.
     */
    fun authorize(callerUid: Int, token: String?, store: KeyValueStore): Boolean {
        if (callerUid != SHELL_UID && callerUid != ROOT_UID) return false
        val state = state(store)
        if (state != STATE_ACTIVE && state != STATE_ENDING) return false
        val held = store.getString(KEY_TOKEN) ?: return false
        if (token == null) return false
        return MessageDigest.isEqual(held.toByteArray(), token.toByteArray())
    }

    /**
     * Whether the next app start must end a session: one left ending; one still open with
     * neither the monitor nor the watcher running (the app or the phone went down mid-session);
     * or, with no session on record, tweaks or a restriction still held, as before sessions
     * were kept on disk. An open session whose watcher still runs is left to the watcher.
     */
    fun needsFinishAtStart(
        store: KeyValueStore,
        tweaksActive: Boolean,
        restrictionActive: Boolean,
        monitorRunning: Boolean,
        watcherAlive: () -> Boolean
    ): Boolean = when (state(store)) {
        STATE_ENDING -> true
        STATE_ACTIVE -> !monitorRunning && !watcherAlive()
        else -> (tweaksActive || restrictionActive) && !monitorRunning
    }

    /**
     * Ends the session: marks it ending, saves its record once, puts back the tweaks and the
     * background restriction, and marks it ended only when every one of them read back. With no
     * session open it still puts back whatever is held, as the monitor always did.
     */
    @Synchronized
    fun finish(ctx: SessionContext, reason: EndReason): SessionEndOutcome {
        val store = ctx.session
        val open = state(store) == STATE_ACTIVE || state(store) == STATE_ENDING
        if (state(store) == STATE_ACTIVE) {
            store.putStringNow(KEY_REASON, reason.code)
            store.putStringNow(KEY_STATE, STATE_ENDING)
        }
        val record = if (open) saveRecord(ctx, reason) else null

        val tweaks = PerformanceTuner.revertGamingTweaks(ctx.tuner)
        val restricted = BackgroundRestrictor.restore(ctx.shell, ctx.restrictions)
        log { Log.i(TAG, "Session end (${reason.code}): tweaks ${tweaks.applied} failed ${tweaks.failed}; background apps " + restricted.packages.joinToString("; ") { "${it.packageName} ${it.detail()}" }) }
        // A reboot soon after can bring the restricted bucket back: checked at the next app start.
        if (restricted.ok && restricted.packages.isNotEmpty()) {
            BucketRecheck.remember(ctx.shell, ctx.restrictions, restricted, GameResolution.bootId(ctx.shell))
        }

        val restored = tweaks.ok && restricted.ok
        if (restored && open && store.getBoolean(KEY_RECORD_SAVED, false)) {
            SessionWatcher.stop(ctx.shell)
            SessionWatcher.clearData(ctx.shell)
            SESSION_KEYS.forEach { store.putStringNow(it, null) }
            store.putStringNow(KEY_STATE, STATE_ENDED)
        }
        return SessionEndOutcome(restored && (!open || state(store) == STATE_ENDED), record, tweaks, restricted)
    }

    /** [saveRecordOnce], returning the record saved now. */
    private fun saveRecord(ctx: SessionContext, reason: EndReason): SessionRecord? {
        var saved: SessionRecord? = null
        saveRecordOnce(ctx, reason) { saved = it }
        return saved
    }

    /**
     * Saves the session's record unless an earlier try did. False when it must wait: the
     * watcher's data cannot be read without Shizuku, and is not given up for lost.
     */
    private fun saveRecordOnce(ctx: SessionContext, reason: EndReason, onSaved: (SessionRecord) -> Unit = {}): Boolean {
        val store = ctx.session
        if (store.getBoolean(KEY_RECORD_SAVED, false)) return true
        val watcher = watcherHeld(store)
        if (watcher && !ctx.shell.isAvailable()) return false
        val data = if (watcher) SessionWatcher.collect(ctx.shell) else null
        buildRecord(ctx, data, store.getString(KEY_REASON) ?: reason.code)?.let { record ->
            ctx.sessions.save(record)
            log { Log.i(TAG, "Session saved: ${record.encode()}") }
            onSaved(record)
        }
        store.putBooleanNow(KEY_RECORD_SAVED, true)
        live = null
        return true
    }

    /**
     * The record, from the watcher's data when it saw the game, else from in-process samples.
     * Null when the game was never seen running, as before.
     */
    internal fun buildRecord(ctx: SessionContext, data: WatcherData?, reasonCode: String): SessionRecord? {
        val store = ctx.session
        val inProcess = live
        val pkg = store.getString(KEY_PACKAGE) ?: inProcess?.packageName ?: return null
        val rate = store.getString(KEY_RATE)?.toFloatOrNull() ?: inProcess?.refreshRate ?: return null
        val now = ctx.elapsedNow()
        val watched = data?.takeIf { it.seenAtMs != null }
        val session = if (watched != null) {
            val seenAt = watched.seenAtMs!!
            SessionAccumulator(pkg, rate, ctx.wallNow() - (now - seenAt), seenAt).also(watched::foldInto)
        } else {
            inProcess?.takeIf { it.packageName == pkg } ?: return null
        }
        store.getString(KEY_RESOLUTION)?.let { session.renderResolution = it }
        store.getString(KEY_FPS_CAP)?.toIntOrNull()?.let { session.fpsCap = it }
        if (store.getString(KEY_ALIVE_EARLY) != null) session.restrictedAliveEarly = decodeList(store.getString(KEY_ALIVE_EARLY))
        session.restrictedAliveAtEnd = BackgroundRestrictor.alive(ctx.shell, ctx.restrictions)
        val endAt = watched?.endedAtMs ?: now
        return session.finish(endAt).copy(endReason = watched?.endReason ?: reasonCode)
    }

    private fun decodeList(field: String?): List<String>? = when {
        field.isNullOrBlank() -> null
        field == NONE -> emptyList()
        else -> field.split(',').filter { it.isNotBlank() }
    }

    /** Logging must never fail a session end (android.util.Log is absent in JVM tests). */
    private inline fun log(block: () -> Unit) {
        runCatching { block() }
    }
}
