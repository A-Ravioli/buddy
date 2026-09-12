package buddy.ledger

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventIdAndJsonTest {
    @Test
    fun `same facts give the same id and different facts do not`() {
        val a = EventId.of(1_700_000_000_000, "com.app", "sms", "+1555", "hi")
        val b = EventId.of(1_700_000_000_000, "com.app", "sms", "+1555", "hi")
        val c = EventId.of(1_700_000_000_000, "com.app", "sms", "+1555", "hi!")
        val d = EventId.of(1_700_000_000_001, "com.app", "sms", "+1555", "hi")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertNotEquals(EventId.of(1, "a", "b", null), EventId.of(1, "a", "b", " null"))
    }

    @Test
    fun `ids sort by time`() {
        val early = EventId.of(1_600_000_000_000, "a", "b", "x")
        val late = EventId.of(1_700_000_000_000, "a", "b", "x")
        assertTrue(early < late)
        assertTrue(early.startsWith("1600000000000-"))
    }

    @Test
    fun `flat json round-trips awkward strings`() {
        val m = mapOf(
            "plain" to "value",
            "quote" to "a \"b\" c",
            "backslash" to "c:\\path",
            "newline" to "line1\nline2\ttab",
            "unicode" to "caf\u00e9 \u2603",
            "control" to "\u0001",
            "" to "",
        )
        val json = FlatJson.encode(m)
        assertEquals(m, FlatJson.decode(json))
        assertEquals("{}", FlatJson.encode(emptyMap()))
        assertEquals(emptyMap(), FlatJson.decode("{ }"))
    }

    @Test
    fun `encoding is canonical regardless of insertion order`() {
        assertEquals(FlatJson.encode(mapOf("b" to "1", "a" to "2")), FlatJson.encode(mapOf("a" to "2", "b" to "1")))
    }

    @Test
    fun `decoder rejects nesting and garbage`() {
        assertThrows<IllegalArgumentException> { FlatJson.decode("{\"a\": {\"b\": \"c\"}}") }
        assertThrows<IllegalArgumentException> { FlatJson.decode("{\"a\": 1}") }
        assertThrows<IllegalArgumentException> { FlatJson.decode("[]") }
        assertThrows<IllegalArgumentException> { FlatJson.decode("{\"a\": \"b\"") }
    }

    @Test
    fun `event rejects blank identity fields`() {
        assertThrows<IllegalArgumentException> {
            Event(id = " ", ts = 1, sourceApp = "a", channel = "b", kind = EventKind.MESSAGE, trust = Trust.USER)
        }
        assertThrows<IllegalArgumentException> {
            Event(id = "x", ts = 0, sourceApp = "a", channel = "b", kind = EventKind.MESSAGE, trust = Trust.USER)
        }
    }
}
