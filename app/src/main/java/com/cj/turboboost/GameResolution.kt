package com.cj.turboboost

import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A preset on a game's card. [scale] is exactly what `cmd game set --downscale` receives; the
 * shell accepts 0.3 to 0.9 in steps of 0.05. Selections are stored by [scale], not by name.
 */
enum class ResolutionLevel(val label: String, val scale: String) {
    MEDIUM("MEDIUM", "0.65"),
    LOW("LOW", "0.6"),
    LOWER("LOWER", "0.55"),
    LOWEST("LOWEST", "0.5");

    /** The factor as the card shows it: `0.55x`. */
    val factor: String get() = "${scale}x"

    companion object {
        fun forScale(scale: String?): ResolutionLevel? = entries.firstOrNull { GameResolution.sameScale(it.scale, scale) }
    }
}

/**
 * A frame-rate cap on a game's card. [fps] is exactly what `cmd game set --fps` receives; the
 * shell takes any integer, so only these fixed values are ever sent. Selections are stored by
 * [fps], not by name. The cap can only lower the game's frame rate, and a game may ignore it.
 */
enum class FpsCap(val fps: Int) {
    FPS_45(45),
    FPS_40(40),
    FPS_30(30);

    val label: String get() = fps.toString()

    companion object {
        fun forFps(fps: String?): FpsCap? = fps?.trim()?.toIntOrNull()?.let { v -> entries.firstOrNull { it.fps == v } }
    }
}

/** A graphics buffer size as SurfaceFlinger reports it (`w/h:810x1845`). */
data class BufferSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    override fun toString() = "${width}x$height"

    companion object {
        fun parse(raw: String?): BufferSize? {
            val m = Regex("""^\s*(\d+)x(\d+)\s*$""").find(raw ?: return null) ?: return null
            val w = m.groupValues[1].toIntOrNull() ?: return null
            val h = m.groupValues[2].toIntOrNull() ?: return null
            return if (w > 0 && h > 0) BufferSize(w, h) else null
        }
    }
}

/** One mode's intervention from `cmd game list-configs`. Blank fields are null. */
data class InterventionConfig(val scaling: String?, val fps: String?)

/** A game's GameManager state: its current mode, the modes it offers, and per-mode configs. */
data class GameModeState(
    val mode: String,
    val available: List<String>,
    val configs: Map<Int, InterventionConfig>
) {
    val custom: InterventionConfig? get() = configs[GameResolution.CUSTOM_MODE_ID]
}

/** What the game had before this app's first change: restored as-is, never re-taken from our own values. */
data class GameModeSnapshot(
    val mode: String,
    val custom: InterventionConfig?,
    /** Whether any mode had an intervention config at all; with none, `cmd game reset` is exact. */
    val hadInterventions: Boolean
)

/** What one apply or restore did. */
data class GameResolutionResult(
    val ok: Boolean,
    val detail: String,
    /** Restore refused: the game's mode no longer holds what this app set. */
    val changedOutside: Boolean = false,
    /** The game or this Android version cannot take a downscale at all. */
    val unavailable: Boolean = false
)

/** Everything a game card shows about its render resolution. */
data class GameResolutionStatus(
    val unavailableReason: String? = null,
    /** The preset this app holds for the game; null means the game's own default. */
    val level: ResolutionLevel? = null,
    /** The frame-rate cap this app holds for the game; null means none. */
    val cap: FpsCap? = null,
    /** The game's mode and configs as last read, for the diagnostics panel. */
    val state: GameModeState? = null,
    /** "Renders at ..." from a real SurfaceFlinger measurement, or null when there is none. */
    val result: String? = null,
    /** A change was made while the game ran; that process still has the old setting. */
    val pendingRestart: Boolean = false,
    /** The game's mode now, when it no longer matches what this app set. */
    val changedOutside: String? = null,
    /**
     * The phone restarted and Android put the game back in standard mode, with the config this
     * app wrote still intact. [GameResolution.reapplyBeforeLaunch] switches it back.
     */
    val resetByRestart: Boolean = false
)

/**
 * Per-game render resolution through Android's GameManager downscaling (`cmd game set
 * --downscale`, Android 14+). Only the game renders smaller; the display, system UI and
 * gestures are never touched, unlike the `wm size` lever this replaced (HiOS breaks the
 * portrait swipe-up and scales its own apps twice under any `wm size` override).
 *
 * The setting is read by the game process at start, so it takes effect on the next launch.
 * The game's previous mode and custom config are snapshotted before the first change and put
 * back by [restore], which refuses to overwrite a state someone else has changed since.
 *
 * The same custom config also carries an optional frame-rate cap ([FpsCap], `--fps`). Both are
 * written in one `set` so neither clears the other: on a Pova 7 (Android 16), `set` without
 * `--fps` writes `fps-override: null` and drops a cap, while `set` without `--downscale`
 * keeps the scaling. The cap is held and verified exactly like the preset.
 */
