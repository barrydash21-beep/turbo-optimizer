package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A shell that behaves like `settings` and `cmd power`, so apply/revert can be round-tripped. */
private class SettingsShell(
    initial: Map<String, String> = emptyMap(),
    /** Simulates HiOS ignoring writes to these keys. */
    private val ignoredKeys: Set<String> = emptySet(),
    private val fixedModeOutput: String = "exit=0"
) : ShellExecutor {

    val system = initial.toMutableMap()
    val commands = mutableListOf<String>()

    override fun isAvailable() = true

    override fun run(command: String): ShellResult {
        commands += command
        val parts = command.split(" ")
        if (parts.size >= 4 && parts[0] == "settings" && parts[2] == "system") {
            val key = parts[3]
            when (parts[1]) {
                "put" -> if (key !in ignoredKeys) system[key] = parts[4]
                "delete" -> system.remove(key)
            }
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        val parts = command.split(" ")
        return when {
            command.startsWith("settings get system") -> system[parts[3]] ?: "null"
            command.startsWith("cmd power") -> fixedModeOutput
            else -> null
        }
    }
}

class PerformanceTunerTest {

    private val realShell = RamCleaner.shell

    @Before
    fun setUp() {
        // Nothing to do; each test installs its own shell.
    }

    @After
    fun tearDown() {
        RamCleaner.shell = realShell
    }

    @Test
    fun `apply saves the originals, locks 60Hz and revert puts the exact values back`() {
        val shell = SettingsShell(mapOf("min_refresh_rate" to "90.0", "peak_refresh_rate" to "144.0"))
        RamCleaner.shell = shell
        val store = FakeStore()

        val report = PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)

        assertTrue(report.ok)
        assertTrue(PerformanceTuner.isActive(store))
        assertEquals("60.0", shell.system["peak_refresh_rate"])

        assertTrue(PerformanceTuner.revertGamingTweaks(store).ok)
        assertEquals("90.0", shell.system["min_refresh_rate"])
        assertEquals("144.0", shell.system["peak_refresh_rate"])
        assertFalse(PerformanceTuner.isActive(store))
        assertTrue(shell.commands.contains("cmd power set-fixed-performance-mode-enabled false 2>&1; echo exit=\$?"))
    }

    @Test
    fun `both keys are pinned to the chosen rate and verified by read-back`() {
        val shell = SettingsShell(mapOf("min_refresh_rate" to "10.0", "peak_refresh_rate" to "120.0"))
        RamCleaner.shell = shell
        val store = FakeStore()

        val report = PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.othergame"), 90f)

        assertTrue(report.ok)
        assertTrue(shell.commands.contains("settings put system min_refresh_rate 90.0"))
        assertTrue(shell.commands.contains("settings put system peak_refresh_rate 90.0"))
        assertEquals(mapOf("min_refresh_rate" to "90.0", "peak_refresh_rate" to "90.0"), report.readBack)

        PerformanceTuner.revertGamingTweaks(store)
        assertEquals("10.0", shell.system["min_refresh_rate"])
        assertEquals("120.0", shell.system["peak_refresh_rate"])
    }

    @Test
    fun `HiOS holding min at 10 is reported for that key alone, and peak still applies`() {
        // What the Pova 7 does: min_refresh_rate writes exit 0 but stay 10.0.
        val shell = SettingsShell(
            mapOf("min_refresh_rate" to "10.0", "peak_refresh_rate" to "120.0"),
            ignoredKeys = setOf("min_refresh_rate")
        )
        RamCleaner.shell = shell

        val report = PerformanceTuner.applyGamingTweaks(FakeStore(), GameRegistry.profileFor("com.example.othergame"), 90f)

        assertEquals(listOf("min_refresh_rate ignored (stays 10.0)"), report.failed)
        assertEquals("90.0", shell.system["peak_refresh_rate"])
    }

    @Test
    fun `fixed performance mode only runs when the profile asks for it`() {
        val shell = SettingsShell()
        RamCleaner.shell = shell

        val report = PerformanceTuner.applyGamingTweaks(
            FakeStore(),
            GameRegistry.profileFor("com.example.othergame").copy(useFixedPerformanceMode = false),
            60f
        )

        assertFalse(shell.commands.any { it.contains("set-fixed-performance-mode-enabled true") })
        assertNull(report.fixedModeOutput)
    }

    @Test
    fun `the fixed mode command captures stderr and the exit code`() {
        val shell = SettingsShell(fixedModeOutput = "exit=0")
        RamCleaner.shell = shell

        val report = PerformanceTuner.applyGamingTweaks(FakeStore(), GameRegistry.profileFor("com.example.game"), 60f)

        assertTrue(shell.commands.contains("cmd power set-fixed-performance-mode-enabled true 2>&1; echo exit=\$?"))
        assertEquals("exit=0", report.fixedModeOutput)
        assertTrue(report.applied.contains("Fixed performance mode"))
    }

    @Test
    fun `a key that was unset is deleted on revert, not written`() {
        val shell = SettingsShell(mapOf("peak_refresh_rate" to "144.0"))
        RamCleaner.shell = shell
        val store = FakeStore()

        PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)
        PerformanceTuner.revertGamingTweaks(store)

        assertNull(shell.system["min_refresh_rate"])
        assertTrue(shell.commands.contains("settings delete system min_refresh_rate"))
        assertFalse(shell.commands.any { it.startsWith("settings put system min_refresh_rate") && it.endsWith("null") })
    }

    @Test
    fun `a second apply does not overwrite the saved originals with 60`() {
        val shell = SettingsShell(mapOf("peak_refresh_rate" to "144.0"))
        RamCleaner.shell = shell
        val store = FakeStore()

        PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)
        PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)
        PerformanceTuner.revertGamingTweaks(store)

        assertEquals("144.0", shell.system["peak_refresh_rate"])
    }

    @Test
    fun `originals captured before another step changes them survive`() {
        val shell = SettingsShell(mapOf("peak_refresh_rate" to "144.0"))
        RamCleaner.shell = shell
        val store = FakeStore()

        PerformanceTuner.captureOriginals(store)
        shell.system["peak_refresh_rate"] = "120.0" // e.g. the profile's own refresh-rate pin
        PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)
        PerformanceTuner.revertGamingTweaks(store)

        assertEquals("144.0", shell.system["peak_refresh_rate"])
    }

    @Test
    fun `keys the system ignores are reported and the flag still gets cleared on revert`() {
        val shell = SettingsShell(
            mapOf("peak_refresh_rate" to "144.0"),
            ignoredKeys = setOf("min_refresh_rate", "peak_refresh_rate")
        )
        RamCleaner.shell = shell
        val store = FakeStore()

        val report = PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f)

        assertFalse(report.ok)
        assertTrue(report.failed.contains("min_refresh_rate ignored (stays null)"))
        assertTrue(report.failed.contains("peak_refresh_rate ignored (stays 144.0)"))
        assertTrue(PerformanceTuner.revertGamingTweaks(store).ok)
        assertFalse(PerformanceTuner.isActive(store))
    }

    @Test
    fun `unsupported fixed performance mode is reported but does not stop the rest`() {
        val shell = SettingsShell(
            mapOf("peak_refresh_rate" to "144.0"),
            fixedModeOutput = "Unknown command: set-fixed-performance-mode-enabled\nexit=0"
        )
        RamCleaner.shell = shell

        val report = PerformanceTuner.applyGamingTweaks(FakeStore(), GameRegistry.profileFor("com.example.game"), 60f)

        assertTrue(report.failed.contains("Fixed performance mode"))
        assertTrue(report.applied.contains("Refresh rate (peak_refresh_rate)"))
        assertEquals("60.0", shell.system["peak_refresh_rate"])
    }

    @Test
    fun `nothing is touched when Shizuku is unavailable`() {
        val shell = FakeShell(available = false)
        RamCleaner.shell = shell
        val store = FakeStore()

        assertTrue(PerformanceTuner.applyGamingTweaks(store, GameRegistry.profileFor("com.example.game"), 60f).unavailable)
        assertTrue(shell.commands.isEmpty())
        assertFalse(PerformanceTuner.isActive(store))
    }

    @Test
    fun `a revert that cannot run leaves the flag set for the next start`() {
        val store = FakeStore().apply { putBoolean("tweaks_active", true) }
        RamCleaner.shell = FakeShell(available = false)

        assertTrue(PerformanceTuner.revertGamingTweaks(store).unavailable)
        assertTrue(PerformanceTuner.isActive(store))
    }
}

