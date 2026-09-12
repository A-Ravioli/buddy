package buddy.cognition

import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.Trust
import buddy.triage.Triage
import buddy.triage.TriageDecision
import buddy.triage.TriageProfile

/**
 * Runs triage on new events, records each decision as a TRIAGE event, and feeds the
 * entity graph. The ledger's idempotent append means running it twice on the same
 * event costs nothing and changes nothing.
 */
class TriageRecorder(
    private val ledger: Ledger,
    private val entities: EntityStore,
    private val triage: Triage,
) {
    fun process(event: Event, profile: TriageProfile): TriageDecision? {
        if (event.sourceApp == "buddy") return null
        entities.apply(event)
        val d = triage.triage(event, profile)
        ledger.append(toEvent(d, event.ts))
        return d
    }

    companion object {
        fun toEvent(d: TriageDecision, ts: Long) = Event(
            id = EventId.of(ts, "buddy", "triage", d.eventId),
            ts = ts,
            sourceApp = "buddy",
            channel = "triage",
            kind = EventKind.TRIAGE,
            threadId = null,
            structured = buildMap {
                put("event_id", d.eventId)
                put("class", d.klass.name)
                put("urgent", d.urgent.toString())
                put("confidence", "%.2f".format(java.util.Locale.ROOT, d.confidence))
                put("reasons", d.reasons.joinToString("|"))
                d.extracted.filterKeys { it != "otp" }.forEach { (k, v) -> put("x_$k", v) }
            },
            trust = Trust.SYSTEM,
        )

        /** Reads a decision back from its event. */
        fun fromEvent(e: Event): TriageDecision? {
            if (e.kind != EventKind.TRIAGE) return null
            val s = e.structured
            return TriageDecision(
                eventId = s["event_id"] ?: return null,
                klass = buddy.triage.TriageClass.valueOf(s["class"] ?: return null),
                urgent = s["urgent"] == "true",
                reasons = s["reasons"]?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
                extracted = s.filterKeys { it.startsWith("x_") }.mapKeys { it.key.removePrefix("x_") },
                confidence = s["confidence"]?.toDoubleOrNull() ?: 0.5,
            )
        }
    }
}
