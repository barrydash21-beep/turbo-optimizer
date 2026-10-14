package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaves like GameManager's `cmd game`, with output in the formats recorded on a Pova 7
 * (Android 16): per-mode intervention configs, `set` writing the custom mode's config and
 * switching to custom, `mode` switching, `reset` clearing everything back to standard.
 */
private class GameShell(
    val pkg: String,
    var mode: String = "standard",
    var available: List<String> = listOf("standard", "custom"),
    /** Mode id -> (scaling, fps). */
    val configs: MutableMap<Int, Pair<String?, String?>> = mutableMapOf(),
    val isGame: Boolean = true,
    var pid: String? = null,
    var measureFile: String? = null,
    var bootId: String = "boot-1",
    /** `set --downscale` values the shell refuses, as AOSP does for text that is not a float. */
    val rejects: Set<String> = emptySet(),
    /** Exits 0 but stores this scale instead of the one asked for. */
    val storesInstead: String? = null
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable() = true

    override fun run(command: String): ShellResult {
        commands += command
        if (command.startsWith("rm -f") && command.endsWith(".txt")) measureFile = null
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        val marker = " 2>&1; echo \"__exit:\$?\""
        if (command.endsWith(marker)) {
            val (code, out) = exec(command.removeSuffix(marker))
            return "$out\n__exit:$code"
        }
        return when {
            command == "pidof $pkg" -> pid
            command == "cat /proc/sys/kernel/random/boot_id" -> bootId
            command.startsWith("cat ") -> measureFile
            else -> null
        }
    }

    /** The `cmd game` subcommands this app uses. */
    private fun exec(command: String): Pair<Int, String> {
        val parts = command.split(" ")
        if (!isGame) {
            return 0 to "Package $pkg is not of game type, to use the game mode commands, it must specify game " +
                "category in the manifest as android:appCategory=\"game\""
        }
        return when (parts[2]) {
            "list-modes" -> 0 to "$pkg current mode: $mode, available game modes: [${available.joinToString(",")}]"
            "list-configs" -> 0 to if (configs.isEmpty()) {
                "$pkg interventions: \n No intervention found for package $pkg"
            } else {
                "$pkg interventions: \n[Name:$pkg Modes: {" + configs.toSortedMap().entries.joinToString(", ") { (id, c) ->
                    "$id=[Game Mode:$id,Scaling:${c.first.orEmpty()},Use Angle:false,Fps:${c.second.orEmpty()},Loading Boost Duration:-1]"
                } + "}]"
            }
            "set" -> {
                val scale = parts.getOrNull(parts.indexOf("--downscale") + 1)
                val fps = parts.indexOf("--fps").takeIf { it >= 0 }?.let { parts[it + 1] }
                if (scale in rejects) return 255 to "Invalid scaling ratio '$scale'"
                configs[4] = (if (scale == "disable") null else storesInstead ?: scale) to fps
                mode = "custom"
                0 to "Set custom mode intervention config for user `0` in game `$pkg` as: `downscaling-ratio: $scale;fps-override: $fps`"
            }
            "mode" -> {
                mode = parts[3]
                0 to "Set game mode to `${mode.uppercase()}` for user `0` in game `$pkg`"
            }
            "reset" -> {
                configs.clear()
                mode = "standard"
                0 to ""
            }
            else -> 1 to "Unknown command: ${parts[2]}"
        }
    }

    /** Commands that change GameManager state. */
    val writes: List<String>
        get() = commands.map { it.substringBefore(" 2>&1") }
            .filter { it.startsWith("cmd game set") || it.startsWith("cmd game mode") || it.startsWith("cmd game reset") }
}

class GameResolutionTest {

    private val uncapped = "com.mobile.legends"
    private val capped = "com.garena.game.codm"

    // --- preset value mapping -----------------------------------------------------------

