package buddy.ledger

/**
 * [Ledger] over SQLite via a [SqlDriver].
 *
 * Append-only is enforced in the database, not just in this class: triggers abort any
 * UPDATE or DELETE on the events table. Full-text search is an FTS5 external-content
 * index kept in sync by a trigger, so the text is stored once.
 */
class SqliteLedger(private val db: SqlDriver) : Ledger {

    init {
        migrate()
    }

    private fun migrate() {
        // Journal mode is the driver's business (JDBC sets WAL on open; the Android
        // driver uses enableWriteAheadLogging), because PRAGMA statements that return
        // rows cannot be executed uniformly across the two.
        db.transaction {
            db.exec(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id          TEXT PRIMARY KEY NOT NULL,
                    ts          INTEGER NOT NULL,
                    source_app  TEXT NOT NULL,
                    channel     TEXT NOT NULL,
                    kind        TEXT NOT NULL,
                    actor       TEXT,
                    thread_id   TEXT,
                    text        TEXT,
                    structured  TEXT NOT NULL DEFAULT '{}',
                    trust       TEXT NOT NULL,
                    raw_ref     TEXT,
                    supersedes  TEXT,
                    inserted_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.exec("CREATE INDEX IF NOT EXISTS events_ts ON events(ts DESC)")
            db.exec("CREATE INDEX IF NOT EXISTS events_thread ON events(thread_id, ts)")
            db.exec("CREATE INDEX IF NOT EXISTS events_source ON events(source_app, channel, ts)")
            db.exec("CREATE INDEX IF NOT EXISTS events_supersedes ON events(supersedes)")
            db.exec(
                """
                CREATE TRIGGER IF NOT EXISTS events_no_update BEFORE UPDATE ON events
                BEGIN SELECT RAISE(ABORT, 'ledger is append-only'); END
                """.trimIndent(),
            )
            db.exec(
                """
                CREATE TRIGGER IF NOT EXISTS events_no_delete BEFORE DELETE ON events
                BEGIN SELECT RAISE(ABORT, 'ledger is append-only'); END
                """.trimIndent(),
            )
            db.exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
                    text, actor, content='events', content_rowid='rowid', tokenize='unicode61'
                )
                """.trimIndent(),
            )
            db.exec(
                """
                CREATE TRIGGER IF NOT EXISTS events_fts_insert AFTER INSERT ON events
                BEGIN INSERT INTO events_fts(rowid, text, actor) VALUES (new.rowid, new.text, new.actor); END
                """.trimIndent(),
            )
            db.exec("CREATE TABLE IF NOT EXISTS ledger_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.exec("INSERT OR IGNORE INTO ledger_meta(key, value) VALUES ('schema_version', '1')")
        }
    }

    override fun append(event: Event): Boolean = db.transaction { insert(event) }

    override fun appendAll(events: Iterable<Event>): Int = db.transaction {
        var n = 0
        for (e in events) if (insert(e)) n++
        n
    }

    private fun insert(e: Event): Boolean {
        db.exec(
            """
            INSERT OR IGNORE INTO events
                (id, ts, source_app, channel, kind, actor, thread_id, text, structured, trust, raw_ref, supersedes, inserted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                e.id, e.ts, e.sourceApp, e.channel, e.kind.name, e.actor, e.threadId, e.text,
                FlatJson.encode(e.structured), e.trust.name, e.rawRef, e.supersedes, System.currentTimeMillis(),
            ),
        )
        // changes() is the row count of the most recent statement on this connection:
        // 1 when INSERT OR IGNORE inserted, 0 when the id already existed.
        return db.query("SELECT changes()") { it.long(0) ?: 0L }.first() == 1L
    }

    override fun get(id: String): Event? =
        db.query("$SELECT WHERE id = ?", listOf(id), ::row).firstOrNull()

    override fun recent(limit: Int, beforeTs: Long?): List<Event> =
        if (beforeTs == null) {
            db.query("$SELECT ORDER BY ts DESC, id DESC LIMIT ?", listOf(limit), ::row)
        } else {
            db.query("$SELECT WHERE ts < ? ORDER BY ts DESC, id DESC LIMIT ?", listOf(beforeTs, limit), ::row)
        }

    override fun thread(threadId: String, limit: Int): List<Event> =
        db.query("$SELECT WHERE thread_id = ? ORDER BY ts ASC, id ASC LIMIT ?", listOf(threadId, limit), ::row)

    override fun search(query: String, limit: Int): List<Event> =
        db.query(
            """
            SELECT e.id, e.ts, e.source_app, e.channel, e.kind, e.actor, e.thread_id, e.text, e.structured,
                   e.trust, e.raw_ref, e.supersedes
            FROM events_fts f JOIN events e ON e.rowid = f.rowid
            WHERE events_fts MATCH ?
            ORDER BY e.ts DESC LIMIT ?
            """.trimIndent(),
            listOf(ftsQuery(query), limit),
            ::row,
        )

    override fun corrections(id: String): List<Event> =
        db.query("$SELECT WHERE supersedes = ? ORDER BY ts ASC, id ASC", listOf(id), ::row)

    override fun count(): Long = db.query("SELECT COUNT(*) FROM events") { it.long(0) ?: 0L }.first()

    private fun row(r: Row): Event = Event(
        id = r.string(0)!!,
        ts = r.long(1)!!,
        sourceApp = r.string(2)!!,
        channel = r.string(3)!!,
        kind = EventKind.valueOf(r.string(4)!!),
        actor = r.string(5),
        threadId = r.string(6),
        text = r.string(7),
        structured = FlatJson.decode(r.string(8) ?: "{}"),
        trust = Trust.valueOf(r.string(9)!!),
        rawRef = r.string(10),
        supersedes = r.string(11),
    )

    companion object {
        private const val SELECT =
            "SELECT id, ts, source_app, channel, kind, actor, thread_id, text, structured, trust, raw_ref, supersedes FROM events"

        /**
         * Turns free text into a conservative FTS5 query: each term quoted, joined with
         * implicit AND. Users and models never write FTS syntax directly, so operators
         * in the input are neutralised rather than interpreted.
         */
        fun ftsQuery(text: String): String =
            text.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" }
                .ifBlank { "\"\"" }
    }
}
