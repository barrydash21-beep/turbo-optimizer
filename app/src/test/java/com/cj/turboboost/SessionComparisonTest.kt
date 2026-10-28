package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComparisonTest {

    private fun run(
        startedAt: Long,
        avg: Float? = 60f,
        low: Float? = 45f,
        msToThermal1: Long? = null,
        maxThermal: Int? = 0,
        rate: Float = 90f,
        resolution: String? = "default",
        cap: Int? = null,
        pkg: String = FAKE_GAME
    ) = SessionRecord(
        packageName = pkg, refreshRate = rate, startedAtMs = startedAt, durationMs = 300_000L,
        avgFps = avg, onePercentLowFps = low, msToThermal1 = msToThermal1, maxThermal = maxThermal,
        samples = 10, fpsSamples = 10, thermalSamples = 10, renderResolution = resolution, fpsCap = cap
    )

    @Test
    fun `median of odd and even counts, none for nothing`() {
        assertEquals(2.0, SessionComparison.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, SessionComparison.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertNull(SessionComparison.median(emptyList()))
    }

    @Test
    fun `sessions group by rate, resolution and cap, for the selected game only`() {
        val records = listOf(
            run(1, cap = 40), run(2, cap = 40), run(3),
            run(4, resolution = "LOWER 0.55", cap = 40),
            run(5, rate = 60f),
            run(6, pkg = "com.example.other")
        )
        val groups = SessionComparison.groups(records, FAKE_GAME)
        assertEquals(4, groups.size)
        // Most recently played first.
        assertEquals(SessionGroupKey(60f, "default", null), groups[0].key)
        assertEquals(2, groups.first { it.key == SessionGroupKey(90f, "default", 40) }.runs)
        assertEquals("90 Hz, default, cap 40", SessionGroupKey(90f, "default", 40).label)
        assertEquals("90 Hz, resolution not recorded, no cap", SessionGroupKey(90f, null, null).label)
    }

    @Test
    fun `baselines and boosted runs, and runs under different game states, never share a group`() {
        val records = listOf(
            run(1).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard"),
            run(2).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard"),
            run(3).copy(profile = "BALANCED", gameStateStart = "standard"),
            run(4).copy(profile = "BALANCED", gameStateStart = "custom;4=0.6/60")
        )
        val groups = SessionComparison.groups(records, FAKE_GAME)
        assertEquals(3, groups.size)
        assertEquals(2, groups.first { it.key.profile == SessionRecord.BASELINE }.runs)
        assertEquals(
            "BALANCED, 90 Hz, default, no cap, mode custom 4=0.6/60",
            SessionGroupKey(90f, "default", null, "BALANCED", "custom;4=0.6/60").label
        )
    }

    @Test
    fun `restarted, contaminated and invalid baseline sessions are left out and counted, unknown ones kept`() {
        val records = listOf(
            run(1).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard", contamination = Contamination.CLEAN.name),
            run(2).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard;2=0.8/90"),
            run(3).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard", contamination = Contamination.CONTAMINATED.name),
            run(4).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard", endReason = EndReason.RESTARTED.code),
            run(5).copy(profile = SessionRecord.BASELINE, gameStateStart = "standard", contamination = Contamination.UNKNOWN.name)
        )
        assertEquals(2, SessionComparison.groups(records, FAKE_GAME).single().runs)
        assertEquals(3, SessionComparison.excludedCount(records, FAKE_GAME))
        assertEquals(0, SessionComparison.excludedCount(records, "com.example.other"))
    }

    @Test
    fun `medians skip missing values and say how many runs had data`() {
        val stats = SessionComparison.stats(
            SessionGroupKey(90f, "default", 40),
            listOf(
                run(1, avg = 40f, low = null, msToThermal1 = 120_000L, maxThermal = 1),
                run(2, avg = null, low = 30f, msToThermal1 = null, maxThermal = 0),
                run(3, avg = 39f, low = 32f, msToThermal1 = 60_000L, maxThermal = 2),
                run(4, avg = 41f, low = null, msToThermal1 = null, maxThermal = null)
            )
        )
        assertEquals(4, stats.runs)
        assertEquals(40f, stats.avgFps!!, 0f)
        assertEquals(3, stats.avgFpsRuns)
        assertEquals(31f, stats.onePercentLow!!, 0f)
        assertEquals(2, stats.onePercentLowRuns)
        assertEquals(90_000L, stats.msToThermal1)
        assertEquals(2, stats.reachedThermal1)
        // Run 2 was readable and stayed at 0; run 4 had no thermal reading at all.
        assertEquals(1, stats.neverReachedThermal1)
        assertEquals(2, stats.worstThermal)
        assertEquals(3, stats.thermalRuns)
    }

    @Test
    fun `no data at all stays null, never zero`() {
        val stats = SessionComparison.stats(
            SessionGroupKey(90f, null, null),
            listOf(run(1, avg = null, low = null, maxThermal = null))
        )
        assertNull(stats.avgFps)
        assertNull(stats.onePercentLow)
        assertNull(stats.msToThermal1)
        assertNull(stats.worstThermal)
        assertEquals(0, stats.neverReachedThermal1)
    }

    @Test
    fun `a difference needs three runs in both groups`() {
        val a = SessionComparison.stats(SessionGroupKey(90f, "default", null), (1L..3L).map { run(it, avg = 58f, msToThermal1 = 100_000L, maxThermal = 1) })
        val b = SessionComparison.stats(SessionGroupKey(90f, "default", 40), (4L..6L).map { run(it, avg = 40f, msToThermal1 = 160_000L, maxThermal = 1) })
        val diff = SessionComparison.difference(a, b)
        assertNotNull(diff)
        assertEquals(-18f, diff!!.avgFps!!, 0.001f)
        assertEquals(60_000L, diff.msToThermal1)

        val short = SessionComparison.stats(b.key, (4L..5L).map { run(it, avg = 40f) })
        assertFalse(short.enoughRuns)
        assertNull(SessionComparison.difference(a, short))
        assertNull(SessionComparison.difference(short, a))
        assertTrue(a.enoughRuns)
    }

    @Test
    fun `a difference is left out for a value one group never recorded`() {
        val a = SessionComparison.stats(SessionGroupKey(90f, "default", null), (1L..3L).map { run(it, maxThermal = 0) })
        val b = SessionComparison.stats(SessionGroupKey(90f, "default", 40), (4L..6L).map { run(it, msToThermal1 = 50_000L, maxThermal = 1) })
        assertNull(SessionComparison.difference(a, b)!!.msToThermal1)
    }
}
