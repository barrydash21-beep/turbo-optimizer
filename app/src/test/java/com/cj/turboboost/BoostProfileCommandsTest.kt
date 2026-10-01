package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each profile actually sends to the shell. */
class BoostProfileCommandsTest {

    private val realShell = RamCleaner.shell
    private val realSync = RamCleaner.syncSettings

    @After
    fun restoreGlobals() {
        RamCleaner.shell = realShell
        RamCleaner.syncSettings = realSync
    }

    private val game = "com.example.game"

    private fun boost(
        profile: BoostProfile,
        gamePackage: String = game,
        sync: FakeSyncSettings = FakeSyncSettings()
    ): Pair<FakeShell, StepReport> {
        val shell = FakeShell()
        RamCleaner.shell = shell
        RamCleaner.syncSettings = sync
        val report = RamCleaner.applyProfile(profile, gamePackage, FakeStore())
        return shell to report
    }

    // -----------------------------------------------------------------------
    // H5: the default profile must not silence the phone
    // -----------------------------------------------------------------------

    @Test
    fun `BALANCED does not enable Do Not Disturb`() {
        val sync = FakeSyncSettings(enabled = true)
        val (shell, _) = boost(BoostProfile.BALANCED, sync = sync)

        assertFalse(shell.ran("settings put global zen_mode"))
        assertFalse(shell.ran("cmd notification set_dnd"))
        assertTrue("BALANCED must leave master auto-sync alone", sync.enabled)
    }

    @Test
    fun `SAVER does not enable Do Not Disturb`() {
        val sync = FakeSyncSettings(enabled = true)
        val (shell, _) = boost(BoostProfile.POWER_SAVING, sync = sync)

        assertFalse(shell.ran("settings put global zen_mode"))
        assertFalse(shell.ran("cmd notification set_dnd"))
        assertTrue(sync.enabled)
    }

    @Test
    fun `TURBO enables Do Not Disturb and suspends auto-sync`() {
        val sync = FakeSyncSettings(enabled = true)
        val (shell, _) = boost(BoostProfile.PERFORMANCE, sync = sync)

        assertTrue(shell.commands.contains("settings put global zen_mode 1"))
        assertTrue(shell.commands.contains("cmd notification set_dnd priority"))
        assertFalse("TURBO suspends master auto-sync", sync.enabled)
    }

    @Test
    fun `no profile touches thermal throttling`() {
        for (profile in BoostProfile.entries) {
            val (shell, _) = boost(profile)
            assertFalse(profile.name, shell.ran("cmd thermalservice"))
            assertFalse(profile.name, shell.ran("setprop ctl."))
            assertFalse(profile.name, shell.commands.any { it.contains("thermal") })
        }
    }

    // -----------------------------------------------------------------------
    // Refresh rate belongs to PerformanceTuner alone
    // -----------------------------------------------------------------------

    @Test
    fun `no profile writes or deletes a refresh-rate key`() {
        for (profile in BoostProfile.entries) {
            val (shell, _) = boost(profile)
            assertFalse(shell.commands.any { it.contains("refresh_rate") })
        }
    }

    @Test
    fun `game mode targets the selected game only`() {
        val (shell, _) = boost(BoostProfile.PERFORMANCE, gamePackage = "com.example.othergame")
        assertTrue(shell.commands.contains("cmd game mode performance com.example.othergame"))
        assertFalse(shell.ran("cmd game mode performance com.example.game"))
    }

    // -----------------------------------------------------------------------
    // T1: no user-controlled data reaches the shell
    // -----------------------------------------------------------------------

    @Test
    fun `no command contains shell metacharacters`() {
        val injectionChars = listOf(";", "&", "|", "`", "$(", "\n", "\r", "\"", "'", "\\")
        for (profile in BoostProfile.entries) {
            val (shell, _) = boost(profile)
            for (command in shell.commands) {
                for (bad in injectionChars) {
                    assertFalse(
                        "command \"$command\" contains ${bad.trim().ifEmpty { "whitespace" }}",
                        command.contains(bad)
                    )
                }
            }
        }
    }

    @Test
    fun `every package named in a command comes from AppPackages`() {
        val known = (listOf(game) + AppPackages.BACKGROUND_HOGS + AppPackages.VPN_BYPASS_BASE).toSet()
        // A package name is the only dotted token the commands ever carry, apart from the
        // settings keys.
        val dotted = Regex("""\b[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,}\b""")
        for (profile in BoostProfile.entries) {
            val (shell, _) = boost(profile)
            for (command in shell.commands) {
                for (match in dotted.findAll(command)) {
                    val token = match.value
                    if (token.startsWith("init.svc")) continue // getprop key, not a package
                    assertTrue("unknown package \"$token\" in \"$command\"", token in known)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // M2: results are real
    // -----------------------------------------------------------------------

    @Test
    fun `a clean run reports every step applied and nothing failed`() {
        val (_, report) = boost(BoostProfile.BALANCED)
        assertTrue(report.allOk)
        assertTrue(report.failed.isEmpty())
        assertTrue(report.applied.isNotEmpty())
        assertEquals("Boosted", report.summary("Boosted"))
    }

    @Test
    fun `a failing step is named in the report and the summary`() {
        RamCleaner.shell = FakeShell(failingPrefixes = setOf("cmd game mode"))
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.applyProfile(BoostProfile.BALANCED, game, FakeStore())

        assertFalse(report.allOk)
        assertTrue(report.failed.contains("Game mode"))
        assertTrue(report.summary("Boosted").contains("Game mode"))
    }

    @Test
    fun `an unavailable binder reports unavailable and runs nothing`() {
        val shell = FakeShell(available = false)
        RamCleaner.shell = shell
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.applyProfile(BoostProfile.PERFORMANCE, game, FakeStore())

        assertTrue(report.unavailable)
        assertFalse(report.allOk)
        assertTrue(shell.commands.isEmpty())
        assertEquals("Shizuku not connected", report.summary("Boosted"))
    }

    @Test
    fun `a missing Shizuku API is reported distinctly from a command failure`() {
        RamCleaner.shell = FakeShell(apiUnsupported = true)
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.applyProfile(BoostProfile.BALANCED, game, FakeStore())

        assertTrue(report.apiUnsupported)
        assertEquals("Shizuku API not supported", report.summary("Boosted"))
    }
}
