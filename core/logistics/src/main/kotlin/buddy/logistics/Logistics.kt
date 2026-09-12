package buddy.logistics

import buddy.ledger.Event
import buddy.triage.Extractors

/** Where a parcel is, as far as the ledger can tell. */
enum class DeliveryStatus { ORDERED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, MISSED, RETURNED, UNKNOWN }

data class Delivery(
    val tracking: String,
    val carrier: String?,
    val status: DeliveryStatus,
    val lastTs: Long,
    val lastEventId: String,
    /** A date hint from the text, if any ("tomorrow", "thursday"). */
    val expected: String? = null,
)

/**
 * Tracks deliveries across events by tracking number. Status moves forward only:
 * a late "shipped" notification after "delivered" does not regress the parcel.
 */
class DeliveryTracker {
    private val deliveries = HashMap<String, Delivery>()

    fun update(e: Event): Delivery? {
        val fields = e.structured + Extractors.extract(e.text)
        val tracking = fields["tracking"] ?: return null
        val status = statusOf(e.text.orEmpty().lowercase())
        val carrier = carrierOf(e.sourceApp, e.actor, e.text.orEmpty())
        val current = deliveries[tracking]
        val next = if (current != null && rank(status) < rank(current.status)) current.status else status
        val d = Delivery(tracking, carrier ?: current?.carrier, next, maxOf(e.ts, current?.lastTs ?: 0), e.id, fields["date_hint"] ?: current?.expected)
        deliveries[tracking] = d
        return d
    }

    fun all(): List<Delivery> = deliveries.values.sortedByDescending { it.lastTs }
    fun open(): List<Delivery> = all().filter { it.status !in setOf(DeliveryStatus.DELIVERED, DeliveryStatus.RETURNED) }

    private fun rank(s: DeliveryStatus) = when (s) {
        DeliveryStatus.UNKNOWN -> 0; DeliveryStatus.ORDERED -> 1; DeliveryStatus.SHIPPED -> 2
        DeliveryStatus.OUT_FOR_DELIVERY -> 3; DeliveryStatus.MISSED -> 4; DeliveryStatus.DELIVERED -> 5; DeliveryStatus.RETURNED -> 6
    }

    companion object {
        private val delivered = Regex("""(?<![a-z])(?:has been |was |been )?delivered(?![a-z])""")

        /** Order matters: a missed delivery mentions redelivery, which must not read as delivered. */
        fun statusOf(lower: String): DeliveryStatus = when {
            "returned" in lower || "refund" in lower -> DeliveryStatus.RETURNED
            "we missed you" in lower || "unable to deliver" in lower || "delivery attempt" in lower || "couldn't deliver" in lower || "redeliver" in lower -> DeliveryStatus.MISSED
            delivered.containsMatchIn(lower) -> DeliveryStatus.DELIVERED
            "out for delivery" in lower || "on its way to you" in lower || "arriving today" in lower -> DeliveryStatus.OUT_FOR_DELIVERY
            "shipped" in lower || "dispatched" in lower || "on its way" in lower -> DeliveryStatus.SHIPPED
            "order" in lower && ("confirm" in lower || "received" in lower || "placed" in lower) -> DeliveryStatus.ORDERED
            else -> DeliveryStatus.UNKNOWN
        }

        private val carriers = listOf("ups", "dpd", "royal mail", "evri", "hermes", "fedex", "dhl", "usps", "amazon", "yodel", "parcelforce")
        fun carrierOf(sourceApp: String, actor: String?, text: String): String? {
            val hay = "$sourceApp ${actor.orEmpty()} $text".lowercase()
            return carriers.firstOrNull { it in hay }
        }
    }
}

/** One leg of a trip, from a confirmation. */
data class Segment(
    /** "flight", "train", "hotel", "car". */
    val kind: String,
    val code: String?,
    val from: String?,
    val to: String?,
    val dateHint: String?,
    val timeHint: String?,
    val reference: String?,
    val sourceEventId: String,
)

data class Trip(val segments: List<Segment>) {
    val references: Set<String> get() = segments.mapNotNull { it.reference }.toSet()
}

/**
 * Builds an itinerary from confirmation events (docs/04-domain-playbooks.md, "Travel").
 * Regex-based, deliberately: a confirmation is a formatted document, and the fields
 * that matter are the ones that need to be exactly right on the day.
 */
object ItineraryBuilder {
    private val flight = Regex("""\b([A-Z]{2}\s?\d{2,4})\b""")
    private val route = Regex("""(?i)\b([A-Z]{3})\s*(?:->|to|-|–)\s*([A-Z]{3})\b""")
    private val routeWords = Regex("""(?i)\bfrom\s+([A-Z][a-zA-Z ]{2,30}?)\s+to\s+([A-Z][a-zA-Z ]{2,30}?)(?:[.,\n]|\s+on\b|\s+at\b|$)""")
    private val time = Regex("""\b(\d{1,2}:\d{2})\b""")
    private val hotel = Regex("""(?i)\b(hotel|inn|hostel|resort|apartment|airbnb)\b""")
    private val checkIn = Regex("""(?i)check[- ]?in[:\s]+([^\n,.]{3,30})""")
    private val train = Regex("""(?i)\b(train|rail|eurostar|coach)\b""")

    fun segmentOf(e: Event): Segment? {
        val text = e.text ?: return null
        val fields = e.structured + Extractors.extract(text)
        val ref = fields["booking_ref"]
        val lower = text.lowercase()
        val dateHint = fields["date_hint"]
        val timeHint = time.find(text)?.groupValues?.get(1)
        val codes = route.find(text)
        val words = routeWords.find(text)
        return when {
            "flight" in lower || "boarding" in lower || "airline" in lower || "airways" in lower -> Segment(
                "flight", flight.find(text)?.groupValues?.get(1)?.replace(" ", ""),
                codes?.groupValues?.get(1)?.uppercase() ?: words?.groupValues?.get(1)?.trim(),
                codes?.groupValues?.get(2)?.uppercase() ?: words?.groupValues?.get(2)?.trim(),
                dateHint, timeHint, ref, e.id,
            )
            train.containsMatchIn(text) -> Segment("train", null, words?.groupValues?.get(1)?.trim(), words?.groupValues?.get(2)?.trim(), dateHint, timeHint, ref, e.id)
            hotel.containsMatchIn(text) -> Segment("hotel", null, null, null, checkIn.find(text)?.groupValues?.get(1)?.trim() ?: dateHint, timeHint, ref, e.id)
            else -> null
        }
    }

    fun build(events: List<Event>): Trip = Trip(events.mapNotNull(::segmentOf).sortedBy { it.sourceEventId })
}
