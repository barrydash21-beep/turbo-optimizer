package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H4: the boost must snapshot the user's settings before touching them, and restore must
 * write that snapshot back rather than hardcoded platform defaults.
 */
class SnapshotRestoreTest {

    private val realShell = RamCleaner.shell
    private val realSync = RamCleaner.syncSettings

    @After
    fun restoreGlobals() {
        RamCleaner.shell = realShell
        RamCleaner.syncSettings = realSync
    }

    /** A device where the user has deliberately customised everything the boost touches. */
    private fun customisedDevice(failingPrefixes: Set<String> = emptySet()) = FakeShell(
        captures = mapOf(
            "settings get global zen_mode" to "1",
            "settings get system pointer_speed" to "-3",
            "settings get global window_animation_scale" to "0.5",
            "settings get global transition_animation_scale" to "0",
            "settings get global animator_duration_scale" to "0.5"
        ),
        failingPrefixes = failingPrefixes
    )

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    @Test
    fun `the snapshot records the values the device had before the boost`() {
        val store = FakeStore()
        val shell = customisedDevice()

        assertTrue(SystemSnapshot.captureIfAbsent(shell, store, FakeSyncSettings(enabled = false)))

        assertEquals("1", SystemSnapshot.zenMode(store))
        assertEquals("-3", SystemSnapshot.pointerSpeed(store))
        assertEquals("0.5", SystemSnapshot.animationScale(store, "window_animation_scale"))
        assertEquals("0", SystemSnapshot.animationScale(store, "transition_animation_scale"))
        assertFalse(SystemSnapshot.masterSync(store))
        assertTrue(SystemSnapshot.exists(store))
    }

