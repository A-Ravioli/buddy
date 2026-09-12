package buddy.actuation

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.Trust
import buddy.policy.Proposal
import buddy.policy.Verdict

/** The result of a connector doing something. */
data class Outcome(
    val ok: Boolean,
    val detail: String = "",
    /** Opaque token the connector can undo with, when the action is reversible. */
    val undoToken: String? = null,
)

/**
 * A connector executes actions against one app or service, checks that they took
 * effect, and undoes them where possible. Connectors never decide; they do.
 */
interface Connector {
    val actions: Set<String>
    fun execute(p: Proposal): Outcome

    /** Observes the postcondition: did the message send, did the event appear. */
    fun verify(p: Proposal, o: Outcome): Boolean = o.ok
    fun undo(p: Proposal, o: Outcome): Boolean = false
}

/**
 * Runs verdicts (docs/01-architecture.md, "Actuation"). Every step is written to the
 * ledger as an ACTION event: proposed, held, vetoed, done, failed, undone. The event
 * chain is the timeline the user sees and the undo affordance reads.
 *
 * A transaction is: precondition (a connector exists), execute, verify, record. A
 * failed verify is recorded as failed and escalated; nothing retries blindly.
 */
class Executor(
    private val ledger: Ledger,
    connectors: List<Connector>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val byAction = HashMap<String, Connector>().apply {
        for (c in connectors) for (a in c.actions) put(a, c)
    }
    private val proposals = HashMap<String, Proposal>()

    /** Applies a verdict to a proposal. Returns the recorded ACTION event. */
    fun apply(p: Proposal, v: Verdict): Event {
        proposals[p.id] = p
        return when (v) {
            is Verdict.Run -> execute(p, v.reasons, supersedes = null)
            is Verdict.Hold -> record(p, "held", v.reasons, structured = mapOf("until" to v.untilTs.toString()))
            is Verdict.Escalate -> record(p, "escalated", v.reasons)
            is Verdict.Deny -> record(p, "denied", v.reasons)
        }
    }

    /** Held actions whose hold has expired and that have not been vetoed or released. */
    fun dueHolds(nowTs: Long = now()): List<Pair<Event, Proposal>> =
        ledger.recent(500)
            .filter { it.kind == EventKind.ACTION && it.structured["state"] == "held" }
            .filter { ledger.corrections(it.id).isEmpty() }
            .filter { (it.structured["until"]?.toLongOrNull() ?: Long.MAX_VALUE) <= nowTs }
            .mapNotNull { e -> proposalOf(e)?.let { e to it } }

    /** Runs a held action now. */
    fun release(held: Event): Event {
        val p = proposalOf(held) ?: error("not a held action: ${held.id}")
        return execute(p, listOf("hold_released"), supersedes = held.id)
    }

    /** The user vetoed a held action from the brief. */
    fun veto(held: Event, by: String = "user"): Event {
        val p = proposalOf(held) ?: error("not a held action: ${held.id}")
        return record(p, "vetoed", listOf("vetoed_by_$by"), supersedes = held.id, trust = Trust.USER)
    }

    /** Undoes a completed action if its connector can. Records the correction either way. */
    fun undo(done: Event, critical: Boolean = false): Event {
        val p = proposalOf(done) ?: error("not an action: ${done.id}")
        val token = done.structured["undo_token"]
        val connector = byAction[p.spec.name]
        val ok = token != null && connector != null && connector.undo(p, Outcome(true, undoToken = token))
        return record(
            p, if (ok) "undone" else "undo_failed", listOf(if (critical) "critical_regret" else "regret"),
            supersedes = done.id, trust = Trust.USER, kind = EventKind.CORRECTION,
            structured = mapOf("critical" to critical.toString()),
        )
    }

    private fun execute(p: Proposal, reasons: List<String>, supersedes: String?): Event {
        val connector = byAction[p.spec.name]
            ?: return record(p, "failed", reasons + "no_connector", supersedes = supersedes)
        val outcome = try {
            connector.execute(p)
        } catch (t: Throwable) {
            Outcome(false, "exception: ${t.message}")
        }
        if (!outcome.ok) return record(p, "failed", reasons, supersedes = supersedes, structured = mapOf("detail" to outcome.detail))
        val verified = try { connector.verify(p, outcome) } catch (t: Throwable) { false }
        if (!verified) return record(p, "unverified", reasons, supersedes = supersedes, structured = mapOf("detail" to outcome.detail))
        return record(
            p, "done", reasons, supersedes = supersedes,
            structured = buildMap {
                put("detail", outcome.detail)
                outcome.undoToken?.let { put("undo_token", it) }
            },
        )
    }

    private fun record(
        p: Proposal,
        state: String,
        reasons: List<String>,
        supersedes: String? = null,
        trust: Trust = Trust.SYSTEM,
        kind: EventKind = EventKind.ACTION,
        structured: Map<String, String> = emptyMap(),
    ): Event {
        val ts = now()
        val e = Event(
            id = EventId.of(ts, "buddy", "action", p.id, state, supersedes),
            ts = ts,
            sourceApp = "buddy",
            channel = "action",
            kind = kind,
            actor = if (trust == Trust.USER) "me" else null,
            threadId = p.payload["thread_id"],
            text = p.reason.ifBlank { null },
            structured = buildMap {
                put("proposal_id", p.id)
                put("action", p.spec.name)
                put("domain", p.spec.domain.name)
                put("state", state)
                put("reasons", reasons.joinToString("|"))
                p.target?.let { put("target", it) }
                p.amount?.let { put("amount", it.toString()) }
                p.currency?.let { put("currency", it) }
                p.sourceEventIds.takeIf { it.isNotEmpty() }?.let { put("source_events", it.joinToString("|")) }
                p.payload.forEach { (k, v) -> put("p_$k", v) }
                putAll(structured)
            },
            trust = trust,
            supersedes = supersedes,
        )
        ledger.append(e)
        return e
    }

    /** Rebuilds the proposal from an ACTION event, so holds survive a process restart. */
    fun proposalOf(e: Event): Proposal? {
        val s = e.structured
        val spec = Actions.byName[s["action"] ?: return null] ?: return null
        return Proposal(
            id = s["proposal_id"] ?: return null,
            spec = spec,
            target = s["target"],
            payload = s.filterKeys { it.startsWith("p_") }.mapKeys { it.key.removePrefix("p_") },
            amount = s["amount"]?.toDoubleOrNull(),
            currency = s["currency"],
            reason = e.text ?: "",
            sourceEventIds = s["source_events"]?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }
}
