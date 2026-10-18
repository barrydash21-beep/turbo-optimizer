package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answers the appops, standby-bucket and process commands from state, the way the test device
 * did: an unset op reads "No operations.", and an exempt package's bucket set exits 0 but
 * changes nothing.
 */
private class FakeDevice(
    val installed: Set<String>,
    val ops: MutableMap<String, String> = mutableMapOf(),
    val buckets: MutableMap<String, Int> = mutableMapOf(),
    private val exempt: Set<String> = emptySet(),
    val running: MutableSet<String> = mutableSetOf(),
    /** Commands with any of these prefixes exit non-zero. */
    var failingPrefixes: Set<String> = emptySet(),
    private val available: Boolean = true,
    private val keyboard: String = "com.example.keyboard/.Ime"
) : ShellExecutor {

    val commands = mutableListOf<String>()

    init {
        exempt.forEach { buckets[it] = BackgroundRestrictor.BUCKET_EXEMPTED }
    }

    override fun isAvailable(): Boolean = available

    override fun run(command: String): ShellResult {
        commands += command
        if (failingPrefixes.any { command.startsWith(it) }) return ShellResult(exitCode = 1)
        val p = command.split(" ")
        when {
            command.startsWith("cmd appops set ") -> ops[p[3]] = p[5]
            command.startsWith("am set-standby-bucket ") -> if (p[2] !in exempt) {
                buckets[p[2]] = if (p[3] == "restricted") BackgroundRestrictor.BUCKET_RESTRICTED else p[3].toInt()
            }
            command.startsWith("am force-stop ") -> running.removeAll { it == p[2] || it.startsWith(p[2] + ":") }
        }
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        commands += command
        if (failingPrefixes.any { command.startsWith(it) }) return null
        val pkg = command.split(" ").getOrNull(3)
        return when {
            command == "pm list packages" -> installed.joinToString("\n") { "package:$it" }
            command.startsWith("cmd appops get ") ->
                ops[pkg]?.let { "${BackgroundRestrictor.OP}: $it" } ?: "No operations.\nDefault mode: allow"
            command.startsWith("am get-standby-bucket ") -> (buckets[command.split(" ")[2]] ?: 10).toString()
            command == "ps -A -o NAME" -> running.joinToString("\n")
            command.startsWith("cmd package resolve-activity") -> "priority=0\ncom.example.launcher/.Home"
            command == "settings get secure default_input_method" -> keyboard
            command == "cmd role get-role-holders android.app.role.SMS" -> "com.example.sms"
            command == "cmd role get-role-holders android.app.role.DIALER" -> "com.example.dialer"
            else -> null
        }
    }

    fun ran(prefix: String): Boolean = commands.any { it.startsWith(prefix) }

    /** Commands that change or stop [pkg]. */
    fun mutations(pkg: String): List<String> = commands.filter {
        (it.startsWith("cmd appops set ") || it.startsWith("am set-standby-bucket ") || it.startsWith("am force-stop ")) &&
            it.split(" ").contains(pkg)
    }
}

class BackgroundRestrictorTest {

    private val game = "com.example.game"
    private val social = "com.example.social"
    private val chat = "com.example.chat"
    private val pushed = "com.example.pushed"

    private fun device(exempt: Set<String> = emptySet()) = FakeDevice(
        installed = setOf(game, social, chat, pushed, "com.example.sms", "com.example.launcher"),
        ops = mutableMapOf(chat to "ignore"),
        buckets = mutableMapOf(social to 30, chat to 40),
        exempt = exempt,
        running = mutableSetOf(social, "$social:push", chat, game)
    )

    private fun restrict(shell: FakeDevice, store: KeyValueStore, list: List<String> = listOf(social, chat, pushed)) =
        BackgroundRestrictor.restrict(shell, store, list, game, sdkInt = 36)