object GameResolution {

    private const val TAG = "TurboBooster"

    const val CUSTOM = "custom"
    const val CUSTOM_MODE_ID = 4

    /** `cmd game` custom mode and `--downscale` exist from Android 14. */
    const val MIN_SDK = 34

    private const val EXIT_MARK = "__exit:"
    private const val NO_VALUE = "none"

    private const val KEY_SNAP_MODE = "snap_mode_"
    private const val KEY_SNAP_CUSTOM = "snap_custom_"
    private const val KEY_SNAP_ANY = "snap_any_"
    private const val KEY_APPLIED = "applied_"
    private const val KEY_STALE_PID = "stale_pid_"
    private const val KEY_NATIVE = "native_"
    private const val KEY_MEASURED = "measured_"
    private const val KEY_BOOT = "boot_"

    /** The cap this app set and still holds, by fps; beside [KEY_APPLIED], which holds the scale. */
    private const val KEY_APPLIED_FPS = "fps_cap_"

    /**
     * `scale|fps` of a change in progress, written before its `set` and cleared after. Still
     * present at the next read means the app died mid-change: see [settleInterrupted].
     */
    private const val KEY_PENDING = "pending_change_"

    /** Changes on every boot; how a mode reset by a restart is told from one made by someone else. */
    private const val BOOT_ID = "/proc/sys/kernel/random/boot_id"

    /** Writable by the shell uid Shizuku runs as (2000), and by root. */
    private const val MEASURE_BASE = "/data/local/tmp/turboboost_measure_"

    /** Watcher cadence: a sample every 15 s for up to 30 min. */
    private const val POLL_S = 15
    private const val MAX_POLLS = 120

    /** Stable samples saved before the watcher stops; each one overwrites the last. */
    private const val MAX_SAMPLES = 4

    /** Samples with the game running before a non-SurfaceView buffer is accepted as its render size. */
    private const val SURFACE_GRACE_POLLS = 8

    // --- parsing ------------------------------------------------------------------------

    private val MODES = Regex("""current mode:\s*(\w+),\s*available game modes:\s*\[([^\]]*)\]""")
    private val CONFIG = Regex("""(\d+)=\[([^\]]*)\]""")
    private val WH = Regex("""w/h:(\d+)x(\d+)""")

    /** `pkg current mode: performance, available game modes: [standard,performance,custom]`. */
    fun parseModes(output: String?): Pair<String, List<String>>? {
        val m = MODES.find(output ?: return null) ?: return null
        val available = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return m.groupValues[1] to available
    }

    /** `cmd game list-configs` output; empty for "No intervention found", null when unreadable. */
    fun parseConfigs(output: String?): Map<Int, InterventionConfig>? {
        if (output == null) return null
        if (output.contains("No intervention found")) return emptyMap()
        if (!output.contains("Modes:")) return null
        return CONFIG.findAll(output).associate { match ->
            val fields = match.groupValues[2].split(',').associate { field ->
                field.substringBefore(':').trim() to field.substringAfter(':', "").trim()
            }
            match.groupValues[1].toInt() to InterventionConfig(
                scaling = fields["Scaling"]?.takeIf { it.isNotEmpty() },
                fps = fields["Fps"]?.takeIf { it.isNotEmpty() }
            )
        }
    }

    /** Why `cmd game` cannot be used for this package, from its own error text; null when it can. */
    fun unavailableReason(output: String): String? = when {
        output.contains("not of game type") ->
            "Android doesn't list this app as a game, so it can't be downscaled."
        output.contains("Unknown command") || output.contains("Can't find service") ||
            output.contains("Unknown service") ->
            "Per-game downscaling isn't available on this Android version."
        else -> null
    }

    /** Scales compare as numbers: "0.6" and "0.60" are the same setting. */
    fun sameScale(a: String?, b: String?): Boolean {
        // Two ways of saying "no scaling" are the same setting; one of them against a factor is not.
        if (isNoScaling(a) || isNoScaling(b)) return isNoScaling(a) && isNoScaling(b)
        val x = a?.toFloatOrNull() ?: return false
        val y = b?.toFloatOrNull() ?: return false
        return abs(x - y) < 0.001f
    }

    /**
     * Whether [scale] scales nothing: absent, `disable`, or a number outside (0, 1). `set
     * --downscale disable` reads back as `Scaling:-1.0` on the test device. Text that is
     * neither is not "no scaling"; it is unreadable.
     */
    fun isNoScaling(scale: String?): Boolean {
        val raw = scale?.trim() ?: return true
        if (raw == "disable") return true
        val value = raw.toFloatOrNull() ?: return false
        return value <= 0f || value >= 1f
    }

