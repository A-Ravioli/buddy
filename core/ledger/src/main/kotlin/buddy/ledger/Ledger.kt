package buddy.ledger

/**
 * The life ledger: an append-only store of [Event]s.
 *
 * There is no update and no delete. A correction is a new event with [Event.supersedes]
 * set. [append] is idempotent on [Event.id], so perception sources can re-deliver
 * freely.
 */
interface Ledger {
    /** Appends the event. Returns false if an event with the same id already exists. */
    fun append(event: Event): Boolean

    /** Appends all events in one transaction. Returns how many were new. */
    fun appendAll(events: Iterable<Event>): Int

    fun get(id: String): Event?

    /** Most recent events by [Event.ts], newest first, optionally only those before [beforeTs]. */
    fun recent(limit: Int, beforeTs: Long? = null): List<Event>

    /** Events in a thread, oldest first. */
    fun thread(threadId: String, limit: Int = 200): List<Event>

    /** Full-text search over text and actor. Newest first. */
    fun search(query: String, limit: Int = 50): List<Event>

    /** Events that supersede the given one, oldest first. Empty when it stands. */
    fun corrections(id: String): List<Event>

    fun count(): Long
}
