package com.cj.turboboost

import java.util.Locale

/** How far a diagnostics row's item is known to work on this phone. */
enum class CheckStatus(val label: String) {
    /** A read-back confirmed it. */
    SUPPORTED("Supported"),
    UNSUPPORTED("Unsupported"),
    /** The command ran, but nothing confirmed the effect. */
    UNVERIFIED("Unverified"),
    NOT_CHECKED("Not checked")
}

/** One row of the diagnostics panel: what, how far it works, and one short line of evidence. */
data class DiagnosticRow(val name: String, val status: CheckStatus, val evidence: String)

/** What `cmd game help` says about the two levers. */
data class GameHelp(
    /** The `--downscale` values it lists, `disable` included. */
    val downscaleValues: List<String>,
    /** The `--fps` line, or null when help has none. */
    val fpsLine: String?
)

/**
 * Everything the rows are built from. Gathered once per open or Re-check; null fields were not
 * read. Shell output is evidence only: nothing in here is ever sent back to the shell.
 */
data class DiagnosticsInput(
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val buildDisplay: String,
    val shizukuRunning: Boolean,
    val shizukuPermission: Boolean,
    /** Whether shell commands could run; false leaves every shell row unchecked. */
    val shellReady: Boolean,
    val gamePackage: String?,
    /** The installed version name, "" when it has none; null when the game is not installed. */
    val gameVersion: String?,
    val helpOutput: String?,
    val read: GameResolution.Read?,
    /** The card's state, as the app last refreshed it. */
    val status: GameResolutionStatus?,
    /** Why the card's last change failed, if it did. */
    val lastChangeNote: String?,
    /** The newest session at the cap held that measured an average FPS. */
    val capSession: SessionRecord?,
    /**
     * The newest session of the same package with no cap, at the same render resolution as
     * [capSession], that measured an average FPS: what the game does without the cap.
     */
    val uncappedSession: SessionRecord? = null,
    val thermalDump: String?,
    /** The first line of `/proc/stat` as the overlay would read it, or null when neither the app nor Shizuku could. */
    val cpuStat: String? = null,
    /** Who read [cpuStat]: "the app" or "Shizuku". */
    val cpuStatVia: String? = null,
    /** Per restricted package, what the system ignored in this app run's last restriction; null when none ran. */
    val restrictIgnored: Map<String, List<String>>?
)

/** The read-only diagnostics panel: its rows, the `cmd game help` cache and the copied report. */
object DiagnosticsPanel {

    /** Between these fractions of the cap, a session's average counts as following it. */
    private const val CAP_TOLERANCE = 1.1f
    private const val CAP_FLOOR = 0.85f

    /** Without the cap the game must run at least this much faster, or the cap is not what held it down. */
    private const val UNCAPPED_MIN = 1.2f

    private val DOWNSCALE = Regex("""--downscale\s*\[([^\]]*)\]""")

    /** Parsed `cmd game help`, kept for the life of the process: it never changes while it runs. */
    @Volatile
    private var helpCache: Pair<String, GameHelp>? = null