    /**
     * The render size of [pkg] from a SurfaceFlinger dump: the most common buffer size among
     * its SurfaceView layers (where game engines draw), else among its window layers.
     */
    fun parseBuffers(dump: String?, pkg: String): BufferSize? {
        val owner = Regex("""(?<![\w.])${Regex.escape(pkg)}/""")
        val found = dump.orEmpty().lineSequence()
            .filter { owner.containsMatchIn(it) }
            .mapNotNull { line ->
                val m = WH.find(line) ?: return@mapNotNull null
                val size = BufferSize(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (size.pixels > 0) size to line.contains("SurfaceView[") else null
            }
            .toList()
        val pool = found.filter { it.second }.ifEmpty { found }.map { it.first }
        return pool.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<BufferSize, Int>>({ it.value }, { it.key.pixels }))?.key
    }

    /**
     * Fewer pixels than this is no effect: a capped game measured 720x1639 against 720x1648 under
     * a downscale its own 720p cap already sits below, a few rows of window shape, not a change.
     */
    private const val NO_EFFECT_BELOW_PERCENT = 2

    /** The card's result line. Null without a measurement: never an estimate. */
    fun resultText(native: BufferSize?, measured: BufferSize?): String? {
        if (measured == null) return null
        if (native == null) return "Renders at $measured"
        val cut = ((1.0 - measured.pixels.toDouble() / native.pixels) * 100).roundToInt()
        return if (cut < NO_EFFECT_BELOW_PERCENT) {
            "No effect: game already renders below this"
        } else {
            "Renders at $measured (\u2212$cut% pixels)"
        }
    }

    /** A custom config that scales nothing and caps nothing is the same as having none. */
    private fun effective(config: InterventionConfig?): InterventionConfig? {
        if (config == null) return null
        val scale = config.scaling?.takeUnless { isNoScaling(it) }?.toFloatOrNull()
        val fps = config.fps?.toIntOrNull()?.takeIf { it > 0 }
        return if (scale == null && fps == null) null else InterventionConfig(scale?.toString(), fps?.toString())
    }

    fun matchesSnapshot(state: GameModeState, snap: GameModeSnapshot): Boolean {
        if (state.mode != snap.mode) return false
        val now = effective(state.custom)
        val was = effective(snap.custom)
        if (now == null || was == null) return now == was
        val sameScaling = (now.scaling == null && was.scaling == null) || sameScale(now.scaling, was.scaling)
        return sameScaling && now.fps == was.fps
    }

    /**
     * Whether [custom] is exactly what this app wrote: [scale] (null: no scaling) and [fps]
     * (null: no cap). `disable`, -1 and 0 all read as "none".
     */
    private fun holdsConfig(custom: InterventionConfig?, scale: String?, fps: String?): Boolean {
        if (scale == null && fps == null) return false
        val now = effective(custom) ?: return false
        val sameScaling = sameScale(now.scaling, scale)
        return sameScaling && now.fps == fps?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.toString()
    }

    /** Whether the game still holds exactly the downscale [applied] and cap [appliedFps] this app set. */
    fun isAsApplied(state: GameModeState, applied: String?, appliedFps: String? = null): Boolean =
        state.mode == CUSTOM && holdsConfig(state.custom, applied, appliedFps)

    /**
     * Whether a phone restart, not a person, took the game out of the preset. Seen on a Pova 7
     * (Android 16): after a reboot every game is back in standard mode while its per-mode
     * configs survive, so the downscale stops applying. Only when the boot changed since the
     * preset was set, the mode is standard, and the custom config is exactly what this app
     * wrote (its scale and its cap, or no cap); anything else is a change made outside the app.
     */
    fun isResetByRestart(
        state: GameModeState,
        applied: String?,
        appliedBoot: String?,
        currentBoot: String?,
        appliedFps: String? = null
    ): Boolean {
        if ((applied == null && appliedFps == null) || appliedBoot == null || currentBoot == null || appliedBoot == currentBoot) return false
        return state.mode == "standard" && holdsConfig(state.custom, applied, appliedFps)
    }

    /**
     * The `set` that writes [scale] and [fps] in one go. No cap leaves `--fps` out, which
     * clears it ([GameResolution] doc), so a scale-only command is unchanged from 1.2.3.
     * Both values come from [ResolutionLevel] and [FpsCap] only.
     */
    fun setCommand(pkg: String, scale: ResolutionLevel?, fps: FpsCap?): String =
        "cmd game set --downscale ${scale?.scale ?: "disable"}" + (fps?.let { " --fps ${it.fps}" } ?: "") + " $pkg"

