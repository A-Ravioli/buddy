package buddy.entities

import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.Row
import buddy.ledger.SqlDriver
import buddy.ledger.Trust

/** A person, as the graph currently understands them. */
data class Person(
    val id: Long,
    val displayName: String?,
    /** Identity keys from [Identity.key], all resolving to this person. */
    val identities: Set<String>,
    /** "family", "close", "friend", "colleague", "service", "unknown". Set by the user or memory notes. */
    val relationship: String = "unknown",
    val eventCount: Long = 0,
    val lastSeenTs: Long = 0,
    /** Messages the user has sent to this person. Zero means the user has never contacted them. */
    val userMessages: Long = 0,
)

/** A conversation across sources. */
data class Thread(
    val threadId: String,
    val sourceApp: String,
    val personId: Long?,
    val eventCount: Long,
    val lastTs: Long,
    /** Who spoke last: "me", "them", or null. Reply expectation follows from this. */
    val lastActor: String?,
)

/** A recurring counterparty and amount, detected from transactions. */
data class Recurring(
    val counterparty: String,
    val amount: String,
    val currency: String,
    val occurrences: Int,
    val medianIntervalDays: Double,
    val lastTs: Long,
) {
    val nextExpectedTs: Long get() = lastTs + (medianIntervalDays * 86_400_000L).toLong()
}

/**
 * Entity graph v0 over the ledger's SQL driver. Tables live beside `events`; they are
 * mutable and fully derivable from the ledger by [rebuild].
 */
class EntityStore(private val db: SqlDriver) {

