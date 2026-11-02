package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsPanelTest {

    /** `cmd game help` as printed on a Pova 7 (Android 16), trimmed to the parts the panel reads. */
    private val help = """
        Game manager (game) commands:
          help
              Print this help text.
          set [intervention configs] <PACKAGE_NAME>
              Set app to run at custom mode using provided intervention configs
              Intervention configs consists of:
              --downscale [0.3|0.35|0.4|0.45|0.5|0.55|0.6|0.65
                          |0.7|0.75|0.8|0.85|0.9|disable]: Set app to run at the
                                                           specified scaling ratio.
              --fps: Integer value to set app to run at the specified fps,
                     if supported. 0 to disable.
    """.trimIndent()

    private val state = GameModeState(
        "custom",
        listOf("standard", "performance", "custom"),
        mapOf(2 to InterventionConfig("0.8", "90"), 4 to InterventionConfig("0.55", "40"))
    )

    private fun session(avg: Float, cap: Int?) =
        SessionRecord(FAKE_GAME, 90f, 1L, 300_000L, avg, null, null, 0, 10, 10, 10, renderResolution = "LOWER 0.55", fpsCap = cap)

    private fun input(
        cap: FpsCap = FpsCap.FPS_40,
        status: GameResolutionStatus? = GameResolutionStatus(level = ResolutionLevel.LOWER, cap = cap, state = state),
        capAvg: Float? = null,
        uncappedAvg: Float? = null,
        helpOutput: String? = help,
        shellReady: Boolean = true,
        thermal: String? = "Thermal Status: 0",
        ignored: Map<String, List<String>>? = null
    ) = DiagnosticsInput(
        androidRelease = "16", sdk = 36, manufacturer = "TECNO", model = "TECNO LJ6", buildDisplay = "LJ6-16.3.0",
        shizukuRunning = shellReady, shizukuPermission = shellReady, shellReady = shellReady,
        gamePackage = FAKE_GAME, gameVersion = "1.0",
        helpOutput = helpOutput,
        read = GameResolution.Read(state),
        status = status,
        lastChangeNote = null,
        capSession = capAvg?.let { session(it, cap.fps) },
        uncappedSession = uncappedAvg?.let { session(it, null) },
        thermalDump = thermal,
        restrictIgnored = ignored
    )

    private fun row(rows: List<DiagnosticRow>, name: String) = rows.first { it.name.startsWith(name) }

    @Before
    fun clearCache() = DiagnosticsPanel.clearHelpCache()

    @Test
    fun `help lists the downscale values and the fps option`() {
        val parsed = DiagnosticsPanel.parseGameHelp(help)!!
        assertEquals(listOf("0.3", "0.35", "0.4", "0.45", "0.5", "0.55", "0.6", "0.65", "0.7", "0.75", "0.8", "0.85", "0.9", "disable"), parsed.downscaleValues)
        assertTrue(parsed.fpsLine!!.startsWith("--fps: Integer value"))
        assertNull(DiagnosticsPanel.parseGameHelp("Unknown command: help"))
        assertNull(DiagnosticsPanel.parseGameHelp(null))
        assertNull(DiagnosticsPanel.parseGameHelp(help.replace("Game manager", "Something")))
    }

    @Test
    fun `help is read once per process, even though cmd game help exits 255`() {
        val shell = FakeShell(captures = mapOf("cmd game help 2>&1; echo \"__exit:\$?\"" to "$help\n__exit:255"))
        assertNotNull(DiagnosticsPanel.gameHelp(shell).second)
        assertNotNull(DiagnosticsPanel.gameHelp(shell).second)
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun `unreadable help is not cached`() {
        val shell = FakeShell()
        assertNull(DiagnosticsPanel.gameHelp(shell).second)
        assertNull(DiagnosticsPanel.gameHelp(shell).second)
        assertEquals(2, shell.commands.size)
    }

    @Test
    fun `every row has a status and evidence`() {
        val rows = DiagnosticsPanel.rows(input())
        assertEquals(11, rows.size)
        assertTrue(rows.all { it.evidence.isNotBlank() })
        assertEquals(CheckStatus.SUPPORTED, row(rows, "Android").status)
        assertEquals("Android 16, SDK 36", row(rows, "Android").evidence)
        assertEquals(CheckStatus.SUPPORTED, row(rows, "cmd game").status)
        assertEquals("Mode custom; custom Scaling 0.55, Fps 40", row(rows, "Game mode").evidence)
    }

    @Test
    fun `lobby case - capped and uncapped both near the cap is unverified`() {
        // Measured on the test device: a lobby that runs at about 30 with or without the cap.
        val lobby = row(DiagnosticsPanel.rows(input(cap = FpsCap.FPS_30, capAvg = 28f, uncappedAvg = 30f)), "FPS cap")
        assertEquals(CheckStatus.UNVERIFIED, lobby.status)
        assertTrue(lobby.evidence, lobby.evidence.contains("at the cap 28.0 FPS, uncapped 30.0 FPS"))
    }

    @Test
    fun `honoured case - near the cap with it, well above without it, is supported`() {
        val honoured = row(DiagnosticsPanel.rows(input(capAvg = 39f, uncappedAvg = 55f)), "FPS cap")
        assertEquals(CheckStatus.SUPPORTED, honoured.status)
        assertEquals("Fps 40 read back; at the cap 39.0 FPS, uncapped 55.0 FPS", honoured.evidence)
    }

    @Test
    fun `ignored case - the same rate with and without the cap is unverified, and says so`() {
        val ignored = row(DiagnosticsPanel.rows(input(capAvg = 55f, uncappedAvg = 55f)), "FPS cap")
        assertEquals(CheckStatus.UNVERIFIED, ignored.status)
        assertTrue(ignored.evidence, ignored.evidence.contains("at the cap 55.0 FPS, uncapped 55.0 FPS"))
        assertTrue(ignored.evidence, ignored.evidence.contains("the game ignored the cap"))
    }

    @Test
    fun `one session alone is never enough, and the evidence says what is missing`() {
        val noUncapped = row(DiagnosticsPanel.rows(input(capAvg = 39f)), "FPS cap")
        assertEquals(CheckStatus.UNVERIFIED, noUncapped.status)
        assertTrue(noUncapped.evidence, noUncapped.evidence.contains("at the cap 39.0 FPS, no uncapped session"))

        val noCapped = row(DiagnosticsPanel.rows(input(uncappedAvg = 55f)), "FPS cap")
        assertEquals(CheckStatus.UNVERIFIED, noCapped.status)
        assertTrue(noCapped.evidence, noCapped.evidence.contains("at the cap not measured yet, uncapped 55.0 FPS"))

        val neither = row(DiagnosticsPanel.rows(input()), "FPS cap")
        assertEquals(CheckStatus.UNVERIFIED, neither.status)
        assertTrue(neither.evidence, neither.evidence.contains("no uncapped session"))
    }

    @Test
    fun `the band edges are 0_85 and 1_1 times the cap, and 1_2 times uncapped`() {
        assertEquals(CheckStatus.SUPPORTED, DiagnosticsPanel.capVerdict(40, 34.1f, 48.1f).first)
        assertEquals(CheckStatus.SUPPORTED, DiagnosticsPanel.capVerdict(40, 43.9f, 48.1f).first)
        assertEquals(CheckStatus.UNVERIFIED, DiagnosticsPanel.capVerdict(40, 33.9f, 60f).first)
        assertEquals(CheckStatus.UNVERIFIED, DiagnosticsPanel.capVerdict(40, 44.1f, 60f).first)
        assertEquals(CheckStatus.UNVERIFIED, DiagnosticsPanel.capVerdict(40, 39f, 47.9f).first)
    }

    @Test
    fun `fps cap without --fps in help is unsupported, with the help as evidence`() {
        val noFps = help.lines().filterNot { it.contains("--fps") || it.contains("0 to disable") }.joinToString("\n")
        val result = row(DiagnosticsPanel.rows(input(helpOutput = noFps)), "FPS cap")
        assertEquals(CheckStatus.UNSUPPORTED, result.status)
        assertTrue(result.evidence.contains("Game manager"))
    }

    @Test
    fun `without a cap held the fps row is not checked, and lists what is offered`() {
        val result = row(DiagnosticsPanel.rows(input(status = GameResolutionStatus(state = state))), "FPS cap")
        assertEquals(CheckStatus.NOT_CHECKED, result.status)
        assertTrue(result.evidence, result.evidence.contains("offers 45, 40, 30"))
    }

    @Test
    fun `downscale is supported only with a measured buffer`() {
        val measured = GameResolutionStatus(level = ResolutionLevel.LOWER, result = "Renders at 594x1353 (−32% pixels)")
        assertEquals(CheckStatus.SUPPORTED, row(DiagnosticsPanel.rows(input(status = measured)), "Downscale").status)
        assertEquals(CheckStatus.UNVERIFIED, row(DiagnosticsPanel.rows(input(status = measured.copy(result = null))), "Downscale").status)
        // A size with no native one to compare: measured, but no reduction shown.
        val noNative = row(DiagnosticsPanel.rows(input(status = measured.copy(result = "Renders at 594x1353"))), "Downscale")
        assertEquals(CheckStatus.UNVERIFIED, noNative.status)
        assertTrue(noNative.evidence, noNative.evidence.contains("no native size"))
        val noEffect = measured.copy(result = "No effect: game already renders below this")
        assertEquals(CheckStatus.UNVERIFIED, row(DiagnosticsPanel.rows(input(status = noEffect)), "Downscale").status)
        val outside = measured.copy(changedOutside = "standard")
        assertTrue(row(DiagnosticsPanel.rows(input(status = outside)), "Downscale").evidence.contains("now standard"))
        val unavailable = GameResolutionStatus(unavailableReason = "Android doesn't list this app as a game, so it can't be downscaled.")
        assertEquals(CheckStatus.UNSUPPORTED, row(DiagnosticsPanel.rows(input(status = unavailable)), "Downscale").status)
    }

    @Test
    fun `thermal row follows the dump`() {
        assertEquals(CheckStatus.SUPPORTED, row(DiagnosticsPanel.rows(input()), "Thermal").status)
        assertEquals(CheckStatus.UNVERIFIED, row(DiagnosticsPanel.rows(input(thermal = "IsStatusOverride: true\nThermal Status: 3")), "Thermal").status)
        assertEquals(CheckStatus.UNSUPPORTED, row(DiagnosticsPanel.rows(input(thermal = "")), "Thermal").status)
    }

    @Test
    fun `background restriction lists the packages whose bucket change was ignored`() {
        assertEquals(CheckStatus.NOT_CHECKED, row(DiagnosticsPanel.rows(input()), "Background").status)
        val some = mapOf("com.example.social" to listOf("bucket ignored (stays 5)"), "com.example.chat" to emptyList())
        val partly = row(DiagnosticsPanel.rows(input(ignored = some)), "Background")
        assertEquals(CheckStatus.UNVERIFIED, partly.status)
        assertEquals("Bucket ignored for 1 of 2: com.example.social bucket ignored (stays 5)", partly.evidence)
        val none = row(DiagnosticsPanel.rows(input(ignored = mapOf("com.example.chat" to emptyList()))), "Background")
        assertEquals(CheckStatus.SUPPORTED, none.status)
    }

    @Test
    fun `without Shizuku the shell rows are not checked`() {
        val rows = DiagnosticsPanel.rows(input(shellReady = false, helpOutput = null, thermal = null))
        assertEquals(CheckStatus.UNSUPPORTED, row(rows, "Shizuku").status)
        listOf("cmd game", "Game mode", "Downscale", "FPS cap", "Thermal").forEach {
            assertEquals(it, CheckStatus.NOT_CHECKED, row(rows, it).status)
        }
    }

    @Test
    fun `the report is plain text, one line per row`() {
        val rows = DiagnosticsPanel.rows(input(capAvg = 39.8f, uncappedAvg = 58.1f))
        val text = DiagnosticsPanel.report(rows, "1.2.4", "2026-10-23 12:00:00")
        val lines = text.lines()
        assertEquals("Turbo diagnostics, app 1.2.4, checked 2026-10-23 12:00:00", lines.first())
        assertEquals(rows.size + 1, lines.size)
        assertTrue(lines.contains("- FPS cap: Supported - Fps 40 read back; at the cap 39.8 FPS, uncapped 58.1 FPS"))
        assertFalse(text.contains('\t'))
    }
}
