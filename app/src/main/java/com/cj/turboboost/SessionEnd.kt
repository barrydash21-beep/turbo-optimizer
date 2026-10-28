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
    /** Where [GameStateRef] keeps each game's reference state. */
    val gameResolution: KeyValueStore,
    /** The user's own settings as they were before the boost ([SystemSnapshot]). */
    val snapshot: KeyValueStore,
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
            gameResolution = Stores.gameResolution(context),
            snapshot = Stores.snapshot(context),
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
    val restrictions: RestrictionReport,
    /** The game mode a profile switched, put back ([ProfileGameMode.restore]). */
    val gameMode: GameModeOutcome = GameModeOutcome(true, "nothing held"),
    /** The user's own settings put back; null when no snapshot was held. */
    val system: StepReport? = null
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

    /** [SessionRecord.profile]: the boost profile's name, or [SessionRecord.BASELINE]. */
    private const val KEY_KIND = "kind"

    /** The game's state read once the session's own changes were made, and the start check. */
    private const val KEY_STATE_START = "game_state_start"
    private const val KEY_CHECK_START = "check_start"

    private val SESSION_KEYS = listOf(
        KEY_TOKEN, KEY_PACKAGE, KEY_RATE, KEY_RESOLUTION, KEY_FPS_CAP, KEY_WATCHER, KEY_ALIVE_EARLY, KEY_REASON,
        KEY_KIND, KEY_STATE_START, KEY_CHECK_START
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

    /** The kind of the open session: a [BoostProfile] name, or [SessionRecord.BASELINE]. */
    fun kind(store: KeyValueStore): String? = store.getString(KEY_KIND)

    /**
     * Starts a session for [pkg] under [token]. [kind] is the profile's name or
     * [SessionRecord.BASELINE]; [startCheck] is the game's state checked against its reference
     * before the boost changed anything. A session still open is closed first through
     * [replace]: its record is kept, nothing it changed is restored, since the new boost holds
     * the same changes on.
     */
    @Synchronized
    fun begin(
        ctx: SessionContext,
        pkg: String,
        rate: Float,
        renderResolution: String?,
        fpsCap: Int?,
        token: String,
        kind: String? = null,
        startCheck: Contamination? = null
    ) {
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
        store.putStringNow(KEY_KIND, kind)
        store.putStringNow(KEY_CHECK_START, (startCheck ?: Contamination.UNKNOWN).name)
        // Last: the state is what marks a session as present.
        store.putStringNow(KEY_STATE, STATE_ACTIVE)
    }

    /**
     * Records the game's state the session starts under, after the boost's own changes, and
     * makes it the reference the end is checked against. Null when it could not be read.
     */
    fun observeStart(ctx: SessionContext, pkg: String): String? {
        val state = GameStateRef.read(ctx.shell, pkg)
        ctx.session.putStringNow(KEY_STATE_START, state)
        GameStateRef.save(ctx.gameResolution, pkg, state, GameResolution.bootId(ctx.shell))
        return state
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
     * Ends the session: marks it ending, saves its record once, puts back everything the boost
     * holds ([SessionRestore]: the tweaks, the user's own settings, the background restriction)
     * and the game mode a profile switched, and marks it ended only when every one of them
     * read back. With no session open it still puts back whatever is held, as the monitor
     * always did.
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
        val pkg = store.getString(KEY_PACKAGE)

        val held = SessionRestore.restoreHeld(ctx.shell, ctx.tuner, ctx.snapshot, ctx.restrictions)
        val tweaks = held.tweaks
        val restricted = held.restrictions
        log { Log.i(TAG, "Session end (${reason.code}): tweaks ${tweaks.applied} failed ${tweaks.failed}; settings ${held.system?.let { "${it.applied} failed ${it.failed}" } ?: "none held"}; background apps " + restricted.packages.joinToString("; ") { "${it.packageName} ${it.detail()}" }) }
        // A reboot soon after can bring the restricted bucket back: checked at the next app start.
        if (restricted.ok && restricted.packages.isNotEmpty()) {
            BucketRecheck.remember(ctx.shell, ctx.restrictions, restricted, GameResolution.bootId(ctx.shell))
        }
        // Before the reference is read: the mode it holds afterwards is the game's own again.
        val gameMode = ProfileGameMode.restore(ctx.shell, ctx.gameResolution)
        log { Log.i(TAG, "Session end (${reason.code}): game mode ${gameMode.detail}") }

        // What the game holds once this session's own changes are undone is the next session's reference.
        if (open && pkg != null && ctx.shell.isAvailable()) {
            GameStateRef.save(ctx.gameResolution, pkg, GameStateRef.read(ctx.shell, pkg), GameResolution.bootId(ctx.shell))
        }

        val restored = held.allRestored && gameMode.ok
        if (restored && open && store.getBoolean(KEY_RECORD_SAVED, false)) {
            SessionWatcher.stop(ctx.shell)
            SessionWatcher.clearData(ctx.shell)
            SESSION_KEYS.forEach { store.putStringNow(it, null) }
            store.putStringNow(KEY_STATE, STATE_ENDED)
        }
        return SessionEndOutcome(restored && (!open || state(store) == STATE_ENDED), record, tweaks, restricted, gameMode, held.system)
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
        // Before anything is restored, so it is the state the session ended under.
        val end = store.getString(KEY_PACKAGE)?.takeIf { ctx.shell.isAvailable() }?.let { GameStateRef.check(ctx.shell, ctx.gameResolution, it) }
        buildRecord(ctx, data, store.getString(KEY_REASON) ?: reason.code, end)?.let { record ->
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
     * Null when the game was never seen running, as before. [end] is the game's state read at
     * the end, and its check; null when it could not be read.
     */
    internal fun buildRecord(
        ctx: SessionContext,
        data: WatcherData?,
        reasonCode: String,
        end: Pair<String?, Contamination>? = null
    ): SessionRecord? {
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
        val record = session.finish(endAt)
        val continuity = if (watched != null) {
            continuity(watched.frameReads, record.durationMs, SessionWatcher.FRAME_READ_MS.toLong())
        } else {
            continuity(record.samples, record.durationMs, GameMonitorService.SAMPLE_MS)
        }
        val batteryEnd = if (watched != null) {
            watched.batteryEnd
        } else {
            ctx.shell.takeIf { it.isAvailable() }?.let { Diagnostics.parseBatteryTemp(it.capture("dumpsys battery")) }
        }
        return record.copy(
            batteryTempStart = watched?.batteryStart ?: record.batteryTempStart,
            batteryTempEnd = batteryEnd,
            endReason = watched?.endReason ?: reasonCode,
            profile = store.getString(KEY_KIND),
            gameStateStart = store.getString(KEY_STATE_START),
            gameStateEnd = end?.first,
            contamination = Contamination.worst(Contamination.fromName(store.getString(KEY_CHECK_START)), end?.second).name,
            continuity = continuity
        )
    }

    /**
     * Reads taken over reads due at [intervalMs] across [durationMs], at most 1. Null when no
     * full interval passed, or nothing was read on a cadence (an interval of 0).
     */
    internal fun continuity(reads: Int, durationMs: Long, intervalMs: Long): Float? {
        if (intervalMs <= 0L) return null
        val due = durationMs / intervalMs
        if (due < 1L) return null
        return (reads.toFloat() / due).coerceAtMost(1f)
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
