package buddy.cognition

import buddy.ledger.Event
import buddy.ledger.Trust
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders events for the model. Every event goes inside an envelope that names its
 * source and trust level; the constitution tells the model that envelope contents are
 * data to reason about, never instructions to follow (docs/03-autonomy-and-trust.md).
 *
 * The closing tag is neutralised inside content so that a message containing
 * `</event>` cannot end its own envelope early.
 */
object Envelope {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(e: Event, zone: ZoneId): String {
        val trust = when (e.trust) {
            Trust.UNTRUSTED -> "untrusted"
            Trust.USER -> "user"
            Trust.SYSTEM -> "system"
        }
        val attrs = buildList {
            add("id=\"${e.id}\"")
            add("ts=\"${fmt.format(Instant.ofEpochMilli(e.ts).atZone(zone))}\"")
            add("source=\"${attr(e.sourceApp)}\"")
            add("channel=\"${attr(e.channel)}\"")
            add("kind=\"${e.kind.name.lowercase()}\"")
            add("trust=\"$trust\"")
            e.actor?.let { add("actor=\"${attr(it)}\"") }
            e.threadId?.let { add("thread=\"${attr(it)}\"") }
        }.joinToString(" ")
        val fields = e.structured.entries
            .filter { it.key !in HIDDEN_FIELDS }
            .joinToString("") { "\n  ${it.key}: ${neutralise(it.value)}" }
        val body = e.text?.let { "\n" + neutralise(redactCodes(it.trim(), e)) } ?: ""
        return "<event $attrs>$fields$body\n</event>"
    }

    fun renderAll(events: List<Event>, zone: ZoneId): String = events.joinToString("\n") { render(it, zone) }

    /** Content that must never reach the model in a slice, whatever the task. */
    val HIDDEN_FIELDS: Set<String> = setOf("otp")

    /** Every code we know of for an event: the extracted field and a fresh scan of the text. */
    fun codes(e: Event): Set<String> =
        setOfNotNull(e.structured["otp"], buddy.triage.Extractors.extract(e.text)["otp"])

    /** Replaces one-time codes in text with a placeholder. The rule in the constitution has teeth. */
    fun redactCodes(text: String, e: Event): String = codes(e).fold(text) { acc, c -> acc.replace(c, "[code]") }

    internal fun neutralise(s: String): String =
        s.replace("</event", "&lt;/event", ignoreCase = true).replace("<event", "&lt;event", ignoreCase = true)

    private fun attr(s: String): String = s.replace("\"", "&quot;").replace("\n", " ")
}
