package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsParsingTest {

    private val thermalDump = """
        IsStatusOverride: false
        ThermalEventListeners:
        	callbacks: 5
        Thermal Status: 2
        Cached temperatures:
        	Temperature{mValue=53.985, mType=0, mName=CPU, mStatus=0}
    """.trimIndent()

    @Test
    fun `thermal status is read from the dump`() {
        assertEquals(2, Diagnostics.parseThermalStatus(thermalDump))
        assertFalse(Diagnostics.thermalOverridden(thermalDump))
    }

    @Test
    fun `an overridden thermal status is unavailable, not the forced value`() {
        val overridden = thermalDump.replace("IsStatusOverride: false", "IsStatusOverride: true")
        assertNull(Diagnostics.parseThermalStatus(overridden))
        assertTrue(Diagnostics.thermalOverridden(overridden))
    }

    @Test
    fun `a missing or garbled thermal dump is unavailable`() {
        assertNull(Diagnostics.parseThermalStatus(null))
        assertNull(Diagnostics.parseThermalStatus(""))
        assertNull(Diagnostics.parseThermalStatus("Thermal Status: hot"))
    }

    /** Lines captured from `dumpsys SurfaceFlinger --list` on the Pova 7 (Android 16). */
    private val layerList = """
        RequestedLayerState{StatusBar#92 parentId=88}
        RequestedLayerState{ActivityRecord{18714002 u0 com.example.thirdgame/com.example.thirdgame.UnityActivity t118}#28411 parentId=28410}
        RequestedLayerState{com.example.othergame/com.example.othergame.NativeActivity#176596}
        RequestedLayerState{ActivityRecord{29398193 u0 com.example.othergame/com.example.othergame.NativeActivity t690}#176579 parentId=176578}
        RequestedLayerState{26392a7 com.example.othergame/com.example.othergame.NativeActivity#176594 parentId=176579}
        RequestedLayerState{c0cc179 SurfaceView[com.ss.android.ugc.trill/com.ss.android.ugc.aweme.splash.SplashActivity](BLAST)#177148 parentId=177147}
        RequestedLayerState{Surface(name=ActivityRecord{39023040 u0 com.example.game/com.example.game.PermissionActivity t696})/@0xc9c5e34 - rotation-leash#176930}
        RequestedLayerState{7ee25b4 com.example.game/com.example.game.MainActivity#176949 parentId=176926}
        RequestedLayerState{SurfaceView[com.example.game/com.example.game.MainActivity](BLAST)#177001 parentId=176999}
    """.trimIndent()

    @Test
    fun `layer names are unwrapped up to their id`() {
        val names = Diagnostics.layerNames(layerList)
        assertTrue(names.contains("StatusBar#92"))
        assertTrue(names.contains("7ee25b4 com.example.game/com.example.game.MainActivity#176949"))
        assertTrue(names.contains("com.example.othergame/com.example.othergame.NativeActivity#176596"))
        // Pre-Android-14 bare names pass through.
        assertEquals(listOf("SurfaceView - com.foo/Bar#0"), Diagnostics.layerNames("SurfaceView - com.foo/Bar#0"))
    }

    @Test
    fun `candidate layers prefer the BLAST SurfaceView and drop containers`() {
        assertEquals(
            listOf(
                "SurfaceView[com.example.game/com.example.game.MainActivity](BLAST)#177001",
                "7ee25b4 com.example.game/com.example.game.MainActivity#176949"
            ),
            Diagnostics.candidateLayers(layerList, "com.example.game")
        )
        // A NativeActivity game has no SurfaceView: its window layers are the candidates.
        val bs = Diagnostics.candidateLayers(layerList, "com.example.othergame")
        assertEquals(2, bs.size)
        assertTrue(bs.none { it.startsWith("ActivityRecord{") })
        // The third game only has a container here: nothing to measure.
        assertTrue(Diagnostics.candidateLayers(layerList, "com.example.thirdgame").isEmpty())
        assertTrue(Diagnostics.candidateLayers(null, "com.example.thirdgame").isEmpty())
    }

    private fun latencyDump(presents: List<Long>, extraRows: List<String> = emptyList()): String =
        (listOf("8333333") + presents.map { "${it - 1000}\t$it\t${it - 500}" } + extraRows).joinToString("\n")

    @Test
    fun `present times skip unfilled rows, pending frames and frames already counted`() {
        val dump = latencyDump(
            listOf(1_000_000L, 2_000_000L, 3_000_000L),
            listOf("0\t0\t0", "5\t${Long.MAX_VALUE}\t5")
        )
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), Diagnostics.presentTimes(dump))
        assertEquals(listOf(3_000_000L), Diagnostics.presentTimes(dump, afterNs = 2_000_000L))
        assertTrue(Diagnostics.presentTimes("8333333\n0\t0\t0\n0\t0\t0").isEmpty())
        assertTrue(Diagnostics.presentTimes(null).isEmpty())
    }

    @Test
    fun `average fps is frames over elapsed time`() {
        // 90 frames at a steady 11.11 ms.
        val presents = (0..90).map { it * 11_111_111L + 1 }
        assertEquals(90f, Diagnostics.averageFps(Diagnostics.intervals(presents))!!, 0.1f)
    }

    @Test
    fun `too few frames is unavailable, not a guess`() {
        val presents = (0..5).map { it * 16_666_667L + 1 }
        assertNull(Diagnostics.averageFps(Diagnostics.intervals(presents)))
        assertNull(Diagnostics.onePercentLow(Diagnostics.intervals(presents)))
    }

    @Test
    fun `one percent low is the fps of the slowest one percent of frames`() {
        // 198 frames at 8.33 ms and 2 hitches of 50 ms: worst 1% of 200 = the 2 hitches.
        val intervals = List(198) { 8_333_333L } + listOf(50_000_000L, 50_000_000L)
        assertEquals(20f, Diagnostics.onePercentLow(intervals)!!, 0.01f)
        assertNull(Diagnostics.onePercentLow(intervals.take(99)))
    }
}

