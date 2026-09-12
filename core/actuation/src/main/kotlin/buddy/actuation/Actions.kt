package buddy.actuation

import buddy.policy.ActionSpec
import buddy.policy.BlastRadius
import buddy.policy.Domain
import buddy.policy.Reversibility

/**
 * The action registry: every action buddy can take, with its classification. The
 * cloud act loop exposes exactly these as tools; connectors implement them; the policy
 * engine gates them by their spec. Adding an action means adding it here first.
 *
 * Payload keys are documented per action and are the tool's input schema.
 */
object Actions {
    val SEND_MESSAGE = ActionSpec(
        "send_message", Domain.MESSAGING, Reversibility.SOFT, BlastRadius.KNOWN,
        "Send a text message in an existing conversation. Payload: thread_id, text.",
    )
    val NOTIFICATION_REPLY = ActionSpec(
        "notification_reply", Domain.MESSAGING, Reversibility.SOFT, BlastRadius.KNOWN,
        "Reply through a notification's inline reply. Payload: notification_key, text.",
    )
    val NOTIFICATION_MARK_READ = ActionSpec(
        "notification_mark_read", Domain.DEVICE, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Trigger a notification's mark-as-read action. Payload: notification_key.",
    )
    val NOTIFICATION_DISMISS = ActionSpec(
        "notification_dismiss", Domain.DEVICE, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Dismiss a notification. Payload: notification_key.",
    )
    val ARCHIVE_EMAIL = ActionSpec(
        "archive_email", Domain.EMAIL, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Archive an email. Payload: message_id.",
    )
    val LABEL_EMAIL = ActionSpec(
        "label_email", Domain.EMAIL, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Apply a label or folder to an email. Payload: message_id, label.",
    )
    val REPLY_EMAIL = ActionSpec(
        "reply_email", Domain.EMAIL, Reversibility.SOFT, BlastRadius.KNOWN,
        "Reply to an email. Payload: message_id, text.",
    )
    val UNSUBSCRIBE_EMAIL = ActionSpec(
        "unsubscribe_email", Domain.EMAIL, Reversibility.SOFT, BlastRadius.EXTERNAL,
        "Unsubscribe from a sender using the list-unsubscribe header. Payload: message_id.",
    )
    val RESPOND_INVITE = ActionSpec(
        "respond_invite", Domain.CALENDAR, Reversibility.SOFT, BlastRadius.KNOWN,
        "Accept, decline, or tentatively accept a calendar invite. Payload: event_id, response (accepted|declined|tentative).",
    )
    val CREATE_EVENT = ActionSpec(
        "create_event", Domain.CALENDAR, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Create a calendar event. Payload: title, begin (epoch ms), end (epoch ms), location, description.",
    )
    val SNOOZE = ActionSpec(
        "snooze", Domain.DEVICE, Reversibility.REVERSIBLE, BlastRadius.SELF,
        "Put an item back in front of the user later. Payload: event_id, until (epoch ms).",
    )

    val all: List<ActionSpec> = listOf(
        SEND_MESSAGE, NOTIFICATION_REPLY, NOTIFICATION_MARK_READ, NOTIFICATION_DISMISS,
        ARCHIVE_EMAIL, LABEL_EMAIL, REPLY_EMAIL, UNSUBSCRIBE_EMAIL, RESPOND_INVITE, CREATE_EVENT, SNOOZE,
    )

    val byName: Map<String, ActionSpec> = all.associateBy { it.name }

    /** Payload keys per action, for the tool schema. */
    val payloadKeys: Map<String, List<String>> = mapOf(
        SEND_MESSAGE.name to listOf("thread_id", "text"),
        NOTIFICATION_REPLY.name to listOf("notification_key", "text"),
        NOTIFICATION_MARK_READ.name to listOf("notification_key"),
        NOTIFICATION_DISMISS.name to listOf("notification_key"),
        ARCHIVE_EMAIL.name to listOf("message_id"),
        LABEL_EMAIL.name to listOf("message_id", "label"),
        REPLY_EMAIL.name to listOf("message_id", "text"),
        UNSUBSCRIBE_EMAIL.name to listOf("message_id"),
        RESPOND_INVITE.name to listOf("event_id", "response"),
        CREATE_EVENT.name to listOf("title", "begin", "end", "location", "description"),
        SNOOZE.name to listOf("event_id", "until"),
    )
}