    /** The commands that put [snap] back on [pkg], in order. */
    fun restoreCommands(pkg: String, snap: GameModeSnapshot): List<String> = buildList {
        val custom = effective(snap.custom)
        when {
            // The game had its own custom config: write it back. Its own cap goes back with
            // it; with none, leaving `--fps` out clears this app's cap.
            custom != null -> add(
                "cmd game set --downscale ${custom.scaling ?: "disable"}" +
                    (custom.fps?.let { " --fps $it" } ?: "") + " $pkg"
            )
            // Nothing at all before us: reset is exact.
            !snap.hadInterventions -> add("cmd game reset $pkg")
            // Other modes have configs that reset would wipe: only switch our scaling off.
            else -> add("cmd game set --downscale disable $pkg")
        }
        add("cmd game mode ${snap.mode} $pkg")
    }

    // --- store --------------------------------------------------------------------------

    fun snapshot(store: KeyValueStore, pkg: String): GameModeSnapshot? {
        val mode = store.getString(KEY_SNAP_MODE + pkg) ?: return null
        val custom = store.getString(KEY_SNAP_CUSTOM + pkg)?.takeIf { it != NO_VALUE }?.let { raw ->
            val (scaling, fps) = raw.split('|').let { it.getOrNull(0) to it.getOrNull(1) }
            InterventionConfig(scaling?.takeIf { it.isNotEmpty() }, fps?.takeIf { it.isNotEmpty() })
        }
        return GameModeSnapshot(mode, custom, store.getBoolean(KEY_SNAP_ANY + pkg, true))
    }

    private fun saveSnapshot(store: KeyValueStore, pkg: String, state: GameModeState) {
        val custom = state.custom?.let { "${it.scaling.orEmpty()}|${it.fps.orEmpty()}" } ?: NO_VALUE
        // On disk before the `set` runs: a process killed right after it must still find them.
        store.putStringNow(KEY_SNAP_CUSTOM + pkg, custom)
        store.putBooleanNow(KEY_SNAP_ANY + pkg, state.configs.isNotEmpty())
        // Last: the mode key is what marks a snapshot as present.
        store.putStringNow(KEY_SNAP_MODE + pkg, state.mode)
    }

    private fun clearSnapshot(store: KeyValueStore, pkg: String) {
        store.putString(KEY_SNAP_MODE + pkg, null)
        store.putString(KEY_SNAP_CUSTOM + pkg, null)
        store.putString(KEY_APPLIED + pkg, null)
        store.putString(KEY_APPLIED_FPS + pkg, null)
        store.putString(KEY_PENDING + pkg, null)
        store.putString(KEY_MEASURED + pkg, null)
        store.putString(KEY_BOOT + pkg, null)
    }

    /** The scale this app set and still holds for [pkg], or null. */
    fun applied(store: KeyValueStore, pkg: String): String? = store.getString(KEY_APPLIED + pkg)

    /** The cap this app set and still holds for [pkg], or null. */
    fun appliedFps(store: KeyValueStore, pkg: String): FpsCap? = FpsCap.forFps(store.getString(KEY_APPLIED_FPS + pkg))

    /** Whether this app holds a preset or a cap for [pkg], so the game must stay in custom mode. */
    fun holds(store: KeyValueStore, pkg: String): Boolean = applied(store, pkg) != null || appliedFps(store, pkg) != null

    fun native(store: KeyValueStore, pkg: String): BufferSize? = BufferSize.parse(store.getString(KEY_NATIVE + pkg))

    /** The measurement taken under [scale], or null when none was taken under it. */
    fun measured(store: KeyValueStore, pkg: String, scale: String): BufferSize? {
        val raw = store.getString(KEY_MEASURED + pkg) ?: return null
        return if (sameScale(raw.substringAfter('@', ""), scale)) BufferSize.parse(raw.substringBefore('@')) else null
    }

    /** For the session record: `LOW 0.6` with a preset, `default` without. */
    fun sessionValue(store: KeyValueStore, pkg: String): String =
        applied(store, pkg)?.let { scale -> "${ResolutionLevel.forScale(scale)?.label ?: "CUSTOM"} $scale" } ?: "default"

    /** For the session record: the cap held, or null for none ([SessionRecord.fpsCap]). */
    fun sessionFpsCap(store: KeyValueStore, pkg: String): Int? = appliedFps(store, pkg)?.fps

    /** A change made while [runningPid] ran does not reach that process; remember which one it was. */
    private fun markStale(store: KeyValueStore, pkg: String, runningPid: String?) {
        store.putString(KEY_STALE_PID + pkg, runningPid?.trim()?.takeIf { it.isNotEmpty() })
    }

    // --- shell --------------------------------------------------------------------------

