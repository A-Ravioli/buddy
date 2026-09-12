package buddy.automation

import buddy.perception.capture.CaptureNode
import buddy.perception.capture.CaptureSnapshot
import buddy.policy.ActionSpec

/** How a step finds a node on the screen. Text matches are case-insensitive and trimmed. */
sealed class Match {
    data class Text(val contains: String) : Match()
    data class Id(val resourceId: String) : Match()
    data class Desc(val contains: String) : Match()
    data class Hint(val contains: String) : Match()

    fun matches(n: CaptureNode): Boolean = when (this) {
        is Text -> n.text?.contains(contains, ignoreCase = true) == true
        is Id -> n.resourceId == resourceId
        is Desc -> n.contentDescription?.contains(contains, ignoreCase = true) == true
        is Hint -> n.hint?.contains(contains, ignoreCase = true) == true
    }
}

/** One step of a recipe. Strings may contain `{param}` placeholders filled at run time. */
sealed class Step {
    data class Launch(val packageName: String) : Step()
    data class WaitFor(val match: Match, val timeoutMs: Long = 8_000) : Step()
    data class Tap(val match: Match) : Step()
    data class Type(val match: Match, val text: String) : Step()
    data class Scroll(val down: Boolean = true) : Step()
    /** The screen must show this, or the run aborts as drift. */
    data class Verify(val match: Match, val meaning: String) : Step()
    data object Back : Step()
}

/**
 * A recipe is a per-app procedure for one action (docs/01-architecture.md, "Actuation",
 * path 3). Recipes are data: they are written per app, tested against captured
 * screens, and run by the interpreter with the same policy classification as any
 * other action.
 */
data class Recipe(
    val name: String,
    val packageName: String,
    val spec: ActionSpec,
    val steps: List<Step>,
    /** Parameters the steps reference. */
    val params: List<String> = emptyList(),
)

/** A node found on a screen, with its position in the tree for the driver. */
data class NodeRef(val node: CaptureNode, val path: List<Int>)

/** What the interpreter needs from the phone. The test driver is scripted; the phone's injects input. */
interface Driver {
    fun launch(packageName: String): Boolean
    fun screen(): CaptureSnapshot?
    fun tap(ref: NodeRef): Boolean
    fun type(ref: NodeRef, text: String): Boolean
    fun scroll(down: Boolean): Boolean
    fun back(): Boolean
    fun sleep(ms: Long)
    fun now(): Long
}

object Screens {
    /** Depth-first search for the first visible node matching. */
    fun find(snapshot: CaptureSnapshot, match: Match): NodeRef? {
        fun walk(n: CaptureNode, path: List<Int>): NodeRef? {
            if (!n.visible) return null
            if (match.matches(n)) return NodeRef(n, path)
            n.children.forEachIndexed { i, c -> walk(c, path + i)?.let { return it } }
            return null
        }
        return walk(snapshot.root, emptyList())
    }
}
