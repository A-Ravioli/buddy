package buddy.android.cognition

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.device.Arrival
import buddy.android.ledger.LedgerHolder
import buddy.android.surface.SurfaceStore
import buddy.cognition.Planned
import buddy.cognition.TriageRecorder
import buddy.ledger.EventKind
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * Runs the planning cycles: morning and evening by default. Each cycle takes the
 * events since the last brief, their recorded triage decisions, and asks the planner.
 * The brief is kept as a BRIEF event for the screen, and buddy announces it himself.
 */
object BriefScheduler {
    const val ACTION_CYCLE = "app.buddy.action.BRIEF_CYCLE"
    const val ACTION_HOLDS = "app.buddy.action.RELEASE_HOLDS"
    const val EXTRA_CYCLE = "cycle"

    /** Held so a cycle fired by an alarm can announce itself; set in [schedule]. */
    @Volatile
    private var app: Context? = null

    private val cycles = listOf("morning" to LocalTime.of(7, 30), "evening" to LocalTime.of(19, 30))

    fun schedule(context: Context) {
        app = context.applicationContext
        val am = context.getSystemService(AlarmManager::class.java)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        for ((name, time) in cycles) {
            var at = now.with(time)
            if (!at.isAfter(now)) at = at.plusDays(1)
            val pi = pending(context, name)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
        }
        Log.i(BuddyApp.TAG, "brief cycles scheduled")
    }

    private fun pending(context: Context, cycle: String): PendingIntent {
        val intent = Intent(context, CycleReceiver::class.java).setAction(ACTION_CYCLE).putExtra(EXTRA_CYCLE, cycle)
        return PendingIntent.getBroadcast(context, cycle.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Runs one cycle now, on a background thread. Used by the alarm and by the screen's refresh. */
    fun runCycle(context: Context, cycle: String, onDone: ((Planned) -> Unit)? = null) {
        thread(name = "buddy-brief") {
            val planned = try {
                plan(cycle)
            } catch (t: Throwable) {
                Log.e(BuddyApp.TAG, "brief cycle failed", t)
                null
            } ?: return@thread
            buddy.android.surface.SurfaceStore.onPlanned(planned)
            announce(planned)
            onDone?.invoke(planned)
        }
    }

    private fun plan(cycle: String): Planned? {
        val ledger = LedgerHolder.getOrNull() ?: return null
        val planner = Brain.planner ?: return null
        val now = System.currentTimeMillis()
        val lastBrief = ledger.recent(50).firstOrNull { it.kind == EventKind.BRIEF }?.ts ?: (now - 24 * 3_600_000L)
        // Everything since the last brief, oldest first, with their triage decisions.
        val window = ArrayList<buddy.ledger.Event>()
        var before: Long? = null
        loop@ while (true) {
            val page = ledger.recent(500, before)
            if (page.isEmpty()) break
            for (e in page) {
                if (e.ts <= lastBrief) break@loop
                window.add(e)
            }
            before = page.last().ts
        }
        window.reverse()
        val decisions = window.filter { it.kind == EventKind.TRIAGE }.mapNotNull(TriageRecorder::fromEvent).associateBy { it.eventId }
        val subjects = window.filter { it.kind != EventKind.TRIAGE && it.kind != EventKind.BRIEF }
        // The agent works its jobs first, so the brief reports what actually happened
        // (docs/07: the cycle is now one of the reasons to wake, not the only one).
        Wakes.onCycle(cycle, subjects, decisions)
        return planner.plan(subjects, decisions, now, cycle)
    }

    /**
     * The few-minute tick: held actions whose window expired, then the tasks whose own
     * clock came round and the ones that have gone stale.
     */
    fun releaseDueHolds() {
        val exec = Brain.executor ?: return
        for ((held, _) in exec.dueHolds()) {
            val done = exec.release(held)
            Log.i(BuddyApp.TAG, "released hold ${held.id}: ${done.structured["state"]}")
        }
        Wakes.tick()
    }

    fun scheduleHoldRelease(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, CycleReceiver::class.java).setAction(ACTION_HOLDS)
        val pi = PendingIntent.getBroadcast(context, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5 * 60_000L, 5 * 60_000L, pi)
    }

    /**
     * The brief arriving. This used to be a notification, which on this build reaches
     * nobody: the shade does not open, the status bar carries no icons of buddy's, and
     * the lock screen shows his face rather than a list (patches 0013 and 0015). The
     * face lifting its eyes is the message — [SurfaceStore] has already published that
     * by the time this runs — and the chime is what makes someone look at it.
     */
    private fun announce(p: Planned) {
        val context = app ?: return
        val urgent = p.brief.urgent.isNotEmpty()
        val hour = ZonedDateTime.now(ZoneId.systemDefault()).hour
        val quiet = Brain.policyProfile.limits.inQuietHours(hour)
        Arrival.announce(context, Arrival.decide(urgent, quiet, onScreen = SurfaceStore.onScreen))
    }

    class CycleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_HOLDS -> thread(name = "buddy-holds") { releaseDueHolds() }
                ACTION_CYCLE -> {
                    val cycle = intent.getStringExtra(EXTRA_CYCLE) ?: "adhoc"
                    runCycle(context, cycle)
                    schedule(context) // re-arm tomorrow's alarm
                }
            }
        }
    }
}
