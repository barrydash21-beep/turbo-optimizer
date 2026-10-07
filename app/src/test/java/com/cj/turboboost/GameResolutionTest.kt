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
    var bootId: String = "boot-1"
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
                configs[4] = (if (scale == "disable") null else scale) to fps
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

    private val ml = "com.mobile.legends"
    private val codm = "com.garena.game.codm"

    // --- preset value mapping -----------------------------------------------------------

    @Test
    fun `presets are MEDIUM at 0_65 and LOW at 0_6, labelled by level`() {
        assertEquals(listOf("MEDIUM", "LOW"), ResolutionLevel.entries.map { it.label })
        assertEquals(listOf("0.65", "0.6"), ResolutionLevel.entries.map { it.scale })
        assertEquals(ResolutionLevel.LOW, ResolutionLevel.forScale("0.60"))
        assertEquals(ResolutionLevel.MEDIUM, ResolutionLevel.forScale("0.65"))
        assertNull(ResolutionLevel.forScale("0.83"))
        assertNull(ResolutionLevel.forScale(null))
        assertFalse(ResolutionLevel.entries.any { it.label.contains('%') })
    }

    @Test
    fun `apply sends the preset's exact downscale value`() {
        val shell = GameShell(ml)
        GameResolution.apply(shell, FakeStore(), ml, ResolutionLevel.LOW, null)
        assertEquals(listOf("cmd game set --downscale 0.6 $ml"), shell.writes)
    }

    // --- restore: no prior config (Mobile Legends) ---------------------------------------

    @Test
    fun `no prior config - restore resets to exactly what was there and clears the snapshot`() {
        val shell = GameShell(ml)
        val store = FakeStore()

        val applied = GameResolution.apply(shell, store, ml, ResolutionLevel.MEDIUM, null)
        assertTrue(applied.detail, applied.ok)
        assertEquals("custom", shell.mode)
        assertEquals("0.65" to null, shell.configs[4])
        assertEquals("0.65", GameResolution.applied(store, ml))

        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, ml, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game reset $ml", "cmd game mode standard $ml"), shell.writes)
        assertEquals("standard", shell.mode)
        assertTrue(shell.configs.isEmpty())
        assertNull(GameResolution.snapshot(store, ml))
        assertNull(GameResolution.applied(store, ml))
    }

    // --- restore: prior config like CoD Mobile's -----------------------------------------

    private fun codmShell() = GameShell(
        codm,
        mode = "performance",
        available = listOf("standard", "performance", "custom"),
        configs = mutableMapOf(2 to ("0.8" to "60"), 4 to ("0.7" to "90"))
    )

    @Test
    fun `prior config - restore writes back the old custom config and mode, never reset`() {
        val shell = codmShell()
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.LOW, null).ok)
        assertEquals("0.6" to null, shell.configs[4])

        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, codm, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game set --downscale 0.7 --fps 90 $codm", "cmd game mode performance $codm"), shell.writes)
        assertEquals("performance", shell.mode)
        assertEquals("0.8" to "60", shell.configs[2])
        assertEquals("0.7" to "90", shell.configs[4])
        assertNull(GameResolution.snapshot(store, codm))
    }

    @Test
    fun `a second preset keeps the first snapshot, never one taken from the app's own values`() {
        val shell = codmShell()
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.LOW, null).ok)
        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.MEDIUM, null).ok)

        val snap = GameResolution.snapshot(store, codm)!!
        assertEquals("performance", snap.mode)
        assertEquals(InterventionConfig("0.7", "90"), snap.custom)

        assertTrue(GameResolution.restore(shell, store, codm, force = false, runningPid = null).ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
    }

    @Test
    fun `other modes configured but no custom config - scaling is switched off, other configs kept`() {
        val shell = GameShell(
            codm,
            mode = "performance",
            available = listOf("standard", "performance", "custom"),
            configs = mutableMapOf(2 to ("0.8" to "60"))
        )
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.MEDIUM, null).ok)
        shell.commands.clear()
        val restored = GameResolution.restore(shell, store, codm, force = false, runningPid = null)

        assertTrue(restored.detail, restored.ok)
        assertEquals(listOf("cmd game set --downscale disable $codm", "cmd game mode performance $codm"), shell.writes)
        assertEquals("0.8" to "60", shell.configs[2])
        assertEquals("performance", shell.mode)
    }

    // --- restore: changed outside the app -------------------------------------------------

    @Test
    fun `changed outside the app - restore refuses and writes nothing, keeps the snapshot`() {
        val shell = codmShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.LOW, null).ok)

        // Something else (Game Space, the user, adb) switches the game's mode.
        shell.mode = "standard"
        shell.commands.clear()
        val refused = GameResolution.restore(shell, store, codm, force = false, runningPid = null)

        assertFalse(refused.ok)
        assertTrue(refused.changedOutside)
        assertTrue(refused.detail, refused.detail.contains("now standard"))
        assertTrue(shell.writes.isEmpty())
        assertEquals("standard", shell.mode)
        assertTrue(GameResolution.snapshot(store, codm) != null)
        assertEquals("standard", GameResolution.status(shell, store, codm).changedOutside)
    }

    @Test
    fun `changed outside - a different downscale counts too, and restore anyway puts the snapshot back`() {
        val shell = codmShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.LOW, null).ok)
        shell.configs[4] = "0.5" to null

        assertTrue(GameResolution.restore(shell, store, codm, force = false, runningPid = null).changedOutside)

        val forced = GameResolution.restore(shell, store, codm, force = true, runningPid = null)
        assertTrue(forced.detail, forced.ok)
        assertEquals("performance", shell.mode)
        assertEquals("0.7" to "90", shell.configs[4])
        assertNull(GameResolution.snapshot(store, codm))
    }

    @Test
    fun `restore with nothing applied does nothing`() {
        val shell = codmShell()
        val result = GameResolution.restore(shell, FakeStore(), codm, force = false, runningPid = null)
        assertTrue(result.ok)
        assertTrue(shell.writes.isEmpty())
    }

    // --- phone restart (Pova 7: modes back to standard, configs kept) ----------------------

    @Test
    fun `a restart that drops the mode is not an outside change, and is set again before launch`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.MEDIUM, null).ok)

        // Reboot: what the Pova 7 showed for Mobile Legends.
        shell.bootId = "boot-2"
        shell.mode = "standard"
        val status = GameResolution.status(shell, store, ml)
        assertTrue(status.resetByRestart)
        assertNull(status.changedOutside)

        shell.commands.clear()
        val again = GameResolution.reapplyBeforeLaunch(shell, store, ml)!!
        assertTrue(again.detail, again.ok)
        assertEquals(listOf("cmd game mode custom $ml"), shell.writes)
        assertEquals("custom", shell.mode)
        assertEquals("0.65" to null, shell.configs[4])

        // Done once: nothing to do on the next launch.
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, ml))
        assertFalse(GameResolution.status(shell, store, ml).resetByRestart)
    }

    @Test
    fun `standard mode without a restart is an outside change and is never re-applied`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.MEDIUM, null).ok)
        shell.mode = "standard"

        assertEquals("standard", GameResolution.status(shell, store, ml).changedOutside)
        shell.commands.clear()
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, ml))
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun `after a restart a config someone else changed is left alone`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.MEDIUM, null).ok)
        shell.bootId = "boot-2"
        shell.mode = "standard"
        shell.configs[4] = "0.65" to "60" // a frame-rate cap this app never sets

        val status = GameResolution.status(shell, store, ml)
        assertFalse(status.resetByRestart)
        assertEquals("standard", status.changedOutside)
        shell.commands.clear()
        assertNull(GameResolution.reapplyBeforeLaunch(shell, store, ml))
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun `DEFAULT after a restart reset restores without asking`() {
        val shell = codmShell()
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, codm, ResolutionLevel.LOW, null).ok)
        shell.bootId = "boot-2"
        shell.mode = "standard"

        val restored = GameResolution.restore(shell, store, codm, force = false, runningPid = null)
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
        val shell = GameShell(ml, available = listOf("standard", "performance"))
        val result = GameResolution.apply(shell, FakeStore(), ml, ResolutionLevel.LOW, null)
        assertFalse(result.ok)
        assertTrue(result.unavailable)
        assertTrue(shell.writes.isEmpty())
    }

    // --- running game ------------------------------------------------------------------------

    @Test
    fun `a change while the game runs is pending until that process is gone`() {
        val shell = GameShell(ml, pid = "16439")
        val store = FakeStore()

        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.MEDIUM, "16439").ok)
        assertTrue(GameResolution.status(shell, store, ml).pendingRestart)

        shell.pid = "20001" // relaunched
        assertFalse(GameResolution.status(shell, store, ml).pendingRestart)
        assertNull(GameResolution.stalePid(store, ml))
    }

    @Test
    fun `apply never force-stops the game`() {
        val shell = GameShell(ml, pid = "16439")
        GameResolution.apply(shell, FakeStore(), ml, ResolutionLevel.LOW, "16439")
        GameResolution.restore(shell, FakeStore(), ml, force = true, runningPid = "16439")
        assertFalse(shell.commands.any { it.contains("force-stop") || it.startsWith("kill") })
    }

    // --- parsing (real Pova 7 output) -------------------------------------------------------

    @Test
    fun `parses list-modes and list-configs as printed on Android 16`() {
        assertEquals(
            "performance" to listOf("standard", "performance", "custom"),
            GameResolution.parseModes("$codm current mode: performance, available game modes: [standard,performance,custom]")
        )
        val configs = GameResolution.parseConfigs(
            "$codm interventions: \n[Name:$codm Modes: {2=[Game Mode:2,Scaling:0.8,Use Angle:false,Fps:60,Loading Boost Duration:-1], " +
                "4=[Game Mode:4,Scaling:0.7,Use Angle:false,Fps:90,Loading Boost Duration:-1]}]"
        )!!
        assertEquals(InterventionConfig("0.8", "60"), configs[2])
        assertEquals(InterventionConfig("0.7", "90"), configs[4])
        assertEquals(
            InterventionConfig("0.83", null),
            GameResolution.parseConfigs("[Name:$ml Modes: {4=[Game Mode:4,Scaling:0.83,Use Angle:false,Fps:,Loading Boost Duration:-1]}]")!![4]
        )
        assertEquals(emptyMap<Int, InterventionConfig>(), GameResolution.parseConfigs(" No intervention found for package $ml"))
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
        assertEquals(BufferSize(810, 1845), GameResolution.parseBuffers(sfLines, ml))
        // Only a window buffer: that is what the game draws into.
        assertEquals(BufferSize(1080, 2460), GameResolution.parseBuffers(sfLines.lines()[2], ml))
        assertNull(GameResolution.parseBuffers(sfLines, "com.example.other"))
        assertNull(GameResolution.parseBuffers("garbage", ml))
        assertNull(GameResolution.parseBuffers(null, ml))
    }

    // --- result line -------------------------------------------------------------------------

    @Test
    fun `result line compares with the native measurement`() {
        val native = BufferSize(810, 1845)
        // Measured on the Pova 7: Mobile Legends at 0.67.
        assertEquals("Renders at 724x1648 (−20% pixels)", GameResolution.resultText(native, BufferSize(724, 1648)))
        // Mobile Legends at 0.83: more pixels than native.
        assertEquals("No effect: game already renders below this", GameResolution.resultText(native, BufferSize(815, 1858)))
        // CoD Mobile at 0.67: under half a percent, within its own 720p cap.
        assertEquals(
            "No effect: game already renders below this",
            GameResolution.resultText(BufferSize(720, 1648), BufferSize(720, 1639))
        )
        assertEquals("Renders at 648x1472", GameResolution.resultText(null, BufferSize(648, 1472)))
        assertNull(GameResolution.resultText(native, null))
    }

    // --- measurement filing ------------------------------------------------------------------

    private fun file(pid: String, scale: String?, size: String) =
        "pid:$pid\nscale:${scale ?: "none"}\n+ name:a SurfaceView[$ml/b.C]#1, w/h:$size, x"

    @Test
    fun `native is stored once, under the game's default only`() {
        val shell = GameShell(ml, measureFile = file("100", null, "810x1845"))
        val store = FakeStore()

        assertTrue(GameResolution.collect(shell, store, ml))
        assertEquals(BufferSize(810, 1845), GameResolution.native(store, ml))
        assertNull(shell.measureFile) // removed once read

        shell.measureFile = file("101", null, "1080x2460")
        GameResolution.collect(shell, store, ml)
        assertEquals(BufferSize(810, 1845), GameResolution.native(store, ml))
    }

    @Test
    fun `a measurement is shown only for the preset it was taken under`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.LOW, null).ok)

        shell.measureFile = file("200", "0.65", "702x1599") // an older preset: dropped
        assertFalse(GameResolution.collect(shell, store, ml))
        assertNull(GameResolution.measured(store, ml, "0.6"))

        shell.measureFile = file("201", "0.6", "648x1476")
        assertTrue(GameResolution.collect(shell, store, ml))
        assertEquals(BufferSize(648, 1476), GameResolution.measured(store, ml, "0.6"))
        assertEquals("Renders at 648x1476", GameResolution.status(shell, store, ml).result)
    }

    @Test
    fun `a measurement of the process that predates the change is ignored`() {
        val shell = GameShell(ml, pid = "300")
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.LOW, "300").ok)

        assertFalse(GameResolution.record(store, ml, GameResolution.Measurement("300", "0.6", BufferSize(810, 1845))))
        assertNull(GameResolution.measured(store, ml, "0.6"))
    }

    @Test
    fun `no measurement means no result line, never an estimate`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.LOW, null).ok)
        assertNull(GameResolution.status(shell, store, ml).result)
        shell.measureFile = "pid:5\nscale:0.6\nno buffer lines here"
        assertNull(GameResolution.status(shell, store, ml).result)
    }

    // --- session record ---------------------------------------------------------------------

    @Test
    fun `session records carry the preset and older ones still decode`() {
        val shell = GameShell(ml)
        val store = FakeStore()
        assertEquals("default", GameResolution.sessionValue(store, ml))
        assertTrue(GameResolution.apply(shell, store, ml, ResolutionLevel.LOW, null).ok)
        assertEquals("LOW 0.6", GameResolution.sessionValue(store, ml))

        val record = SessionRecord(
            packageName = ml, refreshRate = 90f, startedAtMs = 1L, durationMs = 2L,
            avgFps = null, onePercentLowFps = null, msToThermal1 = null, maxThermal = null,
            samples = 0, fpsSamples = 0, thermalSamples = 0, observedPeak = null,
            renderResolution = GameResolution.sessionValue(store, ml)
        )
        assertEquals(record, SessionRecord.decode(record.encode()))
        // Written by the removed display lever.
        assertEquals("900x2050@400", SessionRecord.decode(record.copy(renderResolution = "900x2050@400").encode())!!.renderResolution)
    }

    // --- shell watcher -----------------------------------------------------------------------

    @Test
    fun `watcher runs detached in the shell, skips the stale process and replaces an older watcher`() {
        val shell = GameShell(ml)
        assertTrue(GameResolution.startWatcher(shell, ml, "0.6", "16439"))

        val launch = shell.commands.last()
        assertTrue(launch, launch.startsWith("setsid sh -c '"))
        assertTrue(launch, launch.endsWith("</dev/null >/dev/null 2>&1 &"))
        assertTrue(launch, launch.contains("pidof $ml"))
        assertTrue(launch, launch.contains("!= \"16439\""))
        assertTrue(launch, launch.contains("echo \"scale:0.6\""))
        assertTrue(launch, launch.contains("dumpsys SurfaceFlinger"))
        assertFalse(launch, launch.contains("force-stop"))
        assertTrue(shell.commands[shell.commands.size - 2].contains("kill"))
    }

    @Test
    fun `nothing reaches the shell for a value that is not a package name`() {
        val shell = GameShell(ml)
        val bad = "x; reboot"
        assertFalse(GameResolution.startWatcher(shell, bad, null, null))
        assertFalse(GameResolution.apply(shell, FakeStore(), bad, ResolutionLevel.LOW, null).ok)
        assertTrue(shell.commands.isEmpty())
    }
}
