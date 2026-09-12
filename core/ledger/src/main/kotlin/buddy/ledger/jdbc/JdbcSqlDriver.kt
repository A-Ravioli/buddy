package buddy.ledger.jdbc

import buddy.ledger.Row
import buddy.ledger.SqlDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * [SqlDriver] over JDBC. Used for tests, host-side tooling, and the replay harness.
 * The caller supplies the JDBC driver on the classpath (sqlite-jdbc in tests).
 */
class JdbcSqlDriver(private val conn: Connection) : SqlDriver {

    override fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val out = ArrayList<T>()
                val row = JdbcRow(rs)
                while (rs.next()) out.add(map(row))
                out
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (!conn.autoCommit) return block() // already inside a transaction
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    }

    private class JdbcRow(private val rs: ResultSet) : Row {
        override fun string(index: Int): String? = rs.getString(index + 1)
        override fun long(index: Int): Long? = rs.getLong(index + 1).let { if (rs.wasNull()) null else it }
    }

    companion object {
        fun sqlite(path: String): JdbcSqlDriver =
            JdbcSqlDriver(DriverManager.getConnection("jdbc:sqlite:$path")).also {
                it.exec("PRAGMA journal_mode = WAL")
                it.exec("PRAGMA foreign_keys = ON")
            }
        fun inMemory(): JdbcSqlDriver = sqlite(":memory:")
    }
}