    @Test
    fun `presets run from MEDIUM at 0_65 down to LOWEST at 0_5, keeping the 1_2_0 names`() {
        assertEquals(listOf("MEDIUM", "LOW", "LOWER", "LOWEST"), ResolutionLevel.entries.map { it.label })
        assertEquals(listOf("0.65", "0.6", "0.55", "0.5"), ResolutionLevel.entries.map { it.scale })
        assertEquals(listOf("0.65x", "0.6x", "0.55x", "0.5x"), ResolutionLevel.entries.map { it.factor })
        assertEquals(ResolutionLevel.LOW, ResolutionLevel.forScale("0.60"))
        assertEquals(ResolutionLevel.MEDIUM, ResolutionLevel.forScale("0.65"))
        assertEquals(ResolutionLevel.LOWER, ResolutionLevel.forScale("0.55"))
        assertEquals(ResolutionLevel.LOWEST, ResolutionLevel.forScale("0.50"))
        assertNull(ResolutionLevel.forScale("0.83"))
        assertNull(ResolutionLevel.forScale(null))
        assertFalse(ResolutionLevel.entries.any { it.label.contains('%') })
        // Nothing offered goes below 0.5.
        assertTrue(ResolutionLevel.entries.all { it.scale.toFloat() >= 0.5f })
    }

    @Test
    fun `scales compare as numbers`() {
        assertTrue(GameResolution.sameScale("0.5", "0.50"))
        assertTrue(GameResolution.sameScale("0.55", "0.550"))
        assertFalse(GameResolution.sameScale("0.5", "0.55"))
        assertFalse(GameResolution.sameScale("0.5", null))
        assertFalse(GameResolution.sameScale("0.5", "disable"))
    }

    @Test
    fun `apply sends the preset's exact downscale value`() {
        val shell = GameShell(uncapped)
        GameResolution.apply(shell, FakeStore(), uncapped, ResolutionLevel.LOW, null)
        assertEquals(listOf("cmd game set --downscale 0.6 $uncapped"), shell.writes)
    }

    // --- restore: no prior config --------------------------------------------

    @Test
    fun `no prior config - restore resets to exactly what was there and clears the snapshot`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()

