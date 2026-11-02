package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeavyStatusTest {

    private fun status(
        stopped: Boolean = false,
        running: Boolean = false,
        listed: Boolean = false,
        notes: List<String>? = null,
        alive: Boolean = false,
        held: Boolean = true
    ) = HeavyApps.status(stopped, running, listed, notes, alive, held)

    private fun session(startedAtMs: Long, aliveEarly: List<String>?) =
        SessionRecord(FAKE_GAME, 90f, startedAtMs, 300_000L, 60f, null, null, 0, 10, 10, 10, restrictedAliveEarly = aliveEarly)

    // ---- stop ----

    @Test fun `a stopped app that is not running says Stopped`() {
        assertEquals("Stopped", status(stopped = true, running = false))
    }

    @Test fun `a stopped app found running again says it came back`() {
        assertEquals("Came back", status(stopped = true, running = true))
    }

    // ---- restriction, from the restrictor's own read-backs ----

    @Test fun `a restriction whose read-backs all held says Restricted`() {
        assertEquals("Restricted", status(notes = emptyList()))
    }

    @Test fun `a bucket the system did not keep says so`() {
        assertEquals(
            "Restricted: bucket ignored by system",
            status(notes = listOf("bucket ignored (stays 5)"))
        )
    }

    @Test fun `an ignored background setting alone is not called a bucket`() {
        assertEquals("Restricted", status(notes = listOf("background ignored (reads allow)")))
    }

    @Test fun `alive at the early check says it came back, whatever else was ignored`() {
        assertEquals("Came back", status(notes = emptyList(), alive = true))
        assertEquals("Came back", status(notes = listOf("bucket ignored (stays 5)"), alive = true))
    }

    @Test fun `once the session has put the restriction back the status says it was the last session`() {
        assertEquals("Restricted (last session)", status(notes = emptyList(), held = false))
        assertEquals("Restricted: bucket ignored by system (last session)", status(notes = listOf("bucket ignored (stays 5)"), held = false))
        assertEquals("Came back (last session)", status(notes = emptyList(), alive = true, held = false))
    }

    // ---- only on the list ----

    @Test fun `an app only put on the list is not called restricted`() {
        assertEquals("On the list: applies at the next boost", status(listed = true))
    }

    @Test fun `an app nothing happened to has no status`() {
        assertNull(status())
    }

    @Test fun `a stop from the card wins over an older restriction`() {
        assertEquals("Stopped", status(stopped = true, notes = emptyList()))
    }

    // ---- reads that already exist ----

    @Test fun `the early check counts only for the session that ran the restriction`() {
        assertEquals(listOf("com.a"), HeavyApps.aliveEarlyFor(session(2_000L, listOf("com.a")), restrictedAtMs = 1_000L))
        // An older session's alive list says nothing about a restriction that began after it.
        assertNull(HeavyApps.aliveEarlyFor(session(500L, listOf("com.a")), restrictedAtMs = 1_000L))
        assertNull(HeavyApps.aliveEarlyFor(null, restrictedAtMs = 1_000L))
        assertNull(HeavyApps.aliveEarlyFor(session(2_000L, null), restrictedAtMs = 1_000L))
    }

    @Test fun `stopped marks are kept for the read after a stop, then only those running again`() {
        val stopped = setOf("com.a", "com.b")
        assertEquals(stopped, HeavyApps.stoppedAfterRefresh(stopped, running = emptySet(), keepStopped = true))
        assertEquals(setOf("com.b"), HeavyApps.stoppedAfterRefresh(stopped, running = setOf("com.b", "com.c"), keepStopped = false))
        assertEquals(emptySet<String>(), HeavyApps.stoppedAfterRefresh(stopped, running = emptySet(), keepStopped = false))
    }
}
