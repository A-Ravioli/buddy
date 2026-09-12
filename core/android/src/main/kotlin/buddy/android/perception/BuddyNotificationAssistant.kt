package buddy.android.perception

import android.app.NotificationManager
import android.os.Bundle
import android.service.notification.Adjustment
import android.service.notification.NotificationAssistantService
import android.service.notification.StatusBarNotification
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
import buddy.perception.NotificationNormalizer
import buddy.triage.TriageClass

/**
 * Notification interception before display (docs/01-architecture.md). The assistant
 * role runs on every notification before it is shown and can adjust it. buddy
 * triages the notification the same way the ledger does and:
 *
 * - DROP: importance NONE, so it never appears.
 * - FILE: importance MIN, so it exists silently for the shade but never interrupts.
 * - ACT_*: importance LOW; buddy will handle it in a cycle, the user need not.
 * - ESCALATE: left as the app posted it. Urgent escalations stay as they are too;
 *   the brief is what batches, and an urgent item should ring through.
 *
 * The decision is also recorded in the ledger by the listener path, so the timeline
 * shows why a notification was hidden and the user can correct it.
 */
class BuddyNotificationAssistant : NotificationAssistantService() {

    override fun onNotificationEnqueued(sbn: StatusBarNotification): Adjustment? {
        if (sbn.packageName == packageName) return null
        val triage = Brain.triage ?: return null
        val snapshot = runCatching { BuddyNotificationListener.snapshot(this, sbn) }.getOrElse { return null }
        val events = NotificationNormalizer.toEvents(snapshot)
        if (events.isEmpty()) return null
        // One notification can carry several messages; the strongest decision wins.
        val decisions = events.mapNotNull { triage.process(it, Brain.profile) }
        val strongest = decisions.maxByOrNull { rank(it.klass, it.urgent) } ?: return null
        val importance = when (strongest.klass) {
            TriageClass.DROP -> NotificationManager.IMPORTANCE_NONE
            TriageClass.FILE -> NotificationManager.IMPORTANCE_MIN
            TriageClass.ACT_NOW, TriageClass.ACT_LATER -> NotificationManager.IMPORTANCE_LOW
            TriageClass.ESCALATE -> return null
        }
        val signals = Bundle().apply { putInt(Adjustment.KEY_IMPORTANCE, importance) }
        Log.d(BuddyApp.TAG, "assistant: ${sbn.packageName} -> ${strongest.klass} (${strongest.reasons.joinToString()})")
        return Adjustment(sbn.packageName, sbn.key, signals, "buddy:${strongest.klass.name.lowercase()}", sbn.user)
    }

    override fun onNotificationSnoozedUntilContext(sbn: StatusBarNotification, snoozeCriterionId: String) = Unit

    private fun rank(k: TriageClass, urgent: Boolean): Int = when (k) {
        TriageClass.DROP -> 0
        TriageClass.FILE -> 1
        TriageClass.ACT_LATER -> 2
        TriageClass.ACT_NOW -> 3
        TriageClass.ESCALATE -> if (urgent) 5 else 4
    }
}
