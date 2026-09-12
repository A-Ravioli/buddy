package buddy.cognition

import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import java.time.ZoneId

/**
 * The bounded, deterministic set of context a task gets (docs/02-context-and-memory.md,
 * "Retrieval index"). Same inputs, same slice, so evaluation is meaningful.
 *
 * Sections are added in priority order and the budget is enforced in characters (a
 * rough four characters per token). When the budget runs out, later sections are
 * truncated, never earlier ones.
 */
data class ContextSlice(val sections: List<Section>, val truncated: Boolean) {
    data class Section(val title: String, val body: String)

    fun render(): String = sections.joinToString("\n\n") { "## ${it.title}\n${it.body}" }
}

class SliceBuilder(
    private val ledger: Ledger,
    private val entities: EntityStore,
    private val zone: ZoneId,
    private val budgetChars: Int = 60_000,
    private val perThread: Int = 8,
) {
    /**
     * Builds the slice for a planning cycle: the triaged items in the window, their
     * threads, the people involved, open replies, and the situation.
     */
    fun forBrief(
        window: List<Event>,
        decisions: Map<String, TriageDecision>,
        situation: String,
        nowTs: Long,
    ): ContextSlice {
        val sections = ArrayList<ContextSlice.Section>()
        var used = 0
        var truncated = false

        fun add(title: String, body: String) {
            if (body.isBlank()) return
            val remaining = budgetChars - used
            if (remaining <= 0) { truncated = true; return }
            val text = if (body.length > remaining) { truncated = true; body.take(remaining) + "\n[truncated]" } else body
            sections.add(ContextSlice.Section(title, text))
            used += text.length + title.length + 6
        }

        add("Situation", situation)

        val byClass = window.groupBy { decisions[it.id]?.klass ?: TriageClass.FILE }
        val urgent = byClass[TriageClass.ESCALATE].orEmpty().filter { decisions[it.id]?.urgent == true }
        add("Urgent", Envelope.renderAll(urgent, zone))
        add("Needs a decision", Envelope.renderAll(byClass[TriageClass.ESCALATE].orEmpty().filter { decisions[it.id]?.urgent != true }, zone))
        add("Work buddy could do (not yet acting)", Envelope.renderAll(byClass[TriageClass.ACT_NOW].orEmpty() + byClass[TriageClass.ACT_LATER].orEmpty(), zone))

        // Thread context for everything above, most recent messages first per thread.
        val threadIds = (urgent + byClass[TriageClass.ESCALATE].orEmpty() + byClass[TriageClass.ACT_NOW].orEmpty() + byClass[TriageClass.ACT_LATER].orEmpty())
            .mapNotNull { it.threadId }.distinct()
        val threadText = threadIds.joinToString("\n\n") { tid ->
            val recent = ledger.thread(tid, 200).takeLast(perThread).filter { it.id !in window.map { w -> w.id }.toSet() }
            if (recent.isEmpty()) "" else "### thread $tid\n" + Envelope.renderAll(recent, zone)
        }
        add("Earlier in those threads", threadText)

        val actors = (urgent + byClass[TriageClass.ESCALATE].orEmpty() + byClass[TriageClass.ACT_NOW].orEmpty() + byClass[TriageClass.ACT_LATER].orEmpty())
            .mapNotNull { it.structured["counterparty"] ?: it.actor }.distinct()
        val peopleText = actors.mapNotNull { a ->
            val p = entities.personFor(buddy.entities.Identity.key(a)) ?: return@mapNotNull null
            "- ${p.displayName ?: a}: ${p.relationship}, ${p.eventCount} events, identities ${p.identities.joinToString(", ")}"
        }.joinToString("\n")
        add("People involved", peopleText)

        val awaiting = entities.awaitingReply(olderThanTs = nowTs - 2 * 86_400_000L, limit = 20)
        add("Threads where someone is waiting on the user (over two days)", awaiting.joinToString("\n") { t ->
            val who = t.personId?.let { entities.person(it)?.displayName } ?: t.threadId
            "- $who (${t.sourceApp}), last message ${(nowTs - t.lastTs) / 86_400_000L} days ago"
        })

        val filed = byClass[TriageClass.FILE].orEmpty()
        add("Filed without action (counts)", filed.groupBy { "${it.sourceApp}/${it.channel}" }.entries.sortedByDescending { it.value.size }
            .joinToString("\n") { "- ${it.key}: ${it.value.size}" })
        add("Dropped as noise (count)", byClass[TriageClass.DROP].orEmpty().size.takeIf { it > 0 }?.let { "$it items" } ?: "")

        val upcoming = window.filter { it.kind == EventKind.CALENDAR_CHANGE && (it.structured["begin"]?.toLongOrNull() ?: 0) > nowTs }
            .sortedBy { it.structured["begin"]?.toLongOrNull() }
        add("Upcoming calendar", Envelope.renderAll(upcoming.take(15), zone))

        return ContextSlice(sections, truncated)
    }
}
