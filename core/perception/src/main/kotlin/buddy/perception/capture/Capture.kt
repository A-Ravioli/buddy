package buddy.perception.capture

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust

/**
 * A node of the view tree as delivered by the framework's content capture service:
 * the class, the visible text, and the children. Passwords never arrive here; the
 * framework masks them before the service sees them.
 */
data class CaptureNode(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val resourceId: String? = null,
    val visible: Boolean = true,
    val children: List<CaptureNode> = emptyList(),
    /** Absolute screen bounds in pixels, when the source provides them. */
    val bounds: Bounds? = null,
) {
    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        val centerX: Int get() = left + width / 2
        val centerY: Int get() = top + height / 2
    }
}

/** One snapshot of one app's window, assembled from a content capture session. */
data class CaptureSnapshot(
    val packageName: String,
    val activity: String?,
    val ts: Long,
    val root: CaptureNode,
)

/**
 * Turns a capture snapshot into events. There is one parser per app that we know how
 * to read (its message list, its order page), and a generic fallback that records the
 * visible text so the debug timeline and the eval harness can see what was on screen.
 */
interface CaptureParser {
    fun parse(snapshot: CaptureSnapshot): List<Event>
}

class CaptureParserRegistry(
    private val parsers: Map<String, CaptureParser> = emptyMap(),
    private val fallback: CaptureParser = GenericCaptureParser(),
) {
    fun parse(snapshot: CaptureSnapshot): List<Event> =
        (parsers[snapshot.packageName] ?: fallback).parse(snapshot)
}

/**
 * Fallback parser: one SCREEN_STATE event with the visible text lines, identified by
 * the app, the activity, and the text, so the same screen observed repeatedly is one
 * row. The raw tree is not kept; this is the "parsed, not hoarded" rule from the plan.
 */
class GenericCaptureParser(private val maxChars: Int = 4000) : CaptureParser {
    override fun parse(snapshot: CaptureSnapshot): List<Event> {
        val lines = visibleText(snapshot.root)
        if (lines.isEmpty()) return emptyList()
        val text = lines.joinToString("\n").take(maxChars)
        return listOf(
            Event(
                id = EventId.of(snapshot.ts, snapshot.packageName, CHANNEL, snapshot.activity, text),
                ts = snapshot.ts,
                sourceApp = snapshot.packageName,
                channel = CHANNEL,
                kind = EventKind.SCREEN_STATE,
                text = text,
                structured = buildMap {
                    snapshot.activity?.let { put("activity", it) }
                    put("lines", lines.size.toString())
                },
                trust = Trust.UNTRUSTED,
            ),
        )
    }

    companion object {
        const val CHANNEL = "screen"

        /** Depth-first visible text, trimmed, with consecutive duplicates collapsed. */
        fun visibleText(root: CaptureNode): List<String> {
            val out = ArrayList<String>()
            fun walk(n: CaptureNode) {
                if (!n.visible) return
                val t = (n.text ?: n.contentDescription)?.trim()
                if (!t.isNullOrEmpty() && out.lastOrNull() != t) out.add(t)
                n.children.forEach(::walk)
            }
            walk(root)
            return out
        }
    }
}
