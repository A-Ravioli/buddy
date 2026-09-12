package buddy.cognition

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
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
        val existing = ledger.recent(2000).filter { it.kind == EventKind.MEMORY_NOTE }.take(50)
        val rest = window.filter { it !in corrections && it.kind != EventKind.MEMORY_NOTE && it.kind != EventKind.TRIAGE && it.kind != EventKind.BRIEF }
        val user = buildString {
            appendLine("## Corrections and vetoes by the user (highest weight)")
            appendLine(Envelope.renderAll(corrections, zone).ifBlank { "none" })
            appendLine()
            appendLine("## Existing notes (do not repeat; refine or contradict only with new evidence)")
            appendLine(existing.joinToString("\n") { "- [${it.structured["kind"]}] ${it.text}" }.ifBlank { "none" })
            appendLine()
            appendLine("## Recent events")
            appendLine(Envelope.renderAll(rest, zone))
        }.take(maxChars)

        val r = cloud.structured(listOf(Prompts.CONSTITUTION, Prompts.MEMORY_PLAYBOOK), user, null, Notes::class.java)
        val notes = r.value?.notes ?: return Result(emptyList(), r.status, r.detail)
        val written = notes.filter { it.text.isNotBlank() }.mapNotNull { n ->
            val e = Event(
                id = EventId.of(nowTs, "buddy", "memory", n.kind, n.text.trim().lowercase()),
                ts = nowTs,
                sourceApp = "buddy",
                channel = "memory",
                kind = EventKind.MEMORY_NOTE,
                actor = n.entity.ifBlank { null },
                text = n.text.trim(),
                structured = mapOf(
                    "kind" to n.kind,
                    "confidence" to "%.2f".format(java.util.Locale.ROOT, n.confidence.coerceIn(0.0, 1.0)),
                    "from_corrections" to corrections.isNotEmpty().toString(),
                ),
                trust = Trust.SYSTEM,
            )
            if (ledger.append(e)) e else null
        }
        return Result(written, "ok")
    }
}
