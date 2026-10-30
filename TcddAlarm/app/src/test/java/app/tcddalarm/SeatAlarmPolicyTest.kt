package app.tcddalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatAlarmPolicyTest {

    @Test
    fun explicitStartRunsEvenWhenTheSavedFlagIsOff() {
        assertTrue(SeatAlarmPolicy.shouldRun(SeatAlarmPolicy.ACTION_START, false))
    }

    @Test
    fun explicitStopDoesNotRun() {
        assertFalse(SeatAlarmPolicy.shouldRun(SeatAlarmPolicy.ACTION_STOP, true))
    }

    @Test
    fun restartWithoutAnActionFollowsTheSavedFlag() {
        assertTrue(SeatAlarmPolicy.shouldRun(null, true))
        assertFalse(SeatAlarmPolicy.shouldRun(null, false))
        assertFalse(SeatAlarmPolicy.shouldRun("app.tcddalarm.START", false))
    }

    @Test
    fun intervalStaysInsideTheAllowedRange() {
        assertEquals(SeatAlarmPolicy.MINIMUM_INTERVAL_SECONDS, SeatAlarmPolicy.intervalSeconds(1))
        assertEquals(180, SeatAlarmPolicy.intervalSeconds(180))
        assertEquals(
            SeatAlarmPolicy.MAXIMUM_INTERVAL_SECONDS,
            SeatAlarmPolicy.intervalSeconds(999_999),
        )
    }
}
