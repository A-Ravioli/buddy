package buddy.perception

/**
 * Platform-neutral pictures of what the Android perception sources hand us. The
 * Android services in :core:android build these from platform objects and do nothing
 * else; every decision about what becomes an event lives in the normalisers here,
 * where it can be tested without a device.
 */

/** One message inside a messaging-style notification. */
data class ConversationMessage(
    val sender: String?,
    val text: String,
    val ts: Long,
    /** True when the message was sent by the user (the notification's own "user" person). */
    val isFromUser: Boolean,
)

data class NotificationSnapshot(
    /** StatusBarNotification.key: unique per posted notification, stable across updates. */
    val key: String,
    val packageName: String,
    /** When the notification was (re)posted. */
    val postTime: Long,
    /** Notification.when: the time the app says the event happened; often stable across updates. */
    val whenTs: Long?,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val category: String? = null,
    val channelId: String? = null,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    /** MessagingStyle fields, empty for other styles. */
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val shortcutId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    /** Titles of the notification's actions ("Reply", "Mark as read"). */
    val actions: List<String> = emptyList(),
)

/** A row from the SMS content provider. */
data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    /** Telephony.Sms.DATE: received (inbox) or created (sent) time. */
    val date: Long,
    val body: String,
    /** Telephony.Sms.TYPE: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued. */
    val type: Int,
) {
    companion object {
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
        const val TYPE_DRAFT = 3
        const val TYPE_OUTBOX = 4
        const val TYPE_FAILED = 5
        const val TYPE_QUEUED = 6
    }
}

/** A row from CalendarContract.Instances joined with its event. */
data class CalendarInstanceRow(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val organizer: String?,
    /** CalendarContract.Events.STATUS: 0 tentative, 1 confirmed, 2 canceled. */
    val status: Int?,
    /** CalendarContract.Events.SELF_ATTENDEE_STATUS: 0 none, 1 accepted, 2 declined, 3 invited, 4 tentative. */
    val selfAttendeeStatus: Int?,
)

/** A row from CallLog.Calls. */
data class CallLogRow(
    val id: Long,
    val number: String?,
    val cachedName: String?,
    val date: Long,
    val durationSeconds: Long,
    /** CallLog.Calls.TYPE: 1 incoming, 2 outgoing, 3 missed, 4 voicemail, 5 rejected, 6 blocked, 7 answered elsewhere. */
    val type: Int,
) {
    companion object {
        const val INCOMING = 1
        const val OUTGOING = 2
        const val MISSED = 3
        const val VOICEMAIL = 4
        const val REJECTED = 5
        const val BLOCKED = 6
        const val ANSWERED_EXTERNALLY = 7
    }
}

data class LocationSample(
    val ts: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String?,
)

/** A device fact: battery level, charging, screen on/off, connectivity. */
data class DeviceStateSample(
    val ts: Long,
    /** "battery", "charging", "screen", "connectivity", "doze". */
    val aspect: String,
    val value: String,
)
