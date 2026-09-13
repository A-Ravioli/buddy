package buddy.android.surface.lockscreen

import buddy.android.surface.creature.Mood
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lock screen draws in SystemUI, a process that cannot ask buddy anything, so what it
 * shows is decided by these few lines. Worth pinning: the night wraps midnight, quiet
 * hours beat a waiting item, and a value that got mangled leaves the face resting rather
 * than throwing inside the keyguard.
 */
class LockFaceTest {

    @Test
    fun `quiet hours wrap midnight`() {
        val night = LockFace.Quiet(22, 8)
        assertTrue(night.contains(23))
        assertTrue(night.contains(0))
        assertTrue(night.contains(7))
        assertFalse(night.contains(8))
        assertFalse(night.contains(21))
    }

    @Test
    fun `a window inside one day is the plain reading`() {
        val nap = LockFace.Quiet(13, 15)
        assertTrue(nap.contains(13))
        assertTrue(nap.contains(14))
        assertFalse(nap.contains(15))
        assertFalse(nap.contains(2))
    }

    /** Start equal to end is no quiet hours, not a phone asleep for 24 hours. */
    @Test
    fun `an empty window is never quiet`() {
        val none = LockFace.Quiet(3, 3)
        for (hour in 0..23) assertFalse(none.contains(hour))
    }

    @Test
    fun `quiet hours beat whatever is waiting`() {
        val night = LockFace.Quiet(22, 8)
        assertEquals(LockFace.State.ASLEEP, LockFace.state(waiting = true, quiet = night, hour = 3))
        assertEquals(LockFace.State.NEEDS_YOU, LockFace.state(waiting = true, quiet = night, hour = 9))
        assertEquals(LockFace.State.RESTING, LockFace.state(waiting = false, quiet = night, hour = 9))
    }

    @Test
    fun `no window published means the face just answers`() {
        assertEquals(LockFace.State.NEEDS_YOU, LockFace.state(waiting = true, quiet = null, hour = 3))
        assertEquals(LockFace.State.RESTING, LockFace.state(waiting = false, quiet = null, hour = 3))
    }

    @Test
    fun `the window survives the trip through settings`() {
        assertEquals(LockFace.Quiet(22, 8), LockFace.parseQuiet("22-8"))
        assertEquals(LockFace.Quiet(0, 0), LockFace.parseQuiet("0-0"))
    }

    @Test
    fun `anything else is no window at all`() {
        for (raw in listOf(null, "", "22", "22-", "-8", "late-early", "22-8-9", "24-8", "22-99")) {
            assertNull(LockFace.parseQuiet(raw), "expected no window from ${raw ?: "null"}")
        }
    }

    @Test
    fun `each state is one of the creature's own moods`() {
        assertEquals(Mood.ASLEEP, LockFace.moodOf(LockFace.State.ASLEEP))
        assertEquals(Mood.NEEDS_YOU, LockFace.moodOf(LockFace.State.NEEDS_YOU))
        assertEquals(Mood.RESTING, LockFace.moodOf(LockFace.State.RESTING))
    }
}
