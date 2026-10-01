package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoosterPrefsTest {

    @Test
    fun `every profile round trips`() {
        for (profile in BoostProfile.entries) {
            val prefs = BoosterPrefs(FakeStore())
            prefs.profile = profile
            assertEquals(profile, prefs.profile)
        }
    }

    @Test
    fun `unset profile defaults to BALANCED`() {
        assertEquals(BoostProfile.BALANCED, BoosterPrefs(FakeStore()).profile)
    }

    @Test
    fun `corrupt profile string falls back to BALANCED`() {
        for (corrupt in listOf("", "TURBO", "balanced", "PERFORMANCE_2", "null", "  ")) {
            val store = FakeStore().apply { putString("profile", corrupt) }
            assertEquals(
                "stored value \"$corrupt\" should fall back",
                BoostProfile.BALANCED,
                BoosterPrefs(store).profile
            )
        }
    }

    @Test
    fun `fromStored maps names exactly and everything else to BALANCED`() {
        assertEquals(BoostProfile.PERFORMANCE, BoostProfile.fromStored("PERFORMANCE"))
        assertEquals(BoostProfile.POWER_SAVING, BoostProfile.fromStored("POWER_SAVING"))
        assertEquals(BoostProfile.BALANCED, BoostProfile.fromStored(null))
        assertEquals(BoostProfile.BALANCED, BoostProfile.fromStored("nonsense"))
    }

    @Test
    fun `vpnEnabled defaults on and round trips`() {
        val prefs = BoosterPrefs(FakeStore())
        assertTrue(prefs.vpnEnabled)
        prefs.vpnEnabled = false
        assertFalse(prefs.vpnEnabled)
        prefs.vpnEnabled = true
        assertTrue(prefs.vpnEnabled)
    }

    @Test
    fun `selected game and each game's rate survive a new prefs instance`() {
        val store = FakeStore()
        BoosterPrefs(store).apply {
            selectedGame = "com.example.othergame"
            setRate("com.example.othergame", 90f)
            setRate("com.example.thirdgame", 120f)
        }

        val reopened = BoosterPrefs(store)
        assertEquals("com.example.othergame", reopened.selectedGame)
        assertEquals(90f, reopened.rateFor("com.example.othergame")!!, 0f)
        assertEquals(120f, reopened.rateFor("com.example.thirdgame")!!, 0f)
        assertEquals(null, reopened.rateFor("com.example.game"))
    }

    @Test
    fun `a corrupt stored rate reads as unset`() {
        for (corrupt in listOf("", "abc", "-60", "NaN", "0")) {
            val store = FakeStore().apply { putString("rate_com.example.game", corrupt) }
            assertEquals("\"$corrupt\"", null, BoosterPrefs(store).rateFor("com.example.game"))
        }
    }

    @Test
    fun `the game list starts empty, keeps order and survives a new prefs instance`() {
        val store = FakeStore()
        assertTrue(BoosterPrefs(store).games.isEmpty())
        BoosterPrefs(store).games = listOf("com.example.othergame", "com.example.game", "com.example.othergame")
        assertEquals(listOf("com.example.othergame", "com.example.game"), BoosterPrefs(store).games)
        BoosterPrefs(store).games = BoosterPrefs(store).games - "com.example.othergame"
        assertEquals(listOf("com.example.game"), BoosterPrefs(store).games)
    }

    @Test
    fun `a stored game list drops anything that is not a package name`() {
        val store = FakeStore().apply { putString("games", "com.example.game,bad name,com.x;reboot,,com.example.othergame") }
        assertEquals(listOf("com.example.game", "com.example.othergame"), BoosterPrefs(store).games)
        assertFalse(BoosterPrefs(FakeStore()).games.any { it.isBlank() })
    }
}
