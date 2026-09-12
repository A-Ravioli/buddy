package buddy.android.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.UserManager
import android.util.Log
import buddy.android.BuddyApp
import buddy.ledger.Ledger
import buddy.ledger.SqliteLedger
import java.io.File

/**
 * Owns the one ledger database.
 *
 * The file lives in credential-encrypted storage (the app's normal data directory), so
 * it is encrypted at rest with a key the hardware keystore binds to the lock
 * credential, and it cannot be opened until the user has unlocked the device once
 * since boot. That is the "encrypted, bound to the lock credential" requirement from
 * the plan, provided by the platform rather than a second encryption layer.
 */
object LedgerHolder {
    private lateinit var appContext: Context

    @Volatile
    private var ledger: Ledger? = null
    private val listeners = ArrayList<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The ledger, or null before the first unlock. */
    fun getOrNull(): Ledger? = ledger

    fun get(): Ledger = ledger ?: error("ledger not available before user unlock")

    /** Runs [block] now if the ledger is open, otherwise once it opens. */
    fun whenAvailable(block: () -> Unit) {
        val l = ledger
        if (l != null) {
            block()
        } else {
            synchronized(listeners) { listeners.add(block) }
            // Re-check after registering, in case it opened in between.
            if (ledger != null) drainListeners()
        }
    }

    @Synchronized
    fun onUserUnlocked() {
        if (ledger != null) return
        if (!appContext.getSystemService(UserManager::class.java).isUserUnlocked) return
        val dir = File(appContext.filesDir, "ledger").apply { mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(File(dir, "ledger.db"), null)
        db.enableWriteAheadLogging()
        ledger = SqliteLedger(FrameworkSqlDriver(db))
        Log.i(BuddyApp.TAG, "ledger opened, ${ledger!!.count()} events")
        drainListeners()
    }

    private fun drainListeners() {
        val pending = synchronized(listeners) { ArrayList(listeners).also { listeners.clear() } }
        pending.forEach { it() }
    }
}
