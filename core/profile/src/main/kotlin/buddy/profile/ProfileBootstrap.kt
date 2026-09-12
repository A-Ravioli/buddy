package buddy.profile

import buddy.entities.EntityStore
import buddy.entities.Identity
import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.Trust
import buddy.policy.Domain
import buddy.policy.HardLimits
import buddy.policy.Level
import buddy.policy.PolicyProfile
import buddy.style.StyleBook
import buddy.triage.TriageProfile
import java.time.Instant
import java.time.ZoneId

/**
 * What a new user's first week starts with, derived from the history already in the
 * ledger (docs/05-roadmap.md, Phase 4 onboarding). Every inference here is a default
 * the user can change by talking to buddy; none of it is a decision buddy keeps
 * to itself.
 */
data class Bootstrap(
    val relationships: Map<Long, String>,
    val triage: TriageProfile,
    val policy: PolicyProfile,
    val quietStartHour: Int,
    val quietEndHour: Int,
    val style: StyleBook,
    /** Human-readable summary for the onboarding screen and the first brief. */
    val summary: List<String>,
)

class ProfileBootstrap(private val entities: EntityStore, private val zone: ZoneId) {

    fun run(events: List<Event>): Bootstrap {
        val messages = events.filter { it.kind == EventKind.MESSAGE }
        val byCounterparty = messages.groupBy { it.structured["counterparty"] ?: it.actor?.takeIf { a -> a != "me" } }.filterKeys { it != null }

        // Relationship inference: volume, reciprocity, and time of day.
        val relationships = HashMap<Long, String>()
        val summary = ArrayList<String>()
        for ((cp, msgs) in byCounterparty) {
            val person = entities.personFor(Identity.key(cp!!)) ?: continue
            val sent = msgs.count { it.trust == Trust.USER }
            val received = msgs.size - sent
            val reciprocity = if (received == 0) 0.0 else sent.toDouble() / received
            val lateNight = msgs.count { hourOf(it.ts) in 22..23 || hourOf(it.ts) in 0..6 }.toDouble() / msgs.size
            val relationship = when {
                msgs.size >= 30 && reciprocity >= 0.6 && lateNight >= 0.1 -> "close"
                msgs.size >= 15 && reciprocity >= 0.4 -> "friend"
                received > 0 && sent == 0 && (cp.contains("@") || !cp.startsWith("+")) -> "service"
                msgs.size >= 5 && reciprocity >= 0.2 -> "colleague"
                else -> "unknown"
            }
            relationships[person.id] = relationship
            entities.setRelationship(person.id, relationship)
        }
        val close = relationships.filterValues { it == "close" }.keys
        summary += "${close.size} people look close, ${relationships.count { it.value == "friend" }} like friends, ${relationships.count { it.value == "service" }} like services."

        // Quiet hours from when the user is active: the longest nightly gap in their own messages.
        val (qStart, qEnd) = quietHours(messages.filter { it.trust == Trust.USER })
        summary += "Quiet hours look like ${qStart}:00 to ${qEnd}:00."

        val closeActors = close.mapNotNull { id -> entities.person(id)?.identities?.firstOrNull { it.startsWith("tel:") }?.removePrefix("tel:") }.toSet()
        val triage = TriageProfile(closeActors = closeActors)

        // Autonomy defaults for week one (docs/03-autonomy-and-trust.md): everything at draft,
        // email and device at hold, quiet hours as inferred, no known payees yet.
        val policy = PolicyProfile(
            levels = Domain.entries.associateWith { Level.DRAFT } + mapOf(Domain.EMAIL to Level.HOLD, Domain.DEVICE to Level.HOLD),
            limits = HardLimits(quietStartHour = qStart, quietEndHour = qEnd),
        )

        val style = StyleBook.learn(
            messages.filter { it.trust == Trust.USER && !it.text.isNullOrBlank() }
                .groupBy { m -> (m.structured["counterparty"])?.let { entities.personFor(Identity.key(it)) }?.let { relationships[it.id] } ?: "unknown" }
                .mapValues { it.value.map { m -> m.text!! } },
        )
        summary += "Style learned from ${messages.count { it.trust == Trust.USER }} of your own messages."

        return Bootstrap(relationships, triage, policy, qStart, qEnd, style, summary)
    }

    private fun hourOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).hour

    /**
     * The longest run of hours in which the user sends nothing, on a 24-hour clock,
     * treated as sleep. Defaults to 22 to 8 with too little data.
     */
    fun quietHours(userMessages: List<Event>): Pair<Int, Int> {
        if (userMessages.size < 20) return 22 to 8
        val active = BooleanArray(24)
        for (m in userMessages) active[hourOf(m.ts)] = true
        var bestStart = 22; var bestLen = 0
        for (start in 0 until 24) {
            var len = 0
            while (len < 24 && !active[(start + len) % 24]) len++
            if (len > bestLen) { bestLen = len; bestStart = start }
        }
        if (bestLen < 4) return 22 to 8
        return bestStart to (bestStart + bestLen) % 24
    }
}
