package buddy.ledger

import buddy.ledger.jdbc.JdbcSqlDriver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revocation that leaves copies is not revocation (docs/07, decision 14). These are
 * the cases that decide whether "disconnect Gmail" means anything.
 */
class ForgetTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private val now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
    }

    private fun event(source: String, text: String, n: Int, kind: EventKind = EventKind.MESSAGE) = Event(
        id = EventId.of(now + n, source, "inbox", n.toString()),
        ts = now + n,
        sourceApp = source,
        channel = "inbox",
        kind = kind,
        actor = "sam@example.com",
        text = text,
        trust = Trust.UNTRUSTED,
    )

    private fun note(text: String, derivedFrom: List<String>, n: Int) = Event(
        id = EventId.of(now + n, "buddy", "memory", n.toString()),
        ts = now + n,
        sourceApp = "buddy",
        channel = "memory",
        kind = EventKind.MEMORY_NOTE,
        text = text,
        structured = mapOf(SqliteLedger.DERIVED_FROM to derivedFrom.joinToString("|")),
        trust = Trust.SYSTEM,
    )

    @Test
    fun `forget removes the events, the search index, and the notes that rested on them`() {
        val gmail = listOf(event("com.google.gmail", "the lease renewal is attached", 1), event("com.google.gmail", "invoice attached", 2))
        val sms = event("android.sms", "lease sorted?", 3)
        ledger.appendAll(gmail + sms)
        val doomedNote = note("The landlord answers within a day.", gmail.map { it.id }, 4)
        val mixedNote = note("Sam prefers email to text.", listOf(gmail[0].id, sms.id), 5)
        val sourcelessNote = note("The user runs on Tuesdays.", emptyList(), 6)
        ledger.appendAll(listOf(doomedNote, mixedNote, sourcelessNote))

        assertEquals(2, ledger.search("lease").size, "both the mail and the text mention the lease")

        val report = ledger.forget("com.google.gmail", now + 100)

        assertEquals(2, report.events)
        assertEquals(1, report.notes)
        for (e in gmail) assertNull(ledger.get(e.id), "purged event ${e.id} is still readable")
        assertNull(ledger.get(doomedNote.id), "a note whose whole evidence was purged must go with it")
        // A note that also rests on evidence from elsewhere is still true and names no source.
        assertNotNull(ledger.get(mixedNote.id))
        assertNotNull(ledger.get(sourcelessNote.id))

        // The index is the part that bit Instinct: summaries kept coming from indexed
        // copies after the account was disconnected.
        val hits = ledger.search("lease")
        assertEquals(listOf(sms.id), hits.map { it.id }, "the search index still holds purged text")
        assertTrue(ledger.search("invoice").isEmpty())
    }

    @Test
    fun `forget leaves a tombstone saying what went`() {
        ledger.appendAll(listOf(event("com.google.gmail", "hello", 1), event("com.google.gmail", "hello again", 2)))
        val report = ledger.forget("com.google.gmail", now + 100)

        val tombstone = assertNotNull(ledger.get(report.tombstoneId))
        assertEquals(EventKind.TOMBSTONE, tombstone.kind)
        assertEquals("com.google.gmail", tombstone.structured["forgot_source"])
        assertEquals("2", tombstone.structured["events"])
        // The record says a purge happened. A silent gap would be its own dishonesty.
        assertEquals(1, ledger.count())
    }

    @Test
    fun `the ledger is append-only again the moment the purge is over`() {
        val e = event("com.google.gmail", "hello", 1)
        ledger.append(e)
        val keep = event("android.sms", "hello", 2)
        ledger.append(keep)
        ledger.forget("com.google.gmail", now + 100)

        assertFailsWith<Exception> { db.exec("DELETE FROM events WHERE id = ?", listOf(keep.id)) }
        assertNotNull(ledger.get(keep.id))
    }

    @Test
    fun `forgetting a source that was never there is a no-op with a tombstone`() {
        ledger.append(event("android.sms", "hello", 1))
        val report = ledger.forget("com.never.installed", now + 100)
        assertEquals(0, report.events)
        assertEquals(0, report.notes)
        assertEquals(2, ledger.count())
    }
}
