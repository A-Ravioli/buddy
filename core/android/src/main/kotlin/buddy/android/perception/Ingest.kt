package buddy.android.perception

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import buddy.ledger.Event

/**
 * The single entry point perception sources use to record events. Serialises writes
 * onto one background thread, and holds events in memory until the ledger is
 * available after first unlock.
 */
object Ingest {
    private val thread = HandlerThread("buddy-ingest").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = ArrayList<Event>()

    fun init(context: Context) {
        LedgerHolder.whenAvailable { handler.post { flush() } }
    }

    fun submit(events: List<Event>) {
        if (events.isEmpty()) return
        handler.post {
            val ledger = LedgerHolder.getOrNull()
            if (ledger == null) {
                pending.addAll(events)
                if (pending.size > MAX_PENDING) pending.subList(0, pending.size - MAX_PENDING).clear()
                return@post
            }
            flush()
            val n = ledger.appendAll(events)
            if (n > 0) Log.d(BuddyApp.TAG, "ingest: $n new of ${events.size} (${events.first().sourceApp}/${events.first().channel})")
        }
    }

    fun submit(event: Event?) {
        if (event != null) submit(listOf(event))
    }

    private fun flush() {
        val ledger = LedgerHolder.getOrNull() ?: return
        if (pending.isEmpty()) return
        val n = ledger.appendAll(pending)
        Log.i(BuddyApp.TAG, "ingest: flushed ${pending.size} pre-unlock events, $n new")
        pending.clear()
    }

    private const val MAX_PENDING = 5000
}
