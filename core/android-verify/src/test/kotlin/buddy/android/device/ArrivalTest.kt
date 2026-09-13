package buddy.android.device

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * buddy's only way of making someone look at the phone, so the rule it follows is worth
 * pinning: quiet hours are quiet whatever is waiting, and a user already looking at the
 * screen is not interrupted by a phone they are holding.
 */
class ArrivalTest {

    @Test
    fun `quiet hours stay quiet, urgent or not`() {
        assertEquals(Arrival.Announcement.NOTHING, Arrival.decide(urgent = true, quiet = true, onScreen = false))
        assertEquals(Arrival.Announcement.NOTHING, Arrival.decide(urgent = false, quiet = true, onScreen = false))
    }

    @Test
    fun `nothing sounds at someone already looking`() {
        assertEquals(Arrival.Announcement.NOTHING, Arrival.decide(urgent = true, quiet = false, onScreen = true))
    }

    @Test
    fun `urgent sounds, ordinary only taps`() {
        assertEquals(Arrival.Announcement.SOUND_AND_HAPTIC, Arrival.decide(urgent = true, quiet = false, onScreen = false))
        assertEquals(Arrival.Announcement.HAPTIC, Arrival.decide(urgent = false, quiet = false, onScreen = false))
    }

    /** Short enough to be an arrival and not a ringtone. */
    @Test
    fun `the chime is under half a second`() {
        val samples = Arrival.waveform()
        val seconds = samples.size / 44_100.0
        assertTrue(seconds > 0.3, "the chime is $seconds seconds, too short to hear")
        assertTrue(seconds < 0.5, "the chime is $seconds seconds, that is a ringtone")
    }

    /** A clipped note is a click. The envelope has to keep every sample inside the rail. */
    @Test
    fun `the chime never clips`() {
        var peak = 0
        for (s in Arrival.waveform()) peak = maxOf(peak, abs(s.toInt()))
        assertTrue(peak < Short.MAX_VALUE.toInt(), "peaked at $peak")
        assertTrue(peak > Short.MAX_VALUE / 4, "peaked at $peak, nobody would hear it")
    }

    /** It has to start and end at silence, or the speaker pops at both ends. */
    @Test
    fun `the chime starts and ends at rest`() {
        val samples = Arrival.waveform()
        assertEquals(0, samples.first().toInt())
        assertTrue(abs(samples.last().toInt()) < Short.MAX_VALUE / 100, "ends at ${samples.last()}")
    }
}
