package buddy.ledger

/**
 * Who authored the content of an event. The policy engine and the prompt assembly both
 * key off this, so it is a closed set.
 *
 * Everything that arrived from outside the phone is UNTRUSTED: a message, a
 * notification, a calendar invite, text on a screen, a voice that is not the user's.
 * USER is the user's own words and actions. SYSTEM is buddy's own records (actions it
 * took, notes it wrote), which are trusted as facts about buddy but never as
 * instructions.
 */
enum class Trust { UNTRUSTED, USER, SYSTEM }

/** The closed set of event kinds. New kinds are added here, never invented ad hoc. */
enum class EventKind {
    /** A message in a conversation: SMS, chat, email. */
    MESSAGE,
    /** A notification that is not a conversation message. */
    NOTIFICATION,
    /** A calendar instance appeared, changed, or was removed. */
    CALENDAR_CHANGE,
    /** A phone or VoIP call started, ended, or was missed. */
    CALL,
    /** A location fix. */
    LOCATION,
    /** Something someone said, transcribed on the device. */
    UTTERANCE,
    /** Screen content observed through content capture, already parsed. */
    SCREEN_STATE,
    /** Battery, connectivity, charging, screen on/off, and similar device facts. */
    DEVICE_STATE,
    /** A durable observation written by memory consolidation. */
    MEMORY_NOTE,
    /** An action buddy took, with its reason. */
    ACTION,
    /** A user correction of an earlier event or action. */
    CORRECTION,
    /** A triage decision about another event (see core/triage). */
    TRIAGE,
    /** A brief produced by a planning cycle (see core/cognition). */
    BRIEF,
}

/**
 * One record in the life ledger. Events are immutable and the ledger is append-only;
 * a correction is a new event whose [supersedes] points at the old one.
 *
 * [structured] is a flat map of parsed fields (amount, due date, tracking number,
 * counterparty) that the on-device extractors fill in. It is a string map on purpose:
 * the ledger stores what was observed, and typing happens in the entity graph.
 */
data class Event(
    val id: String,
    /** Epoch milliseconds of when the thing happened (not when it was recorded). */
    val ts: Long,
    /** Package name of the app the event came from, or "buddy" for buddy's own records. */
    val sourceApp: String,
    /** The stream inside the app: "sms", "inbox", "calendar", "speech", "screen". */
    val channel: String,
    val kind: EventKind,
    /** Raw sender or speaker identifier. Identity resolution happens later, in the entity graph. */
    val actor: String? = null,
    /** Conversation grouping key, stable across events in the same thread. */
    val threadId: String? = null,
    val text: String? = null,
    val structured: Map<String, String> = emptyMap(),
    val trust: Trust,
    /** Pointer back to the original: a content URI, a notification key, a call log id. Never raw audio. */
    val rawRef: String? = null,
    /** Id of the event this one corrects or replaces. */
    val supersedes: String? = null,
) {
    init {
        require(id.isNotBlank()) { "event id must not be blank" }
        require(sourceApp.isNotBlank()) { "sourceApp must not be blank" }
        require(channel.isNotBlank()) { "channel must not be blank" }
        require(ts > 0) { "ts must be a positive epoch millisecond value" }
    }
}
