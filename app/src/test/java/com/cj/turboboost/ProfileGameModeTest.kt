package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGameModeTest {

    private val pkg = FAKE_GAME

    @Test
    fun `the game's own mode is saved before the switch and put back after`() {
        // Another app's config holds the game in custom mode; a BALANCED boost sets standard.
        val shell = GameModeShell(mode = "custom")
        val store = FakeStore()

        val applied = ProfileGameMode.apply(shell, store, pkg, "standard")
        assertTrue(applied.ok)
        assertEquals("standard", shell.mode)
        assertTrue(ProfileGameMode.held(store))
        // Read, then switched: the save happened before the switch ran.
        assertEquals(listOf("cmd game list-modes $pkg", "cmd game mode standard $pkg", "cmd game list-modes $pkg"), shell.commands)

        val restored = ProfileGameMode.restore(shell, store)
        assertTrue(restored.ok)
        assertEquals("custom", shell.mode)
        assertFalse(ProfileGameMode.held(store))
    }

    @Test
    fun `a mode the game does not offer is never sent`() {
        val shell = GameModeShell(mode = "custom", offered = listOf("standard", "custom"))
        val store = FakeStore()
        val outcome = ProfileGameMode.apply(shell, store, pkg, "performance")
        assertFalse(outcome.ok)
        assertEquals("performance not offered, stays custom", outcome.detail)
        assertFalse(shell.ran("cmd game mode"))
        assertFalse(ProfileGameMode.held(store))
    }

    @Test
    fun `nothing is switched when the mode cannot be read, or already holds`() {
        val unreadable = FakeShell()
        assertFalse(ProfileGameMode.apply(unreadable, FakeStore(), pkg, "standard").ok)
        assertFalse(unreadable.ran("cmd game mode"))

        val already = GameModeShell(mode = "standard")
        val store = FakeStore()
        assertTrue(ProfileGameMode.apply(already, store, pkg, "standard").ok)
        assertFalse(already.ran("cmd game mode"))
        assertFalse(ProfileGameMode.held(store))
    }

    @Test
    fun `a switch that did not stick leaves nothing saved`() {
        val shell = GameModeShell(mode = "custom", ignoresSwitch = true)
        val store = FakeStore()
        assertFalse(ProfileGameMode.apply(shell, store, pkg, "standard").ok)
        assertFalse(ProfileGameMode.held(store))
    }

    @Test
    fun `a second boost on top keeps the first saved mode`() {
        val shell = GameModeShell(mode = "custom")
        val store = FakeStore()
        ProfileGameMode.apply(shell, store, pkg, "standard")
        ProfileGameMode.apply(shell, store, pkg, "performance")
        assertTrue(ProfileGameMode.restore(shell, store).ok)
        assertEquals("custom", shell.mode)
    }

    @Test
    fun `a mode changed by someone else since is left alone`() {
        val shell = GameModeShell(mode = "custom")
        val store = FakeStore()
        ProfileGameMode.apply(shell, store, pkg, "performance")
        // Another app, or a phone restart, moves the game on.
        shell.mode = "battery"

        val outcome = ProfileGameMode.restore(shell, store)

        assertTrue(outcome.ok)
        assertEquals("battery", shell.mode)
        assertFalse(shell.ran("cmd game mode custom"))
        assertFalse(ProfileGameMode.held(store))
        assertTrue(outcome.detail.contains("changed outside to battery"))
    }

    @Test
    fun `a restore that cannot run or does not stick is kept for the next try`() {
        val shell = GameModeShell(mode = "custom")
        val store = FakeStore()
        ProfileGameMode.apply(shell, store, pkg, "standard")

        assertFalse(ProfileGameMode.restore(GameModeShell(available = false), store).ok)
        assertTrue(ProfileGameMode.held(store))

        shell.ignoresSwitch = true
        assertFalse(ProfileGameMode.restore(shell, store).ok)
        assertTrue(ProfileGameMode.held(store))

        shell.ignoresSwitch = false
        assertTrue(ProfileGameMode.restore(shell, store).ok)
        assertEquals("custom", shell.mode)
    }

    @Test
    fun `the session end puts the mode back through the same step as everything else`() {
        val real = RamCleaner.shell
        try {
            val shell = GameModeShell(mode = "custom")
            RamCleaner.shell = shell
            val resolution = FakeStore()
            ProfileGameMode.apply(shell, resolution, pkg, "standard")
            val ctx = SessionContext(shell, FakeStore(), FakeStore(), FakeStore(), SessionStore(FakeStore()), resolution, { 0L }, { 0L })

            val outcome = SessionEnd.finish(ctx, EndReason.MANUAL)

            assertTrue(outcome.ok)
            assertTrue(outcome.gameMode.ok)
            assertEquals("custom", shell.mode)
            assertFalse(ProfileGameMode.held(resolution))
        } finally {
            RamCleaner.shell = real
        }
    }
}
