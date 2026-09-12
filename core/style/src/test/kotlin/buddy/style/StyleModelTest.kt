package buddy.style

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StyleModelTest {
    private val toPartner = listOf(
        "on my way x", "love you, back at 7 x", "can you grab milk? x", "haha yes x", "running late sorry x",
        "ok x", "dinner's in the oven x", "sleep well x",
    )
    private val toLandlord = listOf(
        "Hi Mr Patel,\n\nThe boiler is making a noise again. Could someone take a look this week?\n\nThanks,\nSam",
        "Hi Mr Patel,\n\nThank you for arranging that. Tuesday works.\n\nThanks,\nSam",
        "Hi Mr Patel,\n\nPlease find the signed form attached.\n\nThanks,\nSam",
        "Hi Mr Patel,\n\nThe rent has been transferred. Thank you.\n\nThanks,\nSam",
    )

    @Test
    fun `features capture the obvious differences`() {
        val p = StyleModel.learn(toPartner)
        val l = StyleModel.learn(toLandlord)
        assertTrue(p.medianLength < 25 && l.medianLength > 60)
        assertTrue(p.lowercaseStartRate > 0.8 && l.lowercaseStartRate == 0.0)
        assertEquals("x", p.commonSignOff)
        assertEquals("thanks", l.commonSignOff)
        assertEquals("hi", l.commonGreeting)
        assertNull(p.commonGreeting)
        assertTrue(l.politenessRate >= 0.75)
        assertEquals(0, StyleModel.learn(emptyList()).samples)
    }

    @Test
    fun `drafts in the wrong register score low and in the right one high`() {
        val book = StyleBook.learn(mapOf("close" to toPartner, "service" to toLandlord))
        val casual = "yep leaving now x"
        val formal = "Hi Mr Patel,\n\nCould we move the inspection to Thursday afternoon?\n\nThanks,\nSam"
        assertTrue(book.score(casual, "close") > book.score(formal, "close"))
        assertTrue(book.score(formal, "service") > book.score(casual, "service"))
        assertTrue(book.accepts(casual, "close"))
        assertTrue(book.accepts(formal, "service"))
        assertFalse(book.accepts(formal, "close"))
        assertFalse(book.accepts("yep x", "service"))
    }

    @Test
    fun `no evidence gives a neutral score and unknown classes fall back`() {
        val book = StyleBook.learn(emptyMap())
        assertEquals(0.5, book.score("anything", "colleague"))
        assertEquals(0.0, StyleModel.score("   ", StyleModel.learn(toPartner)))
    }
}
