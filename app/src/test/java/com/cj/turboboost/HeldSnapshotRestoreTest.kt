package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `settings` for the global and system namespaces, so a boost and its restore can be round-tripped. */
private class AllSettingsShell(initial: Map<String, String>) : ShellExecutor {
    val values = initial.toMutableMap()
    val commands = mutableListOf<String>()

    /** Writes to these `namespace/key`s exit 0 but do not stick. */
    var ignored: Set<String> = emptySet()

    /** Reads of these fail outright. */
    var unreadable: Set<String> = emptySet()

    override fun isAvailable() = true

    override fun run(command: String): ShellResult {
        commands += command
        val p = command.split(" ")
        if (p.size == 5 && p[0] == "settings" && p[1] == "put") {
            val key = "${p[2]}/${p[3]}"
            if (key !in ignored) values[key] = p[4]
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        val p = command.split(" ")
        if (p.size == 4 && p[0] == "settings" && p[1] == "get") {
            val key = "${p[2]}/${p[3]}"
            return if (key in unreadable) null else values[key] ?: "null"
        }
        return null
    }

    fun wrote(key: String) = commands.any { it.startsWith("settings put ${key.replace('/', ' ')} ") }
}

class HeldSnapshotRestoreTest {

    private val realShell = RamCleaner.shell
    private val realSync = RamCleaner.syncSettings

    @After
    fun tearDown() {
        RamCleaner.shell = realShell
        RamCleaner.syncSettings = realSync
    }

    private val userSettings = mapOf(
        "global/zen_mode" to "0",
        "system/pointer_speed" to "0",
        "global/window_animation_scale" to "1",
        "global/transition_animation_scale" to "1",
        "global/animator_duration_scale" to "0.5"
    )

    /** A TURBO boost on a phone with [userSettings]: snapshot taken, then every lever applied. */
    private fun boosted(): Triple<AllSettingsShell, FakeSyncSettings, FakeStore> {
        val shell = AllSettingsShell(userSettings)
        val sync = FakeSyncSettings(enabled = true)
        RamCleaner.shell = shell
        RamCleaner.syncSettings = sync
        val snapshot = FakeStore()
        RamCleaner.applyProfile(BoostProfile.PERFORMANCE, FAKE_GAME, snapshot, FakeStore())
        assertEquals("1", shell.values["global/zen_mode"])
        assertEquals("7", shell.values["system/pointer_speed"])
        assertEquals("0", shell.values["global/window_animation_scale"])
        assertFalse(sync.enabled)
        return Triple(shell, sync, snapshot)
    }

    @Test
    fun `every setting a boost changed goes back and reads back, then the snapshot is cleared`() {
        val (shell, sync, snapshot) = boosted()

        val report = RamCleaner.restoreHeldSnapshot(snapshot)!!

        assertTrue(report.allOk)
        assertEquals(userSettings, shell.values.filterKeys { it in userSettings })
        assertTrue(sync.enabled)
        assertTrue(shell.commands.contains("cmd notification set_dnd off"))
        assertFalse(SystemSnapshot.exists(snapshot))
    }

    @Test
    fun `a setting changed since the boost is not overwritten`() {
        val (shell, sync, snapshot) = boosted()
        // Mid-game the user sets total silence, turns animations back on and turns sync on.
        shell.values["global/zen_mode"] = "2"
        shell.values["global/window_animation_scale"] = "1.5"
        sync.enabled = true
        shell.commands.clear()

        val report = RamCleaner.restoreHeldSnapshot(snapshot)!!

        assertTrue(report.allOk)
        assertEquals("2", shell.values["global/zen_mode"])
        assertFalse(shell.wrote("global/zen_mode"))
        assertFalse(shell.commands.any { it.startsWith("cmd notification set_dnd") })
        assertEquals("1.5", shell.values["global/window_animation_scale"])
        // The ones still at the boost's value go back.
        assertEquals("0", shell.values["system/pointer_speed"])
        assertEquals("1", shell.values["global/transition_animation_scale"])
        assertTrue(sync.enabled)
        assertFalse(SystemSnapshot.exists(snapshot))
    }

