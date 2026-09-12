package buddy.android.cognition

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.buddy.R
import buddy.android.BuddyApp
import buddy.android.ledger.LedgerHolder
import buddy.android.ui.BriefActivity
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
 * The brief is posted as one notification and kept as a BRIEF event for the screen.
 */
object BriefScheduler {
    const val ACTION_CYCLE = "app.buddy.action.BRIEF_CYCLE"
    const val EXTRA_CYCLE = "cycle"
    private const val CHANNEL = "brief"
    private const val NOTIFICATION_ID = 2

    private val cycles = listOf("morning" to LocalTime.of(7, 30), "evening" to LocalTime.of(19, 30))

    fun schedule(context: Context) {
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
            post(context, planned)
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
        return planner.plan(subjects, decisions, now, cycle)
    }

    private fun post(context: Context, p: Planned) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.brief_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, BriefActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val needs = p.brief.urgent.size + p.brief.decisions.size
        val title = if (needs == 0) context.getString(R.string.brief_nothing) else context.resources.getQuantityString(R.plurals.brief_needs_you, needs, needs)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(p.brief.spoken.take(200))
            .setStyle(Notification.BigTextStyle().bigText(p.brief.spoken))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    class CycleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CYCLE) return
            val cycle = intent.getStringExtra(EXTRA_CYCLE) ?: "adhoc"
            runCycle(context, cycle)
            schedule(context) // re-arm tomorrow's alarm
        }
    }
}
