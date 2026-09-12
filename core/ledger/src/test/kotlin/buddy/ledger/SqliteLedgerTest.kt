package buddy.ledger

import buddy.ledger.jdbc.JdbcSqlDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteLedgerTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun event(
        ts: Long,
        text: String? = "hello",
        actor: String? = "+15551234567",
        thread: String? = "t1",
        structured: Map<String, String> = emptyMap(),
        supersedes: String? = null,
    ) = Event(
        id = EventId.of(ts, "com.example.sms", "sms", actor, text),
        ts = ts,
        sourceApp = "com.example.sms",
        channel = "sms",
        kind = EventKind.MESSAGE,
        actor = actor,
        threadId = thread,
        text = text,
        structured = structured,
        trust = Trust.UNTRUSTED,
        rawRef = "content://sms/$ts",
        supersedes = supersedes,
    )

    @Test
    fun `append then get round-trips every field`() {
        val e = event(1_700_000_000_000, structured = mapOf("amount" to "12.50", "note" to "a \"quoted\" value\nline2"))
        assertTrue(ledger.append(e))
        assertEquals(e, ledger.get(e.id))
        assertEquals(1, ledger.count())
    }

    @Test
    fun `append is idempotent on id`() {
        val e = event(1_700_000_000_000)
        assertTrue(ledger.append(e))
        assertFalse(ledger.append(e))
        assertFalse(ledger.append(e.copy(text = "different body, same identity fields would give a new id, so keep id")))
        assertEquals(1, ledger.count())
    }

    @Test
    fun `appendAll counts only new events`() {
        val a = event(1_700_000_000_000, text = "a")
        val b = event(1_700_000_001_000, text = "b")
        assertEquals(2, ledger.appendAll(listOf(a, b)))
        assertEquals(1, ledger.appendAll(listOf(b, event(1_700_000_002_000, text = "c"))))
        assertEquals(3, ledger.count())
    }

    @Test
    fun `recent is newest first and pages by ts`() {
        val ts = listOf(3L, 1L, 2L).map { 1_700_000_000_000 + it * 1000 }
        ts.forEach { ledger.append(event(it, text = "m$it")) }
        assertEquals(ts.sortedDescending(), ledger.recent(10).map { it.ts })
        assertEquals(listOf(ts.min()), ledger.recent(10, beforeTs = ts.sorted()[1]).map { it.ts })
        assertEquals(2, ledger.recent(2).size)
    }

    @Test
    fun `thread is oldest first and scoped`() {
        ledger.append(event(1_700_000_002_000, text = "second", thread = "t1"))
        ledger.append(event(1_700_000_001_000, text = "first", thread = "t1"))
        ledger.append(event(1_700_000_003_000, text = "other", thread = "t2"))
        assertEquals(listOf("first", "second"), ledger.thread("t1").map { it.text })
    }

    @Test
    fun `search finds text and actor and ignores fts operators in input`() {
        ledger.append(event(1_700_000_001_000, text = "the boiler needs a new pump", actor = "Plumber Pete"))
        ledger.append(event(1_700_000_002_000, text = "dinner thursday?", actor = "Sam"))
        assertEquals(listOf("the boiler needs a new pump"), ledger.search("boiler pump").map { it.text })
        assertEquals(listOf("the boiler needs a new pump"), ledger.search("pete").map { it.text })
        // Operators and quotes must not be interpreted or break the query.
        assertEquals(emptyList(), ledger.search("boiler OR \"dinner").map { it.text }.filter { it == "dinner thursday?" })
        assertEquals(emptyList<Event>(), ledger.search("   "))
    }

    @Test
    fun `update and delete are rejected by the database itself`() {
        val e = event(1_700_000_000_000)
        ledger.append(e)
        val update = assertThrows<SQLException> { db.exec("UPDATE events SET text = 'x' WHERE id = ?", listOf(e.id)) }
        assertTrue(update.message!!.contains("append-only"))
        val delete = assertThrows<SQLException> { db.exec("DELETE FROM events WHERE id = ?", listOf(e.id)) }
        assertTrue(delete.message!!.contains("append-only"))
        assertEquals(e, ledger.get(e.id))
    }

    @Test
    fun `corrections are new events that point at the old one`() {
        val original = event(1_700_000_000_000, text = "dinner at 7")
        ledger.append(original)
        val fix = Event(
            id = EventId.of(1_700_000_005_000, "buddy", "correction", original.id),
            ts = 1_700_000_005_000,
            sourceApp = "buddy",
            channel = "correction",
            kind = EventKind.CORRECTION,
            text = "dinner at 8",
            trust = Trust.USER,
            supersedes = original.id,
        )
        assertTrue(ledger.append(fix))
        assertEquals(listOf(fix), ledger.corrections(original.id))
        assertEquals(emptyList<Event>(), ledger.corrections(fix.id))
        assertEquals(original, ledger.get(original.id))
    }

    @Test
    fun `a failed batch leaves nothing behind`() {
        val good = event(1_700_000_000_000)
        val bad = good.copy(id = "x".repeat(10), kind = EventKind.MESSAGE)
        // Force a failure mid-batch with a duplicate primary key on a raw insert.
        assertThrows<SQLException> {
            db.transaction {
                ledger.append(good)
                db.exec("INSERT INTO events(id, ts, source_app, channel, kind, structured, trust, inserted_at) VALUES (?, 1, 'a', 'b', 'MESSAGE', '{}', 'USER', 1)", listOf(good.id))
            }
        }
        assertNull(ledger.get(good.id))
        assertNull(ledger.get(bad.id))
        assertEquals(0, ledger.count())
    }

    @Test
    fun `schema migration is idempotent across reopen`() {
        ledger.append(event(1_700_000_000_000))
        SqliteLedger(db) // second construction on the same connection must not fail or wipe
        assertEquals(1, ledger.count())
    }
}