    /**
     * Runs [command] with stderr folded into stdout and the exit code on a marker line, so a
     * rejected command's own message reaches the user. Null when nothing could be run.
     */
    fun runCaptured(shell: ShellExecutor, command: String): Pair<Int?, String>? {
        val out = shell.capture("$command 2>&1; echo \"$EXIT_MARK\$?\"") ?: return null
        val at = out.lastIndexOf(EXIT_MARK)
        if (at < 0) return null to out.trim()
        return out.substring(at + EXIT_MARK.length).trim().toIntOrNull() to out.substring(0, at).trim()
    }

    /** A [read]: the state, or why there is none and whether that is permanent for this game. */
    data class Read(val state: GameModeState?, val reason: String? = null, val unavailable: Boolean = false)

    /** The game's state, or a reason it has none this app can use. */
    fun read(shell: ShellExecutor, pkg: String): Read {
        val modes = runCaptured(shell, "cmd game list-modes $pkg")
            ?: return Read(null, "Could not run cmd game through Shizuku")
        unavailableReason(modes.second)?.let { return Read(null, it, unavailable = true) }
        val (mode, available) = parseModes(modes.second)
            ?: return Read(null, "Unexpected cmd game output: ${modes.second.take(80)}")
        val configs = parseConfigs(runCaptured(shell, "cmd game list-configs $pkg")?.second)
            ?: return Read(null, "Could not read the game's interventions")
        if (CUSTOM !in available) {
            return Read(null, "This game doesn't offer Android's custom game mode.", unavailable = true)
        }
        return Read(GameModeState(mode, available, configs))
    }

    fun bootId(shell: ShellExecutor): String? = shell.capture("cat $BOOT_ID")?.trim()?.takeIf { it.isNotEmpty() }

    private fun resetByRestart(shell: ShellExecutor, store: KeyValueStore, pkg: String, state: GameModeState): Boolean =
        isResetByRestart(state, applied(store, pkg), store.getString(KEY_BOOT + pkg), bootId(shell), appliedFps(store, pkg)?.fps?.toString())

    private fun isAsApplied(store: KeyValueStore, pkg: String, state: GameModeState): Boolean =
        isAsApplied(state, applied(store, pkg), appliedFps(store, pkg)?.fps?.toString())

    /**
     * Finishes a change the app died in the middle of ([KEY_PENDING]): when the game holds
     * exactly what that change asked for, it is recorded as applied; when it still holds what
     * this app held before, or its snapshot with nothing held, the leftovers are dropped.
     * Anything else stays as it is, and [status] reports it as changed outside the app, with
     * the snapshot kept for RESTORE ANYWAY.
     */
    private fun settleInterrupted(shell: ShellExecutor, store: KeyValueStore, pkg: String, state: GameModeState) {
        val pending = store.getString(KEY_PENDING + pkg) ?: return
        val scale = pending.substringBefore('|').takeIf { it.isNotEmpty() }
        val fps = pending.substringAfter('|', "").takeIf { it.isNotEmpty() }
        val snap = snapshot(store, pkg)
        when {
            // The snapshot is saved before the pending mark, so no `set` ran without one.
            snap == null -> Unit
            isAsApplied(state, scale, fps) -> {
                val boot = bootId(shell)
                store.putString(KEY_APPLIED + pkg, scale)
                store.putString(KEY_APPLIED_FPS + pkg, fps)
                store.putString(KEY_BOOT + pkg, boot)
                GameStateRef.saveState(store, pkg, state, boot)
                log { Log.i(TAG, "Interrupted change on $pkg completed: $pending") }
            }
            holds(store, pkg) && isAsApplied(store, pkg, state) -> Unit
            !holds(store, pkg) && matchesSnapshot(state, snap) -> clearSnapshot(store, pkg)
            else -> {
                log { Log.w(TAG, "Interrupted change on $pkg left ${describe(state)}") }
                return
            }
        }
        store.putString(KEY_PENDING + pkg, null)
    }

    /** `pidof` output for [pkg], or null when it is not running. */
    fun pidOf(shell: ShellExecutor, pkg: String): String? =
        shell.capture("pidof $pkg")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Sets [level] for [pkg]. The previous mode and custom config are snapshotted first, once;
     * a later preset on top keeps that first snapshot. [runningPid] is the game's process if it
     * is running now, which keeps the old setting until it restarts. The cap already held is kept.
     */
    fun apply(shell: ShellExecutor, store: KeyValueStore, pkg: String, level: ResolutionLevel, runningPid: String?): GameResolutionResult =
        apply(shell, store, pkg, level, appliedFps(store, pkg), runningPid)

