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
     * The slice for one wake (docs/07, section 6).
     *
     * Deliberately much smaller than [forBrief]: the situation, the open jobs as one
     * line each, the focused task's compiled state, and the events that caused the
     * wake. Everything else the agent wants, it asks for with `recall` or
     * `read_thread`. That is what keeps a wake the same size in year two as in week
     * one.
     */
    fun forWake(
        wake: buddy.tasks.Wake,
        tasks: List<buddy.tasks.Task>,
        focus: buddy.tasks.Task?,
        triggers: List<Event>,
        decisions: Map<String, TriageDecision>,
        nowTs: Long,
        localHour: Int,
        userTurn: String? = null,
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

        add(
            "Situation",
            "Now is ${java.time.Instant.ofEpochMilli(nowTs).atZone(zone)} (local hour $localHour). " +
                "Woken by ${wake.reason.name}${if (wake.detail.isBlank()) "" else ": ${wake.detail}"}.",
        )
        userTurn?.takeIf { it.isNotBlank() }?.let { add("What the person just said", it) }

        focus?.let {
            add(
                "The task you are working on",
                buildString {
                    appendLine("id: ${it.id}")
                    appendLine("goal: ${it.goal}")
                    appendLine("state: ${it.state.name.lowercase()}${it.waitingOn?.let { w -> ", waiting on $w" } ?: ""}")
                    appendLine("mandate: ${buddy.tasks.TaskStore.describe(it.mandate)}")
                    appendLine("spent so far: ${it.actions} actions, ${it.spend} money, ${it.tokens} tokens")
                    it.dueTs?.let { d -> appendLine("due: ${java.time.Instant.ofEpochMilli(d).atZone(zone)}") }
                    appendLine()
                    appendLine("Working state from the last turn:")
                    append(it.workingSet.ifBlank { "(nothing yet — this is the first turn on this task)" })
                },
            )
        }

        val others = tasks.filter { it.id != focus?.id }
        add(
            "Your other open jobs",
            others.joinToString("\n") {
                "- ${it.id} [${it.state.name.lowercase()}] ${it.goal}" +
                    (it.waitingOn?.let { w -> " (waiting on $w)" } ?: "") +
                    (it.dueTs?.let { d -> " (due ${java.time.Instant.ofEpochMilli(d).atZone(zone).toLocalDate()})" } ?: "")
            },
        )

        add("What woke you", Envelope.renderAll(triggers, zone))

        val triageLines = triggers.mapNotNull { e ->
            decisions[e.id]?.let { d -> "- ${e.id}: ${d.klass.name.lowercase()}${if (d.urgent) " (urgent)" else ""} — ${d.reasons.joinToString(", ")}" }
        }
        add("What triage made of it", triageLines.joinToString("\n"))

        val actors = triggers.mapNotNull { it.structured["counterparty"] ?: it.actor }.distinct()
        add(
            "People involved",
            actors.mapNotNull { a ->
                val p = entities.personFor(buddy.entities.Identity.key(a)) ?: return@mapNotNull null
                "- ${p.displayName ?: a}: ${p.relationship}, ${p.eventCount} events, identities ${p.identities.joinToString(", ")}"
            }.joinToString("\n"),
        )

        return ContextSlice(sections, truncated)
    }

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
