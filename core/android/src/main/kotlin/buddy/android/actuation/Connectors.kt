package buddy.android.actuation

import android.app.RemoteInput
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.telephony.SmsManager
import buddy.actuation.Actions
import buddy.actuation.Connector
import buddy.actuation.Outcome
import buddy.android.perception.BuddyNotificationListener
import buddy.policy.Proposal

/**
 * Phase 2 connectors: notification actions, the calendar provider, and SMS. Each one
 * does exactly what its action says and reports what happened. Decisions were made
 * upstream by the policy engine.
 */

/** Inline reply, mark-as-read, dismiss, and snooze through the notification listener. */
class NotificationActionConnector(private val context: Context) : Connector {
    override val actions = setOf(
        Actions.NOTIFICATION_REPLY.name, Actions.NOTIFICATION_MARK_READ.name,
        Actions.NOTIFICATION_DISMISS.name, Actions.SNOOZE.name,
    )

    override fun execute(p: Proposal): Outcome {
        val listener = BuddyNotificationListener.instance ?: return Outcome(false, "notification listener not connected")
        val key = p.payload["notification_key"] ?: p.payload["event_id"] ?: return Outcome(false, "no notification_key")
        val sbn = listener.activeNotifications?.firstOrNull { it.key == key } ?: return Outcome(false, "notification gone")
        return when (p.spec.name) {
            Actions.NOTIFICATION_DISMISS.name -> { listener.cancelNotification(key); Outcome(true, "dismissed") }
            Actions.SNOOZE.name -> {
                val until = p.payload["until"]?.toLongOrNull() ?: return Outcome(false, "no until")
                listener.snoozeNotification(key, (until - System.currentTimeMillis()).coerceAtLeast(60_000))
                Outcome(true, "snoozed")
            }
            Actions.NOTIFICATION_MARK_READ.name -> {
                val action = sbn.notification.actions?.firstOrNull { it.title?.toString()?.contains("read", ignoreCase = true) == true }
                    ?: return Outcome(false, "no mark-read action")
                action.actionIntent.send()
                Outcome(true, "mark read sent")
            }
            Actions.NOTIFICATION_REPLY.name -> {
                val text = p.payload["text"] ?: return Outcome(false, "no text")
                val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return Outcome(false, "no inline reply")
                val fill = Intent()
                val results = android.os.Bundle()
                for (ri in action.remoteInputs) results.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(action.remoteInputs, fill, results)
                action.actionIntent.send(context, 0, fill)
                Outcome(true, "replied via ${sbn.packageName}")
            }
            else -> Outcome(false, "unsupported")
        }
    }
}

/** Invite responses and event creation through the calendar provider. */
class CalendarConnector(private val context: Context) : Connector {
    override val actions = setOf(Actions.RESPOND_INVITE.name, Actions.CREATE_EVENT.name)

    override fun execute(p: Proposal): Outcome = when (p.spec.name) {
        Actions.RESPOND_INVITE.name -> respond(p)
        Actions.CREATE_EVENT.name -> create(p)
        else -> Outcome(false, "unsupported")
    }

    private fun respond(p: Proposal): Outcome {
        val eventId = p.payload["event_id"]?.toLongOrNull() ?: return Outcome(false, "no event_id")
        val status = when (p.payload["response"]) {
            "accepted" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
            "declined" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            "tentative" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
            else -> return Outcome(false, "bad response")
        }
        // The self attendee is the one whose relationship is ATTENDEE, matching the calendar owner.
        val values = ContentValues().apply { put(CalendarContract.Attendees.ATTENDEE_STATUS, status) }
        val n = context.contentResolver.update(
            CalendarContract.Attendees.CONTENT_URI, values,
            "${CalendarContract.Attendees.EVENT_ID} = ? AND ${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} = ?",
            arrayOf(eventId.toString(), CalendarContract.Attendees.RELATIONSHIP_ATTENDEE.toString()),
        )
        return if (n > 0) Outcome(true, "responded ${p.payload["response"]}") else Outcome(false, "no attendee row updated")
    }

    private fun create(p: Proposal): Outcome {
        val begin = p.payload["begin"]?.toLongOrNull() ?: return Outcome(false, "no begin")
        val end = p.payload["end"]?.toLongOrNull() ?: return Outcome(false, "no end")
        val calendarId = primaryCalendarId() ?: return Outcome(false, "no writable calendar")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, p.payload["title"] ?: "")
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_LOCATION, p.payload["location"] ?: "")
            put(CalendarContract.Events.DESCRIPTION, p.payload["description"] ?: "")
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return Outcome(false, "insert failed")
        return Outcome(true, "created ${uri.lastPathSegment}", undoToken = "event:${uri.lastPathSegment}")
    }

    override fun undo(p: Proposal, o: Outcome): Boolean {
        val id = o.undoToken?.removePrefix("event:")?.toLongOrNull() ?: return false
        return context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }

    private fun primaryCalendarId(): Long? =
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
}

/** Outgoing SMS. The provider observer records the sent row as an event like any other. */
class SmsConnector(private val context: Context) : Connector {
    override val actions = setOf(Actions.SEND_MESSAGE.name)

    override fun execute(p: Proposal): Outcome {
        if (p.payload["thread_id"]?.startsWith("sms:") != true) return Outcome(false, "not an sms thread")
        val to = p.target ?: return Outcome(false, "no target")
        val text = p.payload["text"] ?: return Outcome(false, "no text")
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(to, null, parts, null, null)
        return Outcome(true, "sent ${parts.size} part(s) to $to")
    }
}
