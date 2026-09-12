package buddy.audio

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioGateTest {
    private val base = Situation(ts = 1, speechPresent = true, userSpeaking = true, scene = "conversation")

    @Test
    fun `off limits rules are deterministic and named`() {
        val r = OffLimitsRules()
        assertNull(r.reason(base))
        assertEquals("manual_pause", r.reason(base.copy(manualPause = true)))
        assertEquals("marked_place", r.reason(base.copy(atMarkedPlace = true)))
        assertEquals("place:medical", r.reason(base.copy(placeCategory = "Medical")))
        assertEquals("calendar:therapy", r.reason(base.copy(meetingTitle = "Therapy session")))
        assertNull(r.reason(base.copy(meetingTitle = "Weekly planning", placeCategory = "cafe")))
    }

    @Test
    fun `budget rolls per day and keeps a reserve for priority`() {
        val b = TranscriptionBudget(dailyMinutes = 60, reserveMinutes = 10)
        assertTrue(b.allows("d1", priority = false))
        b.record("d1", 55 * 60)
        assertEquals(5, b.remainingMinutes("d1"))
        assertFalse(b.allows("d1", priority = false))
        assertTrue(b.allows("d1", priority = true))
        b.record("d1", 5 * 60)
        assertFalse(b.allows("d1", priority = true))
        assertTrue(b.allows("d2", priority = false))
        assertEquals(0, b.usedMinutes("d2"))
    }

    @Test
    fun `gate order`() {
        val g = AudioGate(budget = TranscriptionBudget(dailyMinutes = 60, reserveMinutes = 10))
        assertEquals("off_limits:place:legal", g.decide(base.copy(placeCategory = "legal", hotword = true), "d").reason)
        assertEquals("in_call_dialer_path", g.decide(base.copy(inCall = true), "d").reason)
        assertTrue(g.decide(base.copy(speechPresent = false, hotword = true), "d").transcribe)
        assertEquals("no_speech", g.decide(base.copy(speechPresent = false), "d").reason)
        assertEquals("not_participant", g.decide(base.copy(userSpeaking = false), "d").reason)
        // The user talking over the TV is still a conversation the user is part of.
        assertEquals("conversation", g.decide(base.copy(scene = "tv"), "d").reason)
        // With bystander transcription on, TV alone is still not worth transcribing.
        assertEquals("scene_tv", AudioGate(transcribeBystanders = true).decide(base.copy(scene = "tv", userSpeaking = false), "d").reason)
        val conv = g.decide(base, "d")
        assertTrue(conv.transcribe); assertEquals("conversation", conv.reason); assertFalse(conv.priority)
        val meeting = g.decide(base.copy(userSpeaking = false, inMeeting = true), "d")
        assertTrue(meeting.transcribe); assertTrue(meeting.priority)
    }

    @Test
    fun `budget reserve protects meetings`() {
        val b = TranscriptionBudget(dailyMinutes = 60, reserveMinutes = 10)
        val g = AudioGate(budget = b)
        b.record("d", 52 * 60)
        assertEquals("budget_reserve", g.decide(base, "d").reason)
        assertTrue(g.decide(base.copy(inMeeting = true), "d").transcribe)
        b.record("d", 8 * 60)
        assertEquals("budget_exhausted", g.decide(base.copy(inMeeting = true), "d").reason)
    }

    @Test
    fun `bystander transcription is opt in`() {
        val g = AudioGate(transcribeBystanders = true)
        assertTrue(g.decide(base.copy(userSpeaking = false), "d").transcribe)
    }
}
