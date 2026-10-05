package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaves like `wm size` / `wm density`: overrides are held, printed, and cleared by `reset`. */
private class WmShell(
    private val physicalSize: DisplaySize,
    private val physicalDensity: Int,
    var overrideSize: DisplaySize? = null,
    var overrideDensity: Int? = null,
    /** Simulates a ROM that accepts `wm density` (exit 0) but keeps its own value. */
    private val ignoreDensity: Boolean = false,
    var available: Boolean = true
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable() = available

    override fun run(command: String): ShellResult {
        commands += command
        val parts = command.split(" ")
        if (parts.size == 3 && parts[0] == "wm") {
            when (parts[1]) {
                "size" -> overrideSize = if (parts[2] == "reset") null else DisplaySize.parse(parts[2])
                    ?.takeIf { it != physicalSize }
                "density" -> if (!ignoreDensity) {
                    overrideDensity = if (parts[2] == "reset") null else parts[2].toInt().takeIf { it != physicalDensity }
                }
            }
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        return when (command) {
            "wm size" -> "Physical size: $physicalSize" + (overrideSize?.let { "\nOverride size: $it" } ?: "")
            "wm density" -> "Physical density: $physicalDensity" + (overrideDensity?.let { "\nOverride density: $it" } ?: "")
            else -> null
        }
    }
}

class ResolutionTest {

    private val pova = DisplaySize(1080, 2460)

    // --- parsing ------------------------------------------------------------------------

    @Test
    fun `parses wm output with no override lines as no override`() {
        val state = DisplayResolution.parseState("Physical size: 1080x2460", "Physical density: 480")!!
        assertEquals(pova, state.physicalSize)
        assertNull(state.overrideSize)
        assertEquals(480, state.physicalDensity)
        assertNull(state.overrideDensity)
        assertFalse(state.hasOverride)
        assertEquals(pova, state.size)
        assertEquals(480, state.density)
    }

    @Test
    fun `parses override lines, with CRLF and stray whitespace`() {
        val state = DisplayResolution.parseState(
            "Physical size: 1080x2460\r\nOverride size: 900x2050\r\n",
            "  Physical density: 480\n  Override density: 400  \n"
        )!!
        assertEquals(DisplaySize(900, 2050), state.overrideSize)
        assertEquals(400, state.overrideDensity)
        assertTrue(state.hasOverride)
        assertEquals(DisplaySize(900, 2050), state.size)
        assertEquals(400, state.density)
    }

    @Test
    fun `a density-only override is still an override`() {
        val state = DisplayResolution.parseState("Physical size: 1080x2460", "Physical density: 480\nOverride density: 440")!!
        assertNull(state.overrideSize)
        assertEquals(440, state.overrideDensity)
        assertTrue(state.hasOverride)
    }

    @Test
    fun `missing physical lines or unreadable output give no state`() {
        assertNull(DisplayResolution.parseState(null, "Physical density: 480"))
        assertNull(DisplayResolution.parseState("Physical size: 1080x2460", null))
        assertNull(DisplayResolution.parseState("Override size: 900x2050", "Physical density: 480"))
        assertNull(DisplayResolution.parseState("Error: no display", "Physical density: 480"))
        assertNull(DisplaySize.parse("1080 by 2460"))
        assertNull(DisplaySize.parse("0x2460"))
    }

    // --- preset and density math --------------------------------------------------------

    @Test
    fun `Pova 7 presets are exactly 900x2050 and 720x1640 with density scaled by 5-6 and 2-3`() {
        val state = DisplayState(pova, null, 480, null)
        val presets = DisplayResolution.presets(state)

        assertEquals(listOf("NATIVE", "83%", "67%"), presets.map { it.label })
        assertEquals(listOf(100, 83, 67), presets.map { it.percent })
        assertTrue(presets[0].native)
        assertEquals(ResolutionTarget(pova, 480), presets[0].target)
        assertEquals(ResolutionTarget(DisplaySize(900, 2050), 400), presets[1].target)
        assertEquals(ResolutionTarget(DisplaySize(720, 1640), 320), presets[2].target)
        assertEquals(0.833, 400.0 / 480, 0.001)
        assertEquals(0.667, 320.0 / 480, 0.001)
    }

    @Test
    fun `presets come from the physical size even when an override is active`() {
        val withOverride = DisplayState(pova, DisplaySize(1000, 2278), 480, 440)
        val without = DisplayState(pova, null, 480, null)
        assertEquals(
            DisplayResolution.presets(without).map { it.target },
            DisplayResolution.presets(withOverride).map { it.target }
        )
    }

    @Test
    fun `other panels keep aspect ratio and land on even numbers`() {
        // A 1080x2436 panel, for instance.
        val tall = DisplayResolution.presets(DisplayState(DisplaySize(1080, 2436), null, 480, null))
        assertEquals(DisplaySize(900, 2030), tall[1].target.size)
        assertEquals(DisplaySize(720, 1624), tall[2].target.size)

        // 1440x3200 @ 560: heights 2666.67 and 2133.33 must round to even.
        val qhd = DisplayResolution.presets(DisplayState(DisplaySize(1440, 3200), null, 560, null))
        assertEquals(ResolutionTarget(DisplaySize(1200, 2666), 467), qhd[1].target)
        assertEquals(ResolutionTarget(DisplaySize(960, 2134), 373), qhd[2].target)
        for (p in tall + qhd) {
            assertEquals(0, p.target.size.width % 2)
            assertEquals(0, p.target.size.height % 2)
        }
    }

    @Test
    fun `nothing below 50 percent of physical width is offered or allowed`() {
        assertNull(DisplayResolution.scaled(pova, 480, 1, 3))
        assertNull(DisplayResolution.scaled(pova, 480, 49, 100))
        assertEquals(ResolutionTarget(DisplaySize(540, 1230), 240), DisplayResolution.scaled(pova, 480, 1, 2))

        assertFalse(DisplayResolution.isAllowed(ResolutionTarget(DisplaySize(538, 1226), 239), pova))
        assertFalse(DisplayResolution.isAllowed(ResolutionTarget(DisplaySize(1200, 2732), 533), pova))
        assertFalse(DisplayResolution.isAllowed(ResolutionTarget(DisplaySize(900, 2050), 0), pova))
        assertTrue(DisplayResolution.isAllowed(ResolutionTarget(DisplaySize(540, 1230), 240), pova))
    }

    @Test
    fun `own preset is recognised, a user override is not`() {
        assertTrue(DisplayResolution.isOwnPreset(DisplayState(pova, DisplaySize(900, 2050), 480, 400)))
        assertFalse(DisplayResolution.isOwnPreset(DisplayState(pova, DisplaySize(900, 2050), 480, null)))
        assertFalse(DisplayResolution.isOwnPreset(DisplayState(pova, null, 480, 440)))
        assertFalse(DisplayResolution.isOwnPreset(DisplayState(pova, null, 480, null)))
    }

    @Test
    fun `summary value names the override or native`() {
        assertEquals("900x2050@400", DisplayResolution.summaryValue(DisplayState(pova, DisplaySize(900, 2050), 480, 400)))
        assertEquals("native", DisplayResolution.summaryValue(DisplayState(pova, null, 480, null)))
        assertNull(DisplayResolution.summaryValue(null))
    }

    // --- apply / restore ----------------------------------------------------------------

    private val p83 = ResolutionTarget(DisplaySize(900, 2050), 400)
    private val p67 = ResolutionTarget(DisplaySize(720, 1640), 320)

    @Test
    fun `no prior override - apply, read back, restore resets both and clears pending`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()

        val applied = ResolutionLever.apply(shell, store, p83)
        assertTrue(applied.detail, applied.ok)
        assertTrue(ResolutionLever.isPending(store))
        assertEquals(p83, ResolutionLever.intended(store))
        assertTrue(shell.commands.containsAll(listOf("wm size 900x2050", "wm density 400")))

        val restored = ResolutionLever.restore(shell, store)
        assertTrue(restored.detail, restored.ok)
        assertTrue(shell.commands.containsAll(listOf("wm size reset", "wm density reset")))
        assertNull(shell.overrideSize)
        assertNull(shell.overrideDensity)
        assertFalse(restored.state!!.hasOverride)
        assertFalse(ResolutionLever.isPending(store))
        assertNull(ResolutionLever.intended(store))
    }

    @Test
    fun `existing override - restore re-applies the stored override instead of resetting`() {
        val shell = WmShell(pova, 480, overrideSize = DisplaySize(1000, 2278), overrideDensity = 440)
        val store = FakeStore()

        assertTrue(ResolutionLever.apply(shell, store, p67).ok)
        val restored = ResolutionLever.restore(shell, store)

        assertTrue(restored.detail, restored.ok)
        assertEquals(DisplaySize(1000, 2278), shell.overrideSize)
        assertEquals(440, shell.overrideDensity)
        assertTrue(shell.commands.containsAll(listOf("wm size 1000x2278", "wm density 440")))
        assertFalse(shell.commands.contains("wm size reset"))
        assertFalse(ResolutionLever.isPending(store))
    }

    @Test
    fun `a second apply keeps the first original`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()

        assertTrue(ResolutionLever.apply(shell, store, p83).ok)
        assertTrue(ResolutionLever.apply(shell, store, p67).ok)
        assertEquals(p67, ResolutionLever.intended(store))

        assertTrue(ResolutionLever.restore(shell, store).ok)
        assertNull(shell.overrideSize)
        assertNull(shell.overrideDensity)
    }

    @Test
    fun `read-back mismatch restores immediately and reports both sides`() {
        val shell = WmShell(pova, 480, ignoreDensity = true)
        val store = FakeStore()

        val result = ResolutionLever.apply(shell, store, p83)

        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("asked for 900x2050 @ 400 dpi"))
        assertTrue(result.detail, result.detail.contains("system reports 900x2050 @ 480 dpi"))
        assertTrue(result.detail, result.detail.endsWith("restored"))
        assertNull(shell.overrideSize)
        assertFalse(ResolutionLever.isPending(store))
    }

    @Test
    fun `below the floor is rejected before anything is written`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()

        val result = ResolutionLever.apply(shell, store, ResolutionTarget(DisplaySize(500, 1138), 222))

        assertFalse(result.ok)
        assertFalse(ResolutionLever.isPending(store))
        assertFalse(shell.commands.any { it.startsWith("wm size ") || it.startsWith("wm density ") })
    }

    @Test
    fun `restore without Shizuku keeps pending for the next start`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()
        assertTrue(ResolutionLever.apply(shell, store, p83).ok)

        shell.available = false
        val result = ResolutionLever.restore(shell, store)

        assertFalse(result.ok)
        assertTrue(result.unavailable)
        assertTrue(ResolutionLever.isPending(store))
        assertEquals(DisplaySize(900, 2050), shell.overrideSize)
    }

    @Test
    fun `a restore the read-back does not confirm keeps pending`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()
        assertTrue(ResolutionLever.apply(shell, store, p83).ok)

        // Something re-applies an override straight after our reset.
        val sticky = object : ShellExecutor by shell {
            override fun run(command: String): ShellResult {
                val r = shell.run(command)
                if (command == "wm density reset") shell.overrideDensity = 400
                return r
            }
        }
        val result = ResolutionLever.restore(sticky, store)

        assertFalse(result.ok)
        assertTrue(result.detail, result.detail.contains("system still reports"))
        assertTrue(ResolutionLever.isPending(store))
    }

    @Test
    fun `restoreIfPending runs nothing when nothing is pending`() {
        val shell = WmShell(pova, 480, overrideSize = DisplaySize(1000, 2278))
        val store = FakeStore()

        assertTrue(ResolutionLever.restoreIfPending(shell, store).ok)
        assertTrue(shell.commands.isEmpty())
        assertEquals(DisplaySize(1000, 2278), shell.overrideSize)
    }

    @Test
    fun `restore with no snapshot resets both axes`() {
        val shell = WmShell(pova, 480, overrideSize = DisplaySize(900, 2050), overrideDensity = 400)
        val store = FakeStore()

        assertTrue(ResolutionLever.restore(shell, store).ok)
        assertNull(shell.overrideSize)
        assertNull(shell.overrideDensity)
    }

    // --- shell watchdog ----------------------------------------------------------------

    @Test
    fun `watchdog runs detached in the shell and resets when there was no override`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()
        assertTrue(ResolutionLever.apply(shell, store, p83).ok)

        assertTrue(ResolutionLever.startWatchdog(shell, store, 15))

        val launch = shell.commands.last()
        assertTrue(launch, launch.startsWith("setsid sh -c '"))
        assertTrue(launch, launch.endsWith("</dev/null >/dev/null 2>&1 &"))
        assertTrue(launch, launch.contains("sleep 15;"))
        assertTrue(launch, launch.contains("wm size reset; wm density reset'"))
        // Any earlier deadline is cancelled first.
        assertTrue(shell.commands[shell.commands.size - 2].contains("kill"))
    }

    @Test
    fun `watchdog puts an existing override back, not a reset`() {
        val shell = WmShell(pova, 480, overrideSize = DisplaySize(1000, 2278), overrideDensity = 440)
        val store = FakeStore()
        assertTrue(ResolutionLever.apply(shell, store, p67).ok)

        ResolutionLever.startWatchdog(shell, store, 15)

        val launch = shell.commands.last()
        assertTrue(launch, launch.contains("wm size 1000x2278; wm density 440'"))
        assertFalse(launch, launch.contains("reset"))
    }

    @Test
    fun `every restore cancels the watchdog before touching the display`() {
        val shell = WmShell(pova, 480)
        val store = FakeStore()
        assertTrue(ResolutionLever.apply(shell, store, p83).ok)
        ResolutionLever.startWatchdog(shell, store, 15)
        shell.commands.clear()

        assertTrue(ResolutionLever.restore(shell, store).ok)

        assertTrue(shell.commands.first(), shell.commands.first().contains("kill"))
        assertTrue(shell.commands.indexOf("wm size reset") > 0)
    }

    // --- session record -----------------------------------------------------------------

    @Test
    fun `session records carry the render resolution and older ones still decode`() {
        val record = SessionRecord(
            packageName = "com.example.game", refreshRate = 90f, startedAtMs = 1L, durationMs = 2L,
            avgFps = null, onePercentLowFps = null, msToThermal1 = null, maxThermal = null,
            samples = 0, fpsSamples = 0, thermalSamples = 0, observedPeak = null,
            renderResolution = "900x2050@400"
        )
        assertEquals(record, SessionRecord.decode(record.encode()))
        assertEquals(record.copy(renderResolution = null), SessionRecord.decode(record.copy(renderResolution = null).encode()))

        val v2 = SessionRecord.decode("com.example.game|90.0|1|2||||1|0|0|0|120.0")!!
        assertNull(v2.renderResolution)
        assertEquals(120f, v2.observedPeak)
    }
}