class SessionAccumulatorTest {

    private fun steadyFrames(startNs: Long, count: Int, periodNs: Long) = (0 until count).map { startNs + it * periodNs }

    @Test
    fun `aggregates samples without double counting frames`() {
        val acc = SessionAccumulator("com.example.othergame", 120f, startedAtMs = 1_000L, startedElapsedMs = 0L)
        val first = steadyFrames(1_000_000_000L, 128, 11_111_111L)
        acc.addSample(30_000L, thermal = 0, presents = first)
        // The next dump still holds the tail of the first; only newer frames may count.
        val second = first.takeLast(20) + steadyFrames(first.last() + 11_111_111L, 108, 11_111_111L)
        acc.addSample(60_000L, thermal = 1, presents = second.filter { it > acc.lastPresent })
        acc.addSample(90_000L, thermal = 2, presents = emptyList())

        val record = acc.finish(95_000L)

        assertEquals(95_000L, record.durationMs)
        assertEquals(90f, record.avgFps!!, 0.1f)
        assertEquals(60_000L, record.msToThermal1)
        assertEquals(2, record.maxThermal)
        assertEquals(3, record.samples)
        assertEquals(2, record.fpsSamples)
        assertEquals(3, record.thermalSamples)
        assertTrue("90 fps at 120 Hz is more than 15% below", record.struggled)
    }

    @Test
    fun `nothing measurable stays unavailable`() {
        val acc = SessionAccumulator("com.example.thirdgame", 60f, 0L, 0L)
        acc.addSample(30_000L, thermal = null, presents = emptyList())

        val record = acc.finish(40_000L)

        assertNull(record.avgFps)
        assertNull(record.onePercentLowFps)
        assertNull(record.maxThermal)
        assertNull(record.msToThermal1)
        assertFalse(record.struggled)
    }

    @Test
    fun `thermal that never reaches 1 has no time-to-thermal but a max of 0`() {
        val acc = SessionAccumulator("com.example.game", 60f, 0L, 0L)
        acc.addSample(30_000L, thermal = 0, presents = emptyList())
        val record = acc.finish(30_000L)
        assertEquals(0, record.maxThermal)
        assertNull(record.msToThermal1)
    }
}

class SessionStoreTest {

    private fun record(startedAt: Long, avg: Float? = 59.5f, rate: Float = 60f, thermal: Int? = 0) = SessionRecord(
        packageName = "com.example.game", refreshRate = rate, startedAtMs = startedAt, durationMs = 600_000L,
        avgFps = avg, onePercentLowFps = null, msToThermal1 = null, maxThermal = thermal,
        samples = 20, fpsSamples = if (avg == null) 0 else 20, thermalSamples = 20
    )

