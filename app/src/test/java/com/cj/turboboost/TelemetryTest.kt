package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RamInfoTest {

    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `used is total minus available`() {
        val info = RamInfo.from(totalBytes = 8 * gb, availableBytes = 2 * gb)
        assertEquals(0.75f, info.fraction, 0.0001f)
        assertEquals("6.0", info.used)
        assertEquals("8.0 GB", info.total)
    }

    @Test
    fun `zero total does not divide by zero`() {
        val info = RamInfo.from(totalBytes = 0L, availableBytes = 0L)
        assertEquals(0f, info.fraction, 0f)
        assertEquals("--", info.used)
        assertEquals("-- GB", info.total)
    }

    @Test
    fun `negative total is treated as unknown`() {
        val info = RamInfo.from(totalBytes = -1L, availableBytes = 0L)
        assertEquals(0f, info.fraction, 0f)
        assertEquals("--", info.used)
    }

    @Test
    fun `available above total clamps instead of going negative`() {
        val info = RamInfo.from(totalBytes = 4 * gb, availableBytes = 5 * gb)
        assertEquals(0f, info.fraction, 0f)
        assertEquals("0.0", info.used)
    }

    @Test
    fun `a full device reports a fraction of one`() {
        val info = RamInfo.from(totalBytes = 4 * gb, availableBytes = 0L)
        assertEquals(1f, info.fraction, 0.0001f)
    }

    @Test
    fun `used and total are separate fields, never one splittable string`() {
        val info = RamInfo.from(totalBytes = 6 * gb, availableBytes = 3 * gb)
        assertTrue(!info.used.contains("/"))
        assertTrue(!info.total.contains("/"))
    }
}

class RefreshRateTest {

    @Test
    fun `a panel advertising 59_94 displays as 60`() {
        assertEquals(60, RefreshRate.display(RefreshRate.selectBest(listOf(59.94f, 47.99f))))
    }

    @Test
    fun `a panel advertising 119_99 displays as 120`() {
        assertEquals(120, RefreshRate.display(RefreshRate.selectBest(listOf(59.94f, 119.99f))))
    }

    @Test
    fun `a panel advertising 89_96 displays as 90`() {
        assertEquals(90, RefreshRate.display(RefreshRate.selectBest(listOf(60f, 89.96f))))
    }

    @Test
    fun `no advertised mode falls back to 60`() {
        assertEquals(RefreshRate.FALLBACK, RefreshRate.selectBest(emptyList()), 0f)
        assertEquals(60, RefreshRate.display(RefreshRate.selectBest(emptyList())))
    }

    @Test
    fun `nonsense rates are ignored rather than selected`() {
        assertEquals(
            RefreshRate.FALLBACK,
            RefreshRate.selectBest(listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)),
            0f
        )
        assertEquals(90f, RefreshRate.selectBest(listOf(0f, 90f, Float.NaN)), 0.0001f)
    }

    @Test
    fun `the highest advertised mode wins regardless of order`() {
        assertEquals(120f, RefreshRate.selectBest(listOf(120f, 60f, 90f)), 0.0001f)
        assertEquals(120f, RefreshRate.selectBest(listOf(60f, 120f, 90f)), 0.0001f)
    }

    @Test
    fun `allowed rates are matched against the panel's advertised modes`() {
        val allowed = listOf(60f, 90f, 120f)
        // What the Pova 7 reports.
        assertEquals(allowed, RefreshRate.supported(allowed, listOf(120.00001f, 90f, 60f)))
        // A 60/90 panel never offers 120.
        assertEquals(listOf(60f, 90f), RefreshRate.supported(allowed, listOf(59.94f, 89.96f)))
        // Unreadable modes: only the universally safe rate.
        assertEquals(listOf(60f), RefreshRate.supported(allowed, emptyList()))
    }

    @Test
    fun `the stored rate wins only while it is still supported`() {
        val supported = listOf(60f, 90f)
        assertEquals(90f, RefreshRate.resolve(supported, stored = 90f, default = 60f)!!, 0f)
        // Stored 120 on a panel that lost it: fall back to the default.
        assertEquals(60f, RefreshRate.resolve(supported, stored = 120f, default = 60f)!!, 0f)
        // Default not supported either: highest supported below it.
        assertEquals(90f, RefreshRate.resolve(supported, stored = null, default = 120f)!!, 0f)
        assertEquals(null, RefreshRate.resolve(emptyList(), stored = 60f, default = 60f))
    }

    @Test
    fun `next lower rate`() {
        val supported = listOf(60f, 90f, 120f)
        assertEquals(90f, RefreshRate.nextLower(120f, supported)!!, 0f)
        assertEquals(60f, RefreshRate.nextLower(90f, supported)!!, 0f)
        assertEquals(null, RefreshRate.nextLower(60f, supported))
    }

    @Test
    fun `every added game gets the generic 60-90-120 profile defaulting to 60`() {
        val game = GameRegistry.profileFor("com.example.game", "Example")
        assertEquals("com.example.game", game.packageName)
        assertEquals("Example", game.displayName)
        assertEquals(listOf(60f, 90f, 120f), game.allowedRefreshRates)
        assertEquals(60f, game.defaultRefreshRate, 0f)
        assertTrue(game.useFixedPerformanceMode)
    }

    @Test
    fun `only real package names are accepted as games`() {
        assertTrue(GameRegistry.isValidPackageName("com.example.game"))
        assertTrue(GameRegistry.isValidPackageName("com.Example_2.app"))
        for (bad in listOf("", "game", ".com.x", "com..x", "com.x;reboot", "com.x y", "com.x\nreboot", "com.x$(id)", "1com.x")) {
            assertFalse("\"$bad\"", GameRegistry.isValidPackageName(bad))
        }
    }

    @Test
    fun `the written value has one decimal and is locale independent`() {
        assertEquals("120.0", RefreshRate.format(119.99f))
        assertEquals("90.0", RefreshRate.format(89.96f))
        assertEquals("60.0", RefreshRate.format(60f))
        // Never truncated toward zero: 59.94 must not become "59".
        assertTrue(RefreshRate.format(59.94f).startsWith("59.9"))
    }
}
