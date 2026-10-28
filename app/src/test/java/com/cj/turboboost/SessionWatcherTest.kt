package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SessionWatcherTest {

    private val authority = "com.cj.turboboost.session"
    private val token = SessionEnd.newToken()
    private val frameNs = 16_666_667L

    @Test
    fun `the script holds the package, the authority and every constant, with no placeholder left`() {
        val script = SessionWatcher.script(FAKE_GAME, authority)
        assertFalse(script.contains('@'))
        assertTrue(script.contains("P=$FAKE_GAME; A=$authority"))
        // Stops at its next tick once the app removes the token.
        assertTrue(script.contains("[ -f \$B.token ] || { rm -f \$B.pid; exit 0; }"))
        assertTrue(script.contains("-ge ${3 * 60 * 60 * 1000L} ]"))
        assertTrue(script.contains("[ ${SessionWatcher.FRAME_READ_MS} -gt 0 ] && [ $((T - LF)) -ge ${SessionWatcher.FRAME_READ_MS} ]"))
        assertTrue(script.contains("-ge ${SessionWatcher.THERMAL_READ_S * 1000} ]"))
        assertTrue(script.contains("-ge ${SessionWatcher.LAUNCH_GRACE_S * 1000} ]"))
        assertTrue(script.contains("[ \$M -ge ${SessionWatcher.MISSES_TO_EXIT} ]"))
        // A changed pid is a restart in front, an exit in the background.
        assertTrue(script.contains("*\" \$G \"*) ;; *) if front; then R=restarted; else R=exited; fi; break ;;"))
        // The session starts only once the game is in front, with the battery temperature.
        assertTrue(script.contains("if front; then G=\$(echo \$p); G=\${G%% *}; echo \"S \$T \$G\" >> \$D; echo \"B \$T \$(battery)\" >> \$D; fi"))
        assertTrue(script.contains("grep -m1 'topResumedActivity=' | grep -qF \" \$P/\""))
        assertTrue(script.contains("[ -n \"\$G\" ] && echo \"B \$E \$(battery)\" >> \$D"))
        // The token is read from its file for each call, never put on a command line by the app.
        assertTrue(script.contains("content call --uri content://\$A --method end --arg \"\$(cat \$B.token)\" --extra reason:s:\$R"))
        // The tick sleeps only what is left of it, so reads stay on the cadence.
        assertTrue(script.contains("w=$((T + ${SessionWatcher.TICK_MS} - $(ms)))"))
    }

    @Test
    fun `the script is refused for anything that is not a package or an authority`() {
        for (bad in listOf("com.example.game; reboot", "com.example game", "\$(id)", "")) {
            assertTrue(bad, runCatching { SessionWatcher.script(bad, authority) }.isFailure)
        }
        assertTrue(runCatching { SessionWatcher.script(FAKE_GAME, "com.example.session'; reboot") }.isFailure)
    }

    @Test
    fun `start writes the token and the script, launches it detached and checks it runs`() {
        val shell = FakeShell()
        assertTrue(SessionWatcher.start(shell, FAKE_GAME, token, authority))
        val command = shell.commands.single()
        assertTrue(command.startsWith("rm -f ${SessionWatcher.TOKEN}"))
        assertTrue(command.contains("umask 077; echo $token > ${SessionWatcher.TOKEN}"))
        assertTrue(command.contains("setsid sh ${SessionWatcher.BASE}.sh </dev/null >/dev/null 2>&1 &"))
        assertTrue(command.endsWith("[ -f ${SessionWatcher.BASE}.pid ] && kill -0 \$(cat ${SessionWatcher.BASE}.pid)"))
        val encoded = command.substringAfter("echo ").substringAfter("echo ").substringBefore(" | base64 -d")
        assertEquals(SessionWatcher.script(FAKE_GAME, authority), String(Base64.getDecoder().decode(encoded)))
    }

    @Test
    fun `a token that is not 32 hex digits starts nothing`() {
        val shell = FakeShell()
        assertFalse(SessionWatcher.start(shell, FAKE_GAME, "not-a-token; reboot", authority))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `a watcher not seen running is stopped, so no second sampler is left`() {
        val shell = object : ShellExecutor {
            val commands = mutableListOf<String>()
            override fun isAvailable() = true
            override fun run(command: String): ShellResult {
                commands += command
                return if (command.contains("setsid")) ShellResult(exitCode = 1) else ShellResult.success()
            }
            override fun capture(command: String): String? = null
        }
        assertFalse(SessionWatcher.start(shell, FAKE_GAME, token, authority))
        assertEquals(2, shell.commands.size)
        assertTrue(shell.commands[1].startsWith("rm -f ${SessionWatcher.TOKEN}; [ -f ${SessionWatcher.BASE}.pid ] && kill"))
    }

    /** One `F` line as the watcher writes it: the first present, then each delta from the one before. */
    private fun frames(at: Long, contiguous: Boolean, first: Long, count: Int, rows: Int = 128): String =
        "F $at ${if (contiguous) 1 else 0} $rows $first" + " $frameNs".repeat(count - 1)

    @Test
    fun `reads are rebuilt from their deltas and folded like in-process samples`() {
        val start = 1_000_000_000_000L
        val text = listOf(
            "S 100000 4242",
            frames(102_000, contiguous = false, first = start, count = 121),
            frames(104_000, contiguous = true, first = start + 121 * frameNs, count = 120),
            // 5 s with no frames read: the next read starts over rather than counting the gap.
            frames(111_000, contiguous = false, first = start + 246 * frameNs + 5_000_000_000L, count = 121),
            "T 130000 1",
            "P 130000 120",
            "garbage line",
            "F 131000 1 5 12 x",
            "E 140000 exited"
        ).joinToString("\n")

        val data = SessionWatcher.parse(text)
        assertEquals(100_000L, data.seenAtMs)
        assertEquals("4242", data.gamePid)
        assertEquals(140_000L, data.endedAtMs)
        assertEquals("exited", data.endReason)
        assertEquals(3, data.frameReads)

        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, data.seenAtMs!!)
        data.foldInto(session)
        val record = session.finish(data.endedAtMs!!)
        assertEquals(60f, record.avgFps!!, 0.05f)
        // The 5 s gap was not counted as a frame, or the 1% low would collapse.
        assertEquals(60f, record.onePercentLowFps!!, 0.05f)
        assertEquals(3, record.fpsSamples)
        assertEquals(30_000L, record.msToThermal1)
        assertEquals(1, record.maxThermal)
        assertEquals(120f, record.observedPeak!!, 0f)
        assertEquals(40_000L, record.durationMs)
    }

    @Test
    fun `a chained read measures the gap from the read before, so a stall between reads counts`() {
        val start = 1_000_000_000_000L
        val stall = 80_000_000L
        val text = listOf(
            "S 0 1",
            frames(2_000, contiguous = false, first = start, count = 120),
            // No frame missed, but 80 ms passed before the next one: a spike.
            frames(4_000, contiguous = true, first = start + 119 * frameNs + stall, count = 120)
        ).joinToString("\n")
        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, 0L)
        SessionWatcher.parse(text).foldInto(session)
        val record = session.finish(4_000L)
        // The 2 worst of 239 intervals are 80 ms and 16.7 ms: 20.7 fps, against 60 without the stall.
        assertEquals(20.7f, record.onePercentLowFps!!, 0.1f)
    }

    @Test
    fun `a pause between reads that follow on is a pause, not a frame, and the series shows it as 0`() {
        val start = 1_000_000_000_000L
        val pause = 10_000_000_000L
        val text = listOf(
            "S 0 1",
            "B 0 377",
            frames(2_000, contiguous = false, first = start, count = 120),
            // A loading screen: nothing drawn for 10 s, then the read still follows on.
            frames(14_000, contiguous = true, first = start + 119 * frameNs + pause, count = 120),
            "B 16000 391",
            "E 16000 exited"
        ).joinToString("\n")
        val data = SessionWatcher.parse(text)
        assertEquals(37.7f, data.batteryStart!!, 0.001f)
        assertEquals(39.1f, data.batteryEnd!!, 0.001f)

        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, 0L)
        data.foldInto(session)
        val record = session.finish(16_000L)

        // Without the pause rule the 10 s would be one frame: 1% low near 0.2 fps.
        assertEquals(60f, record.avgFps!!, 0.05f)
        assertEquals(60f, record.onePercentLowFps!!, 0.05f)
        assertEquals(1, record.pauseCount)
        assertEquals(10_000L, record.pauseMs)
        assertEquals("1:10000", record.pauses)
        // 2 s of frames, about 10 s of nothing, then 2 s more: drawn seconds, then zeros.
        val series = record.fpsSeries!!.split(',')
        assertEquals("60", series[0])
        assertEquals("0", series[5])
        assertEquals(14, series.size)
    }

    @Test
    fun `a stretch the reads missed is unknown in the series, never 0`() {
        val start = 1_000_000_000_000L
        val text = listOf(
            "S 0 1",
            frames(2_000, contiguous = false, first = start, count = 120),
            // Not following on: frames between the two reads were missed.
            frames(9_000, contiguous = false, first = start + 5_000_000_000L, count = 120)
        ).joinToString("\n")
        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, 0L)
        SessionWatcher.parse(text).foldInto(session)
        val record = session.finish(9_000L)
        val series = record.fpsSeries!!.split(',')
        assertEquals("", series[3])
        assertEquals(0, record.pauseCount)
        assertTrue(series[0].toInt() >= 59)
    }

    @Test
    fun `the series and the pause list stop at their caps, and every pause is still counted`() {
        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, 0L)
        val second = 1_000_000_000L
        // One frame every 2 s for an hour: each gap is a pause.
        val presents = (0 until 1_800).map { 1_000_000_000_000L + it * 2 * second }
        session.addSample(0L, null, presents)
        val record = session.finish(3_600_000L)
        assertEquals(SessionAccumulator.MAX_SERIES_S, record.fpsSeries!!.split(',').size)
        assertEquals(SessionAccumulator.MAX_PAUSES, record.pauses!!.split(';').size)
        assertEquals(1_799, record.pauseCount)
        assertEquals(1_799L * 2_000L, record.pauseMs)
        assertNull(record.avgFps)
    }

    @Test
    fun `an overridden thermal status is no reading, and an unknown end reason is dropped`() {
        val data = SessionWatcher.parse("S 0 1\nT 30000 o\nT 60000 -\nE 70000 rebooted")
        val session = SessionAccumulator(FAKE_GAME, 60f, 0L, 0L)
        data.foldInto(session)
        val record = session.finish(70_000L)
        assertNull(record.maxThermal)
        assertEquals(0, record.thermalSamples)
        assertNull(data.endReason)
        assertEquals(70_000L, data.endedAtMs)
    }

    @Test
    fun `a game never seen leaves no start, and an empty file is no data`() {
        val data = SessionWatcher.parse("E 90000 never_started")
        assertNull(data.seenAtMs)
        assertEquals("never_started", data.endReason)
        assertNull(SessionWatcher.parse("").seenAtMs)
        assertNotNull(SessionWatcher.parse(""))
    }
}
