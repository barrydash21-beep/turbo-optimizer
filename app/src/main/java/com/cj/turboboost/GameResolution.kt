package com.cj.turboboost

import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/** A preset on a game's card. [scale] is exactly what `cmd game set --downscale` receives. */
enum class ResolutionLevel(val label: String, val scale: String) {
    MEDIUM("MEDIUM", "0.65"),
    LOW("LOW", "0.6");

    companion object {
        fun forScale(scale: String?): ResolutionLevel? = entries.firstOrNull { GameResolution.sameScale(it.scale, scale) }
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
        val x = a?.toFloatOrNull() ?: return false
        val y = b?.toFloatOrNull() ?: return false
        return abs(x - y) < 0.001f
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
     * Fewer pixels than this is no effect: CoD Mobile measured 720x1639 against 720x1648 under
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
        val scale = config.scaling?.toFloatOrNull()?.takeIf { it > 0f && it < 1f }
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

    /** Whether the game still holds exactly the downscale this app set. */
    fun isAsApplied(state: GameModeState, applied: String?): Boolean =
        applied != null && state.mode == CUSTOM && sameScale(state.custom?.scaling, applied)

    /**
     * Whether a phone restart, not a person, took the game out of the preset. Seen on a Pova 7
     * (Android 16): after a reboot every game is back in standard mode while its per-mode
     * configs survive, so the downscale stops applying. Only when the boot changed since the
     * preset was set, the mode is standard, and the custom config is exactly what this app
     * wrote (its scale, no frame-rate cap); anything else is a change made outside the app.
     */
    fun isResetByRestart(state: GameModeState, applied: String?, appliedBoot: String?, currentBoot: String?): Boolean {
        if (applied == null || appliedBoot == null || currentBoot == null || appliedBoot == currentBoot) return false
        val custom = effective(state.custom) ?: return false
        return state.mode == "standard" && sameScale(custom.scaling, applied) && custom.fps == null
    }

    /** The commands that put [snap] back on [pkg], in order. */
    fun restoreCommands(pkg: String, snap: GameModeSnapshot): List<String> = buildList {
        val custom = effective(snap.custom)
        when {
            // The game had its own custom config: write it back.
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
        store.putString(KEY_SNAP_CUSTOM + pkg, custom)
        store.putBoolean(KEY_SNAP_ANY + pkg, state.configs.isNotEmpty())
        // Last: the mode key is what marks a snapshot as present.
        store.putString(KEY_SNAP_MODE + pkg, state.mode)
    }

    private fun clearSnapshot(store: KeyValueStore, pkg: String) {
        store.putString(KEY_SNAP_MODE + pkg, null)
        store.putString(KEY_SNAP_CUSTOM + pkg, null)
        store.putString(KEY_APPLIED + pkg, null)
        store.putString(KEY_MEASURED + pkg, null)
        store.putString(KEY_BOOT + pkg, null)
    }

    /** The scale this app set and still holds for [pkg], or null. */
    fun applied(store: KeyValueStore, pkg: String): String? = store.getString(KEY_APPLIED + pkg)

    fun native(store: KeyValueStore, pkg: String): BufferSize? = BufferSize.parse(store.getString(KEY_NATIVE + pkg))

    /** The measurement taken under [scale], or null when none was taken under it. */
    fun measured(store: KeyValueStore, pkg: String, scale: String): BufferSize? {
        val raw = store.getString(KEY_MEASURED + pkg) ?: return null
        return if (sameScale(raw.substringAfter('@', ""), scale)) BufferSize.parse(raw.substringBefore('@')) else null
    }

    /** For the session record: `LOW 0.6` with a preset, `default` without. */
    fun sessionValue(store: KeyValueStore, pkg: String): String =
        applied(store, pkg)?.let { scale -> "${ResolutionLevel.forScale(scale)?.label ?: "CUSTOM"} $scale" } ?: "default"

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
        isResetByRestart(state, applied(store, pkg), store.getString(KEY_BOOT + pkg), bootId(shell))

    /** `pidof` output for [pkg], or null when it is not running. */
    fun pidOf(shell: ShellExecutor, pkg: String): String? =
        shell.capture("pidof $pkg")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Sets [level] for [pkg]. The previous mode and custom config are snapshotted first, once;
     * a later preset on top keeps that first snapshot. [runningPid] is the game's process if it
     * is running now, which keeps the old setting until it restarts.
     */
    @Synchronized
    fun apply(shell: ShellExecutor, store: KeyValueStore, pkg: String, level: ResolutionLevel, runningPid: String?): GameResolutionResult {
        if (!GameRegistry.isValidPackageName(pkg)) return GameResolutionResult(false, "Not a package name: $pkg")
        if (!shell.isAvailable()) return GameResolutionResult(false, "Shizuku not connected", unavailable = true)
        val read = read(shell, pkg)
        val before = read.state
            ?: return GameResolutionResult(false, read.reason ?: "Could not read the game mode", unavailable = read.unavailable)

        val created = snapshot(store, pkg) == null
        if (created) saveSnapshot(store, pkg, before)

        val set = runCaptured(shell, "cmd game set --downscale ${level.scale} $pkg")
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale $pkg -> ${level.scale}: exit=${set?.first} out=${set?.second} read back=$after") }
        if (set?.first == 0 && after != null && isAsApplied(after, level.scale)) {
            store.putString(KEY_APPLIED + pkg, level.scale)
            store.putString(KEY_BOOT + pkg, bootId(shell))
            markStale(store, pkg, runningPid)
            return GameResolutionResult(true, "${level.label} set")
        }

        // Nothing changed: drop the snapshot this call took, so the next try starts clean.
        val snap = snapshot(store, pkg)
        if (created && snap != null && after != null && matchesSnapshot(after, snap)) clearSnapshot(store, pkg)
        val said = set?.second?.takeIf { it.isNotBlank() }
        return GameResolutionResult(
            false,
            said?.let { unavailableReason(it) } ?: "Downscale not applied${said?.let { ": $it" } ?: ""}",
            unavailable = said?.let { unavailableReason(it) } != null
        )
    }

    /**
     * Puts the snapshot back. Without [force], refuses when the game's mode no longer holds
     * what this app set: someone else changed it, and their change is not ours to undo.
     * Clears the snapshot only when the read-back matches it.
     */
    @Synchronized
    fun restore(shell: ShellExecutor, store: KeyValueStore, pkg: String, force: Boolean, runningPid: String?): GameResolutionResult {
        val snap = snapshot(store, pkg) ?: run {
            store.putString(KEY_APPLIED + pkg, null)
            return GameResolutionResult(true, "Already at the game's default")
        }
        if (!shell.isAvailable()) return GameResolutionResult(false, "Shizuku not connected", unavailable = true)
        val read = read(shell, pkg)
        val now = read.state ?: return GameResolutionResult(false, read.reason ?: "Could not read the game mode")

        val ours = isAsApplied(now, applied(store, pkg)) || resetByRestart(shell, store, pkg, now)
        if (!force && !ours) {
            return GameResolutionResult(false, "Changed outside Turbo: now ${describe(now)}", changedOutside = true)
        }

        val failed = restoreCommands(pkg, snap).filter { runCaptured(shell, it)?.first != 0 }
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale restore $pkg to $snap: failed=$failed read back=$after") }
        if (after != null && matchesSnapshot(after, snap)) {
            clearSnapshot(store, pkg)
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
        if (!GameRegistry.isValidPackageName(pkg) || applied(store, pkg) == null || !shell.isAvailable()) return null
        val now = read(shell, pkg).state ?: return null
        if (!resetByRestart(shell, store, pkg, now)) return null
        val applied = applied(store, pkg)
        val switched = runCaptured(shell, "cmd game mode $CUSTOM $pkg")
        val after = read(shell, pkg).state
        log { Log.i(TAG, "Downscale $pkg re-applied after a restart: exit=${switched?.first} read back=$after") }
        if (switched?.first == 0 && after != null && isAsApplied(after, applied)) {
            store.putString(KEY_BOOT + pkg, bootId(shell))
            return GameResolutionResult(true, "${ResolutionLevel.forScale(applied)?.label ?: applied} set again after the phone restarted")
        }
        return GameResolutionResult(false, "Could not set the preset again after the phone restarted")
    }

    fun describe(state: GameModeState): String =
        state.mode + (state.custom?.scaling?.takeIf { state.mode == CUSTOM }?.let { ", scaling $it" } ?: "")

    // --- measurement --------------------------------------------------------------------

    /**
     * Starts a detached shell watcher that samples the SurfaceFlinger buffers of a [pkg]
     * process other than [stalePid], and saves them for [collect] once two samples in a row
     * agree on the size. It keeps sampling and overwriting while the game runs: on a Pova 7,
     * Mobile Legends drew its previous session's size (815x1858) for a while on the first
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

    /** The card's status: reads the game's mode, collects any finished measurement, checks a pending restart. */
    fun status(shell: ShellExecutor, store: KeyValueStore, pkg: String): GameResolutionStatus {
        val read = read(shell, pkg)
        val state = read.state ?: return GameResolutionStatus(unavailableReason = read.reason)
        collect(shell, store, pkg)

        val stale = store.getString(KEY_STALE_PID + pkg)
        val pending = stale != null && pidOf(shell, pkg) == stale
        // The process that predates the change is gone: nothing is pending anymore.
        if (stale != null && !pending) store.putString(KEY_STALE_PID + pkg, null)

        val applied = applied(store, pkg)
        val result = applied?.let { resultText(native(store, pkg), measured(store, pkg, it)) }
        val restarted = applied != null && !isAsApplied(state, applied) && resetByRestart(shell, store, pkg, state)
        return GameResolutionStatus(
            level = ResolutionLevel.forScale(applied),
            result = result,
            pendingRestart = pending,
            changedOutside = if (applied != null && !isAsApplied(state, applied) && !restarted) describe(state) else null,
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
