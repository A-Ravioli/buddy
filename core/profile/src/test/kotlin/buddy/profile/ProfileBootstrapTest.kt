package buddy.profile

import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.policy.Domain
import buddy.policy.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileBootstrapTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var entities: EntityStore
    private val zone = ZoneId.of("Europe/London")
    private var seq = 0L

    @BeforeEach
    fun setUp() { db = JdbcSqlDriver.inMemory(); SqliteLedger(db); entities = EntityStore(db) }

    @AfterEach
    fun tearDown() = db.close()

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 9, 1 + day, hour, 15, 0, 0, zone).toInstant().toEpochMilli()

    private fun msg(cp: String, text: String, ts: Long, fromUser: Boolean): Event = Event(
        EventId.of(ts, "android.sms", "sms", (seq++).toString()), ts, "android.sms", "sms", EventKind.MESSAGE,
        if (fromUser) "me" else cp, "sms:$cp", text, mapOf("counterparty" to cp), if (fromUser) Trust.USER else Trust.UNTRUSTED,
    ).also { entities.apply(it) }

    @Test
    fun `relationships, quiet hours, defaults and style come from history`() {
        val events = ArrayList<Event>()
        // Partner: many messages, reciprocal, late at night.
        for (d in 0 until 20) {
            events += msg("+447700900001", "night x", at(d, 23), fromUser = true)
            events += msg("+447700900001", "sleep well", at(d, 23), fromUser = false)
        }
        // Friend: moderate, reciprocal, daytime.
        for (d in 0 until 10) {
            events += msg("+447700900002", "lunch?", at(d, 12), fromUser = false)
            events += msg("+447700900002", "yes", at(d, 12), fromUser = true)
        }
        // Service: only inbound, alphanumeric sender.
        for (d in 0 until 4) events += msg("DPD", "parcel today", at(d, 9), fromUser = false)
        // Colleague-ish: some replies.
        for (d in 0 until 6) {
            events += msg("+447700900003", "report?", at(d, 10), fromUser = false)
            if (d % 3 == 0) events += msg("+447700900003", "sent", at(d, 10), fromUser = true)
        }

        val b = ProfileBootstrap(entities, zone).run(events)
        fun rel(cp: String) = b.relationships[entities.personFor(buddy.entities.Identity.key(cp))!!.id]
        assertEquals("close", rel("+447700900001"))
        assertEquals("friend", rel("+447700900002"))
        assertEquals("service", rel("DPD"))
        assertEquals("colleague", rel("+447700900003"))
        assertEquals(setOf("+447700900001"), b.triage.closeActors)
        assertEquals(Level.DRAFT, b.policy.levels[Domain.MESSAGING])
        assertEquals(Level.HOLD, b.policy.levels[Domain.EMAIL])
        // The user sends at 23:00, 12:00 and 10:00 only: the longest silent run is 0 to 10.
        assertEquals(0, b.quietStartHour)
        assertEquals(10, b.quietEndHour)
        assertEquals(b.quietStartHour, b.policy.limits.quietStartHour)
        assertTrue(b.style.features("close").samples >= 20)
        assertEquals("x", b.style.features("close").commonSignOff)
        assertEquals(3, b.summary.size)
    }

    @Test
    fun `too little history gives safe defaults`() {
        val b = ProfileBootstrap(entities, zone).run(emptyList())
        assertEquals(22 to 8, b.quietStartHour to b.quietEndHour)
        assertTrue(b.relationships.isEmpty())
        assertEquals(0.5, b.style.score("anything", "close"))
    }
}