class BackgroundKillTargetsTest {

    private val selected = "com.example.game"
    private val protectedSet = AppPackages.PROTECTED.toSet() + selected + "com.miui.home" + "com.google.android.inputmethod.latin"

    private fun select(running: Set<String>, thirdParty: Set<String>, bloat: Set<String> = emptySet()) =
        RamCleaner.selectKillTargets(running, thirdParty, bloat, protectedSet)

    @Test
    fun `stops running third-party apps and running bloat only`() {
        val result = select(
            running = setOf("com.facebook.katana", "com.transsion.phoenix", "com.notrunning.no", "android.system"),
            thirdParty = setOf("com.facebook.katana", "com.idle.app"),
            bloat = setOf("com.transsion.phoenix", "com.transsion.absent")
        )
        assertEquals(listOf("com.facebook.katana", "com.transsion.phoenix"), result)
    }

    @Test
    fun `never stops this app, the selected game, Shizuku, the launcher or the keyboard`() {
        val result = select(running = protectedSet, thirdParty = protectedSet, bloat = protectedSet)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the user's other games are stopped like any other app`() {
        val games = setOf("com.example.game", "com.example.othergame", "com.example.thirdgame")
        val result = select(running = games, thirdParty = games)
        assertEquals(listOf("com.example.othergame", "com.example.thirdgame").sorted(), result)
    }

