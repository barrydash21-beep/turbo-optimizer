package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeavyAppsTest {

    private val launcherCmd = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    private val ps = """
          RSS NAME
         1000 system_server
        50000 [kthreadd]
       524288 com.example.big
       102400 com.example.big:service
       307200 com.example.mid
        20480 com.example.small
        10240 com.launcher.home
       900000 com.example.game
       800000 com.android.systemui
       700000 com.vendor.preinstalled
    """.trimIndent()

    private val thirdParty = setOf(
        "com.example.big", "com.example.mid", "com.example.small", "com.launcher.home", "com.example.game"
    )

    private fun shell(extra: Map<String, String> = emptyMap(), launcher: Boolean = true) = FakeShell(
        captures = buildMap {
            put("pm list packages -3", thirdParty.joinToString("\n") { "package:$it" })
            put("ps -A -o RSS,NAME", ps)
            if (launcher) put(launcherCmd, "priority=0 preferredOrder=0\ncom.launcher.home/.Home")
            put("settings get secure default_input_method", "com.example.keyboard/.Ime")
            putAll(extra)
        }
    )

    private lateinit var saved: ShellExecutor

    @Before fun saveShell() {
        saved = RamCleaner.shell
    }

    @After fun restoreShell() {
        RamCleaner.shell = saved
    }

    // ---- parsing and selection ----

    @Test fun `processes of one package are added together and kernel threads are dropped`() {
        val parsed = HeavyApps.parseResident(ps)!!
        assertEquals(524288L + 102400L, parsed["com.example.big"])
        assertNull(parsed["[kthreadd]"])
        assertNull(parsed["system_server"])
        assertNull(parsed["RSS"])
    }

    @Test fun `unreadable ps output is null, not an empty list`() {
        assertNull(HeavyApps.parseResident(null))
        assertNull(HeavyApps.parseResident("RSS NAME\nnot a row"))
    }

    @Test fun `selection drops protected, games and anything not third-party, heaviest first`() {
        val resident = HeavyApps.parseResident(ps)!!
        val protected = RamCleaner.protectedPackages(shell(), "com.example.game")!!
        val picked = HeavyApps.select(resident, thirdParty, protected, games = setOf("com.example.game"))
        assertEquals(listOf("com.example.big", "com.example.mid", "com.example.small"), picked.map { it.packageName })
        // 626688 KB = 612 MB
        assertEquals(612, picked.first().megabytes)
        assertFalse(picked.any { it.packageName == "com.launcher.home" })
    }

    @Test fun `selection is capped at five`() {
        val resident = (1..9).associate { "com.example.app$it" to it * 2048L }
        val picked = HeavyApps.select(resident, resident.keys, emptySet(), emptySet())
        assertEquals(5, picked.size)
        assertEquals("com.example.app9", picked.first().packageName)
    }

    @Test fun `this app and Shizuku never appear`() {
        val resident = mapOf(BuildConfig.APPLICATION_ID to 9_999_999L, AppPackages.SHIZUKU to 9_999_999L, "com.example.big" to 1024L)
        val picked = HeavyApps.select(resident, resident.keys, AppPackages.PROTECTED.toSet(), emptySet())
        assertEquals(listOf("com.example.big"), picked.map { it.packageName })
    }

    // ---- the restriction list ----

    @Test fun `restricting starts from the defaults and adds once`() {
        val defaults = listOf("com.a", "com.b")
        assertEquals(listOf("com.a", "com.b", "com.example.big"), HeavyApps.restrictedWith(null, defaults, "com.example.big"))
        assertEquals(listOf("com.a", "com.b", "com.example.big"), HeavyApps.restrictedWith(listOf("com.a", "com.b", "com.example.big"), defaults, "com.example.big"))
    }

    @Test fun `restricting never drops what the user already listed`() {
        assertEquals(listOf("com.mine", "com.example.big"), HeavyApps.restrictedWith(listOf("com.mine"), listOf("com.default"), "com.example.big"))
    }

    // ---- loading ----

    @Test fun `load ranks the running apps`() {
        val ui = HeavyApps.load(shell(), "com.example.game", setOf("com.example.game"))
        assertEquals(listOf("com.example.big", "com.example.mid", "com.example.small"), ui.apps!!.map { it.packageName })
    }

    @Test fun `load lists nothing when protection cannot be resolved`() {
        val ui = HeavyApps.load(shell(launcher = false), null, emptySet())
        assertNull(ui.apps)
        assertNotNull(ui.note)
    }

    @Test fun `load says so without Shizuku`() {
        val ui = HeavyApps.load(FakeShell(available = false), null, emptySet())
        assertNull(ui.apps)
        assertTrue(ui.note!!.contains("Shizuku"))
    }

    // ---- stopping one app ----

    @Test fun `stop uses the purge's force-stop command`() {
        val s = shell().also { RamCleaner.shell = it }
        assertEquals(StopOutcome.STOPPED, RamCleaner.stopApp("com.example.big", "com.example.game", setOf("com.example.game")))
        assertTrue(s.commands.contains("am force-stop com.example.big"))
    }

    @Test fun `stop refuses protected apps, games and the launcher without running anything`() {
        val s = shell().also { RamCleaner.shell = it }
        val games = setOf("com.example.game", "com.example.other")
        for (pkg in listOf(BuildConfig.APPLICATION_ID, AppPackages.SHIZUKU, "com.launcher.home", "com.example.keyboard", "com.example.other", "com.example.game", "com.android.systemui")) {
            assertEquals(pkg, StopOutcome.REFUSED, RamCleaner.stopApp(pkg, "com.example.game", games))
        }
        assertFalse(s.ran("am force-stop"))
    }

    @Test fun `stop refuses a name that is not a package`() {
        val s = shell().also { RamCleaner.shell = it }
        assertEquals(StopOutcome.REFUSED, RamCleaner.stopApp("com.example.big; reboot", null, emptySet()))
        assertFalse(s.ran("am force-stop"))
    }

    @Test fun `stop refuses everything when the launcher is unknown`() {
        val s = shell(launcher = false).also { RamCleaner.shell = it }
        assertEquals(StopOutcome.REFUSED, RamCleaner.stopApp("com.example.big", null, emptySet()))
        assertFalse(s.ran("am force-stop"))
    }

    @Test fun `stop reports a failed command and a missing Shizuku`() {
        RamCleaner.shell = FakeShell(
            captures = mapOf(
                launcherCmd to "com.launcher.home/.Home",
                "settings get secure default_input_method" to "com.example.keyboard/.Ime"
            ),
            failingPrefixes = setOf("am force-stop")
        )
        assertEquals(StopOutcome.FAILED, RamCleaner.stopApp("com.example.big", null, emptySet()))
        RamCleaner.shell = FakeShell(available = false)
        assertEquals(StopOutcome.UNAVAILABLE, RamCleaner.stopApp("com.example.big", null, emptySet()))
    }
}