    @Test
    fun `every state is saved before anything changes, then op, bucket and stop in that order`() {
        val shell = device()
        val store = FakeStore()
        restrict(shell, store)

        val firstChange = shell.commands.indexOfFirst { it.startsWith("cmd appops set ") }
        val reads = shell.commands.take(firstChange)
        for (pkg in listOf(social, chat, pushed)) {
            assertTrue(reads.contains("cmd appops get $pkg ${BackgroundRestrictor.OP}"))
            assertTrue(reads.contains("am get-standby-bucket $pkg"))
        }
        val lastOpSet = shell.commands.indexOfLast { it.startsWith("cmd appops set ") }
        val firstBucketSet = shell.commands.indexOfFirst { it.startsWith("am set-standby-bucket ") }
        val lastBucketSet = shell.commands.indexOfLast { it.startsWith("am set-standby-bucket ") }
        val firstStop = shell.commands.indexOfFirst { it.startsWith("am force-stop ") }
        assertTrue(lastOpSet < firstBucketSet)
        assertTrue(lastBucketSet < firstStop)

        assertTrue(BackgroundRestrictor.isActive(store))
        assertEquals(listOf(social, chat, pushed), BackgroundRestrictor.heldPackages(store))
        assertEquals("ignore", shell.ops[social])
        assertEquals(BackgroundRestrictor.BUCKET_RESTRICTED, shell.buckets[social])
        assertEquals(setOf(game), shell.running)
    }

    @Test
    fun `restore puts every op and bucket back as read before, and ends the session`() {
        val shell = device()
        val store = FakeStore()
        val before = listOf(social, chat, pushed).associateWith {
            BackgroundRestrictor.parseOpMode(shell.capture("cmd appops get $it ${BackgroundRestrictor.OP}")) to
                BackgroundRestrictor.parseBucket(shell.capture("am get-standby-bucket $it"))
        }
        restrict(shell, store)

        val report = BackgroundRestrictor.restore(shell, store, sdkInt = 36)

        assertTrue(report.ok)
        val after = listOf(social, chat, pushed).associateWith {
            BackgroundRestrictor.parseOpMode(shell.capture("cmd appops get $it ${BackgroundRestrictor.OP}")) to
                BackgroundRestrictor.parseBucket(shell.capture("am get-standby-bucket $it"))
        }
        assertEquals(before, after)
        // "No operations." cannot be written back; its default mode is, which reads the same.
        assertEquals("allow", shell.ops[social])
        assertFalse(BackgroundRestrictor.isActive(store))
        assertTrue(BackgroundRestrictor.heldPackages(store).isEmpty())
    }

    @Test
    fun `restore starts nothing`() {
        val shell = device()
        val store = FakeStore()
        restrict(shell, store)
        shell.commands.clear()
        BackgroundRestrictor.restore(shell, store, sdkInt = 36)
        assertTrue(shell.commands.none { it.startsWith("am start") || it.startsWith("monkey") || it.startsWith("am force-stop") })
        assertEquals(setOf(game), shell.running)
    }

    @Test
    fun `an exempt package's ignored bucket is reported and never worked around`() {
        val shell = device(exempt = setOf(social))
        val store = FakeStore()
        val report = restrict(shell, store)

        val outcome = report.packages.single { it.packageName == social }
        assertEquals(listOf("bucket ignored (stays 5)"), outcome.ignored)
        assertTrue(outcome.ok)
        assertTrue(report.restrictSummary().contains("partly ignored"))
        assertTrue(shell.commands.none { it.contains("deviceidle") })

        shell.commands.clear()
        assertTrue(BackgroundRestrictor.restore(shell, store, sdkInt = 36).ok)
        assertTrue(shell.commands.none { it.startsWith("am set-standby-bucket $social") })
    }

