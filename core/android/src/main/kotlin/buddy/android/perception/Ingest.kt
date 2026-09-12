package buddy.android.perception

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
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
            triage(events)
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
        triage(pending)
        pending.clear()
    }

    /**
     * Phase 1: every recorded event is triaged and the decision recorded. The recorder
     * is idempotent, so events the notification assistant already triaged cost one
     * ignored append.
     */
    private fun triage(events: List<Event>) {
        val recorder = Brain.triage ?: return
        for (e in events) {
            try {
                recorder.process(e, Brain.profile)
            } catch (t: Throwable) {
                Log.w(BuddyApp.TAG, "triage failed for ${e.id}", t)
            }
        }
    }

    private const val MAX_PENDING = 5000
}