    @Test
    fun `a second boost does not overwrite the original snapshot`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings(enabled = false))

        // Second boost: the device now reports the *boosted* values, and auto-sync is off
        // because the first boost turned it off. Capturing these would lose the originals.
        val boosted = FakeShell(
            captures = mapOf(
                "settings get global zen_mode" to "0",
                "settings get system pointer_speed" to "7",
                "settings get global window_animation_scale" to "0"
            )
        )
        assertFalse(SystemSnapshot.captureIfAbsent(boosted, store, FakeSyncSettings(enabled = false)))

        assertEquals("1", SystemSnapshot.zenMode(store))
        assertEquals("-3", SystemSnapshot.pointerSpeed(store))
        assertEquals("0.5", SystemSnapshot.animationScale(store, "window_animation_scale"))
        assertTrue("nothing should have been read at all", boosted.commands.isEmpty())
    }

    @Test
    fun `an unreadable or unset value falls back to the platform default`() {
        val store = FakeStore()
        // "null" is what `settings get` prints for an unset key.
        val shell = FakeShell(captures = mapOf("settings get global zen_mode" to "null"))

        SystemSnapshot.captureIfAbsent(shell, store, FakeSyncSettings())

        assertEquals("0", SystemSnapshot.zenMode(store))
        assertEquals("0", SystemSnapshot.pointerSpeed(store))
        assertEquals("1", SystemSnapshot.animationScale(store, "window_animation_scale"))
    }

    @Test
    fun `applyProfile captures before it mutates`() {
        val store = FakeStore()
        val shell = customisedDevice()
        RamCleaner.shell = shell
        RamCleaner.syncSettings = FakeSyncSettings()

        RamCleaner.applyProfile(BoostProfile.PERFORMANCE, "com.example.game", store)

        val firstWrite = shell.commands.indexOfFirst { it.startsWith("settings put") }
        val lastRead = shell.commands.indexOfLast { it.startsWith("settings get") }
        assertTrue("every read must precede every write", lastRead in 0 until firstWrite)
        assertTrue(SystemSnapshot.exists(store))
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    @Test
    fun `restore writes the snapshot back, not platform defaults`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings(enabled = false))

        val shell = FakeShell()
        val sync = FakeSyncSettings(enabled = false)
        RamCleaner.shell = shell
        RamCleaner.syncSettings = sync

        val report = RamCleaner.restoreSystemState(store)

        assertTrue(report.allOk)
        assertTrue(shell.commands.contains("settings put global zen_mode 1"))
        assertTrue(shell.commands.contains("settings put system pointer_speed -3"))
        assertTrue(shell.commands.contains("settings put global window_animation_scale 0.5"))
        assertTrue(shell.commands.contains("settings put global transition_animation_scale 0"))
    }

    @Test
    fun `restore never deletes the user's refresh rate`() {
        // PerformanceTuner restores the exact saved values; a delete here wiped the user's
        // own rate whenever Restore was tapped without a tweak session held.
        val shell = FakeShell()
        RamCleaner.shell = shell
        RamCleaner.syncSettings = FakeSyncSettings()

        RamCleaner.restoreSystemState(FakeStore())

        assertFalse(shell.commands.any { it.contains("refresh_rate") })
    }

    @Test
    fun `restore puts the user's own DND mode back instead of forcing it off`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings())

        val shell = FakeShell()
        RamCleaner.shell = shell
        RamCleaner.syncSettings = FakeSyncSettings()
        RamCleaner.restoreSystemState(store)

        assertTrue(shell.commands.contains("cmd notification set_dnd priority"))
        assertFalse(shell.commands.contains("cmd notification set_dnd off"))
    }

    @Test
    fun `restore leaves master auto-sync off for a user who had it off`() {
        val store = FakeStore()
        // The metered-data case: auto-sync was deliberately off before the boost.
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings(enabled = false))

        val sync = FakeSyncSettings(enabled = false)
        RamCleaner.shell = FakeShell()
        RamCleaner.syncSettings = sync
        RamCleaner.restoreSystemState(store)

        assertFalse("restore must not force a device-wide sync", sync.enabled)
    }

    @Test
    fun `restore turns auto-sync back on for a user who had it on`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings(enabled = true))

        val sync = FakeSyncSettings(enabled = false)
        RamCleaner.shell = FakeShell()
        RamCleaner.syncSettings = sync
        RamCleaner.restoreSystemState(store)

        assertTrue(sync.enabled)
    }

    @Test
    fun `restore with no snapshot writes platform defaults`() {
        val store = FakeStore()
        val shell = FakeShell()
        RamCleaner.shell = shell
        RamCleaner.syncSettings = FakeSyncSettings(enabled = false)

        RamCleaner.restoreSystemState(store)

        assertTrue(shell.commands.contains("settings put global zen_mode 0"))
        assertTrue(shell.commands.contains("cmd notification set_dnd off"))
        assertTrue(shell.commands.contains("settings put system pointer_speed 0"))
        assertTrue(shell.commands.contains("settings put global window_animation_scale 1"))
    }

    @Test
    fun `restore with no snapshot leaves master auto-sync alone`() {
        // Tapping RESTORE without ever boosting must not turn auto-sync on for a user who
        // deliberately keeps it off -- that is the H4 metered-data harm on a path with no
        // snapshot to consult.
        val sync = FakeSyncSettings(enabled = false)
        RamCleaner.shell = FakeShell()
        RamCleaner.syncSettings = sync

        val report = RamCleaner.restoreSystemState(FakeStore())

        assertFalse(sync.enabled)
        assertFalse(report.applied.contains("Auto-sync"))
        assertFalse(report.failed.contains("Auto-sync"))
    }

    @Test
    fun `a successful restore clears the snapshot`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings())
        RamCleaner.shell = FakeShell()
        RamCleaner.syncSettings = FakeSyncSettings()

        assertTrue(RamCleaner.restoreSystemState(store).allOk)
        assertFalse(SystemSnapshot.exists(store))
        assertTrue(store.isEmpty)
    }

    @Test
    fun `a partial restore keeps the snapshot so it can be retried`() {
        val store = FakeStore()
        SystemSnapshot.captureIfAbsent(customisedDevice(), store, FakeSyncSettings())

        RamCleaner.shell = FakeShell(failingPrefixes = setOf("settings put system pointer_speed"))
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.restoreSystemState(store)

        assertFalse(report.allOk)
        assertTrue(report.failed.contains("Pointer speed"))
        assertTrue("the original values must survive a failed restore", SystemSnapshot.exists(store))
        assertEquals("1", SystemSnapshot.zenMode(store))
    }

    @Test
    fun `restore never claims success when the binder is down`() {
        RamCleaner.shell = FakeShell(available = false)
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.restoreSystemState(FakeStore())

        assertFalse(report.allOk)
        assertTrue(report.unavailable)
        assertEquals("Shizuku not connected", report.summary("System restored"))
    }

    @Test
    fun `restore reports partial failure by name`() {
        RamCleaner.shell = FakeShell(failingPrefixes = setOf("settings put system pointer_speed"))
        RamCleaner.syncSettings = FakeSyncSettings()

        val report = RamCleaner.restoreSystemState(FakeStore())

        assertFalse(report.allOk)
        assertTrue(report.failed.contains("Pointer speed"))
        assertTrue(report.summary("System restored").contains("Pointer speed"))
    }

    // -----------------------------------------------------------------------
    // zen_mode -> set_dnd mapping
    // -----------------------------------------------------------------------

    @Test
    fun `zen mode maps to the matching set_dnd argument`() {
        assertEquals("off", SystemSnapshot.dndArgumentFor("0"))
        assertEquals("priority", SystemSnapshot.dndArgumentFor("1"))
        assertEquals("none", SystemSnapshot.dndArgumentFor("2"))
        assertEquals("alarms", SystemSnapshot.dndArgumentFor("3"))
        assertEquals("priority", SystemSnapshot.dndArgumentFor(" 1 "))
        assertEquals("off", SystemSnapshot.dndArgumentFor("garbage"))
        assertEquals("off", SystemSnapshot.dndArgumentFor(""))
    }
}
