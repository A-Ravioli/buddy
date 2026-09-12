package buddy.perception

import buddy.ledger.EventKind
import buddy.ledger.Trust
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderNormalizersTest {
    @Test
    fun `sms inbox and sent rows`() {
        val inbox = SmsNormalizer.toEvent(SmsRow(7, 3, "+44 7700 900-123", 1_700_000_000_000, " your code is 123456 ", SmsRow.TYPE_INBOX))!!
        assertEquals(EventKind.MESSAGE, inbox.kind)
        assertEquals("+447700900123", inbox.actor)
        assertEquals("sms:3", inbox.threadId)
        assertEquals("your code is 123456", inbox.text)
        assertEquals("in", inbox.structured["direction"])
        assertEquals(Trust.UNTRUSTED, inbox.trust)
        assertEquals("content://sms/7", inbox.rawRef)

        val sent = SmsNormalizer.toEvent(SmsRow(8, 3, "+447700900123", 1_700_000_001_000, "on my way", SmsRow.TYPE_SENT))!!
        assertEquals("me", sent.actor)
        assertEquals("+447700900123", sent.structured["counterparty"])
        assertEquals(Trust.USER, sent.trust)
        assertNull(sent.structured["delivery"])

        val failed = SmsNormalizer.toEvent(SmsRow(9, 3, "+447700900123", 1_700_000_002_000, "x", SmsRow.TYPE_FAILED))!!
        assertEquals("failed", failed.structured["delivery"])
    }

    @Test
    fun `sms drafts and empty bodies are skipped`() {
        assertNull(SmsNormalizer.toEvent(SmsRow(1, 1, "+1", 1, "draft", SmsRow.TYPE_DRAFT)))
        assertNull(SmsNormalizer.toEvent(SmsRow(1, 1, "+1", 1, "   ", SmsRow.TYPE_INBOX)))
    }

    @Test
    fun `address normalisation keeps alphanumeric sender ids`() {
        assertEquals("+447700900123", SmsNormalizer.normalizeAddress("(+44) 7700 900.123"))
        assertEquals("AMAZON", SmsNormalizer.normalizeAddress(" AMAZON "))
        assertEquals("12", SmsNormalizer.normalizeAddress("12")) // too short to be a number, kept as text
    }

    @Test
    fun `calendar change identity follows content not observation time`() {
        val row = CalendarInstanceRow(
            eventId = 42, calendarId = 1, calendarName = "Work", title = "Standup", description = null,
            location = "Room 2", begin = 1_700_000_000_000, end = 1_700_001_800_000, allDay = false,
            organizer = "boss@example.com", status = 1, selfAttendeeStatus = 1,
        )
        val a = CalendarNormalizer.toEvent(row, observedAt = 1_699_990_000_000)
        val b = CalendarNormalizer.toEvent(row, observedAt = 1_699_995_000_000)
        assertEquals(a.id, b.id)
        assertEquals(1_699_995_000_000, b.ts)
        assertEquals(EventKind.CALENDAR_CHANGE, a.kind)
        assertEquals("calendar:42", a.threadId)
        assertEquals("Standup at Room 2", a.text)
        assertEquals("boss@example.com", a.actor)
        assertEquals("confirmed", a.structured["status"])
        assertEquals("accepted", a.structured["my_response"])

        val moved = CalendarNormalizer.toEvent(row.copy(begin = 1_700_003_600_000), observedAt = 1_699_995_000_000)
        assertNotEquals(a.id, moved.id)
        val cancelled = CalendarNormalizer.toEvent(row.copy(status = 2), observedAt = 1_699_995_000_000)
        assertNotEquals(a.id, cancelled.id)
        assertEquals("canceled", cancelled.structured["status"])
    }

    @Test
    fun `call log rows`() {
        val missed = CallLogNormalizer.toEvent(CallLogRow(5, "07700 900123", "Plumber Pete", 1_700_000_000_000, 0, CallLogRow.MISSED))
        assertEquals(EventKind.CALL, missed.kind)
        assertEquals("07700900123", missed.actor)
        assertEquals("call:07700900123", missed.threadId)
        assertEquals("Plumber Pete 07700900123", missed.text)
        assertEquals("missed", missed.structured["outcome"])
        assertEquals(Trust.UNTRUSTED, missed.trust)

        val out = CallLogNormalizer.toEvent(CallLogRow(6, "+15551234567", null, 1_700_000_001_000, 95, CallLogRow.OUTGOING))
        assertEquals("me", out.actor)
        assertEquals("connected", out.structured["outcome"])
        assertEquals("95", out.structured["duration_s"])
        assertEquals(Trust.USER, out.trust)

        val withheld = CallLogNormalizer.toEvent(CallLogRow(7, null, null, 1_700_000_002_000, 0, CallLogRow.REJECTED))
        assertNull(withheld.actor)
        assertNull(withheld.threadId)
        assertEquals("rejected", withheld.structured["outcome"])
    }

    @Test
    fun `location and device state`() {
        val loc = LocationNormalizer.toEvent(LocationSample(1_700_000_000_000, 51.5007322, -0.1246891, 12.4f, "fused"))
        assertEquals(EventKind.LOCATION, loc.kind)
        assertEquals("51.50073", loc.structured["lat"])
        assertEquals("-0.12469", loc.structured["lon"])
        assertEquals("12", loc.structured["accuracy_m"])
        assertEquals(Trust.SYSTEM, loc.trust)

        val bat = DeviceStateNormalizer.toEvent(DeviceStateSample(1_700_000_000_000, "battery", "83"))
        assertEquals("battery=83", bat.text)
        assertEquals(EventKind.DEVICE_STATE, bat.kind)
    }

    @Test
    fun `location debouncer keeps movement and heartbeat only`() {
        val d = LocationDebouncer(minDistanceMeters = 75.0, heartbeatMillis = 60_000)
        val t0 = 1_700_000_000_000
        assertTrue(d.accept(LocationSample(t0, 51.5000, -0.1200, null, null)))
        // 10 m away, 10 s later: noise
        assertEquals(false, d.accept(LocationSample(t0 + 10_000, 51.50009, -0.1200, null, null)))
        // 200 m away: movement
        assertTrue(d.accept(LocationSample(t0 + 20_000, 51.5018, -0.1200, null, null)))
        // no movement but heartbeat elapsed
        assertTrue(d.accept(LocationSample(t0 + 20_000 + 60_000, 51.5018, -0.1200, null, null)))
        assertEquals(111_000.0, LocationDebouncer.distanceMeters(0.0, 0.0, 1.0, 0.0), 500.0)
    }
}
