package buddy.audio

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BystandersTest {
    private class Cues : CueEmitter {
        val log = ArrayList<String>()
        override fun transcriptionStarted(reason: String) { log.add("start:$reason") }
        override fun transcriptionStopped(reason: String) { log.add("stop:$reason") }
    }

    private val conversation = Situation(ts = 1_000, speechPresent = true, userSpeaking = true, scene = "conversation")

    @Test
    fun `stop phrase from anyone`() {
        val d = StopPhraseDetector()
        assertTrue(d.isStop("Buddy, stop listening."))
        assertTrue(d.isStop("hey can you stop listening please"))
        assertTrue(d.isStop("not now buddy"))
        assertFalse(d.isStop("I'll stop by later"))
        assertFalse(d.isStop("listening to music"))
    }

    @Test
    fun `cue fires on transitions and the pause holds for an hour`() {
        val cues = Cues()
        val c = BystanderControls(AudioGate(), cues, pauseMillis = 3_600_000)
        assertTrue(c.decide(conversation, "d").transcribe)
        assertTrue(c.decide(conversation.copy(ts = 2_000), "d").transcribe)
        assertEquals(listOf("start:conversation"), cues.log) // once, not per tick

        assertTrue(c.onUtterance("buddy stop listening", 5_000))
        assertEquals(listOf("start:conversation", "stop:stop_phrase"), cues.log)
        assertFalse(c.decide(conversation.copy(ts = 6_000), "d").transcribe)
        assertEquals("off_limits:manual_pause", c.decide(conversation.copy(ts = 6_000), "d").reason)
        assertFalse(c.decide(conversation.copy(ts = 5_000 + 3_599_000), "d").transcribe)
        assertTrue(c.decide(conversation.copy(ts = 5_000 + 3_600_001), "d").transcribe)
        assertEquals("start:conversation", cues.log.last())

        c.pause(10_000_000, "earbud_gesture")
        assertEquals("stop:earbud_gesture", cues.log.last())
        c.resume()
        assertTrue(c.decide(conversation.copy(ts = 10_000_001), "d").transcribe)
    }

    @Test
    fun `ordinary utterances do not pause`() {
        val c = BystanderControls(AudioGate(), Cues())
        assertFalse(c.onUtterance("let's stop for lunch", 1))
        assertEquals(0, c.pausedUntil)
    }
}
