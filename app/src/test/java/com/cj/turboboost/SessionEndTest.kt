package com.cj.turboboost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A phone as far as a session end needs: `settings`, the watcher's data file, and every command in order. */
private class SessionShell(private val log: MutableList<String>) : ShellExecutor {
    var available = true

    /** HiOS-style: writes exit 0 but do not stick. */
    var ignorePuts = false
    var data: String? = null
    val system = mutableMapOf("min_refresh_rate" to "10.0", "peak_refresh_rate" to "120")

    /** The game's GameManager state, in the formats `cmd game` prints on the Pova 7. */
    var gameMode = "standard"
    val gameConfigs = mutableMapOf<Int, Pair<String, String>>()
    var boot = "boot-1"

    private fun game(command: String): String? {
        val pkg = command.split(" ").getOrNull(3) ?: return null
        return when {
            command.startsWith("cmd game list-modes") -> "$pkg current mode: $gameMode, available game modes: [standard,custom]"
            command.startsWith("cmd game list-configs") -> if (gameConfigs.isEmpty()) {
                "$pkg interventions: \n No intervention found for package $pkg"
            } else {
                "$pkg interventions: \n[Name:$pkg Modes: {" + gameConfigs.toSortedMap().entries.joinToString(", ") { (id, c) ->
                    "$id=[Game Mode:$id,Scaling:${c.first},Use Angle:false,Fps:${c.second},Loading Boost Duration:-1]"
                } + "}]"
            }
            else -> null
        }
    }

    override fun isAvailable() = available

    override fun run(command: String): ShellResult {
        if (!available) return ShellResult.unavailable()
        log += "run:$command"
        val p = command.split(" ")
        if (p.size >= 4 && p[0] == "settings" && p[2] == "system") {
            when (p[1]) {
                "put" -> if (!ignorePuts) system[p[3]] = p[4]
                "delete" -> system.remove(p[3])
            }
        }
        if (command == "rm -f ${SessionWatcher.DATA}") data = null
        return ShellResult.success()
    }

    override fun capture(command: String): String? {
        if (!available) return null
        log += "capture:$command"
        val marker = " 2>&1; echo \"__exit:\$?\""
        if (command.endsWith(marker)) return game(command.removeSuffix(marker))?.let { "$it\n__exit:0" }
        return when {
            command == "cat /proc/sys/kernel/random/boot_id" -> boot
            command.startsWith("settings get system") -> system[command.split(" ")[3]] ?: "null"
            command == "cat ${SessionWatcher.DATA}" -> data
            command.startsWith("cmd power") -> "exit=0"
            else -> null
        }
    }
}

/** A store that writes every put into the same log as the shell, so their order can be checked. */
private class LoggingStore(private val log: MutableList<String>) : KeyValueStore {
    private val inner = FakeStore()
    override fun getString(key: String): String? = inner.getString(key)
    override fun putString(key: String, value: String?) {
        log += "put:$key=$value"
        inner.putString(key, value)
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = inner.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        log += "put:$key=$value"
        inner.putBoolean(key, value)
    }
    override fun clear() = inner.clear()
}

class SessionEndTest {

    private val realShell = RamCleaner.shell
    private val log = mutableListOf<String>()
    private val shell = SessionShell(log)
    private val tuner = FakeStore()
    private val sessions = SessionStore(FakeStore())
    private val ctx = SessionContext(
        shell = shell,
        session = LoggingStore(log),
        tuner = tuner,
        restrictions = FakeStore(),
        sessions = sessions,
        gameResolution = FakeStore(),
        elapsedNow = { 200_000L },
        wallNow = { 1_000_000L }
    )
    private val token = SessionEnd.newToken()
    private val frameNs = 16_666_667L

    @Before
    fun setUp() {
        RamCleaner.shell = shell
        SessionEnd.live = null
    }

    @After
    fun tearDown() {
        RamCleaner.shell = realShell
        SessionEnd.live = null
    }

    /** A boost: the refresh rate pinned, then a session with its watcher. */
    private fun boost(tokenValue: String = token, watcher: Boolean = true) {
        PerformanceTuner.applyGamingTweaks(tuner, GameRegistry.profileFor(FAKE_GAME), 60f)
        SessionEnd.begin(ctx, FAKE_GAME, 60f, "default", null, tokenValue)
        SessionEnd.noteWatcher(ctx.session, watcher)
    }

