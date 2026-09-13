package buddy.android.surface.call

import android.telecom.Call
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * buddy is the only in-call UI on this build, so the call screen drawing the wrong thing
 * means a phone that cannot be answered. These pin the two readings it depends on.
 */
class CallStoreTest {

    @Test
    fun `telecom's states reduce to the four a person can act on`() {
        assertEquals(Phase.RINGING, CallStore.phaseOf(Call.STATE_RINGING))
        assertEquals(Phase.DIALING, CallStore.phaseOf(Call.STATE_DIALING))
        assertEquals(Phase.DIALING, CallStore.phaseOf(Call.STATE_CONNECTING))
        assertEquals(Phase.ACTIVE, CallStore.phaseOf(Call.STATE_ACTIVE))
        assertEquals(Phase.HOLDING, CallStore.phaseOf(Call.STATE_HOLDING))
    }

    /** Anything else is a call that is over, and the screen closes rather than lingering. */
    @Test
    fun `a finished call has no phase`() {
        assertNull(CallStore.phaseOf(Call.STATE_DISCONNECTED))
        assertNull(CallStore.phaseOf(Call.STATE_DISCONNECTING))
        assertNull(CallStore.phaseOf(Call.STATE_NEW))
    }

    @Test
    fun `the timer reads as a call timer`() {
        val start = 1_000_000L
        assertEquals("0:00", CallStore.elapsed(start, start))
        assertEquals("0:09", CallStore.elapsed(start, start + 9_000))
        assertEquals("1:05", CallStore.elapsed(start, start + 65_000))
        assertEquals("60:00", CallStore.elapsed(start, start + 3_600_000))
    }

    /** Nothing to count from, and nothing counted: no "-1:59" on a call being placed. */
    @Test
    fun `a call that has not connected shows no time`() {
        assertEquals("", CallStore.elapsed(0L, 1_000_000L))
        assertEquals("", CallStore.elapsed(1_000_000L, 999_000L))
    }

    /** With no entity graph open, a caller is a number rather than a guess. */
    @Test
    fun `an unknown caller is named honestly`() {
        assertEquals("Unknown number", CallStore.nameFor(null))
        assertEquals("Unknown number", CallStore.nameFor("  "))
        assertEquals("+447700900123", CallStore.nameFor("+447700900123"))
    }
}
