package buddy.cognition

import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.Trust
import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The result of a planning cycle: the brief and how it was made. */
data class Planned(
    val brief: Brief,
    /** "cloud", "fallback", "cloud_refused", "cloud_error". */
    val source: String,
    val detail: String? = null,
    val slice: ContextSlice,
    val cloud: CloudResult<Brief>? = null,
)

/**
 * The planning cycle (docs/01-architecture.md, "Planning cycles"). Given the window of
 * events since the last brief and their triage decisions, build the slice, ask the
 * cloud for the brief, and fall back to a deterministic brief when the cloud is not
 * available. Records the brief as an event so the timeline and the eval can see it.
 */
class BriefPlanner(
    private val ledger: Ledger,
    entities: EntityStore,
    private val cloud: CloudModel?,
    private val zone: ZoneId,
    sliceBudgetChars: Int = 60_000,
) {
    private val slices = SliceBuilder(ledger, entities, zone, sliceBudgetChars)
    private val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm")

    fun plan(
        window: List<Event>,
        decisions: Map<String, TriageDecision>,
        nowTs: Long,
        cycle: String,
        situation: String = "",
        operator: String? = null,
    ): Planned {
        val now = fmt.format(Instant.ofEpochMilli(nowTs).atZone(zone))
        val sit = listOf("Now: $now.", "Cycle: $cycle brief.", situation).filter { it.isNotBlank() }.joinToString(" ")
        val slice = slices.forBrief(window, decisions, sit, nowTs)
        val fallback = fallbackBrief(window, decisions)

        val cloudResult = cloud?.structured(
            system = listOf(Prompts.CONSTITUTION, Prompts.BRIEF_PLAYBOOK),
            user = slice.render(),
            operator = operator,
            schema = Brief::class.java,
        )
        val planned = when {
            cloudResult == null -> Planned(fallback, "fallback", "no cloud model configured", slice)
            cloudResult.status == "ok" && cloudResult.value != null -> Planned(sanitise(cloudResult.value, window), "cloud", null, slice, cloudResult)
            cloudResult.status == "refusal" -> Planned(fallback, "cloud_refused", cloudResult.detail, slice, cloudResult)
            else -> Planned(fallback, "cloud_error", cloudResult.detail, slice, cloudResult)
        }
        ledger.append(briefEvent(planned, nowTs, cycle))
        return planned
    }

    /**
     * The brief without the cloud: counts and the escalated items verbatim. Honest and
     * dull. Used offline, on refusal, and as the baseline the cloud brief is judged
     * against in the eval.
     */
    fun fallbackBrief(window: List<Event>, decisions: Map<String, TriageDecision>): Brief {
        val by = window.groupBy { decisions[it.id]?.klass ?: TriageClass.FILE }
        val brief = Brief()
        brief.urgent = by[TriageClass.ESCALATE].orEmpty().filter { decisions[it.id]?.urgent == true }.map { item(it) }
        brief.decisions = by[TriageClass.ESCALATE].orEmpty().filter { decisions[it.id]?.urgent != true }.map { e ->
            Decision().apply {
                question = "${e.actor ?: e.sourceApp}: ${oneLine(e)}"
                options = listOf("Reply", "Ignore", "Later")
                recommendation = "No recommendation without the cloud model."
                eventIds = listOf(e.id)
            }
        }
        val work = by[TriageClass.ACT_NOW].orEmpty().size + by[TriageClass.ACT_LATER].orEmpty().size
        brief.done = buildList {
            by[TriageClass.FILE].orEmpty().takeIf { it.isNotEmpty() }?.let { add("Filed ${it.size} items.") }
            by[TriageClass.DROP].orEmpty().takeIf { it.isNotEmpty() }?.let { add("Dropped ${it.size} as noise.") }
            if (work > 0) add("$work items buddy could handle once acting is enabled.")
        }
        brief.upcoming = window.filter { it.kind == EventKind.CALENDAR_CHANGE }.sortedBy { it.structured["begin"]?.toLongOrNull() }.take(5).map { item(it) }
        brief.health = ""
        val needs = brief.urgent.size + brief.decisions.size
        brief.spoken = if (needs == 0) {
            "Nothing needs you. " + brief.done.joinToString(" ")
        } else {
            listOfNotNull(
                brief.urgent.takeIf { it.isNotEmpty() }?.let { "Urgent: " + it.joinToString(" ") { u -> u.summary } },
                "${brief.decisions.size} thing${if (brief.decisions.size == 1) "" else "s"} need you: " + brief.decisions.joinToString(" ") { it.question },
                brief.done.joinToString(" "),
            ).joinToString(" ")
        }
        return brief
    }

    /** Drops event ids the model invented and anything that leaks a hidden field. */
    private fun sanitise(b: Brief, window: List<Event>): Brief {
        val known = window.map { it.id }.toSet()
        b.urgent.forEach { it.eventIds = it.eventIds.filter { id -> id in known } }
        b.upcoming.forEach { it.eventIds = it.eventIds.filter { id -> id in known } }
        b.decisions.forEach { it.eventIds = it.eventIds.filter { id -> id in known } }
        val codes = window.flatMap(Envelope::codes).toSet()
        if (codes.isNotEmpty()) {
            fun scrub(s: String) = codes.fold(s) { acc, c -> acc.replace(c, "[code]") }
            b.spoken = scrub(b.spoken)
            b.urgent.forEach { it.summary = scrub(it.summary) }
            b.decisions.forEach { it.question = scrub(it.question); it.recommendation = scrub(it.recommendation) }
            b.done = b.done.map(::scrub)
        }
        return b
    }

    private fun item(e: Event) = BriefItem().apply { summary = "${e.actor ?: e.sourceApp}: ${oneLine(e)}"; eventIds = listOf(e.id) }
    private fun oneLine(e: Event) = (e.text ?: e.structured.entries.joinToString(" ") { "${it.key}=${it.value}" }).replace('\n', ' ').take(140)

    private fun briefEvent(p: Planned, nowTs: Long, cycle: String) = Event(
        id = EventId.of(nowTs, "buddy", "brief", cycle),
        ts = nowTs,
        sourceApp = "buddy",
        channel = "brief",
        kind = EventKind.BRIEF,
        text = p.brief.spoken,
        structured = buildMap {
            put("cycle", cycle)
            put("source", p.source)
            p.detail?.let { put("detail", it) }
            put("urgent", p.brief.urgent.size.toString())
            put("decisions", p.brief.decisions.size.toString())
            put("truncated", p.slice.truncated.toString())
            p.cloud?.let {
                put("input_tokens", it.inputTokens.toString())
                put("output_tokens", it.outputTokens.toString())
                put("cache_read_tokens", it.cacheReadTokens.toString())
                put("cache_write_tokens", it.cacheWriteTokens.toString())
            }
        },
        trust = Trust.SYSTEM,
    )
}