    /** The watcher's file; [end] null is a session it is still watching. */
    private fun watched(end: String? = "exited"): String = listOfNotNull(
        "S 100000 4242",
        "F 102000 0 128 1000000000000" + " $frameNs".repeat(119),
        "T 130000 0",
        "P 130000 60",
        end?.let { "E 150000 $it" }
    ).joinToString("\n")

    @Test
    fun `a fresh token is 32 hex digits and never repeats`() {
        val a = SessionEnd.newToken()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(a))
        assertNotEquals(a, SessionEnd.newToken())
    }

    @Test
    fun `only the shell or root holding the session's token may end it`() {
        boost()
        val store = ctx.session
        assertTrue(SessionEnd.authorize(2000, token, store))
        assertTrue(SessionEnd.authorize(0, token, store))
        // Another Shizuku app runs as the shell uid too: the uid alone is not enough.
        assertFalse(SessionEnd.authorize(2000, "f".repeat(32), store))
        assertFalse(SessionEnd.authorize(2000, null, store))
        assertFalse(SessionEnd.authorize(10_534, token, store))
        shell.data = watched()
        assertTrue(SessionEnd.finish(ctx, EndReason.EXITED).ok)
        // Ended: the old token is gone with it.
        assertFalse(SessionEnd.authorize(2000, token, store))
    }

    @Test
    fun `ending is marked before anything is restored, ended only after every read-back`() {
        boost()
        shell.data = watched()
        log.clear()

        val outcome = SessionEnd.finish(ctx, EndReason.EXITED)

        assertTrue(outcome.ok)
        val ending = log.indexOf("put:state=ending")
        val ended = log.indexOf("put:state=ended")
        val firstRestore = log.indexOfFirst { it.startsWith("run:settings put system") }
        val lastReadBack = log.indexOfLast { it.startsWith("capture:settings get system") }
        assertTrue(ending in 0 until firstRestore)
        assertTrue(ended > lastReadBack)
        assertEquals("120", shell.system["peak_refresh_rate"])
        assertEquals(SessionEnd.STATE_ENDED, SessionEnd.state(ctx.session))
        // The watcher is stopped and its file removed once ended.
        assertTrue(log.contains("run:rm -f ${SessionWatcher.DATA}"))
        assertTrue(log.any { it.startsWith("run:rm -f ${SessionWatcher.TOKEN}") })
    }

    @Test
    fun `the record comes from the watcher's data`() {
        boost()
        shell.data = watched()

        val record = SessionEnd.finish(ctx, EndReason.EXITED).record!!

        assertEquals(FAKE_GAME, record.packageName)
        assertEquals(60f, record.avgFps!!, 0.05f)
        assertEquals(50_000L, record.durationMs)
        // Status 0 never reached 1.
        assertNull(record.msToThermal1)
        assertEquals(0, record.maxThermal)
        assertEquals(60f, record.observedPeak!!, 0f)
        assertEquals("default", record.renderResolution)
        // Seen 100 s ago on the elapsed clock: started 100 s before the wall clock now.
        assertEquals(900_000L, record.startedAtMs)
        assertEquals("exited", record.endReason)
        assertEquals(listOf(record), sessions.all())
    }

    @Test
    fun `a restore that does not read back stays ending, and the next try finishes it without a second record`() {
        boost()
        shell.data = watched()
        shell.ignorePuts = true

        val first = SessionEnd.finish(ctx, EndReason.EXITED)

        assertFalse(first.ok)
        assertEquals(SessionEnd.STATE_ENDING, SessionEnd.state(ctx.session))
        assertEquals(1, sessions.all().size)
        assertTrue(PerformanceTuner.isActive(tuner))
        assertTrue(first.tweaks.failed.any { it.startsWith("Refresh rate (peak_refresh_rate) reads 60") })

        shell.ignorePuts = false
        val second = SessionEnd.finish(ctx, EndReason.APP_START)

        assertTrue(second.ok)
        assertNull(second.record)
        assertEquals(SessionEnd.STATE_ENDED, SessionEnd.state(ctx.session))
        assertEquals(1, sessions.all().size)
        // The reason is the first try's, not the app start's.
        assertEquals("exited", sessions.all().single().endReason)
    }

    @Test
    fun `the watcher's data waits for Shizuku rather than being given up`() {
        boost()
        shell.data = watched()
        shell.available = false

        assertFalse(SessionEnd.finish(ctx, EndReason.EXITED).ok)
        assertTrue(sessions.all().isEmpty())
        assertEquals(SessionEnd.STATE_ENDING, SessionEnd.state(ctx.session))

        shell.available = true
        assertTrue(SessionEnd.finish(ctx, EndReason.APP_START).ok)
        assertEquals(1, sessions.all().size)
    }

    @Test
    fun `a restarted game is recorded as such, from the watcher's own end line`() {
        boost()
        shell.data = watched(end = "restarted")
        assertEquals("restarted", SessionEnd.finish(ctx, EndReason.EXITED).record!!.endReason)
        assertTrue(sessions.all().single().gameRestarted)
    }

    @Test
    fun `a game never seen ends the session with no record`() {
        boost()
        shell.data = "E 90000 never_started"
        val outcome = SessionEnd.finish(ctx, EndReason.NEVER_STARTED)
        assertTrue(outcome.ok)
        assertNull(outcome.record)
        assertTrue(sessions.all().isEmpty())
        assertFalse(PerformanceTuner.isActive(tuner))
    }

    @Test
    fun `without a watcher the in-process samples are the record`() {
        boost(watcher = false)
        val live = SessionAccumulator(FAKE_GAME, 60f, 5L, 190_000L)
        live.addSample(195_000L, 1, (0L..120L).map { 1_000_000_000_000L + it * frameNs })
        SessionEnd.live = live

        val record = SessionEnd.finish(ctx, EndReason.EXITED).record!!

        assertEquals(60f, record.avgFps!!, 0.05f)
        assertEquals(10_000L, record.durationMs)
        assertEquals(1, record.maxThermal)
        assertNull(SessionEnd.live)
        assertFalse(log.contains("capture:cat ${SessionWatcher.DATA}"))
    }

    @Test
    fun `a new boost keeps the open session's record and restores nothing`() {
        boost()
        shell.data = watched(end = null)
        val second = SessionEnd.newToken()

        SessionEnd.begin(ctx, FAKE_GAME, 60f, "default", null, second)

        assertEquals("replaced", sessions.all().single().endReason)
        assertTrue(PerformanceTuner.isActive(tuner))
        assertEquals(SessionEnd.STATE_ACTIVE, SessionEnd.state(ctx.session))
        assertTrue(SessionEnd.authorize(2000, second, ctx.session))
        assertFalse(SessionEnd.authorize(2000, token, ctx.session))
        assertFalse(SessionEnd.watcherHeld(ctx.session))
    }

    @Test
    fun `with no session on record the held tweaks are still put back`() {
        PerformanceTuner.applyGamingTweaks(tuner, GameRegistry.profileFor(FAKE_GAME), 60f)
        val outcome = SessionEnd.finish(ctx, EndReason.APP_START)
        assertTrue(outcome.ok)
        assertNull(outcome.record)
        assertFalse(PerformanceTuner.isActive(tuner))
        assertNull(SessionEnd.state(ctx.session))
    }

    @Test
    fun `app start finishes a session left ending or abandoned, never one its watcher still runs`() {
        val store = FakeStore()
        fun needs(monitor: Boolean, watcher: Boolean, tweaks: Boolean = false) =
            SessionEnd.needsFinishAtStart(store, tweaks, false, monitor) { watcher }

        // No session on record: the tweaks flag decides, as before.
        assertFalse(needs(monitor = false, watcher = false))
        assertTrue(needs(monitor = false, watcher = false, tweaks = true))
        assertFalse(needs(monitor = true, watcher = false, tweaks = true))

        store.putString("state", SessionEnd.STATE_ACTIVE)
        assertTrue(needs(monitor = false, watcher = false))
        assertFalse(needs(monitor = false, watcher = true))
        assertFalse(needs(monitor = true, watcher = false))

        store.putString("state", SessionEnd.STATE_ENDING)
        assertTrue(needs(monitor = true, watcher = true))

        store.putString("state", SessionEnd.STATE_ENDED)
        assertFalse(needs(monitor = false, watcher = false))
    }

    /** A monitor-only session: nothing held, the start observed, as the monitor begins one. */
    private fun baseline(startCheck: Contamination? = Contamination.CLEAN) {
        SessionEnd.begin(ctx, FAKE_GAME, 120f, "default", null, token, SessionRecord.BASELINE, startCheck)
        SessionEnd.noteWatcher(ctx.session, true)
        SessionEnd.observeStart(ctx, FAKE_GAME)
    }

    @Test
    fun `a baseline records its kind, the state it ran under and a clean check, and restores nothing`() {
        baseline()
        shell.data = watched()
        log.clear()

        val outcome = SessionEnd.finish(ctx, EndReason.EXITED)
        val record = outcome.record!!

        assertTrue(outcome.ok)
        assertTrue(record.isBaseline)
        assertEquals("standard", record.gameStateStart)
        assertEquals("standard", record.gameStateEnd)
        assertEquals(Contamination.CLEAN.name, record.contamination)
        assertFalse(record.baselineInvalid)
        // Nothing was held, so nothing was written to the phone's settings or the game.
        assertFalse(log.any { it.startsWith("run:settings") || it.startsWith("run:cmd") })
    }

    @Test
    fun `a baseline that starts with a Game Manager config in any mode is invalid`() {
        shell.gameConfigs[2] = "0.8" to "90"
        baseline()
        shell.data = watched()
        val record = SessionEnd.finish(ctx, EndReason.EXITED).record!!
        assertEquals("standard;2=0.8/90", record.gameStateStart)
        assertTrue(record.baselineInvalid)
        assertTrue(SessionComparison.excluded(record))
    }

    @Test
    fun `a config another app sets mid-session is contamination, and the next session's reference`() {
        boost()
        SessionEnd.observeStart(ctx, FAKE_GAME)
        shell.data = watched()
        shell.gameConfigs[4] = "0.6" to "60"

        val record = SessionEnd.finish(ctx, EndReason.EXITED).record!!

        assertEquals(Contamination.CONTAMINATED.name, record.contamination)
        assertEquals("standard;4=0.6/60", record.gameStateEnd)
        assertTrue(record.contaminated)
        assertEquals("standard;4=0.6/60", GameStateRef.reference(ctx.gameResolution, FAKE_GAME)!!.second)
        // Unchanged since, the next session starts clean against it.
        assertEquals(Contamination.CLEAN, GameStateRef.check(shell, ctx.gameResolution, FAKE_GAME).second)
    }

    @Test
    fun `a session with nothing to compare its start with is unknown, not clean`() {
        baseline(startCheck = null)
        shell.data = watched()
        assertEquals(Contamination.UNKNOWN.name, SessionEnd.finish(ctx, EndReason.EXITED).record!!.contamination)
    }

    @Test
    fun `continuity is reads taken over reads due, and the watcher's file gives it`() {
        assertEquals(1f, SessionEnd.continuity(25, 50_000L, 2_000L)!!, 0f)
        assertEquals(0.4f, SessionEnd.continuity(10, 50_000L, 2_000L)!!, 0.0001f)
        assertEquals(1f, SessionEnd.continuity(30, 50_000L, 2_000L)!!, 0f)
        assertNull(SessionEnd.continuity(0, 1_000L, 2_000L))
        assertNull(SessionEnd.continuity(5, 50_000L, 0L))

        boost()
        shell.data = watched()
        // One frame read over 50 s at a 2 s cadence: 1 of 25.
        assertEquals(0.04f, SessionEnd.finish(ctx, EndReason.EXITED).record!!.continuity!!, 0.0001f)
    }

    @Test
    fun `a session that restarted is left out of the comparison`() {
        fun run(at: Long, end: String?) = SessionRecord(
            FAKE_GAME, 60f, at, 300_000L, 58f, 40f, null, 0, 10, 10, 10, renderResolution = "default", endReason = end
        )
        val groups = SessionComparison.groups(listOf(run(1, "exited"), run(2, "restarted"), run(3, null)), FAKE_GAME)
        assertEquals(2, groups.single().runs)
    }
}
