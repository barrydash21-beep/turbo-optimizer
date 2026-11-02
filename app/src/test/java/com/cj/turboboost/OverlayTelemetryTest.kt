package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTelemetryTest {

    private val cpuLine1 = "cpu  100 0 100 800 0 0 0 0 0 0"
    private val cpuLine2 = "cpu  150 0 150 900 0 0 0 0 0 0"

    // ---- /proc/stat arithmetic ----

    @Test fun `the aggregate cpu line parses to busy and total ticks`() {
        assertEquals(CpuTicks(busy = 200, total = 1000), CpuUsage.parse(cpuLine1))
        // iowait counts as idle.
        assertEquals(CpuTicks(busy = 100, total = 200), CpuUsage.parse("cpu 60 0 40 70 30 0 0 0"))
    }

    @Test fun `anything that is not the aggregate line does not parse`() {
        assertNull(CpuUsage.parse(null))
        assertNull(CpuUsage.parse(""))
        assertNull(CpuUsage.parse("cpu0 1 2 3 4 5 6 7 8"))
        assertNull(CpuUsage.parse("cpu 1 2 3"))
        assertNull(CpuUsage.parse("cpu 1 2 x 4"))
        assertNull(CpuUsage.parse("cpu -1 2 3 4"))
        assertNull(CpuUsage.parse("head: /proc/stat: Permission denied"))
    }

    @Test fun `load is the busy share of the elapsed ticks`() {
        // 100 busy of 200 elapsed.
        assertEquals(50, CpuUsage.percent(CpuUsage.parse(cpuLine1)!!, CpuUsage.parse(cpuLine2)!!))
    }

    @Test fun `no elapsed time or a counter reset is no load, not zero`() {
        val a = CpuUsage.parse(cpuLine2)!!
        assertNull(CpuUsage.percent(a, a))
        assertNull(CpuUsage.percent(a, CpuUsage.parse(cpuLine1)!!))
    }

    // ---- the sampler ----

    @Test fun `the app's own read is used while it works, with no shell command`() {
        val shell = FakeShell()
        val lines = ArrayDeque(listOf(cpuLine1, cpuLine2))
        val sampler = CpuSampler(shell) { lines.removeFirst() }
        assertEquals(CpuReading(readable = true, percent = null), sampler.sample())
        assertEquals(CpuReading(readable = true, percent = 50), sampler.sample())
        assertEquals("the app", sampler.via)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `when the app may not read it the shell does, and the app is not asked again`() {
        var directCalls = 0
        val shell = FakeShell(captures = mapOf(CpuUsage.COMMAND to cpuLine1))
        val sampler = CpuSampler(shell) { directCalls++; null }
        assertEquals(CpuReading(readable = true), sampler.sample())
        sampler.sample()
        assertEquals(1, directCalls)
        assertEquals("Shizuku", sampler.via)
        assertEquals(2, shell.commands.count { it == CpuUsage.COMMAND })
    }

    @Test fun `unreadable on both routes is unreadable, never a number`() {
        val sampler = CpuSampler(FakeShell()) { null }
        assertEquals(CpuReading(readable = false, percent = null), sampler.sample())
    }

    @Test fun `a gap of failed reads is not spanned by the next load`() {
        val reads = ArrayDeque<String?>(listOf(cpuLine1, null, cpuLine2))
        val sampler = CpuSampler(FakeShell()) { reads.removeFirstOrNull() }
        sampler.sample()
        // The direct route failed here and the shell has nothing: unreadable, and the old sample is dropped.
        assertFalse(sampler.sample().readable)
        assertNull(sampler.sample().percent)
    }

    // ---- RAM ----

    @Test fun `ram share is used over total`() {
        assertEquals(75, RamUsage.percent(8_000, 2_000))
        assertNull(RamUsage.percent(0, 0))
    }

    // ---- FPS from presented frames ----

    private fun frames(count: Int, intervalMs: Long, startNs: Long = 1_000_000_000L): List<Long> =
        List(count) { startNs + it * intervalMs * 1_000_000L }

    @Test fun `frames 20 ms apart read as 50 fps`() {
        assertEquals(50, OverlayFps().consume(frames(51, 20)))
    }

    @Test fun `too few frames say nothing and are read again next time`() {
        val fps = OverlayFps()
        assertNull(fps.consume(frames(5, 20)))
        assertEquals(0L, fps.lastPresentNs)
        // The next poll still holds those five frames and adds the rest.
        assertEquals(50, fps.consume(frames(51, 20)))
    }

    @Test fun `the gap between two polls is measured`() {
        val fps = OverlayFps()
        val first = frames(30, 25)
        assertEquals(40, fps.consume(first))
        // The next poll starts 25 ms after the last frame counted: no frame is lost between them.
        val second = frames(30, 25, startNs = first.last() + 25_000_000L)
        assertEquals(40, fps.consume(second))
        assertEquals(second.last(), fps.lastPresentNs)
    }

    @Test fun `a loading pause is not a slow frame`() {
        val steady = frames(40, 16)
        val resumed = frames(40, 16, startNs = steady.last() + 5_000_000_000L)
        // 78 steady intervals and one 5 s pause: the pause is left out.
        val fps = OverlayFps().consume(steady + resumed)!!
        assertTrue("was $fps", fps in 60..64)
    }

    @Test fun `no frames is no fps`() {
        assertNull(OverlayFps().consume(emptyList()))
    }

    // ---- what is printed ----

    @Test fun `every unreadable figure says N-A`() {
        assertEquals("FPS N/A  CPU N/A  RAM N/A", OverlayText.format(null, CpuReading(readable = false), null))
    }

    @Test fun `readable figures are printed as they are`() {
        assertEquals("FPS 60  CPU 23%  RAM 61%", OverlayText.format(60, CpuReading(readable = true, percent = 23), 61))
    }

    @Test fun `the first tick before a delta shows a dash, not N-A`() {
        assertEquals("FPS 60  CPU --  RAM 61%", OverlayText.format(60, CpuReading(readable = true), 61))
    }

    // ---- the diagnostics row ----

    private fun input(shellReady: Boolean, cpuStat: String?, via: String? = null) = DiagnosticsInput(
        androidRelease = "16", sdk = 36, manufacturer = "TECNO", model = "TECNO LJ6", buildDisplay = "LJ6",
        shizukuRunning = shellReady, shizukuPermission = shellReady, shellReady = shellReady,
        gamePackage = null, gameVersion = null, helpOutput = null, read = null, status = null,
        lastChangeNote = null, capSession = null, thermalDump = null, restrictIgnored = null,
        cpuStat = cpuStat, cpuStatVia = via
    )

    private fun cpuRow(i: DiagnosticsInput) = DiagnosticsPanel.rows(i).first { it.name == "Overlay CPU reading" }

    @Test fun `a readable cpu line is Supported and says who read it`() {
        val row = cpuRow(input(true, cpuLine1, "Shizuku"))
        assertEquals(CheckStatus.SUPPORTED, row.status)
        assertTrue(row.evidence.contains("Shizuku"))
    }

    @Test fun `an unreadable one is Unsupported with the reason`() {
        val noShizuku = cpuRow(input(false, null))
        assertEquals(CheckStatus.UNSUPPORTED, noShizuku.status)
        assertTrue(noShizuku.evidence.contains("Shizuku is not connected"))
        val denied = cpuRow(input(true, "head: /proc/stat: Permission denied"))
        assertEquals(CheckStatus.UNSUPPORTED, denied.status)
        assertTrue(denied.evidence.contains("Permission denied"))
    }
}
