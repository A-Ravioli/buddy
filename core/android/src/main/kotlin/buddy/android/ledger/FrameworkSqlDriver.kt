package buddy.android.ledger

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQuery
import buddy.ledger.Row
import buddy.ledger.SqlDriver

/**
 * [SqlDriver] over the framework's SQLite. Typed binding for both statements and
 * queries, so integer parameters (timestamps, limits) stay integers instead of being
 * stringified as rawQuery would do.
 */
class FrameworkSqlDriver(private val db: SQLiteDatabase) : SqlDriver {

    override fun exec(sql: String, args: List<Any?>) {
        db.compileStatement(sql).use { st ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.bindNull(i + 1)
                    is Long -> st.bindLong(i + 1, a)
                    is Int -> st.bindLong(i + 1, a.toLong())
                    is Boolean -> st.bindLong(i + 1, if (a) 1 else 0)
                    is Double -> st.bindDouble(i + 1, a)
                    is Float -> st.bindDouble(i + 1, a.toDouble())
                    is ByteArray -> st.bindBlob(i + 1, a)
                    else -> st.bindString(i + 1, a.toString())
                }
            }
            st.execute()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bind(query, args)
            android.database.sqlite.SQLiteCursor(driver, editTable, query)
        }
        db.rawQueryWithFactory(factory, sql, null, null).use { c ->
            val out = ArrayList<T>(c.count)
            val row = CursorRow(c)
            while (c.moveToNext()) out.add(map(row))
            return out
        }
    }

    private fun bind(q: SQLiteQuery, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> q.bindNull(i + 1)
                is Long -> q.bindLong(i + 1, a)
                is Int -> q.bindLong(i + 1, a.toLong())
                is Boolean -> q.bindLong(i + 1, if (a) 1 else 0)
                is Double -> q.bindDouble(i + 1, a)
                is Float -> q.bindDouble(i + 1, a.toDouble())
                is ByteArray -> q.bindBlob(i + 1, a)
                else -> q.bindString(i + 1, a.toString())
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private class CursorRow(private val c: Cursor) : Row {
        override fun string(index: Int): String? = if (c.isNull(index)) null else c.getString(index)
        override fun long(index: Int): Long? = if (c.isNull(index)) null else c.getLong(index)
    }
}
