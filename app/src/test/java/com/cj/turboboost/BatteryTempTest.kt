package com.cj.turboboost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTempTest {

    @Test fun `the broadcast's tenths become degrees`() {
        assertEquals(37.7f, BatteryTemp.fromTenths(377)!!, 0.001f)
        assertEquals(0f, BatteryTemp.fromTenths(0)!!, 0.001f)
    }

    @Test fun `a missing or impossible reading is no reading, not a number`() {
        assertNull(BatteryTemp.fromTenths(null))
        assertNull(BatteryTemp.fromTenths(BatteryTemp.MISSING))
        assertNull(BatteryTemp.fromTenths(5000))
        assertNull(BatteryTemp.fromTenths(-900))
    }
}