    init {
        db.transaction {
            db.exec(
                """
                CREATE TABLE IF NOT EXISTS people (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    display_name TEXT,
                    relationship TEXT NOT NULL DEFAULT 'unknown',
                    event_count INTEGER NOT NULL DEFAULT 0,
                    last_seen_ts INTEGER NOT NULL DEFAULT 0,
                    user_messages INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.exec(
                """
                CREATE TABLE IF NOT EXISTS identities (
                    key TEXT NOT NULL,
                    person_id INTEGER NOT NULL REFERENCES people(id),
                    PRIMARY KEY (key, person_id)
                )
                """.trimIndent(),
            )
            db.exec("CREATE INDEX IF NOT EXISTS identities_person ON identities(person_id)")
            db.exec(
                """
                CREATE TABLE IF NOT EXISTS threads (
                    thread_id TEXT PRIMARY KEY NOT NULL,
                    source_app TEXT NOT NULL,
                    person_id INTEGER,
                    event_count INTEGER NOT NULL DEFAULT 0,
                    last_ts INTEGER NOT NULL DEFAULT 0,
                    last_actor TEXT
                )
                """.trimIndent(),
            )
            db.exec("CREATE TABLE IF NOT EXISTS entity_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    // ---- people ----------------------------------------------------------------

    /** The person an identity key resolves to, or null if unknown or ambiguous (a name shared by several people). */
    fun personFor(actorKey: String): Person? =
        db.query("SELECT DISTINCT person_id FROM identities WHERE key = ?", listOf(actorKey)) { it.long(0)!! }
            .singleOrNull()?.let(::person)

    fun person(id: Long): Person? {
        val row = db.query("SELECT id, display_name, relationship, event_count, last_seen_ts, user_messages FROM people WHERE id = ?", listOf(id)) { r ->
            Person(r.long(0)!!, r.string(1), emptySet(), r.string(2)!!, r.long(3)!!, r.long(4)!!, r.long(5)!!)
        }.firstOrNull() ?: return null
        val ids = db.query("SELECT key FROM identities WHERE person_id = ?", listOf(id)) { it.string(0)!! }.toSet()
        return row.copy(identities = ids)
    }

    fun people(limit: Int = 500): List<Person> =
        db.query("SELECT id FROM people ORDER BY last_seen_ts DESC LIMIT ?", listOf(limit)) { it.long(0)!! }.mapNotNull(::person)

    /**
     * Finds or creates the person for an actor, then records the sighting. Name-only
     * actors merge into an existing person only when exactly one person has that
     * name; otherwise they get their own record.
     */
    fun observe(actorRaw: String, displayName: String?, ts: Long): Person = db.transaction {
        val key = Identity.key(actorRaw)
        // Phone and email keys are unique per person; a name key may be shared, in which
        // case it cannot identify anyone on its own.
        var id = db.query("SELECT DISTINCT person_id FROM identities WHERE key = ?", listOf(key)) { it.long(0)!! }.singleOrNull()
        if (id == null && displayName != null) {
            val nameKey = "name:${Identity.normalizeName(displayName)}"
            val byName = db.query("SELECT DISTINCT person_id FROM identities WHERE key = ?", listOf(nameKey)) { it.long(0)!! }
            if (byName.size == 1) id = byName[0]
        }
        if (id == null) {
            db.exec("INSERT INTO people(display_name, last_seen_ts) VALUES (?, ?)", listOf(displayName ?: actorRaw.takeIf { key.startsWith("name:") }, ts))
            id = db.query("SELECT last_insert_rowid()") { it.long(0)!! }.first()
        }
        db.exec("INSERT OR IGNORE INTO identities(key, person_id) VALUES (?, ?)", listOf(key, id))
        if (displayName != null) {
            db.exec("INSERT OR IGNORE INTO identities(key, person_id) VALUES (?, ?)", listOf("name:${Identity.normalizeName(displayName)}", id))
            db.exec("UPDATE people SET display_name = COALESCE(display_name, ?) WHERE id = ?", listOf(displayName, id))
        }
        db.exec("UPDATE people SET event_count = event_count + 1, last_seen_ts = MAX(last_seen_ts, ?) WHERE id = ?", listOf(ts, id))
        person(id)!!
    }

    /** Merges [from] into [into]: identities move, counts add, [from] is deleted. Undone by rebuilding. */
    fun merge(into: Long, from: Long) = db.transaction {
        if (into == from) return@transaction
        db.exec("UPDATE identities SET person_id = ? WHERE person_id = ?", listOf(into, from))
        db.exec("UPDATE threads SET person_id = ? WHERE person_id = ?", listOf(into, from))
        db.exec(
            """
            UPDATE people SET
                event_count = event_count + (SELECT event_count FROM people WHERE id = ?),
                user_messages = user_messages + (SELECT user_messages FROM people WHERE id = ?),
                last_seen_ts = MAX(last_seen_ts, (SELECT last_seen_ts FROM people WHERE id = ?)),
                display_name = COALESCE(display_name, (SELECT display_name FROM people WHERE id = ?))
            WHERE id = ?
            """.trimIndent(),
            listOf(from, from, from, from, into),
        )
        db.exec("DELETE FROM people WHERE id = ?", listOf(from))
    }

    fun setRelationship(personId: Long, relationship: String) =
        db.exec("UPDATE people SET relationship = ? WHERE id = ?", listOf(relationship, personId))

    // ---- threads ---------------------------------------------------------------

    fun thread(threadId: String): Thread? =
        db.query("SELECT thread_id, source_app, person_id, event_count, last_ts, last_actor FROM threads WHERE thread_id = ?", listOf(threadId), ::threadRow).firstOrNull()

    /** Threads where the other side spoke last and nothing has happened since [olderThanTs]. */
    fun awaitingReply(olderThanTs: Long, limit: Int = 50): List<Thread> =
        db.query(
            "SELECT thread_id, source_app, person_id, event_count, last_ts, last_actor FROM threads WHERE last_actor = 'them' AND last_ts < ? ORDER BY last_ts ASC LIMIT ?",
            listOf(olderThanTs, limit), ::threadRow,
        )

    private fun threadRow(r: Row) = Thread(r.string(0)!!, r.string(1)!!, r.long(2), r.long(3)!!, r.long(4)!!, r.string(5))

    // ---- ingestion -------------------------------------------------------------

    /** Updates people and threads from one event. Idempotent only per [rebuild]; call once per new event. */
    fun apply(e: Event) {
        if (e.kind != EventKind.MESSAGE && e.kind != EventKind.CALL && e.kind != EventKind.CALENDAR_CHANGE) return
        val counterparty = e.structured["counterparty"] ?: e.actor?.takeIf { e.trust != Trust.USER }
        val person = counterparty?.takeIf { it != "me" }?.let { observe(it, e.structured["cached_name"], e.ts) }
        if (person != null && e.trust == Trust.USER && e.kind == EventKind.MESSAGE) {
            db.exec("UPDATE people SET user_messages = user_messages + 1 WHERE id = ?", listOf(person.id))
        }
        val threadId = e.threadId ?: return
        val lastActor = when {
            e.trust == Trust.USER -> "me"
            e.kind == EventKind.MESSAGE -> "them"
            else -> null
        }
        db.exec(
            """
            INSERT INTO threads(thread_id, source_app, person_id, event_count, last_ts, last_actor)
            VALUES (?, ?, ?, 1, ?, ?)
            ON CONFLICT(thread_id) DO UPDATE SET
                event_count = event_count + 1,
                person_id = COALESCE(excluded.person_id, person_id),
                last_actor = CASE WHEN excluded.last_ts >= last_ts THEN COALESCE(excluded.last_actor, last_actor) ELSE last_actor END,
                last_ts = MAX(last_ts, excluded.last_ts)
            """.trimIndent(),
            listOf(threadId, e.sourceApp, person?.id, e.ts, lastActor),
        )
    }

    /** Drops every derived row and replays [events] in order. The graph is a view. */
    fun rebuild(events: Sequence<Event>) = db.transaction {
        db.exec("DELETE FROM identities")
        db.exec("DELETE FROM threads")
        db.exec("DELETE FROM people")
        events.forEach(::apply)
    }

    // ---- recurring -------------------------------------------------------------

    /**
     * Recurring charges: same counterparty and amount seen at least [minOccurrences]
     * times with a roughly regular interval. Pure over the given events.
     */
    fun recurring(events: List<Event>, minOccurrences: Int = 3, maxJitter: Double = 0.35): List<Recurring> {
        val groups = events
            .filter { it.structured["amount"] != null && (it.structured["counterparty"] ?: it.actor) != null }
            .groupBy { Triple(it.structured["counterparty"] ?: it.actor!!, it.structured["amount"]!!, it.structured["currency"] ?: "") }
        return groups.mapNotNull { (k, es) ->
            if (es.size < minOccurrences) return@mapNotNull null
            val ts = es.map { it.ts }.sorted()
            val gaps = ts.zipWithNext { a, b -> (b - a) / 86_400_000.0 }
            val median = gaps.sorted().let { g -> if (g.size % 2 == 1) g[g.size / 2] else (g[g.size / 2 - 1] + g[g.size / 2]) / 2 }
            if (median < 1.0) return@mapNotNull null
            if (gaps.any { kotlin.math.abs(it - median) / median > maxJitter }) return@mapNotNull null
            Recurring(k.first, k.second, k.third, es.size, median, ts.last())
        }.sortedBy { it.nextExpectedTs }
    }
}
