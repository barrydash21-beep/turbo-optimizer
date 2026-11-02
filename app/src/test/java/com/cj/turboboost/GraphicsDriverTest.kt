package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone's global settings table for the one key the driver list lives in. */
private class DriverSettingsShell(
    /** The stored text; null is unset. */
    var value: String? = null,
    /** `put` exits 0 but the table does not change. */
    var ignoresPut: Boolean = false,
    /** `put` exits non-zero. */
    var failsPut: Boolean = false,
    /** `delete` exits 0 but the key stays. */
    var ignoresDelete: Boolean = false,
    /** What the system stores instead of what was put, e.g. a list it trims. */
    var rewrite: ((String) -> String)? = null,
    var unreadable: Boolean = false,
    var available: Boolean = true
) : ShellExecutor {

    val commands = mutableListOf<String>()
    private val put = Regex("""settings put global ${GraphicsDriver.KEY} '(.*)'""")

    override fun isAvailable() = available

    override fun run(command: String): ShellResult {
        commands += command
        put.matchEntire(command)?.let { m ->
            if (failsPut) return ShellResult(exitCode = 1)
            if (!ignoresPut) value = rewrite?.invoke(m.groupValues[1]) ?: m.groupValues[1]
            return ShellResult.success()
        }
        if (command == "settings delete global ${GraphicsDriver.KEY}") {
            if (!ignoresDelete) value = null
            return ShellResult.success()
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        if (command != "settings get global ${GraphicsDriver.KEY}" || unreadable) return null
        return value ?: "null"
    }
}

class GraphicsDriverTest {

    private val game = "com.example.game"

    private fun apply(shell: ShellExecutor, store: KeyValueStore, sdk: Int = 36, pkg: String = game) =
        GraphicsDriver.apply(shell, store, pkg, sdk)

    // ---- the list ----

    @Test fun `the game is added after every existing entry, once`() {
        assertEquals(listOf("com.a", "com.b", game), GraphicsDriver.merged("com.a,com.b", game))
        assertEquals(listOf("com.a", game, "com.b"), GraphicsDriver.merged("com.a,$game,com.b", game))
        assertEquals(listOf(game), GraphicsDriver.merged("null", game))
        assertEquals(listOf(game), GraphicsDriver.merged("", game))
    }

    @Test fun `an entry that is not a package name is never dropped, so nothing is written`() {
        assertNull(GraphicsDriver.merged("com.a,not a package", game))
        val shell = DriverSettingsShell("com.a,not a package")
        val store = FakeStore()
        assertFalse(apply(shell, store).ok)
        assertEquals("com.a,not a package", shell.value)
        assertFalse(GraphicsDriver.held(store))
        assertFalse(shell.commands.any { it.startsWith("settings put") })
    }

    @Test fun `apply writes the merged list and reads it back`() {
        val shell = DriverSettingsShell("com.a,com.b")
        val store = FakeStore()
        val out = apply(shell, store)
        assertTrue(out.ok)
        assertEquals("com.a,com.b,$game", shell.value)
        assertTrue(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNVERIFIED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `the prior value is on disk before the write runs`() {
        val store = FakeStore()
        val shell = object : ShellExecutor by DriverSettingsShell("com.a") {
            override fun run(command: String): ShellResult {
                if (command.startsWith("settings put")) assertTrue("held before the write", GraphicsDriver.held(store))
                return ShellResult.success()
            }
        }
        apply(shell, store)
    }

    // ---- restore ----

    @Test fun `restore writes back the exact prior value`() {
        val shell = DriverSettingsShell("com.a, com.b")
        val store = FakeStore()
        apply(shell, store)
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a, com.b", shell.value)
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `a key that was unset is deleted again, not set to the word null`() {
        val shell = DriverSettingsShell(null)
        val store = FakeStore()
        assertTrue(apply(shell, store).ok)
        assertEquals(game, shell.value)
        val out = GraphicsDriver.restore(shell, store)
        assertTrue(out.ok)
        assertNull(shell.value)
        assertTrue(shell.commands.contains("settings delete global ${GraphicsDriver.KEY}"))
        assertFalse(shell.commands.any { it.contains("'null'") })
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `an empty but set value is put back as empty`() {
        val shell = DriverSettingsShell("")
        val store = FakeStore()
        apply(shell, store)
        assertEquals(game, shell.value)
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("", shell.value)
    }

    @Test fun `a restore that does not read back stays held and is retried`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        shell.ignoresPut = true
        val out = GraphicsDriver.restore(shell, store)
        assertFalse(out.ok)
        assertTrue(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNSUPPORTED, GraphicsDriver.lastResult(store)!!.status)
        // The system lets go later, e.g. at the next app start.
        shell.ignoresPut = false
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a", shell.value)
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `an unset key whose delete is ignored stays held`() {
        val shell = DriverSettingsShell(null)
        val store = FakeStore()
        apply(shell, store)
        shell.ignoresDelete = true
        assertFalse(GraphicsDriver.restore(shell, store).ok)
        assertTrue(GraphicsDriver.held(store))
    }

    @Test fun `restore without Shizuku keeps it held and touches nothing`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        shell.available = false
        shell.commands.clear()
        assertFalse(GraphicsDriver.restore(shell, store).ok)
        assertTrue(GraphicsDriver.held(store))
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `restoring with nothing held does nothing`() {
        val shell = DriverSettingsShell("com.a")
        val out = GraphicsDriver.restore(shell, FakeStore())
        assertTrue(out.ok)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun `a second boost on top keeps the first prior value`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        apply(shell, store, pkg = "com.example.other")
        assertEquals("com.a,$game,com.example.other", shell.value)
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a", shell.value)
    }

    // ---- the restore guard ----

    @Test fun `unchanged since the write, the exact prior text goes back`() {
        val shell = DriverSettingsShell("com.a,  com.b")
        val store = FakeStore()
        apply(shell, store)
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a,  com.b", shell.value)
        assertEquals(CheckStatus.SUPPORTED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `edited by another app, only our package comes out and every other entry stays`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        // Another app added its own entry and reordered the list during the session.
        shell.value = "com.x,com.a,$game,com.y"
        val out = GraphicsDriver.restore(shell, store)
        assertTrue(out.ok)
        assertEquals("com.x,com.a,com.y", shell.value)
        assertFalse(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNVERIFIED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `edited by another app that already took ours out is left exactly as it is`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        shell.value = "com.a,com.z"
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a,com.z", shell.value)
        assertFalse(shell.commands.any { it.startsWith("settings put") && it.endsWith("'com.a,com.z'") })
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `an unset prior that another app then added to keeps their entry and loses ours`() {
        val shell = DriverSettingsShell(null)
        val store = FakeStore()
        apply(shell, store)
        shell.value = "$game,com.x"
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.x", shell.value)
    }

    @Test fun `an unset prior that another app deleted again is left unset`() {
        val shell = DriverSettingsShell(null)
        val store = FakeStore()
        apply(shell, store)
        shell.value = null
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertNull(shell.value)
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `a package that was already present is not written and not removed`() {
        val shell = DriverSettingsShell("com.a,$game")
        val store = FakeStore()
        assertTrue(apply(shell, store).ok)
        assertFalse(GraphicsDriver.held(store))
        shell.value = "com.a,$game,com.x"
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a,$game,com.x", shell.value)
        assertFalse(shell.commands.any { it.startsWith("settings put") || it.startsWith("settings delete") })
    }

    @Test fun `a second boost keeps the package it added and still leaves a pre-existing one alone`() {
        val shell = DriverSettingsShell("com.a,com.example.other")
        val store = FakeStore()
        apply(shell, store)
        apply(shell, store, pkg = "com.example.other")
        shell.value = "com.a,com.example.other,$game,com.x"
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a,com.example.other,com.x", shell.value)
    }

    @Test fun `a removal that does not read back stays held and is retried`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        shell.value = "com.a,$game,com.x"
        shell.failsPut = true
        val out = GraphicsDriver.restore(shell, store)
        assertFalse(out.ok)
        assertTrue(GraphicsDriver.held(store))
        assertEquals("com.a,$game,com.x", shell.value)
        shell.failsPut = false
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a,com.x", shell.value)
    }

    @Test fun `a list unreadable at the restore stays held`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        shell.unreadable = true
        assertFalse(GraphicsDriver.restore(shell, store).ok)
        assertTrue(GraphicsDriver.held(store))
        shell.unreadable = false
        assertTrue(GraphicsDriver.restore(shell, store).ok)
        assertEquals("com.a", shell.value)
    }

    // ---- the mismatch path ----

    @Test fun `a write the system ignores is Unsupported and leaves the list as it was`() {
        val shell = DriverSettingsShell("com.a", ignoresPut = true)
        val store = FakeStore()
        val out = apply(shell, store)
        assertFalse(out.ok)
        assertTrue(out.detail.startsWith("Unsupported"))
        assertEquals("com.a", shell.value)
        assertFalse(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNSUPPORTED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `a list the system rewrites is Unsupported and put straight back`() {
        val shell = DriverSettingsShell("com.a", rewrite = { it.substringBefore(',') })
        val store = FakeStore()
        val out = apply(shell, store)
        assertFalse(out.ok)
        assertTrue(out.detail.contains("put back"))
        assertEquals("com.a", shell.value)
        assertFalse(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNSUPPORTED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `a refused write is Unsupported`() {
        val shell = DriverSettingsShell(null, failsPut = true)
        val store = FakeStore()
        assertFalse(apply(shell, store).ok)
        assertNull(shell.value)
        assertFalse(GraphicsDriver.held(store))
        assertEquals(CheckStatus.UNSUPPORTED, GraphicsDriver.lastResult(store)!!.status)
    }

    @Test fun `a mismatch on an unset key that cannot be put back stays held`() {
        val shell = DriverSettingsShell(null, rewrite = { "garbled" }, ignoresDelete = true)
        val store = FakeStore()
        val out = apply(shell, store)
        assertFalse(out.ok)
        assertEquals("garbled", shell.value)
        assertTrue(GraphicsDriver.held(store))
    }

    // ---- gates ----

    @Test fun `below Android 10 nothing runs`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        val out = apply(shell, store, sdk = 28)
        assertFalse(out.ok)
        assertTrue(shell.commands.isEmpty())
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `an unreadable list is left alone`() {
        val shell = DriverSettingsShell("com.a", unreadable = true)
        val store = FakeStore()
        assertFalse(apply(shell, store).ok)
        assertFalse(shell.commands.any { it.startsWith("settings put") })
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `a game already on the list changes nothing and holds nothing`() {
        val shell = DriverSettingsShell("com.a,$game")
        val store = FakeStore()
        assertTrue(apply(shell, store).ok)
        assertFalse(shell.commands.any { it.startsWith("settings put") })
        assertFalse(GraphicsDriver.held(store))
    }

    @Test fun `a name that is not a package is refused before anything runs`() {
        val shell = DriverSettingsShell("com.a")
        assertFalse(apply(shell, FakeStore(), pkg = "x'; reboot; '").ok)
        assertTrue(shell.commands.isEmpty())
    }

    // ---- the diagnostics row ----

    private fun row(result: DriverResult?, sdk: Int = 36) = DiagnosticsPanel.rows(
        DiagnosticsInput(
            androidRelease = "16", sdk = sdk, manufacturer = "TECNO", model = "TECNO LJ6", buildDisplay = "LJ6",
            shizukuRunning = true, shizukuPermission = true, shellReady = true,
            gamePackage = null, gameVersion = null, helpOutput = null, read = null, status = null,
            lastChangeNote = null, capSession = null, thermalDump = null, restrictIgnored = null,
            driverResult = result
        )
    ).first { it.name == "Graphics driver opt-in" }

    @Test fun `Supported only after a write, read-back and restore all matched`() {
        val shell = DriverSettingsShell("com.a")
        val store = FakeStore()
        apply(shell, store)
        // Written and read back, restore still pending: not yet.
        assertEquals(CheckStatus.UNVERIFIED, row(GraphicsDriver.lastResult(store)).status)
        GraphicsDriver.restore(shell, store)
        val done = row(GraphicsDriver.lastResult(store))
        assertEquals(CheckStatus.SUPPORTED, done.status)
        assertTrue(done.evidence.contains("unverified"))
    }

    @Test fun `the row says Unsupported on a mismatch, Not checked before any run, and Unsupported below Android 10`() {
        val shell = DriverSettingsShell("com.a", ignoresPut = true)
        val store = FakeStore()
        apply(shell, store)
        assertEquals(CheckStatus.UNSUPPORTED, row(GraphicsDriver.lastResult(store)).status)
        assertEquals(CheckStatus.NOT_CHECKED, row(null).status)
        assertEquals(CheckStatus.UNSUPPORTED, row(null, sdk = 28).status)
    }
}
