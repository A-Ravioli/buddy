package buddy.logistics

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogisticsTest {
    private var seq = 0L
    private fun ev(text: String, actor: String = "DPD", pkg: String = "com.dpd", ts: Long = 1_700_000_000_000 + seq * 3_600_000) = Event(
        EventId.of(ts, pkg, "n", (seq++).toString()), ts, pkg, "n", EventKind.NOTIFICATION, actor, null, text, emptyMap(), Trust.UNTRUSTED,
    )

    @Test
    fun `delivery status moves forward by tracking number`() {
        val t = DeliveryTracker()
        val a = t.update(ev("Your order has been placed. Tracking: 1Z999AA10123456784"))!!
        assertEquals(DeliveryStatus.ORDERED, a.status)
        assertEquals("dpd", a.carrier)
        val b = t.update(ev("Parcel 1Z999AA10123456784 is out for delivery, arriving today"))!!
        assertEquals(DeliveryStatus.OUT_FOR_DELIVERY, b.status)
        assertEquals("today", b.expected)
        val c = t.update(ev("We missed you. 1Z999AA10123456784 will be redelivered tomorrow"))!!
        assertEquals(DeliveryStatus.MISSED, c.status)
        assertEquals(1, t.open().size)
        val d = t.update(ev("1Z999AA10123456784 has been delivered to your safe place"))!!
        assertEquals(DeliveryStatus.DELIVERED, d.status)
        // A late, out-of-order "shipped" does not regress it.
        assertEquals(DeliveryStatus.DELIVERED, t.update(ev("Shipped: 1Z999AA10123456784"))!!.status)
        assertEquals(0, t.open().size)
        assertNull(t.update(ev("No tracking here")))
    }

    @Test
    fun `flights, trains and hotels become segments`() {
        val flight = ev("Your flight BA 2490 from LHR to JFK departs 10:35 on Thursday. Booking reference X7K9Q2.", actor = "British Airways", pkg = "com.ba")
        val train = ev("Your train from London St Pancras to Paris Nord on 12/03 at 08:01 is confirmed. Reference: A1B2C3D.", actor = "Eurostar", pkg = "com.eurostar")
        val hotel = ev("Thanks for booking Hotel Rivoli. Check-in: 14 March from 15:00. Confirmation number H12345.", actor = "booking.com", pkg = "com.booking")
        val noise = ev("Your parcel is out for delivery")
        val trip = ItineraryBuilder.build(listOf(flight, train, hotel, noise))
        assertEquals(3, trip.segments.size)
        val f = trip.segments.first { it.kind == "flight" }
        assertEquals("BA2490", f.code); assertEquals("LHR", f.from); assertEquals("JFK", f.to)
        assertEquals("thursday", f.dateHint); assertEquals("10:35", f.timeHint); assertEquals("X7K9Q2", f.reference)
        val tr = trip.segments.first { it.kind == "train" }
        assertEquals("London St Pancras", tr.from); assertEquals("Paris Nord", tr.to); assertEquals("12/03", tr.dateHint); assertEquals("A1B2C3D", tr.reference)
        val h = trip.segments.first { it.kind == "hotel" }
        assertEquals("14 March from 15:00", h.dateHint); assertEquals("H12345", h.reference)
        assertEquals(setOf("X7K9Q2", "A1B2C3D", "H12345"), trip.references)
    }
}