    /**
     * Sets [level] and [cap] together in one `set` ([setCommand]); the card passes the one it
     * keeps unchanged, so changing either never drops the other. Both null is the game's own
     * default: the snapshot goes back through [restore]. Applied only when the read-back shows
     * both values; a read-back does not show that the game renders or paces itself that way.
     */
    @Synchronized
    fun apply(
        shell: ShellExecutor,
        store: KeyValueStore,
        pkg: String,
        level: ResolutionLevel?,
        cap: FpsCap?,
        runningPid: String?
    ): GameResolutionResult {
        if (!GameRegistry.isValidPackageName(pkg)) return GameResolutionResult(false, "Not a package name: $pkg")
        if (level == null && cap == null) return restore(shell, store, pkg, force = false, runningPid = runningPid)
        if (!shell.isAvailable()) return GameResolutionResult(false, "Shizuku not connected", unavailable = true)
        val read = read(shell, pkg)
        val before = read.state
            ?: return GameResolutionResult(false, read.reason ?: "Could not read the game mode", unavailable = read.unavailable)
        settleInterrupted(shell, store, pkg, before)

        val created = snapshot(store, pkg) == null
        if (created) saveSnapshot(store, pkg, before)
        val scale = level?.scale
        val fps = cap?.fps?.toString()
        store.putStringNow(KEY_PENDING + pkg, "${scale.orEmpty()}|${fps.orEmpty()}")

        val set = runCaptured(shell, setCommand(pkg, level, cap))
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale $pkg -> ${scale ?: "disable"}, fps ${fps ?: "none"}: exit=${set?.first} out=${set?.second} read back=$after") }
        if (set?.first == 0 && after != null && isAsApplied(after, scale, fps)) {
            val boot = bootId(shell)
            store.putString(KEY_APPLIED + pkg, scale)
            store.putString(KEY_APPLIED_FPS + pkg, fps)
            store.putString(KEY_BOOT + pkg, boot)
            GameStateRef.saveState(store, pkg, after, boot)
            store.putString(KEY_PENDING + pkg, null)
            markStale(store, pkg, runningPid)
            return GameResolutionResult(true, "${describeChoice(level, cap)} set")
        }
        store.putString(KEY_PENDING + pkg, null)

        // Nothing changed: drop the snapshot this call took, so the next try starts clean.
        val snap = snapshot(store, pkg)
        if (created && snap != null && after != null && matchesSnapshot(after, snap)) clearSnapshot(store, pkg)
        val said = set?.second?.takeIf { it.isNotBlank() }
        val what = listOfNotNull("Downscale".takeIf { level != null }, "FPS cap".takeIf { cap != null }).joinToString(" and ")
        return GameResolutionResult(
            false,
            said?.let { unavailableReason(it) } ?: "$what not applied${said?.let { ": $it" } ?: ""}",
            unavailable = said?.let { unavailableReason(it) } != null
        )
    }

    /** `LOW`, `40 FPS cap` or `LOW and 40 FPS cap`, for the result toasts. */
    private fun describeChoice(level: ResolutionLevel?, cap: FpsCap?): String =
        listOfNotNull(level?.label, cap?.let { "${it.label} FPS cap" }).joinToString(" and ")

    /**
     * Puts the snapshot back. Without [force], refuses when the game's mode no longer holds
     * what this app set: someone else changed it, and their change is not ours to undo.
     * Clears the snapshot only when the read-back matches it.
     */
    @Synchronized
    fun restore(shell: ShellExecutor, store: KeyValueStore, pkg: String, force: Boolean, runningPid: String?): GameResolutionResult {
        val snap = snapshot(store, pkg) ?: run {
            store.putString(KEY_APPLIED + pkg, null)
            store.putString(KEY_APPLIED_FPS + pkg, null)
            store.putString(KEY_PENDING + pkg, null)
            return GameResolutionResult(true, "Already at the game's default")
        }
        if (!shell.isAvailable()) return GameResolutionResult(false, "Shizuku not connected", unavailable = true)
        val read = read(shell, pkg)
        val now = read.state ?: return GameResolutionResult(false, read.reason ?: "Could not read the game mode")
        settleInterrupted(shell, store, pkg, now)
        if (snapshot(store, pkg) == null) return GameResolutionResult(true, "Already at the game's default")

        val ours = isAsApplied(store, pkg, now) || resetByRestart(shell, store, pkg, now)
        if (!force && !ours) {
            return GameResolutionResult(false, "Changed outside Turbo: now ${describe(now)}", changedOutside = true)
        }

        val failed = restoreCommands(pkg, snap).filter { runCaptured(shell, it)?.first != 0 }
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale restore $pkg to $snap: failed=$failed read back=$after") }
        if (after != null && matchesSnapshot(after, snap)) {
            clearSnapshot(store, pkg)
            GameStateRef.saveState(store, pkg, after, bootId(shell))
            markStale(store, pkg, runningPid)
            return GameResolutionResult(true, "Back to the game's default (${snap.mode})")
        }
        val got = after?.let { "game reports ${describe(it)}" } ?: "read-back failed"
        return GameResolutionResult(false, "Restore not verified: ${if (failed.isNotEmpty()) "${failed.first()} failed, " else ""}$got")
    }

