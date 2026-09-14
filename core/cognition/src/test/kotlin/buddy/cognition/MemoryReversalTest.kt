package buddy.cognition

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reversal corpus (docs/07, build order step 5).
 *
 * A memory that cannot be contradicted is not memory; it is folklore. Each case is a
 * statement that retires an earlier one, and the thing under test is that the retired
 * note stops being retrieved while staying in the record.
 */
class MemoryReversalTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = 1_700_000_000_000L

    /** A statement that should retire an earlier belief, and the belief it retires. */
    private val corpus = listOf(
        Triple("diet", "The user orders the steak whenever it is on the menu.", "I'm vegetarian now."),
        Triple("contact", "Reach the landlord on the old number, 07700 900111.", "The landlord changed numbers."),
        Triple("schedule", "The user runs on Tuesday and Thursday mornings.", "I've moved my runs to the weekend."),
        Triple("standing", "Reply to invitations from Alex automatically.", "Stop replying to Alex for me."),
    )

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
    }

    private fun note(text: String, n: Int): Event {
        val e = Event(
            id = EventId.of(now + n, "buddy", "memory", n.toString()),
            ts = now + n,
            sourceApp = "buddy",
            channel = "memory",
            kind = EventKind.MEMORY_NOTE,
            text = text,
            structured = mapOf("kind" to "pattern", "confidence" to "0.80"),
            trust = Trust.SYSTEM,
        )
        ledger.append(e)
        return e
    }

    private fun correction(text: String, n: Int): Event {
        val e = Event(
            id = EventId.of(now + 1000 + n, "android.sms", "sms", n.toString()),
            ts = now + 1000 + n,
            sourceApp = "android.sms",
            channel = "sms",
            kind = EventKind.CORRECTION,
            actor = "me",
            text = text,
            trust = Trust.USER,
        )
        ledger.append(e)
        return e
    }

    /** A model that retires the note it was shown and writes the replacement. */
    private fun modelThatReverses(replacement: String, retires: () -> String, evidence: () -> List<String>) = object : CloudModel {
        var sawNotes = ""
        override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
            sawNotes = user
            val n = Notes().apply {
                notes = listOf(
                    Note().apply {
                        text = replacement
                        kind = "preference"
                        confidence = 0.9
                        derivedFrom = evidence()
                        this.retires = retires()
                    },
                )
            }
            @Suppress("UNCHECKED_CAST")
            return CloudResult(n as T, "ok")
        }
    }

    @Test
    fun `every reversal in the corpus retires the belief it contradicts`() {
        for ((i, c) in corpus.withIndex()) {
            val (name, old, said) = c
            setUp()
            val stale = note(old, i)
            val correction = correction(said, i)
            val model = modelThatReverses("Standing instruction from the user: $said", { stale.id }, { listOf(correction.id) })
            val consolidator = MemoryConsolidator(ledger, model, zone)

            val result = consolidator.consolidate(listOf(correction), now + 2000)
            assertEquals(1, result.written.size, name)

            val active = consolidator.activeNotes()
            assertTrue(active.none { it.id == stale.id }, "$name: the retired note is still active")
            assertTrue(active.any { it.text?.contains(said) == true }, "$name: the replacement is missing")

            // Retired, not erased: the old note is still in the record, so the timeline
            // can show what buddy used to believe and when it stopped.
            assertTrue(ledger.get(stale.id) != null, "$name: a reversal must not delete history")
            assertEquals(stale.id, result.written.single().supersedes, name)
            assertEquals(stale.id, result.written.single().structured["retires"], name)
        }
    }

    @Test
    fun `notes are written as events, blank ones are skipped, and the same note twice is one row`() {
        val correction = correction("don't reply to mum for me", 1)
        val model = object : CloudModel {
            var lastUser = ""
            override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
                lastUser = user
                val n = Notes().apply {
                    notes = listOf(
                        Note().apply {
                            text = "Never reply to mum on the user's behalf."
                            kind = "standing_instruction"
                            confidence = 0.9
                            entity = "mum"
                            derivedFrom = listOf(correction.id)
                        },
                        Note().apply { text = ""; kind = "pattern" },
                    )
                }
                @Suppress("UNCHECKED_CAST")
                return CloudResult(n as T, "ok")
            }
        }
        val consolidator = MemoryConsolidator(ledger, model, zone)

        val first = consolidator.consolidate(listOf(correction), now + 1)
        assertEquals(1, first.written.size, "the blank note should have been dropped")
        assertEquals(EventKind.MEMORY_NOTE, first.written[0].kind)
        assertEquals("standing_instruction", first.written[0].structured["kind"])
        assertEquals("mum", first.written[0].actor)
        assertEquals(correction.id, first.written[0].structured[SqliteLedger.DERIVED_FROM])
        assertTrue(model.lastUser.startsWith("## Corrections and vetoes"))
        assertTrue(model.lastUser.contains("don't reply to mum for me"))

        // The same observation again is the same row: note ids are content-addressed.
        assertEquals(0, consolidator.consolidate(listOf(correction), now + 1).written.size)
        assertTrue(model.lastUser.contains("[standing_instruction] Never reply to mum"))
    }

    @Test
    fun `a note can only retire one it was actually shown`() {
        val stale = note("The user orders the steak.", 1)
        val correction = correction("I'm vegetarian now.", 1)
        // The model names an id that does not exist: a hallucination, not a reversal.
        val model = modelThatReverses("The user is vegetarian.", { "0000000000000-deadbeef" }, { listOf(correction.id) })

        val result = MemoryConsolidator(ledger, model, zone).consolidate(listOf(correction), now + 2000)
        assertNull(result.written.single().supersedes, "an invented id must not retire anything")
        assertTrue(MemoryConsolidator(ledger, model, zone).activeNotes().any { it.id == stale.id })
    }

    @Test
    fun `provenance only ever names events from the window`() {
        val correction = correction("I'm vegetarian now.", 1)
        val model = modelThatReverses("The user is vegetarian.", { "" }, { listOf(correction.id, "an-id-from-nowhere") })

        val written = MemoryConsolidator(ledger, model, zone).consolidate(listOf(correction), now + 2000).written.single()
        assertEquals(correction.id, written.structured[SqliteLedger.DERIVED_FROM])
    }

    @Test
    fun `the next consolidation is never shown a note that has been retired`() {
        val stale = note("The user orders the steak.", 1)
        val correction = correction("I'm vegetarian now.", 1)
        val first = modelThatReverses("The user is vegetarian.", { stale.id }, { listOf(correction.id) })
        MemoryConsolidator(ledger, first, zone).consolidate(listOf(correction), now + 2000)

        val second = modelThatReverses("Nothing new.", { "" }, { emptyList() })
        MemoryConsolidator(ledger, second, zone).consolidate(listOf(correction), now + 3000)

        assertTrue(second.sawNotes.contains("The user is vegetarian"), "the standing note should be in the prompt")
        assertTrue(
            !second.sawNotes.contains("orders the steak"),
            "the retired note was shown to the next consolidation:\n${second.sawNotes}",
        )
    }
}
