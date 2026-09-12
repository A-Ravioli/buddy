package buddy.ledger

/**
 * The smallest SQL surface the ledger needs. Two implementations: JDBC for the JVM
 * (tests, tooling, the replay harness) and the Android framework's SQLiteDatabase on
 * the phone. Keeping this interface tiny is what lets the schema and the append-only
 * rules be tested off-device against real SQLite.
 */
interface SqlDriver {
    fun exec(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (Row) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    fun close()
}

interface Row {
    fun string(index: Int): String?
    fun long(index: Int): Long?
}
