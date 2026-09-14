package buddy.cognition

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.time.ZoneId

class Note {
    @JsonPropertyDescription("The observation, one or two sentences, specific enough to act on.")
    var text: String = ""

    @JsonPropertyDescription("One of: pattern, preference, relationship, correction, standing_instruction.")
    var kind: String = "pattern"

    @JsonPropertyDescription("0 to 1.")
    var confidence: Double = 0.5

    @JsonPropertyDescription("The person, organisation, or app this is about, or empty.")
    var entity: String = ""

    @JsonPropertyDescription("Ids of the events that are the evidence for this note. Never empty: a note without evidence cannot be checked, aged, or pruned.")
    var derivedFrom: List<String> = emptyList()

    @JsonPropertyDescription("The id of an existing note this one replaces, when the evidence has changed the person's mind. Empty otherwise.")
    var retires: String = ""
}

class Notes {
    @JsonPropertyDescription("Durable observations from this window. Empty if nothing new.")
    var notes: List<Note> = emptyList()
}

/**
 * Memory consolidation (docs/02-context-and-memory.md). In idle cycles, the model
 * reads the recent window with the user's corrections given the most weight and
 * writes MEMORY_NOTE events. Notes are events: they can be seen, corrected, and
 * replayed like everything else.
 */
class MemoryConsolidator(
    private val ledger: Ledger,
    private val cloud: CloudModel,
    private val zone: ZoneId,
    private val maxChars: Int = 60_000,
) {
    data class Result(val written: List<Event>, val status: String, val detail: String? = null)

    fun consolidate(window: List<Event>, nowTs: Long): Result {
        val corrections = window.filter { it.kind == EventKind.CORRECTION || (it.kind == EventKind.ACTION && it.structured["state"] == "vetoed") }
        val existing = activeNotes(50)
        val rest = window.filter { it !in corrections && it.kind != EventKind.MEMORY_NOTE && it.kind != EventKind.TRIAGE && it.kind != EventKind.BRIEF }
        val user = buildString {
            appendLine("## Corrections and vetoes by the user (highest weight)")
            appendLine(Envelope.renderAll(corrections, zone).ifBlank { "none" })
            appendLine()
            appendLine("## Existing notes (do not repeat; to replace one, set retires to its id)")
            appendLine(existing.joinToString("\n") { "- ${it.id} [${it.structured["kind"]}] ${it.text}" }.ifBlank { "none" })
            appendLine()
            appendLine("## Recent events")
            appendLine(Envelope.renderAll(rest, zone))
        }.take(maxChars)

        val r = cloud.structured(listOf(Prompts.CONSTITUTION, Prompts.MEMORY_PLAYBOOK), user, null, Notes::class.java)
        val notes = r.value?.notes ?: return Result(emptyList(), r.status, r.detail)
        val knownIds = existing.mapTo(HashSet()) { it.id }
        val written = notes.filter { it.text.isNotBlank() }.mapNotNull { n ->
            // A note may only retire a note that exists and is still standing. The model
            // naming something else is a hallucinated id, not a reversal.
            val retires = n.retires.takeIf { it in knownIds }
            val evidence = n.derivedFrom.filter { id -> window.any { it.id == id } }
            val e = Event(
                id = EventId.of(nowTs, "buddy", "memory", n.kind, n.text.trim().lowercase(), retires),
                ts = nowTs,
                sourceApp = "buddy",
                channel = "memory",
                kind = EventKind.MEMORY_NOTE,
                actor = n.entity.ifBlank { null },
                text = n.text.trim(),
                structured = buildMap {
                    put("kind", n.kind)
                    put("confidence", "%.2f".format(java.util.Locale.ROOT, n.confidence.coerceIn(0.0, 1.0)))
                    put("from_corrections", corrections.isNotEmpty().toString())
                    // Provenance is what makes pruning real (docs/07, section 6): without
                    // it a note cannot be aged, checked, or taken away with its source.
                    if (evidence.isNotEmpty()) put(SqliteLedger.DERIVED_FROM, evidence.joinToString("|"))
                    retires?.let { put("retires", it) }
                },
                trust = Trust.SYSTEM,
                // A reversal supersedes the note it replaces, exactly as a correction
                // supersedes an event, so the old note stops being retrieved and stays
                // auditable.
                supersedes = retires,
            )
            if (ledger.append(e)) e else null
        }
        return Result(written, "ok")
    }

    /**
     * Notes that still stand: every memory note nothing has superseded. A note the
     * person has since contradicted is history, not memory, and must not come back
     * through recall or the next consolidation.
     */
    fun activeNotes(limit: Int = 50): List<Event> =
        ledger.recent(2000)
            .filter { it.kind == EventKind.MEMORY_NOTE }
            .filter { ledger.corrections(it.id).isEmpty() }
            .take(limit)
}
