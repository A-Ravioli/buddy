package buddy.android.cognition

import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import buddy.cognition.Envelope
import buddy.cognition.TriageRecorder
import buddy.cognition.WakeReport
import buddy.ledger.Event
import buddy.tasks.Task
import buddy.tasks.Wake
import buddy.tasks.WakeReason
import buddy.tasks.Waker
import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * The scheduler (docs/07, section 5): the thing that decides when the agent is awake.
 *
 * It replaces "two alarms a day" with a queue of reasons. The two that matter most
 * are the cheap ones: an event triaged ACT_NOW wakes the agent within the minute
 * instead of waiting up to twelve hours for the next cycle, and an event a parked
 * task said it was waiting for wakes that task the moment it lands.
 *
 * Wakes run one at a time on one thread. The agent is not re-entrant — two wakes
 * moving the same task at once is how a state machine stops being one — and there is
 * nothing to gain from parallelism when every wake ends in a network call anyway.
 */
object Wakes {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "buddy-wake") }
    private val waker = Waker()

    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /**
     * Called by [buddy.android.perception.Ingest] once new events are recorded and
     * triaged. Two questions per event, both index lookups: is this work, and is
     * anything waiting for it?
     */
    fun onEvents(events: List<Event>, decisions: Map<String, TriageDecision>) {
        if (events.isEmpty()) return
        val tasks = Brain.tasks ?: return
        val now = System.currentTimeMillis()

        val open = try {
            tasks.open()
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "wake: could not read tasks", t)
            return
        }

        // A signalled task wakes on the event it was waiting for, whatever triage made
        // of it: the task already decided this event matters.
        val signalled = events.flatMap { e -> waker.signalled(open, e, now).map { it to e } }
        for ((wake, event) in signalled.distinctBy { it.first.taskId }) {
            tasks.get(wake.taskId ?: continue)?.let { tasks.resume(it, wake.detail) }
            submit(wake, listOf(event), decisions)
        }

        val work = events.filter { decisions[it.id]?.klass == TriageClass.ACT_NOW }
        if (work.isNotEmpty()) {
            val urgent = work.any { decisions[it.id]?.urgent == true }
            submit(
                Wake(
                    WakeReason.WORK, now, null, work.map { it.id },
                    "${work.size} item${if (work.size == 1) "" else "s"} triaged act-now" + if (urgent) ", one urgent" else "",
                ),
                work,
                decisions,
            )
        }
    }

    /**
     * The five-minute tick, shared with the hold-release alarm. Wakes tasks whose own
     * clock came round, and sweeps the ones that have stopped being jobs.
     */
    fun tick() {
        val tasks = Brain.tasks ?: return
        val agent = Brain.agent ?: return
        val now = System.currentTimeMillis()
        val open = tasks.open()

        for (wake in waker.due(open, now)) {
            tasks.get(wake.taskId ?: continue)?.let { tasks.resume(it, "the time came round") }
            submit(wake, emptyList(), emptyMap())
        }

        val stale: List<Task> = waker.stale(tasks.open(), now)
        if (stale.isNotEmpty()) {
            io.execute {
                try {
                    val swept = agent.sweep(stale, now)
                    Log.i(BuddyApp.TAG, "wake: swept ${swept.size} stale task(s)")
                    refreshSurface()
                } catch (t: Throwable) {
                    Log.e(BuddyApp.TAG, "sweep failed", t)
                }
            }
        }
    }

    /** The person said something. The agent answers before anything else runs. */
    fun onUserTurn(text: String) {
        submit(Wake(WakeReason.USER_TURN, System.currentTimeMillis(), null, emptyList(), "the person is typing"), emptyList(), emptyMap(), userTurn = text)
    }

    /** A planning cycle. The agent reviews its jobs; the brief is written after it. */
    fun onCycle(cycle: String, window: List<Event>, decisions: Map<String, TriageDecision>) {
        runNow(
            Wake(WakeReason.CYCLE, System.currentTimeMillis(), null, window.map { it.id }, "$cycle cycle"),
            window,
            decisions,
        )
    }

    private fun submit(
        wake: Wake,
        triggers: List<Event>,
        decisions: Map<String, TriageDecision>,
        userTurn: String? = null,
    ) {
        io.execute { runNow(wake, triggers, decisions, userTurn) }
    }

    /** Runs a wake on the calling thread. Used by the cycle, which already has one. */
    fun runNow(
        wake: Wake,
        triggers: List<Event>,
        decisions: Map<String, TriageDecision>,
        userTurn: String? = null,
    ): WakeReport? {
        val agent = Brain.agent ?: return null
        val ledger = LedgerHolder.getOrNull() ?: return null
        return try {
            val now = System.currentTimeMillis()
            val hour = ZonedDateTime.now().hour
            // Everything the window knows to be a code, so the policy engine can deny a
            // payload carrying one even if the model never saw it.
            val codes = triggers.flatMap(Envelope::codes).toSet() +
                ledger.recent(200).flatMap(Envelope::codes).toSet()
            val report = agent.wake(wake, triggers, decisions, now, hour, codes, userTurn = userTurn)
            Log.i(
                BuddyApp.TAG,
                "wake ${wake.reason}: ${report.outcomes.size} proposal(s), " +
                    "${report.tasksTouched.size} task(s), agent=${report.agent?.status ?: report.detail}",
            )
            refreshSurface()
            report
        } catch (t: Throwable) {
            Log.e(BuddyApp.TAG, "wake ${wake.reason} failed", t)
            null
        }
    }

    private fun refreshSurface() {
        try {
            buddy.android.surface.SurfaceStore.refresh()
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "surface refresh after wake failed", t)
        }
    }

    /** The triage decisions for a window, read back from the ledger. */
    fun decisionsFor(window: List<Event>): Map<String, TriageDecision> =
        window.filter { it.kind == buddy.ledger.EventKind.TRIAGE }
            .mapNotNull(TriageRecorder::fromEvent)
            .associateBy { it.eventId }
}