    @Test
    fun `records round trip through the encoding, nulls included`() {
        val r = record(123L, avg = null, thermal = null).copy(onePercentLowFps = null, msToThermal1 = 90_000L)
        assertEquals(r, SessionRecord.decode(r.encode()))
        val full = record(5L).copy(onePercentLowFps = 41.25f)
        assertEquals(full, SessionRecord.decode(full.encode()))
        assertNull(SessionRecord.decode("garbage"))
        val withPeak = record(7L).copy(observedPeak = 120f)
        assertEquals(withPeak, SessionRecord.decode(withPeak.encode()))
    }

    @Test
    fun `records saved before observedPeak existed still decode`() {
        // Verbatim from the device, saved by the previous build.
        val old = SessionRecord.decode("com.example.othergame|90.0|1790745039682|748041|43.28|3.10|248072|1|24|23|24")
        assertNotNull(old)
        assertEquals(43.28f, old!!.avgFps!!, 0.001f)
        assertNull(old.observedPeak)
    }

    @Test
    fun `only the last 20 sessions are kept`() {
        val store = SessionStore(FakeStore())
        repeat(25) { store.save(record(it.toLong())) }
        val all = store.all()
        assertEquals(20, all.size)
        assertEquals(5L, all.first().startedAtMs)
        assertEquals(24L, all.last().startedAtMs)
    }

    @Test
    fun `the newest summary is unseen until dismissed`() {
        val store = SessionStore(FakeStore())
        assertNull(store.unseen())
        store.save(record(1L))
        val unseen = store.unseen()
        assertNotNull(unseen)
        store.markSeen(unseen!!)
        assertNull(store.unseen())
        store.save(record(2L))
        assertEquals(2L, store.unseen()!!.startedAtMs)
    }

    @Test
    fun `a struggling session suggests the next lower supported rate`() {
        val supported = listOf(60f, 90f, 120f)
        // 95 fps at 120: more than 15% below.
        assertEquals(90f, Diagnostics.suggestion(record(1L, avg = 95f, rate = 120f), supported)!!, 0f)
        // Thermal moderate at a good frame rate still suggests dropping.
        assertEquals(60f, Diagnostics.suggestion(record(1L, avg = 89f, rate = 90f, thermal = 2), supported)!!, 0f)
        // Fine session: nothing.
        assertNull(Diagnostics.suggestion(record(1L, avg = 88f, rate = 90f, thermal = 1), supported))
        // Already at the lowest.
        assertNull(Diagnostics.suggestion(record(1L, avg = 30f, rate = 60f), supported))
        // Unavailable FPS alone never triggers a suggestion.
        assertNull(Diagnostics.suggestion(record(1L, avg = null, rate = 120f), supported))
    }
}

class SessionSamplerTest {

    @Test
    fun `finds the layer, reads frames through it and quotes the name`() {
        val layer = "SurfaceView[com.example.game/com.example.game.MainActivity](BLAST)#177001"
        val frames = (0 until 60).joinToString("\n") { "0\t${1_000_000_000L + it * 16_666_667L}\t0" }
        val shell = FakeShell(
            captures = mapOf(
                "dumpsys thermalservice" to "IsStatusOverride: false\nThermal Status: 1",
                "dumpsys SurfaceFlinger --list" to "RequestedLayerState{$layer parentId=1}",
                "dumpsys SurfaceFlinger --latency '$layer'" to "16666667\n$frames",
                // HiOS put its own 120 back after the game launched.
                "settings get system peak_refresh_rate" to "120.0"
            )
        )
        val sampler = SessionSampler(shell, "com.example.game")
        val acc = SessionAccumulator("com.example.game", 60f, 0L, 0L)

        val sample = sampler.sample(acc, 30_000L)

        assertEquals(1, sample.thermal)
        assertEquals(60, sample.frames)
        assertEquals(120f, sample.peak!!, 0f)
        assertEquals(layer, sampler.layer)
        val record = acc.finish(30_000L)
        assertEquals(60f, record.avgFps!!, 0.1f)
        assertEquals(120f, record.observedPeak!!, 0f)
    }

    @Test
    fun `no layer means fps unavailable and no latency dump at all`() {
        val shell = FakeShell(captures = mapOf("dumpsys thermalservice" to "Thermal Status: 0"))
        val sampler = SessionSampler(shell, "com.example.thirdgame")
        val acc = SessionAccumulator("com.example.thirdgame", 60f, 0L, 0L)

        val sample = sampler.sample(acc, 30_000L)

        assertEquals(0, sample.frames)
        assertNull(sampler.layer)
        assertFalse(shell.ran("dumpsys SurfaceFlinger --latency"))
        assertNull(acc.finish(30_000L).avgFps)
    }
}
