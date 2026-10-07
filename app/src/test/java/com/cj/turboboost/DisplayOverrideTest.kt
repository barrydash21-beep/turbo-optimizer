package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers `wm size` / `wm density` like the platform: override lines only when one is set. */
private class WmShell(
    var overrideSize: String? = null,
    var overrideDensity: Int? = null,
    var available: Boolean = true,
    private val readable: Boolean = true
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable() = available

    override fun run(command: String): ShellResult {
        commands += command
        when (command) {
            "wm size reset" -> overrideSize = null
            "wm density reset" -> overrideDensity = null
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        if (!readable) return null
        return when (command) {
            "wm size" -> "Physical size: 1080x2460" + (overrideSize?.let { "\nOverride size: $it" } ?: "")
            "wm density" -> "Physical density: 440" + (overrideDensity?.let { "\nOverride density: $it" } ?: "")
            else -> null
        }
    }

    /** Commands that would change the display. */
    val displayWrites: List<String>
        get() = commands.filter { it.startsWith("wm size ") || it.startsWith("wm density ") }
}

class DisplayOverrideTest {

    @Test
    fun `parses wm output with and without override lines`() {
        val none = DisplayOverride.parseState("Physical size: 1080x2460", "Physical density: 440")!!
        assertFalse(none.hasOverride)

        val both = DisplayOverride.parseState(
            "Physical size: 1080x2460\r\nOverride size: 900x2050\r\n",
            "  Physical density: 440\n  Override density: 367  \n"
        )!!
        assertEquals(DisplaySize(900, 2050), both.overrideSize)
        assertEquals(367, both.overrideDensity)
        assertTrue(both.hasOverride)

        assertTrue(DisplayOverride.parseState("Physical size: 1080x2460", "Physical density: 440\nOverride density: 400")!!.hasOverride)
        assertNull(DisplayOverride.parseState(null, "Physical density: 440"))
        assertNull(DisplayOverride.parseState("Override size: 900x2050", "Physical density: 440"))
    }

    @Test
    fun `no override - check is done for good and nothing is changed`() {
        val shell = WmShell()
        val options = FakeStore()

        assertNull(DisplayOverride.check(shell, options, FakeStore()))
        assertTrue(DisplayOverride.isChecked(options))
        assertTrue(shell.displayWrites.isEmpty())
    }

    @Test
    fun `an override is put to the user and left exactly as it is`() {
        val shell = WmShell(overrideSize = "900x2050", overrideDensity = 367)
        val options = FakeStore()
        val legacy = FakeStore().apply { putBoolean("pending_restore", true) }

        val found = DisplayOverride.check(shell, options, legacy)!!

        assertEquals(DisplaySize(900, 2050), found.overrideSize)
        assertEquals(367, found.overrideDensity)
        assertTrue(shell.displayWrites.isEmpty())
        assertEquals("900x2050", shell.overrideSize)
        // Asked again next start until the user answers.
        assertFalse(DisplayOverride.isChecked(options))
        // The removed lever's records cannot trigger anything any more.
        assertTrue(legacy.isEmpty)
        // Its 15 s shell countdown is stopped rather than left to reset the display.
        assertTrue(shell.commands.first().contains("turboboost_resolution_watchdog.pid"))
    }

    @Test
    fun `unreadable or Shizuku down - asked again next start, nothing changed`() {
        val options = FakeStore()
        val unreadable = WmShell(readable = false)
        assertNull(DisplayOverride.check(unreadable, options, FakeStore()))
        assertFalse(DisplayOverride.isChecked(options))

        val down = WmShell(overrideSize = "900x2050", available = false)
        assertNull(DisplayOverride.check(down, options, FakeStore()))
        assertTrue(down.commands.isEmpty())
    }

    @Test
    fun `once answered the check never runs again`() {
        val shell = WmShell(overrideSize = "900x2050")
        val options = FakeStore()
        DisplayOverride.markChecked(options)

        assertNull(DisplayOverride.check(shell, options, FakeStore()))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `reset clears both overrides and reports the result`() {
        val shell = WmShell(overrideSize = "900x2050", overrideDensity = 367)
        val after = DisplayOverride.reset(shell)!!
        assertFalse(after.hasOverride)
        assertEquals(listOf("wm size reset", "wm density reset"), shell.displayWrites)
    }
}