        val applied = GameResolution.apply(shell, store, uncapped, ResolutionLevel.MEDIUM, null)
        assertTrue(applied.detail, applied.ok)
        assertEquals("custom", shell.mode)
        assertEquals("0.65" to null, shell.configs[4])
        assertEquals("0.65", GameResolution.applied(store, uncapped))

        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, uncapped, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game reset $uncapped", "cmd game mode standard $uncapped"), shell.writes)
        assertEquals("standard", shell.mode)
        assertTrue(shell.configs.isEmpty())
        assertNull(GameResolution.snapshot(store, uncapped))
        assertNull(GameResolution.applied(store, uncapped))
    }

    // --- restore: prior configs in the game's own modes ---------------------

    private fun cappedShell() = GameShell(
        capped,
        mode = "performance",
        available = listOf("standard", "performance", "custom"),
        configs = mutableMapOf(2 to ("0.8" to "60"), 4 to ("0.7" to "90"))
    )

    @Test
    fun `prior config - restore writes back the old custom config and mode, never reset`() {
        val shell = cappedShell()
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOW, null).ok)
        assertEquals("0.6" to null, shell.configs[4])

        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, capped, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game set --downscale 0.7 --fps 90 $capped", "cmd game mode performance $capped"), shell.writes)
        assertEquals("performance", shell.mode)
        assertEquals("0.8" to "60", shell.configs[2])
        assertEquals("0.7" to "90", shell.configs[4])
        assertNull(GameResolution.snapshot(store, capped))
    }

    @Test
    fun `a second preset keeps the first snapshot, never one taken from the app's own values`() {
        val shell = cappedShell()
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOW, null).ok)
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.MEDIUM, null).ok)

        val snap = GameResolution.snapshot(store, capped)!!
        assertEquals("performance", snap.mode)
        assertEquals(InterventionConfig("0.7", "90"), snap.custom)

        assertTrue(GameResolution.restore(shell, store, capped, force = false, runningPid = null).ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
    }

    @Test
    fun `other modes configured but no custom config - scaling is switched off, other configs kept`() {
        val shell = GameShell(
            capped,
            mode = "performance",
            available = listOf("standard", "performance", "custom"),
            configs = mutableMapOf(2 to ("0.8" to "60"))
        )
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.MEDIUM, null).ok)
        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, capped, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game set --downscale disable $capped", "cmd game mode performance $capped"), shell.writes)
        assertEquals("0.8" to "60", shell.configs[2])
        assertEquals("performance", shell.mode)
    }

    // --- restore: changed outside the app -------------------------------------------------

    @Test
    fun `changed outside the app - restore refuses and writes nothing, keeps the snapshot`() {
        val shell = cappedShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOW, null).ok)

        // Something else (Game Space, the user, adb) switches the game's mode.
        shell.mode = "standard"
        shell.commands.clear()
        val refused = GameResolution.restore(shell, store, capped, force = false, runningPid = null)

        assertFalse(refused.ok)
        assertTrue(refused.changedOutside)
        assertTrue(refused.detail, refused.detail.contains("now standard"))
        assertTrue(shell.writes.isEmpty())
        assertEquals("standard", shell.mode)
        assertTrue(GameResolution.snapshot(store, capped) != null)
        assertEquals("standard", GameResolution.status(shell, store, capped).changedOutside)
    }

    @Test
    fun `changed outside - a different downscale counts too, and restore anyway puts the snapshot back`() {
        val shell = cappedShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOW, null).ok)
        shell.configs[4] = "0.5" to null

        assertTrue(GameResolution.restore(shell, store, capped, force = false, runningPid = null).changedOutside)

        val forced = GameResolution.restore(shell, store, capped, force = true, runningPid = null)
        assertTrue(forced.detail, forced.ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
        assertNull(GameResolution.snapshot(store, capped))
    }

    @Test
    fun `restore with nothing applied does nothing`() {
        val shell = cappedShell()
        val result = GameResolution.restore(shell, FakeStore(), capped, force = false, runningPid = null)
        assertTrue(result.ok)
        assertTrue(shell.writes.isEmpty())
    }

    // --- phone restart (Pova 7: modes back to standard, configs kept) ----------------------

    @Test
    fun `a restart that drops the mode is not an outside change, and is set again before launch`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.MEDIUM, null).ok)

        // Reboot: what the Pova 7 showed after a restart.
        shell.bootId = "boot-2"
        shell.mode = "standard"
        val status = GameResolution.status(shell, store, uncapped)
        assertTrue(status.resetByRestart)
        assertNull(status.changedOutside)

        shell.commands.clear()
        val again = GameResolution.reapplyBeforeLaunch(shell, store, uncapped)!!
        assertTrue(again.detail, again.ok)
        assertEquals(listOf("cmd game mode custom $uncapped"), shell.writes)
        assertEquals("custom", shell.mode)
        assertEquals("0.65" to null, shell.configs[4])

        // Done once: nothing to do on the next launch.
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, uncapped))
        assertFalse(GameResolution.status(shell, store, uncapped).resetByRestart)
    }

    @Test
    fun `standard mode without a restart is an outside change and is never re-applied`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.MEDIUM, null).ok)
        shell.mode = "standard"

        assertEquals("standard", GameResolution.status(shell, store, uncapped).changedOutside)
        shell.commands.clear()
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, uncapped))
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun `after a restart a config someone else changed is left alone`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.MEDIUM, null).ok)
        shell.bootId = "boot-2"
        shell.mode = "standard"
        shell.configs[4] = "0.65" to "60" // a frame-rate cap this app never sets

        val status = GameResolution.status(shell, store, uncapped)
        assertFalse(status.resetByRestart)
        assertEquals("standard", status.changedOutside)
        shell.commands.clear()
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, uncapped))
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun `DEFAULT after a restart reset restores without asking`() {
        val shell = cappedShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOW, null).ok)
        shell.bootId = "boot-2"
        shell.mode = "standard"

        val restored = GameResolution.restore(shell, store, capped, force = false, runningPid = null)
        assertTrue(restored.detail, restored.ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
    }

    // --- games that reject downscale -------------------------------------------------------

    @Test
    fun `an app Android does not list as a game is unavailable with its reason, nothing stored`() {
        val shell = GameShell("com.android.chrome", isGame = false)
        val store = FakeStore()

        val result = GameResolution.apply(shell, store, "com.android.chrome", ResolutionLevel.LOW, null)

        assertFalse(result.ok)
        assertTrue(result.unavailable)
        assertTrue(result.detail, result.detail.contains("doesn't list this app as a game"))
        assertTrue(shell.writes.isEmpty())
        assertNull(GameResolution.snapshot(store, "com.android.chrome"))
        assertTrue(GameResolution.status(shell, store, "com.android.chrome").unavailableReason!!.contains("as a game"))
    }

    @Test
    fun `a game without the custom mode is unavailable`() {
        val shell = GameShell(uncapped, available = listOf("standard", "performance"))
        val result = GameResolution.apply(shell, FakeStore(), uncapped, ResolutionLevel.LOW, null)
        assertFalse(result.ok)
        assertTrue(result.unavailable)
        assertTrue(shell.writes.isEmpty())
    }

    // --- running game ------------------------------------------------------------------------

    @Test
    fun `a change while the game runs is pending until that process is gone`() {
        val shell = GameShell(uncapped, pid = "16439")
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.MEDIUM, "16439").ok)
        assertTrue(GameResolution.status(shell, store, uncapped).pendingRestart)

        shell.pid = "20001" // relaunched
        assertFalse(GameResolution.status(shell, store, uncapped).pendingRestart)
        assertNull(GameResolution.stalePid(store, uncapped))
    }

    @Test
    fun `apply never force-stops the game`() {
        val shell = GameShell(uncapped, pid = "16439")
        GameResolution.apply(shell, FakeStore(), uncapped, ResolutionLevel.LOW, "16439")
        GameResolution.restore(shell, FakeStore(), uncapped, force = true, runningPid = "16439")
        assertFalse(shell.commands.any { it.contains("force-stop") || it.startsWith("kill") })
    }

    // --- parsing (real Pova 7 output) -------------------------------------------------------

    @Test
    fun `parses list-modes and list-configs as printed on Android 16`() {
        assertEquals(
            "performance" to listOf("standard", "performance", "custom"),
            GameResolution.parseModes("$capped current mode: performance, available game modes: [standard,performance,custom]")
        )
        val configs = GameResolution.parseConfigs(
            "$capped interventions: \n[Name:$capped Modes: {2=[Game Mode:2,Scaling:0.8,Use Angle:false,Fps:60,Loading Boost Duration:-1], " +
                "4=[Game Mode:4,Scaling:0.7,Use Angle:false,Fps:90,Loading Boost Duration:-1]}]"
        )!!
        assertEquals(InterventionConfig("0.8", "60"), configs[2])
        assertEquals(InterventionConfig("0.7", "90"), configs[4])
        assertEquals(
            InterventionConfig("0.83", null),
            GameResolution.parseConfigs("[Name:$uncapped Modes: {4=[Game Mode:4,Scaling:0.83,Use Angle:false,Fps:,Loading Boost Duration:-1]}]")!![4]
        )
        assertEquals(emptyMap<Int, InterventionConfig>(), GameResolution.parseConfigs(" No intervention found for package $uncapped"))
        assertNull(GameResolution.parseConfigs("something else"))
        assertNull(GameResolution.parseModes("Error: unknown"))
    }

    private val sfLines = """
        + name:cda85c6 SurfaceView[com.mobile.legends/com.moba.unityplugin.MobaGameUnityActivity]#1(BLAST Consumer)1, id:9199820124371, size:6127.00KiB, w/h:810x1845, usage: 0x40000000000b00, req fmt:2
        + name:cda85c6 SurfaceView[com.mobile.legends/com.moba.unityplugin.MobaGameUnityActivity]#1(BLAST Consumer)1, id:9199820124370, size:6127.00KiB, w/h:810x1845, usage: 0x40000000000b00, req fmt:2
        + name:8597e53 com.mobile.legends/com.moba.unityplugin.MobaGameUnityActivity#68464(BLAST Consumer)68464, id:91998, size:10378.00KiB, w/h:1080x2460, usage: 0xb00, req fmt:1
        + name:aa11 SurfaceView[com.mobile.legends.helper/x.Y]#1(BLAST Consumer)1, id:1, size:1.00KiB, w/h:100x100, usage: 0xb00, req fmt:1
    """.trimIndent()

    @Test
    fun `buffer size comes from the game's SurfaceView, not its window or another package`() {
        assertEquals(BufferSize(810, 1845), GameResolution.parseBuffers(sfLines, uncapped))
        // Only a window buffer: that is what the game draws into.
        assertEquals(BufferSize(1080, 2460), GameResolution.parseBuffers(sfLines.lines()[2], uncapped))
        assertNull(GameResolution.parseBuffers(sfLines, "com.example.other"))
        assertNull(GameResolution.parseBuffers("garbage", uncapped))
        assertNull(GameResolution.parseBuffers(null, uncapped))
    }

    // --- result line -------------------------------------------------------------------------

    @Test
    fun `result line compares with the native measurement`() {
        val native = BufferSize(810, 1845)
        // Measured on the Pova 7: uncapped game, native 810x1845, at 0.67.
        assertEquals("Renders at 724x1648 (−20% pixels)", GameResolution.resultText(native, BufferSize(724, 1648)))
        // Uncapped game at 0.83: more pixels than native.
        assertEquals("No effect: game already renders below this", GameResolution.resultText(native, BufferSize(815, 1858)))
        // Capped game, native 720x1648, at 0.67: under half a percent, within its own 720p cap.
        assertEquals(
            "No effect: game already renders below this",
            GameResolution.resultText(BufferSize(720, 1648), BufferSize(720, 1639))
        )
        assertEquals("Renders at 648x1472", GameResolution.resultText(null, BufferSize(648, 1472)))
        assertNull(GameResolution.resultText(native, null))
    }

    // --- measurement filing ------------------------------------------------------------------

    private fun file(pid: String, scale: String?, size: String) =
        "pid:$pid\nscale:${scale ?: "none"}\n+ name:a SurfaceView[$uncapped/b.C]#1, w/h:$size, x"

    @Test
    fun `native is stored once, under the game's default only`() {
        val shell = GameShell(uncapped, measureFile = file("100", null, "810x1845"))
        val store = FakeStore()

        assertTrue(GameResolution.collect(shell, store, uncapped))
        assertEquals(BufferSize(810, 1845), GameResolution.native(store, uncapped))
        assertNull(shell.measureFile) // removed once read

        shell.measureFile = file("101", null, "1080x2460")
        GameResolution.collect(shell, store, uncapped)
        assertEquals(BufferSize(810, 1845), GameResolution.native(store, uncapped))
    }

    @Test
    fun `a measurement is shown only for the preset it was taken under`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOW, null).ok)

        shell.measureFile = file("200", "0.65", "702x1599") // an older preset: dropped
        assertFalse(GameResolution.collect(shell, store, uncapped))
        assertNull(GameResolution.measured(store, uncapped, "0.6"))

        shell.measureFile = file("201", "0.6", "648x1476")
        assertTrue(GameResolution.collect(shell, store, uncapped))
        assertEquals(BufferSize(648, 1476), GameResolution.measured(store, uncapped, "0.6"))
        assertEquals("Renders at 648x1476", GameResolution.status(shell, store, uncapped).result)
    }

    @Test
    fun `a measurement of the process that predates the change is ignored`() {
        val shell = GameShell(uncapped, pid = "300")
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOW, "300").ok)

        assertFalse(GameResolution.record(store, uncapped, GameResolution.Measurement("300", "0.6", BufferSize(810, 1845))))
        assertNull(GameResolution.measured(store, uncapped, "0.6"))
    }

    @Test
    fun `no measurement means no result line, never an estimate`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOW, null).ok)
        assertNull(GameResolution.status(shell, store, uncapped).result)
        shell.measureFile = "pid:5\nscale:0.6\nno buffer lines here"
        assertNull(GameResolution.status(shell, store, uncapped).result)
    }

    // --- session record ---------------------------------------------------------------------

    @Test
    fun `session records carry the preset and older ones still decode`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertEquals("default", GameResolution.sessionValue(store, uncapped))
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOW, null).ok)
        assertEquals("LOW 0.6", GameResolution.sessionValue(store, uncapped))

        val record = SessionRecord(
            packageName = uncapped, refreshRate = 90f, startedAtMs = 1L, durationMs = 2L,
            avgFps = null, onePercentLowFps = null, msToThermal1 = null, maxThermal = null,
            samples = 0, fpsSamples = 0, thermalSamples = 0, observedPeak = null,
            renderResolution = GameResolution.sessionValue(store, uncapped)
        )
        assertEquals(record, SessionRecord.decode(record.encode()))
        // Written by the removed display lever.
        assertEquals("900x2050@400", SessionRecord.decode(record.copy(renderResolution = "900x2050@400").encode())!!.renderResolution)
    }

    // --- the 0.55 and 0.5 presets -------------------------------------------------------------

    private val lowFactors = listOf(ResolutionLevel.LOWER, ResolutionLevel.LOWEST)

    @Test
    fun `LOWER and LOWEST send their exact value and round-trip to the game's default`() {
        for (level in lowFactors) {
            val shell = GameShell(uncapped)
            val store = FakeStore()

            val applied = GameResolution.apply(shell, store, uncapped, level, null)
            assertTrue("$level: ${applied.detail}", applied.ok)
            assertEquals(listOf("cmd game set --downscale ${level.scale} $uncapped"), shell.writes)
            assertEquals(level.scale to null, shell.configs[4])
            assertEquals(level.scale, GameResolution.applied(store, uncapped))
            assertEquals(level, GameResolution.status(shell, store, uncapped).level)

            shell.commands.clear()
            val restored = GameResolution.restore(shell, store, uncapped, force = false, runningPid = null)
            assertTrue("$level: ${restored.detail}", restored.ok)
            assertEquals(listOf("cmd game reset $uncapped", "cmd game mode standard $uncapped"), shell.writes)
            assertEquals("standard", shell.mode)
            assertTrue(shell.configs.isEmpty())
            assertNull(GameResolution.snapshot(store, uncapped))
            assertNull(GameResolution.applied(store, uncapped))
        }
    }

    @Test
    fun `LOWER and LOWEST put a game's own custom config back`() {
        for (level in lowFactors) {
            val shell = cappedShell()
            val store = FakeStore()
            assertTrue(GameResolution.apply(shell, store, capped, level, null).ok)
            assertEquals(level.scale to null, shell.configs[4])

            shell.commands.clear()
            assertTrue(GameResolution.restore(shell, store, capped, force = false, runningPid = null).ok)
            assertEquals(
                listOf("cmd game set --downscale 0.7 --fps 90 $capped", "cmd game mode performance $capped"),
                shell.writes
            )
            assertEquals("0.7" to "90", shell.configs[4])
            assertEquals("performance", shell.mode)
        }
    }

    @Test
    fun `switching LOWER to MEDIUM to DEFAULT keeps the original snapshot`() {
        val shell = cappedShell()
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOWER, null).ok)
        val first = GameResolution.snapshot(store, capped)!!
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.MEDIUM, null).ok)
        assertEquals("0.65", GameResolution.applied(store, capped))
        assertEquals(first, GameResolution.snapshot(store, capped))
        assertEquals(InterventionConfig("0.7", "90"), first.custom)
        assertEquals("performance", first.mode)

        assertTrue(GameResolution.restore(shell, store, capped, force = false, runningPid = null).ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
        assertEquals("0.8" to "60", shell.configs[2])
        assertNull(GameResolution.snapshot(store, capped))
    }

    @Test
    fun `a value the shell rejects is a failure that leaves the game and the store untouched`() {
        val shell = GameShell(uncapped, rejects = setOf("0.5"))
        val store = FakeStore()

        val result = GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOWEST, null)

        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("Downscale not applied"))
        assertTrue(result.detail, result.detail.contains("Invalid scaling ratio '0.5'"))
        assertEquals("standard", shell.mode)
        assertTrue(shell.configs.isEmpty())
        assertNull(GameResolution.applied(store, uncapped))
        assertNull(GameResolution.snapshot(store, uncapped))
        assertNull(GameResolution.status(shell, store, uncapped).level)
    }

    @Test
    fun `a value that is accepted but read back differently is a failure`() {
        val shell = GameShell(uncapped, storesInstead = "0.6")
        val store = FakeStore()

        val result = GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOWEST, null)

        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("Downscale not applied"))
        assertNull(GameResolution.applied(store, uncapped))
    }

    @Test
    fun `a rejected switch keeps the preset already held`() {
        val shell = GameShell(uncapped, rejects = setOf("0.5"))
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOWER, null).ok)

        assertFalse(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOWEST, null).ok)

        assertEquals("0.55", GameResolution.applied(store, uncapped))
        assertEquals(ResolutionLevel.LOWER, GameResolution.status(shell, store, uncapped).level)
        assertTrue(GameResolution.restore(shell, store, uncapped, force = false, runningPid = null).ok)
    }

    @Test
    fun `LOWEST changed outside the app is refused until confirmed`() {
        val shell = cappedShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, capped, ResolutionLevel.LOWEST, null).ok)
        shell.mode = "standard"
        shell.commands.clear()

        val refused = GameResolution.restore(shell, store, capped, force = false, runningPid = null)
        assertFalse(refused.ok)
        assertTrue(refused.changedOutside)
        assertTrue(shell.writes.isEmpty())
        assertEquals("standard", GameResolution.status(shell, store, capped).changedOutside)

        val forced = GameResolution.restore(shell, store, capped, force = true, runningPid = null)
        assertTrue(forced.detail, forced.ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
    }

    @Test
    fun `a restart at LOWER is set again before launch, like the older presets`() {
        val shell = GameShell(uncapped)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, uncapped, ResolutionLevel.LOWER, null).ok)
        shell.bootId = "boot-2"
        shell.mode = "standard"

        val status = GameResolution.status(shell, store, uncapped)
        assertTrue(status.resetByRestart)
        assertEquals(ResolutionLevel.LOWER, status.level)
        assertNull(status.changedOutside)

        shell.commands.clear()
        assertTrue(GameResolution.reapplyBeforeLaunch(shell, store, uncapped)!!.ok)
        assertEquals(listOf("cmd game mode custom $uncapped"), shell.writes)
        assertEquals("0.55" to null, shell.configs[4])
        assertEquals("LOWER 0.55", GameResolution.sessionValue(store, uncapped))
    }

    @Test
    fun `levels stored by 1_2_0 still load, by scale not by name`() {
        // What 1.2.0 left in the store: the applied scale, as the preset sent it.
        for ((stored, expected) in listOf("0.65" to ResolutionLevel.MEDIUM, "0.6" to ResolutionLevel.LOW)) {
            val shell = GameShell(uncapped, mode = "custom", configs = mutableMapOf(4 to (stored to null)))
            val store = FakeStore()
            store.putString("applied_$uncapped", stored)
            store.putString("snap_mode_$uncapped", "standard")
            store.putString("snap_custom_$uncapped", "none")
            store.putBoolean("snap_any_$uncapped", false)

            val status = GameResolution.status(shell, store, uncapped)
            assertEquals(expected, status.level)
            assertNull(status.changedOutside)
            assertEquals("${expected.label} $stored", GameResolution.sessionValue(store, uncapped))

            assertTrue(GameResolution.restore(shell, store, uncapped, force = false, runningPid = null).ok)
            assertEquals("standard", shell.mode)
        }
    }

    @Test
    fun `result line for sizes measured at the new factors`() {
        val native = BufferSize(720, 1640)
        assertEquals("Renders at 594x1353 (−32% pixels)", GameResolution.resultText(native, BufferSize(594, 1353)))
        assertEquals("Renders at 540x1230 (−44% pixels)", GameResolution.resultText(native, BufferSize(540, 1230)))
        // Same size as native: the game's cap sits below the preset.
        assertEquals("No effect: game already renders below this", GameResolution.resultText(native, BufferSize(720, 1640)))
        // Measured only; nothing is estimated from the factor.
        assertNull(GameResolution.resultText(native, null))
    }

    // --- shell watcher -----------------------------------------------------------------------

    @Test
    fun `watcher runs detached in the shell, skips the stale process and replaces an older watcher`() {
        val shell = GameShell(uncapped)
        assertTrue(GameResolution.startWatcher(shell, uncapped, "0.6", "16439"))

        val launch = shell.commands.last()
        assertTrue(launch, launch.startsWith("setsid sh -c '"))
        assertTrue(launch, launch.endsWith("</dev/null >/dev/null 2>&1 &"))
        assertTrue(launch, launch.contains("pidof $uncapped"))
        assertTrue(launch, launch.contains("!= \"16439\""))
        assertTrue(launch, launch.contains("echo \"scale:0.6\""))
        assertTrue(launch, launch.contains("dumpsys SurfaceFlinger"))
        assertFalse(launch, launch.contains("force-stop"))
        assertTrue(shell.commands[shell.commands.size - 2].contains("kill"))
    }

    @Test
    fun `nothing reaches the shell for a value that is not a package name`() {
        val shell = GameShell(uncapped)
        val bad = "x; reboot"
        assertFalse(GameResolution.startWatcher(shell, bad, null, null))
        assertFalse(GameResolution.apply(shell, FakeStore(), bad, ResolutionLevel.LOW, null).ok)
        assertTrue(shell.commands.isEmpty())
    }
}
