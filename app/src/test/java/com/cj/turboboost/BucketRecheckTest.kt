package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `am get-standby-bucket` / `set-standby-bucket` over a bucket per package. */
private class BucketShell(
    val buckets: MutableMap<String, Int>,
    /** Packages whose set exits 0 but changes nothing. */
    val ignores: Set<String> = emptySet()
) : ShellExecutor {

    val commands = mutableListOf<String>()

    override fun isAvailable() = true

    override fun run(command: String): ShellResult {
        commands += command
        val parts = command.split(" ")
        if (command.startsWith("am set-standby-bucket ") && parts[2] !in ignores) {
            buckets[parts[2]] = parts[3].toInt()
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        if (!command.startsWith("am get-standby-bucket ")) return null
        return buckets[command.removePrefix("am get-standby-bucket ")]?.toString()
    }

    val sets: List<String> get() = commands.filter { it.startsWith("am set-standby-bucket") }
}

class BucketRecheckTest {

    private val lite = "com.example.lite"
    private val chat = "com.example.chat"

    private fun restored(vararg packages: String) = RestrictionReport(packages.map { PackageOutcome(it, applied = listOf("bucket")) })

    @Test
    fun `remembers the restored buckets as read back, with the boot`() {
        val shell = BucketShell(mutableMapOf(lite to 10, chat to 30))
        val store = FakeStore()

        BucketRecheck.remember(shell, store, restored(lite, chat), "boot-1")

        assertEquals(listOf(lite to 10, chat to 30), BucketRecheck.saved(store))
        assertEquals("boot-1", store.getString("recheck_boot"))
    }

    @Test
    fun `skips packages that failed, were skipped, or sit in a bucket that cannot be set`() {
        val shell = BucketShell(mutableMapOf(lite to 10, chat to 5, "com.example.social" to 45, "com.example.skip" to 10))
        val store = FakeStore()
        val report = RestrictionReport(
            listOf(
                PackageOutcome(lite),
                PackageOutcome(chat),
                PackageOutcome("com.example.social"),
                PackageOutcome("com.example.skip", skipped = "not installed"),
                PackageOutcome("com.example.fail", failed = listOf("bucket"))
            )
        )

        BucketRecheck.remember(shell, store, report, "boot-1")

        assertEquals(listOf(lite to 10), BucketRecheck.saved(store))
    }

    @Test
    fun `nothing is remembered without a boot id`() {
        val store = FakeStore()
        BucketRecheck.remember(BucketShell(mutableMapOf(lite to 10)), store, restored(lite), null)
        assertTrue(store.isEmpty)
    }

    @Test
    fun `in the same boot nothing is checked and the record is kept`() {
        val shell = BucketShell(mutableMapOf(lite to 10))
        val store = FakeStore()
        BucketRecheck.remember(shell, store, restored(lite), "boot-1")
        shell.commands.clear()

        assertNull(BucketRecheck.recheck(shell, store, "boot-1"))
        assertTrue(shell.commands.isEmpty())
        assertEquals(listOf(lite to 10), BucketRecheck.saved(store))
    }

    @Test
    fun `after a reboot a bucket back at 45 is set to the saved value and read back, then the record is cleared`() {
        // As on the test device: restored to 10, read 45 again after a reboot.
        val shell = BucketShell(mutableMapOf(lite to 10, chat to 30))
        val store = FakeStore()
        BucketRecheck.remember(shell, store, restored(lite, chat), "boot-1")
        shell.buckets[lite] = 45
        shell.commands.clear()

        val outcomes = BucketRecheck.recheck(shell, store, "boot-2")!!

        assertEquals(listOf("am set-standby-bucket $lite 10"), shell.sets)
        assertEquals(BucketRecheck.Outcome(lite, 10, 45, 10, setAgain = true), outcomes[0])
        assertEquals(BucketRecheck.Outcome(chat, 30, 30, 30, setAgain = false), outcomes[1])
        assertEquals(10, shell.buckets[lite])
        assertTrue(BucketRecheck.saved(store).isEmpty())
        assertNull(store.getString("recheck_boot"))
        // Done once.
        assertNull(BucketRecheck.recheck(shell, store, "boot-3"))
    }

    @Test
    fun `a bucket changed to anything but 45 is left alone`() {
        val shell = BucketShell(mutableMapOf(lite to 10))
        val store = FakeStore()
        BucketRecheck.remember(shell, store, restored(lite), "boot-1")
        shell.buckets[lite] = 40 // the system's own aging, not the restriction

        val outcomes = BucketRecheck.recheck(shell, store, "boot-2")!!

        assertTrue(shell.sets.isEmpty())
        assertFalse(outcomes.single().setAgain)
        assertEquals(40, shell.buckets[lite])
    }

    @Test
    fun `a set the system ignores is reported as still 45`() {
        val shell = BucketShell(mutableMapOf(lite to 10), ignores = setOf(lite))
        val store = FakeStore()
        BucketRecheck.remember(shell, store, restored(lite), "boot-1")
        shell.buckets[lite] = 45

        val outcome = BucketRecheck.recheck(shell, store, "boot-2")!!.single()

        assertTrue(outcome.setAgain)
        assertEquals(45, outcome.after)
        assertTrue(BucketRecheck.saved(store).isEmpty())
    }

    @Test
    fun `an unreadable boot leaves the record for later`() {
        val shell = BucketShell(mutableMapOf(lite to 10))
        val store = FakeStore()
        BucketRecheck.remember(shell, store, restored(lite), "boot-1")

        assertNull(BucketRecheck.recheck(shell, store, null))
        assertEquals(listOf(lite to 10), BucketRecheck.saved(store))
    }

    @Test
    fun `nothing from a tampered record reaches the shell`() {
        val shell = BucketShell(mutableMapOf(lite to 45))
        val store = FakeStore()
        store.putString("recheck_boot", "boot-1")
        store.putString("recheck_buckets", "x; reboot:10,$lite:10; reboot,$lite:45,$lite:5")

        val outcomes = BucketRecheck.recheck(shell, store, "boot-2")!!

        assertTrue(outcomes.isEmpty())
        assertTrue(shell.commands.isEmpty())
    }
}