    /**
     * Run just before this app launches [pkg]: when a phone restart took the game out of its
     * preset ([isResetByRestart]), switches it back to custom mode and verifies it. Null when
     * there is nothing to do; any other difference is left alone for the user to decide.
     */
    @Synchronized
    fun reapplyBeforeLaunch(shell: ShellExecutor, store: KeyValueStore, pkg: String): GameResolutionResult? {
        if (!GameRegistry.isValidPackageName(pkg) || !holds(store, pkg) || !shell.isAvailable()) return null
        val now = read(shell, pkg).state ?: return null
        if (!resetByRestart(shell, store, pkg, now)) return null
        val applied = applied(store, pkg)
        val cap = appliedFps(store, pkg)
        // The config with both values survived the restart: switching the mode back restores both.
        val switched = runCaptured(shell, "cmd game mode $CUSTOM $pkg")
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale $pkg re-applied after a restart: exit=${switched?.first} read back=$after") }
        if (switched?.first == 0 && after != null && isAsApplied(store, pkg, after)) {
            val boot = bootId(shell)
            store.putString(KEY_BOOT + pkg, boot)
            GameStateRef.saveState(store, pkg, after, boot)
            val what = listOfNotNull(applied?.let { ResolutionLevel.forScale(it)?.label ?: it }, cap?.let { "${it.label} FPS cap" })
            return GameResolutionResult(true, "${what.joinToString(" and ")} set again after the phone restarted")
        }
        return GameResolutionResult(false, "Could not set the preset again after the phone restarted")
    }

    fun describe(state: GameModeState): String =
        state.mode + (state.custom?.scaling?.takeIf { state.mode == CUSTOM && !isNoScaling(it) }?.let { ", scaling $it" } ?: "") +
            (effective(state.custom)?.fps?.takeIf { state.mode == CUSTOM }?.let { ", fps $it" } ?: "")

    // --- measurement --------------------------------------------------------------------

    /**
     * Starts a detached shell watcher that samples the SurfaceFlinger buffers of a [pkg]
     * process other than [stalePid], and saves them for [collect] once two samples in a row
     * agree on the size. It keeps sampling and overwriting while the game runs: on a Pova 7,
     * a game drew its previous session's size (815x1858) for a while on the first
     * launch after a change, and 702x1599 at 0.65 from the next one. It runs in the Shizuku
     * shell rather than this app because HiOS freezes this app's uid once backgrounded.
     * [scale] is the setting the launch runs under, null for the game's default.
     */
    fun startWatcher(shell: ShellExecutor, pkg: String, scale: String?, stalePid: String?): Boolean {
        if (!GameRegistry.isValidPackageName(pkg)) return false
        val stale = stalePid?.filter { it.isDigit() || it == ' ' }.orEmpty()
        val tag = scale?.takeIf { it.toFloatOrNull() != null } ?: NO_VALUE
        val base = MEASURE_BASE + pkg
        stopWatcher(shell, pkg)
        val script = "echo \$\$ > $base.pid; i=0; k=0; n=0; last=; while [ \$i -lt $MAX_POLLS ]; do " +
            "p=\$(pidof $pkg); " +
            "if [ -n \"\$p\" ] && [ \"\$p\" != \"$stale\" ]; then k=\$((k+1)); " +
            "d=\$(dumpsys SurfaceFlinger | grep -F \"$pkg/\" | grep -F \"w/h:\"); " +
            "if [ -n \"\$d\" ] && { echo \"\$d\" | grep -qF \"SurfaceView[\" || [ \$k -ge $SURFACE_GRACE_POLLS ]; }; then " +
            // The most common buffer size, tagged with the process: a relaunch starts over.
            "top=\"\$p \$(echo \"\$d\" | grep -oE \"w/h:[0-9]+x[0-9]+\" | sort | uniq -c | sort -rn | head -1 | sed \"s/.* //\")\"; " +
            "if [ \"\$top\" = \"\$last\" ]; then " +
            "{ echo \"pid:\$p\"; echo \"scale:$tag\"; echo \"\$d\"; } > $base.tmp && mv $base.tmp $base.txt; " +
            "n=\$((n+1)); [ \$n -ge $MAX_SAMPLES ] && break; fi; last=\$top; fi; " +
            "else last=; fi; sleep $POLL_S; i=\$((i+1)); done; rm -f $base.pid"
        return shell.run("setsid sh -c '$script' </dev/null >/dev/null 2>&1 &").ok
    }