    @Test
    fun `protected packages are never touched, even when listed`() {
        val store = FakeStore()
        val protectedOnes = listOf(
            game, BuildConfig.APPLICATION_ID, AppPackages.SHIZUKU, "com.android.systemui", "com.google.android.gms",
            "com.example.launcher", "com.example.keyboard", "com.example.sms", "com.example.dialer"
        )
        val shell = FakeDevice(installed = protectedOnes.toSet() + social)
        val report = BackgroundRestrictor.restrict(shell, store, protectedOnes + social, game, sdkInt = 36)

        for (pkg in protectedOnes) {
            assertTrue("$pkg was changed", shell.mutations(pkg).isEmpty())
            assertTrue(BackgroundRestrictor.heldPackages(store).none { it == pkg })
        }
        assertEquals(listOf(social), BackgroundRestrictor.heldPackages(store))
        assertTrue(report.packages.filter { it.packageName in protectedOnes }.all { it.skipped != null })
    }

    @Test
    fun `nothing is restricted when the launcher or keyboard cannot be identified`() {
        val shell = FakeDevice(installed = setOf(social), running = mutableSetOf(social), keyboard = "null")
        val store = FakeStore()
        val report = BackgroundRestrictor.restrict(shell, store, listOf(social), game, sdkInt = 36)
        assertEquals("could not identify the launcher or keyboard", report.skipped)
        assertTrue(shell.mutations(social).isEmpty())
        assertTrue(store.isEmpty)
    }

    @Test
    fun `packages that are not installed are left out`() {
        val shell = device()
        val store = FakeStore()
        val report = restrict(shell, store, listOf(social, "com.example.gone"))
        assertEquals(listOf(social), report.packages.map { it.packageName })
        assertTrue(shell.commands.none { it.contains("com.example.gone") })
    }

    @Test
    fun `a second session keeps the first session's originals`() {
        val shell = device()
        val store = FakeStore()
        restrict(shell, store)
        restrict(shell, store)
        BackgroundRestrictor.restore(shell, store, sdkInt = 36)
        assertEquals(30, shell.buckets[social])
        assertEquals("ignore", shell.ops[chat])
        assertEquals("allow", shell.ops[social])
    }

    @Test
    fun `one failing command does not stop the rest`() {
        val shell = device().apply { failingPrefixes = setOf("cmd appops set $social") }
        val store = FakeStore()
        val report = restrict(shell, store)

        assertEquals(listOf("background"), report.packages.single { it.packageName == social }.failed)
        assertEquals(BackgroundRestrictor.BUCKET_RESTRICTED, shell.buckets[social])
        assertEquals("ignore", shell.ops[pushed])
        assertEquals(setOf(game), shell.running)
        assertEquals(listOf(social), report.failedPackages)
    }

    @Test
    fun `a package whose restore fails stays held and is retried until it lands`() {
        val shell = device()
        val store = FakeStore()
        restrict(shell, store)

        shell.failingPrefixes = setOf("am set-standby-bucket $chat")
        val first = BackgroundRestrictor.restore(shell, store, sdkInt = 36)
        assertFalse(first.ok)
        assertEquals(listOf(chat), first.failedPackages)
        assertTrue(BackgroundRestrictor.isActive(store))
        assertEquals(listOf(chat), BackgroundRestrictor.heldPackages(store))

        shell.failingPrefixes = emptySet()
        assertTrue(BackgroundRestrictor.restore(shell, store, sdkInt = 36).ok)
        assertEquals(40, shell.buckets[chat])
        assertFalse(BackgroundRestrictor.isActive(store))
    }

    @Test
    fun `a package uninstalled mid-session is forgotten, not retried forever`() {
        val shell = device()
        val store = FakeStore()
        restrict(shell, store)
        val later = FakeDevice(installed = setOf(game, chat, pushed), ops = shell.ops, buckets = shell.buckets)
        assertTrue(BackgroundRestrictor.restore(later, store, sdkInt = 36).ok)
        assertFalse(BackgroundRestrictor.isActive(store))
        assertTrue(later.mutations(social).isEmpty())
    }