    @Test
    fun `protects by name fragment`() {
        val names = setOf(
            "com.android.systemui", "com.android.phone", "com.foo.telephony.x",
            "com.android.bluetooth", "com.foo.inputmethod.bar", "com.ok.app"
        )
        assertEquals(listOf("com.ok.app"), select(running = names, thirdParty = names))
    }

    @Test
    fun `parses pm, ps and component output`() {
        assertEquals(setOf("com.a", "com.b"), RamCleaner.parsePackageList("package:com.a\r\npackage:com.b\n"))
        assertNull(RamCleaner.parsePackageList(""))
        assertNull(RamCleaner.parsePackageList(null))

        val ps = "NAME\ninit\n[kworker/0:1]\ncom.foo\ncom.foo:remote\ncom.bar\n"
        assertEquals(setOf("com.foo", "com.bar"), RamCleaner.parseProcessNames(ps))

        assertEquals(setOf("com.a.sms"), RamCleaner.parseRoleHolders("com.a.sms\n"))
        assertEquals(setOf("com.a", "com.b"), RamCleaner.parseRoleHolders("com.a;com.b"))
        assertTrue(RamCleaner.parseRoleHolders("").isEmpty())
        assertTrue(RamCleaner.parseRoleHolders(null).isEmpty())

        assertEquals("com.miui.home", RamCleaner.parseComponentPackage("com.miui.home/.launcher.Launcher"))
        assertNull(RamCleaner.parseComponentPackage("null"))
        assertNull(RamCleaner.parseComponentPackage(""))
    }

    @Test
    fun `runtime kill stops resolved targets, sweeps, and counts them`() {
        val shell = FakeShell(
            captures = mapOf(
                "pm list packages -3" to "package:com.facebook.katana\npackage:com.launcher.third\npackage:com.kb.third\n" +
                    "package:com.example.othergame\npackage:com.example.thirdgame\n" +
                    "package:com.google.android.apps.messaging\npackage:com.sh.smart.caller",
                "ps -A -o NAME" to "NAME\ncom.facebook.katana\ncom.launcher.third\ncom.kb.third\ncom.transsion.trancare\n" +
                    "com.example.othergame\ncom.example.thirdgame:remote\ncom.google.android.apps.messaging\ncom.sh.smart.caller",
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" to
                    "priority=0 preferredOrder=0\ncom.launcher.third/.Home",
                "settings get secure default_input_method" to "com.kb.third/.Ime",
                "cmd role get-role-holders android.app.role.SMS" to "com.google.android.apps.messaging",
                "cmd role get-role-holders android.app.role.DIALER" to "com.sh.smart.caller"
            )
        )
        RamCleaner.shell = shell
        try {
            val result = RamCleaner.killBackgroundApps("com.example.othergame")

            assertFalse(result.usedFallback)
            assertEquals(3, result.stopped)
            assertTrue(shell.commands.contains("am force-stop com.facebook.katana"))
            assertTrue("another of the user's games is killable", shell.commands.contains("am force-stop com.example.thirdgame"))
            assertFalse("the selected game is protected", shell.ran("am force-stop com.example.othergame"))
            assertFalse("the SMS app is protected", shell.ran("am force-stop com.google.android.apps.messaging"))
            assertFalse("the dialer is protected", shell.ran("am force-stop com.sh.smart.caller"))
            assertTrue(shell.commands.contains("am force-stop com.transsion.trancare"))
            assertFalse(shell.ran("am force-stop com.launcher.third"))
            assertFalse(shell.ran("am force-stop com.kb.third"))
            assertEquals("am kill-all", shell.commands.last())
        } finally {
            RamCleaner.shell = ShizukuShell
        }
    }

    @Test
    fun `an unresolvable launcher falls back to the static list`() {
        val shell = FakeShell(
            captures = mapOf(
                "pm list packages -3" to "package:com.facebook.katana",
                "ps -A -o NAME" to "NAME\ncom.facebook.katana"
            )
        )
        RamCleaner.shell = shell
        try {
            val result = RamCleaner.killBackgroundApps("com.example.game")
            assertTrue(result.usedFallback)
            assertEquals(AppPackages.BACKGROUND_HOGS.size, result.stopped)
        } finally {
            RamCleaner.shell = ShizukuShell
        }
    }
}