    fun stopWatcher(shell: ShellExecutor, pkg: String): Boolean {
        val base = MEASURE_BASE + pkg
        return shell.run("[ -f $base.pid ] && kill \$(cat $base.pid) 2>/dev/null; rm -f $base.pid").ok
    }

    /** A watcher's saved file: the process it measured, the setting it ran under, and its size. */
    data class Measurement(val pid: String, val scale: String?, val size: BufferSize)

    fun parseMeasurement(text: String?, pkg: String): Measurement? {
        val lines = text?.lines() ?: return null
        val pid = lines.firstOrNull { it.startsWith("pid:") }?.removePrefix("pid:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scale = lines.firstOrNull { it.startsWith("scale:") }?.removePrefix("scale:")?.trim()
        val size = parseBuffers(text, pkg) ?: return null
        return Measurement(pid, scale?.takeIf { it != NO_VALUE }, size)
    }

    /**
     * Files a measurement: as the native size when taken under the game's default and none is
     * stored yet, as the preset's result when taken under the preset now held. Anything else
     * describes a setting that is no longer current and is dropped.
     */
    fun record(store: KeyValueStore, pkg: String, m: Measurement): Boolean {
        val stale = store.getString(KEY_STALE_PID + pkg)
        if (stale != null && stale == m.pid) return false
        store.putString(KEY_STALE_PID + pkg, null)
        val applied = applied(store, pkg)
        return when {
            m.scale == null && applied == null -> {
                if (native(store, pkg) == null) store.putString(KEY_NATIVE + pkg, m.size.toString())
                true
            }
            m.scale != null && sameScale(m.scale, applied) -> {
                store.putString(KEY_MEASURED + pkg, "${m.size}@${m.scale}")
                true
            }
            else -> false
        }
    }

    /** Reads and removes a finished watcher's file, filing what it measured. */
    fun collect(shell: ShellExecutor, store: KeyValueStore, pkg: String): Boolean {
        if (!GameRegistry.isValidPackageName(pkg)) return false
        val base = MEASURE_BASE + pkg
        val text = shell.capture("cat $base.txt") ?: return false
        shell.run("rm -f $base.txt")
        val m = parseMeasurement(text, pkg) ?: return false
        return record(store, pkg, m)
    }

    /**
     * Whether a watcher should be armed: always while a preset is held, so the card follows
     * what the game renders at on its latest launch; under the default only until the
     * native size is stored, which is taken once.
     */
    fun needsMeasurement(store: KeyValueStore, pkg: String): Boolean =
        applied(store, pkg) != null || native(store, pkg) == null

    /**
     * The card's status: reads the game's mode, settles a change the app died during,
     * collects any finished measurement, checks a pending restart. Synchronized with [apply]
     * so an apply in flight is never mistaken for an interrupted one.
     */
    @Synchronized
    fun status(shell: ShellExecutor, store: KeyValueStore, pkg: String): GameResolutionStatus {
        val read = read(shell, pkg)
        val state = read.state ?: return GameResolutionStatus(unavailableReason = read.reason)
        settleInterrupted(shell, store, pkg, state)
        collect(shell, store, pkg)

        val stale = store.getString(KEY_STALE_PID + pkg)
        val pending = stale != null && pidOf(shell, pkg) == stale
        // The process that predates the change is gone: nothing is pending anymore.
        if (stale != null && !pending) store.putString(KEY_STALE_PID + pkg, null)

        val applied = applied(store, pkg)
        val held = holds(store, pkg)
        val result = applied?.let { resultText(native(store, pkg), measured(store, pkg, it)) }
        val asApplied = held && isAsApplied(store, pkg, state)
        val restarted = held && !asApplied && resetByRestart(shell, store, pkg, state)
        // A snapshot with nothing held: a change the app died during left something else.
        val orphaned = !held && snapshot(store, pkg)?.let { !matchesSnapshot(state, it) } == true
        return GameResolutionStatus(
            level = ResolutionLevel.forScale(applied),
            cap = appliedFps(store, pkg),
            state = state,
            result = result,
            pendingRestart = pending,
            changedOutside = if ((held && !asApplied && !restarted) || orphaned) describe(state) else null,
            resetByRestart = restarted
        )
    }

    /** The pid a change would leave stale, for [markStale] via apply/restore. */
    fun stalePid(store: KeyValueStore, pkg: String): String? = store.getString(KEY_STALE_PID + pkg)

    /** Logging must never fail an apply or restore (android.util.Log is absent in JVM tests). */
    private inline fun log(block: () -> Unit) {
        runCatching { block() }
    }
}
