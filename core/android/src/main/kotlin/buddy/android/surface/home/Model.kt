package buddy.android.surface.home

import buddy.android.surface.theme.SurfaceDomain

/**
 * What the chat can hold. Every escalation is a decision with buddy's recommendation and
 * one tap to resolve. Everything else is a receipt you can undo.
 */
sealed interface ChatItem {
    val id: String
}

data class BuddySays(override val id: String, val text: String) : ChatItem
data class UserSays(override val id: String, val text: String, val viaEarbuds: Boolean = false) : ChatItem

data class Decision(
    override val id: String,
    val domain: SurfaceDomain,
    val source: String,
    val title: String,
    val why: String,
    /** buddy's recommendation. The filled button. */
    val accept: String,
    val decline: String,
) : ChatItem

/** A message from a close contact with buddy's draft. Never sent without a tap. */
data class SuggestedReply(
    override val id: String,
    val domain: SurfaceDomain,
    val source: String,
    val quote: String,
    val reply: String,
) : ChatItem

/** Level 2: an action that runs after a hold window unless you stop it. */
data class Hold(
    override val id: String,
    val domain: SurfaceDomain,
    val source: String,
    val title: String,
    val why: String,
    val fraction: Float,
    val stop: String = "Stop",
    val now: String,
) : ChatItem

data class RecallLine(val text: String, val source: String)
data class Recall(override val id: String, val lines: List<RecallLine>, val actions: List<String>) : ChatItem

data class HandledRow(val domain: SurfaceDomain, val label: String, val text: String)
data class Handled(override val id: String, val since: String, val rows: List<HandledRow>) : ChatItem

data class Receipt(override val id: String, val domain: SurfaceDomain, val text: String, val fix: String = "Fix it") : ChatItem

data class ComingUp(override val id: String, val rows: List<Pair<String, String>>) : ChatItem

enum class Affordance { UNDO, FIX, LISTEN, KEPT }

data class TimelineEntry(
    val id: String,
    val time: String,
    val domain: SurfaceDomain,
    val what: String,
    val why: String,
    val affordance: Affordance?,
)

/** Everything the surface draws. Built by SurfaceStore from the ledger and the last plan. */
data class SurfaceState(
    val items: List<ChatItem> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    /** One line for the header: when the last brief was. */
    val status: String = "",
    val handledToday: Int = 0,
    val nextUp: String = "",
    /** The ledger opens after the first unlock; until then there is nothing to show. */
    val locked: Boolean = false,
) {
    val needsYou: Int get() = items.count { it is Decision || it is SuggestedReply || it is Hold }
}