    @Test
    fun `without Shizuku nothing is attempted and the status says so`() {
        val shell = FakeDevice(installed = setOf(social), available = false)
        val store = FakeStore()
        val report = restrict(shell, store)
        assertTrue(report.unavailable)
        assertTrue(shell.commands.isEmpty())
        assertTrue(store.isEmpty)
        assertEquals("Background apps: skipped, Shizuku not connected", report.restrictSummary())

        store.putBoolean("active", true)
        assertTrue(BackgroundRestrictor.restore(shell, store).unavailable)
        assertTrue(BackgroundRestrictor.isActive(store))
    }

    @Test
    fun `below Android 9 nothing runs, below Android 11 the bucket is left alone`() {
        val old = device()
        val oldStore = FakeStore()
        val skipped = BackgroundRestrictor.restrict(old, oldStore, listOf(social), game, sdkInt = 27)
        assertTrue(skipped.skipped != null)
        assertTrue(old.commands.isEmpty())

        val p = device()
        BackgroundRestrictor.restrict(p, FakeStore(), listOf(social), game, sdkInt = 29)
        assertTrue(p.ran("cmd appops set $social"))
        assertTrue(p.commands.none { it.startsWith("am set-standby-bucket") })
        assertEquals(30, p.buckets[social])
    }

    @Test
    fun `alive lists held packages with any live process`() {
        val shell = device()
        val store = FakeStore()
        BackgroundRestrictor.restrict(shell, store, listOf(social, chat), game, sdkInt = 36)
        assertEquals(emptyList<String>(), BackgroundRestrictor.alive(shell, store))
        shell.running += "$social:push"
        assertEquals(listOf(social), BackgroundRestrictor.alive(shell, store))
        assertEquals(emptyList<String>(), BackgroundRestrictor.alive(shell, FakeStore()))
    }

    @Test
    fun `op modes are read as the device prints them`() {
        val op = BackgroundRestrictor.OP
        assertEquals("ignore", BackgroundRestrictor.parseOpMode("$op: ignore"))
        assertEquals("ignore", BackgroundRestrictor.parseOpMode("$op: ignore; time=+1d2h3m ago"))
        assertEquals("allow", BackgroundRestrictor.parseOpMode("No operations.\nDefault mode: allow"))
        assertEquals("allow", BackgroundRestrictor.parseOpMode("No operations."))
        assertEquals("default", BackgroundRestrictor.parseOpMode("$op: default"))
        assertEquals("allow", BackgroundRestrictor.parseOpMode("Uid mode: $op: ignore\n$op: allow"))
        assertNull(BackgroundRestrictor.parseOpMode("Error: Unknown operation string: $op"))
        assertNull(BackgroundRestrictor.parseOpMode(null))
    }

    @Test
    fun `buckets are read as the number the device prints`() {
        assertEquals(5, BackgroundRestrictor.parseBucket("5"))
        assertEquals(45, BackgroundRestrictor.parseBucket("45\n"))
        assertNull(BackgroundRestrictor.parseBucket("Error"))
        assertNull(BackgroundRestrictor.parseBucket(null))
    }

    @Test
    fun `defaults are the installed ones that are not protected`() {
        val installed = setOf("com.facebook.katana", "com.instagram.android", "com.google.android.apps.messaging")
        val protected = setOf("com.google.android.apps.messaging")
        assertEquals(
            listOf("com.facebook.katana", "com.instagram.android"),
            BackgroundRestrictor.defaultsFor(installed, protected)
        )
    }

    @Test
    fun `each package's row says what happened to it`() {
        assertEquals(
            "background ignore, stopped, bucket ignored (stays 5)",
            PackageOutcome("p", applied = listOf("background ignore", "stopped"), ignored = listOf("bucket ignored (stays 5)")).detail()
        )
        assertEquals("protected, left alone", PackageOutcome("p", skipped = "protected, left alone").detail())
    }
}