    /** The levers `cmd game help` lists, or null when the text is not the game manager's help. */
    fun parseGameHelp(text: String?): GameHelp? {
        if (text == null || !text.contains("Game manager")) return null
        val values = DOWNSCALE.find(text)?.groupValues?.get(1)
            ?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val fps = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("--fps") }
        return GameHelp(values, fps)
    }

    /** `cmd game help` and what it lists, read once per process. Null help when unreadable. */
    fun gameHelp(shell: ShellExecutor): Pair<String?, GameHelp?> {
        helpCache?.let { return it }
        // `cmd game help` exits 255 on Android 16 while printing the full text: only the text counts.
        val out = GameResolution.runCaptured(shell, "cmd game help")?.second
        val parsed = parseGameHelp(out) ?: return out to null
        return (out!! to parsed).also { helpCache = it }
    }

    /** For tests: the cache outlives each test otherwise. */
    internal fun clearHelpCache() {
        helpCache = null
    }

    fun rows(input: DiagnosticsInput): List<DiagnosticRow> = listOf(
        androidRow(input),
        DiagnosticRow(
            "Device",
            if (input.manufacturer.isNotBlank() && input.model.isNotBlank()) CheckStatus.SUPPORTED else CheckStatus.NOT_CHECKED,
            "${input.manufacturer} ${input.model}, build ${input.buildDisplay}"
        ),
        shizukuRow(input),
        cmdGameRow(input),
        installedRow(input),
        modeRow(input),
        downscaleRow(input),
        fpsCapRow(input),
        thermalRow(input),
        restrictionRow(input),
        cpuRow(input)
    )

    private fun androidRow(input: DiagnosticsInput) = DiagnosticRow(
        "Android version and SDK",
        if (input.sdk >= GameResolution.MIN_SDK) CheckStatus.SUPPORTED else CheckStatus.UNSUPPORTED,
        "Android ${input.androidRelease}, SDK ${input.sdk}" +
            if (input.sdk >= GameResolution.MIN_SDK) "" else "; game mode levers need SDK ${GameResolution.MIN_SDK}"
    )

    private fun shizukuRow(input: DiagnosticsInput) = when {
        !input.shizukuRunning -> DiagnosticRow("Shizuku", CheckStatus.UNSUPPORTED, "Not running or not reachable")
        !input.shizukuPermission -> DiagnosticRow("Shizuku", CheckStatus.UNSUPPORTED, "Running, permission not granted")
        else -> DiagnosticRow("Shizuku", CheckStatus.SUPPORTED, "Running, permission granted")
    }

    /** The reason a game row could not be checked, or null when it could. */
    private fun notChecked(input: DiagnosticsInput): String? = when {
        input.gamePackage == null -> "No game selected"
        !input.shellReady -> "Shizuku not connected"
        else -> null
    }

    private fun cmdGameRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "cmd game for ${input.gamePackage ?: "the selected game"}"
        notChecked(input)?.let { return DiagnosticRow(name, CheckStatus.NOT_CHECKED, it) }
        val read = input.read ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Not read")
        val state = read.state
        return when {
            state != null -> DiagnosticRow(name, CheckStatus.SUPPORTED, "list-modes: ${state.mode}, available [${state.available.joinToString(",")}]")
            read.unavailable -> DiagnosticRow(name, CheckStatus.UNSUPPORTED, read.reason ?: "Unavailable")
            else -> DiagnosticRow(name, CheckStatus.UNVERIFIED, read.reason ?: "Could not read the game mode")
        }
    }

    private fun installedRow(input: DiagnosticsInput): DiagnosticRow {
        val pkg = input.gamePackage ?: return DiagnosticRow("Game installed", CheckStatus.NOT_CHECKED, "No game selected")
        val version = input.gameVersion ?: return DiagnosticRow("Game installed", CheckStatus.UNSUPPORTED, "$pkg is not installed")
        return DiagnosticRow("Game installed", CheckStatus.SUPPORTED, pkg + version.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())
    }

    private fun configText(config: InterventionConfig?): String =
        config?.let { "Scaling ${it.scaling ?: "none"}, Fps ${it.fps ?: "none"}" } ?: "none"

    private fun modeRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "Game mode and custom config"
        notChecked(input)?.let { return DiagnosticRow(name, CheckStatus.NOT_CHECKED, it) }
        val state = input.read?.state
            ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, input.read?.reason ?: "Not read")
        return DiagnosticRow(name, CheckStatus.SUPPORTED, "Mode ${state.mode}; custom ${configText(state.custom)}")
    }

    private fun downscaleRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "Downscale lever"
        notChecked(input)?.let { return DiagnosticRow(name, CheckStatus.NOT_CHECKED, it) }
        val status = input.status ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Card not read yet")
        val level = status.level
        val held = level?.let { "${it.label} ${it.scale}" }
        return when {
            status.unavailableReason != null -> DiagnosticRow(name, CheckStatus.UNSUPPORTED, status.unavailableReason)
            status.changedOutside != null -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "Changed outside Turbo: now ${status.changedOutside}")
            status.resetByRestart -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "Reset by a phone restart; set again at the next launch from Turbo")
            held == null -> DiagnosticRow(name, CheckStatus.NOT_CHECKED, "No preset held")
            status.pendingRestart -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "$held read back; set while the game ran, applies at its next launch")
            status.result == null -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "$held read back; render buffer not measured yet")
            status.result.startsWith("No effect") -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "$held read back; ${status.result}")
            // Only a comparison with the native size shows a reduction ("... (−32% pixels)").
            !status.result.contains("pixels)") ->
                DiagnosticRow(name, CheckStatus.UNVERIFIED, "$held read back; ${status.result}; no native size to compare with")
            else -> DiagnosticRow(name, CheckStatus.SUPPORTED, "$held read back; last measured buffer: ${status.result}")
        }
    }

    private fun fpsCapRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "FPS cap"
        val offered = FpsCap.entries.joinToString(", ") { it.label }
        if (!input.shellReady) return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Shizuku not connected")
        val help = parseGameHelp(input.helpOutput)
            ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "cmd game help unreadable: ${excerpt(input.helpOutput)}")
        val fpsLine = help.fpsLine
            ?: return DiagnosticRow(name, CheckStatus.UNSUPPORTED, "cmd game help lists no --fps: ${excerpt(input.helpOutput)}")
        if (input.gamePackage == null) return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Help: \"$fpsLine\"; no game selected")
        val status = input.status ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Help: \"$fpsLine\"; card not read yet")
        status.unavailableReason?.let { return DiagnosticRow(name, CheckStatus.UNSUPPORTED, it) }
        val cap = status.cap ?: return DiagnosticRow(
            name,
            CheckStatus.NOT_CHECKED,
            input.lastChangeNote?.takeIf { it.contains("FPS cap") } ?: "Help: \"$fpsLine\"; offers $offered; no cap set"
        )
        return when {
            status.changedOutside != null -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "Cap ${cap.label} changed outside Turbo: now ${status.changedOutside}")
            status.resetByRestart -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "Cap ${cap.label} reset by a phone restart; set again at the next launch from Turbo")
            else -> capVerdict(cap.fps, input.capSession?.avgFps, input.uncappedSession?.avgFps).let { (verdict, why) ->
                DiagnosticRow(name, verdict, "Fps ${cap.label} read back; $why")
            }
        }
    }

    /**
     * Whether measured sessions show the cap working. Supported needs both: a session at the cap
     * averaging 0.85 to 1.1 times the cap, and one without it (same game, same render
     * resolution) averaging at least 1.2 times the cap. Anything else is Unverified, and the
     * evidence always names both averages.
     */
    fun capVerdict(cap: Int, capped: Float?, uncapped: Float?): Pair<CheckStatus, String> {
        val averages = "at the cap ${capped?.let { "${fps(it)} FPS" } ?: "not measured yet"}, " +
            (uncapped?.let { "uncapped ${fps(it)} FPS" } ?: "no uncapped session")
        val why = when {
            capped == null || uncapped == null -> null
            capped > cap * CAP_TOLERANCE -> "the game ignored the cap"
            capped < cap * CAP_FLOOR -> "the game ran below the cap on its own"
            uncapped < cap * UNCAPPED_MIN -> "the game runs near the cap without it too, so this does not show the cap working"
            else -> return CheckStatus.SUPPORTED to averages
        }
        return CheckStatus.UNVERIFIED to (why?.let { "$averages: $it" } ?: averages)
    }

    private fun thermalRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "Thermal status"
        if (!input.shellReady) return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "Shizuku not connected")
        val dump = input.thermalDump
        val status = Diagnostics.parseThermalStatus(dump)
        return when {
            dump.isNullOrBlank() -> DiagnosticRow(name, CheckStatus.UNSUPPORTED, "dumpsys thermalservice returned nothing")
            Diagnostics.thermalOverridden(dump) -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "A status override is active, so the value is forced")
            status != null -> DiagnosticRow(name, CheckStatus.SUPPORTED, "Thermal Status: $status")
            else -> DiagnosticRow(name, CheckStatus.UNSUPPORTED, "No \"Thermal Status\" line: ${excerpt(dump)}")
        }
    }

    /** Whether the overlay can show a CPU figure: `/proc/stat` must be readable and parse. */
    private fun cpuRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "Overlay CPU reading"
        return if (CpuUsage.parse(input.cpuStat) != null) {
            DiagnosticRow(name, CheckStatus.SUPPORTED, "/proc/stat read by ${input.cpuStatVia ?: "the app"}, cpu line parsed")
        } else {
            val why = if (!input.shellReady) {
                "not readable by the app, and Shizuku is not connected"
            } else {
                "unreadable or not a cpu line: ${excerpt(input.cpuStat)}"
            }
            DiagnosticRow(name, CheckStatus.UNSUPPORTED, "/proc/stat $why; the overlay shows N/A")
        }
    }

    private fun restrictionRow(input: DiagnosticsInput): DiagnosticRow {
        val name = "Background restriction"
        val ran = input.restrictIgnored
            ?: return DiagnosticRow(name, CheckStatus.NOT_CHECKED, "No restriction since the app started")
        val ignored = ran.filterValues { notes -> notes.any { it.startsWith("bucket ignored") } }
        val list = ignored.entries.joinToString("; ") { (pkg, notes) -> "$pkg ${notes.first { it.startsWith("bucket ignored") }}" }
        return when {
            ran.isEmpty() -> DiagnosticRow(name, CheckStatus.NOT_CHECKED, "The last session restricted no packages")
            ignored.isEmpty() -> DiagnosticRow(name, CheckStatus.SUPPORTED, "Every bucket change read back (${ran.size} packages)")
            ignored.size == ran.size -> DiagnosticRow(name, CheckStatus.UNSUPPORTED, "Bucket ignored for all: $list")
            else -> DiagnosticRow(name, CheckStatus.UNVERIFIED, "Bucket ignored for ${ignored.size} of ${ran.size}: $list")
        }
    }

    private fun fps(v: Float) = String.format(Locale.US, "%.1f", v)

    /** The first line of [text], short enough for one row. */
    private fun excerpt(text: String?): String =
        text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(80) ?: "no output"

    /** The panel as plain text, for pasting into a chat or an issue. */
    fun report(rows: List<DiagnosticRow>, appVersion: String, checkedAt: String): String = buildString {
        appendLine("Turbo diagnostics, app $appVersion, checked $checkedAt")
        rows.forEach { appendLine("- ${it.name}: ${it.status.label} - ${it.evidence}") }
    }.trimEnd()
}
