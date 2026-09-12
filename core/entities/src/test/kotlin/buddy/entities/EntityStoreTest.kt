package buddy.entities

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityStoreTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var store: EntityStore

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        SqliteLedger(db) // same database as the ledger, as on the phone
        store = EntityStore(db)
    }

    @AfterEach
    fun tearDown() = db.close()

    private var seq = 0L
    private fun msg(actor: String?, text: String, thread: String, ts: Long, trust: Trust = Trust.UNTRUSTED, pkg: String = "com.chat", structured: Map<String, String> = emptyMap()) = Event(
        id = EventId.of(ts, pkg, "c", (seq++).toString()), ts = ts, sourceApp = pkg, channel = "c", kind = EventKind.MESSAGE,
        actor = actor, threadId = thread, text = text, trust = trust, structured = structured,
    )

    @Test
    fun `identity keys normalise phones emails and names`() {
        assertEquals("tel:+447700900123", Identity.key("07700 900123"))
        assertEquals("tel:+447700900123", Identity.key("+44 (0)7700-900123".replace("(0)", "")))
        assertEquals("tel:+15551234567", Identity.key("0015551234567"))
        assertEquals("mailto:sam@example.com", Identity.key("Sam@Example.com"))
        assertEquals("name:sam o brien", Identity.key("Sam O'Brien"))
        assertEquals("me", Identity.key("me"))
        assertNull(Identity.normalizePhone("AMAZON"))
    }

    @Test
    fun `same person across apps by phone and email, name merges only when unambiguous`() {
        val a = store.observe("07700 900123", "Sam Smith", 1_000)
        val b = store.observe("+447700900123", null, 2_000)
        assertEquals(a.id, b.id)
        val c = store.observe("sam@example.com", "Sam Smith", 3_000)
        assertEquals(a.id, c.id) // merged through the unambiguous name
        assertEquals(setOf("tel:+447700900123", "name:sam smith", "mailto:sam@example.com"), c.identities)
        assertEquals(3, c.eventCount)
        assertEquals(3_000, c.lastSeenTs)

        // A second, different "Sam Smith" phone makes the name ambiguous: no merge.
        val other = store.observe("+15550000000", null, 4_000)
        assertNotEquals(a.id, other.id)
        store.observe("+15550000000", "Sam Smith", 4_500)
        val d = store.observe("sam.other@example.com", "Sam Smith", 5_000)
        assertNotEquals(a.id, d.id)
        assertNotEquals(other.id, d.id)
    }

    @Test
    fun `merge moves identities and counts`() {
        val a = store.observe("+447700900123", "Sam", 1_000)
        val b = store.observe("sam@example.com", null, 2_000)
        assertNotEquals(a.id, b.id)
        store.merge(a.id, b.id)
        assertNull(store.person(b.id))
        val merged = store.person(a.id)!!
        assertEquals(setOf("tel:+447700900123", "name:sam", "mailto:sam@example.com"), merged.identities)
        assertEquals(2, merged.eventCount)
        assertEquals(a.id, store.personFor("mailto:sam@example.com")!!.id)
    }

    @Test
    fun `threads track who spoke last and who awaits a reply`() {
        store.apply(msg("+447700900123", "dinner?", "sms:1", 1_000))
        var t = store.thread("sms:1")!!
        assertEquals("them", t.lastActor)
        assertEquals(store.personFor("tel:+447700900123")!!.id, t.personId)
        store.apply(msg("me", "yes", "sms:1", 2_000, trust = Trust.USER, structured = mapOf("counterparty" to "+447700900123")))
        t = store.thread("sms:1")!!
        assertEquals("me", t.lastActor)
        assertEquals(1, store.personFor("tel:+447700900123")!!.userMessages)
        assertEquals(2, t.eventCount)
        // An older event arriving late must not flip last_actor.
        store.apply(msg("+447700900123", "old", "sms:1", 1_500))
        assertEquals("me", store.thread("sms:1")!!.lastActor)

        store.apply(msg("+15550000000", "ping", "sms:2", 3_000))
        assertEquals(listOf("sms:2"), store.awaitingReply(olderThanTs = 4_000).map { it.threadId })
        assertEquals(emptyList(), store.awaitingReply(olderThanTs = 2_500).map { it.threadId })
    }

    @Test
    fun `rebuild is a pure function of the events`() {
        val events = listOf(
            msg("+447700900123", "a", "sms:1", 1_000),
            msg("+15550000000", "b", "sms:2", 2_000),
        )
        events.forEach(store::apply)
        store.apply(events[0]) // double apply corrupts counts; rebuild fixes it
        assertEquals(2, store.thread("sms:1")!!.eventCount)
        store.rebuild(events.asSequence())
        assertEquals(1, store.thread("sms:1")!!.eventCount)
        assertEquals(2, store.people().size)
    }

    @Test
    fun `recurring charges are found with regular intervals only`() {
        val day = 86_400_000L
        fun tx(ts: Long, amount: String, who: String = "GYM") =
            msg(who, "payment", "bank:$who", ts, pkg = "com.bank", structured = mapOf("amount" to amount, "currency" to "GBP", "counterparty" to who))
        val monthly = (0 until 4).map { tx(1_000_000_000_000 + it * 30 * day, "29.99") }
        val irregular = listOf(tx(1_000_000_000_000, "5.00", "CAFE"), tx(1_000_000_000_000 + 2 * day, "5.00", "CAFE"), tx(1_000_000_000_000 + 40 * day, "5.00", "CAFE"))
        val twice = listOf(tx(1_000_000_000_000, "9.99", "APP"), tx(1_000_000_000_000 + 30 * day, "9.99", "APP"))
        val r = store.recurring(monthly + irregular + twice)
        assertEquals(1, r.size)
        assertEquals("GYM", r[0].counterparty)
        assertEquals(4, r[0].occurrences)
        assertEquals(30.0, r[0].medianIntervalDays)
        assertTrue(r[0].nextExpectedTs > monthly.last().ts)
    }
}
