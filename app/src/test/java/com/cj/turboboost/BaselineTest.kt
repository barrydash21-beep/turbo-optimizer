package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private val clean = Baseline.Held(
        tweaks = false, restriction = false, systemSnapshot = false, gamePreset = false, sessionOpen = false, stabilizer = false
    )

    @Test
    fun `a baseline starts only with nothing a boost changes still held`() {
        assertNull(Baseline.refusal(clean))
        for (held in listOf(
            clean.copy(tweaks = true),
            clean.copy(restriction = true),
            // DND, animations or sync still changed by an earlier boost.
            clean.copy(systemSnapshot = true),
            clean.copy(gamePreset = true),
            clean.copy(sessionOpen = true),
            clean.copy(stabilizer = true)
        )) {
            assertTrue("$held", Baseline.refusal(held) != null)
        }
    }

    @Test
    fun `each refusal says what to do`() {
        assertEquals("A boost's changes are still on - tap RESTORE NORMAL SETTINGS first", Baseline.refusal(clean.copy(systemSnapshot = true)))
        assertEquals("The game holds a resolution preset or FPS cap - set it to DEFAULT and OFF first", Baseline.refusal(clean.copy(gamePreset = true)))
        // A session still running comes first: RESTORE would end it.
        assertEquals("A session is still running - wait for it to end or tap RESTORE", Baseline.refusal(clean.copy(sessionOpen = true, tweaks = true)))
    }
}