    @Test
    fun `a setting already back is left, and 0 and 0_0 are the same scale`() {
        val (shell, _, snapshot) = boosted()
        shell.values["global/window_animation_scale"] = "1.0"
        shell.values["global/transition_animation_scale"] = "0.0"
        shell.commands.clear()

        assertTrue(RamCleaner.restoreHeldSnapshot(snapshot)!!.allOk)
        assertFalse(shell.wrote("global/window_animation_scale"))
        assertTrue(shell.wrote("global/transition_animation_scale"))
    }

    @Test
    fun `a write that does not read back, or a read that fails, keeps the snapshot for the next try`() {
        val (shell, _, snapshot) = boosted()
        shell.ignored = setOf("system/pointer_speed")

        val first = RamCleaner.restoreHeldSnapshot(snapshot)!!
        assertFalse(first.allOk)
        assertTrue(first.failed.contains("Pointer speed"))
        assertTrue(SystemSnapshot.exists(snapshot))

        shell.ignored = emptySet()
        shell.unreadable = setOf("global/zen_mode")
        assertFalse(RamCleaner.restoreHeldSnapshot(snapshot)!!.allOk)
        assertTrue(SystemSnapshot.exists(snapshot))

        shell.unreadable = emptySet()
        assertTrue(RamCleaner.restoreHeldSnapshot(snapshot)!!.allOk)
        assertEquals("0", shell.values["system/pointer_speed"])
    }

    @Test
    fun `an unset key is not a boost's value, so it never blocks the restore`() {
        val (shell, _, snapshot) = boosted()
        shell.values.remove("global/animator_duration_scale")
        assertTrue(RamCleaner.restoreHeldSnapshot(snapshot)!!.allOk)
    }

    @Test
    fun `with no snapshot held nothing is read or written`() {
        val shell = AllSettingsShell(userSettings)
        RamCleaner.shell = shell
        assertNull(RamCleaner.restoreHeldSnapshot(FakeStore()))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `the game exit puts the settings back through the session end`() {
        val (shell, sync, snapshot) = boosted()
        val tuner = FakeStore()
        val ctx = SessionContext(shell, FakeStore(), tuner, FakeStore(), SessionStore(FakeStore()), FakeStore(), snapshot, { 0L }, { 0L })
        SessionEnd.begin(ctx, FAKE_GAME, 60f, "default", null, SessionEnd.newToken(), "PERFORMANCE", Contamination.CLEAN)

        val outcome = SessionEnd.finish(ctx, EndReason.EXITED)

        assertTrue(outcome.ok)
        assertTrue(outcome.system!!.allOk)
        assertEquals("0", shell.values["global/zen_mode"])
        assertEquals("0", shell.values["system/pointer_speed"])
        assertTrue(sync.enabled)
        assertEquals(SessionEnd.STATE_ENDED, SessionEnd.state(ctx.session))
    }

    @Test
    fun `a session whose settings did not go back stays ending, for the next app start`() {
        val (shell, _, snapshot) = boosted()
        shell.ignored = setOf("global/zen_mode")
        val ctx = SessionContext(shell, FakeStore(), FakeStore(), FakeStore(), SessionStore(FakeStore()), FakeStore(), snapshot, { 0L }, { 0L })
        SessionEnd.begin(ctx, FAKE_GAME, 60f, "default", null, SessionEnd.newToken(), "PERFORMANCE", Contamination.CLEAN)

        assertFalse(SessionEnd.finish(ctx, EndReason.EXITED).ok)
        assertEquals(SessionEnd.STATE_ENDING, SessionEnd.state(ctx.session))
        assertTrue(SessionEnd.needsFinishAtStart(ctx.session, true, false, false) { false })

        shell.ignored = emptySet()
        assertTrue(SessionEnd.finish(ctx, EndReason.APP_START).ok)
        assertEquals("0", shell.values["global/zen_mode"])
    }
}
