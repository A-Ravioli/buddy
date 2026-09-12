package buddy.android.perception

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import buddy.android.BuddyApp
import buddy.perception.ConversationMessage
import buddy.perception.NotificationNormalizer
import buddy.perception.NotificationSnapshot

/**
 * Maps posted notifications to [NotificationSnapshot]s and hands them to the
 * normaliser. No decisions here; see NotificationNormalizer for what becomes an event.
 */
class BuddyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Log.i(BuddyApp.TAG, "notification listener connected")
        // Backfill whatever is currently posted so the ledger starts full.
        activeNotifications?.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val snapshot = try {
            snapshot(this, sbn)
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "bad notification from ${sbn.packageName}", t)
            return
        }
        Ingest.submit(NotificationNormalizer.toEvents(snapshot))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Removal is not an event in Phase 0. It becomes one when buddy starts acting
        // on notifications and needs to know what the user dismissed.
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        /** The connected listener, for connectors that act through notification actions. */
        @Volatile
        var instance: BuddyNotificationListener? = null
            private set

        fun snapshot(context: Context, sbn: StatusBarNotification): NotificationSnapshot {
            val n = sbn.notification
            val extras = n.extras
            val style = runCatching { Notification.Builder.recoverBuilder(context, n).style }
                .getOrNull() as? Notification.MessagingStyle
            return NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                postTime = sbn.postTime,
                whenTs = n.`when`,
                title = extras.text(Notification.EXTRA_TITLE),
                text = extras.text(Notification.EXTRA_TEXT),
                bigText = extras.text(Notification.EXTRA_BIG_TEXT),
                subText = extras.text(Notification.EXTRA_SUB_TEXT),
                category = n.category,
                channelId = n.channelId,
                isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                conversationTitle = style?.conversationTitle?.toString(),
                isGroupConversation = style?.isGroupConversation ?: false,
                shortcutId = n.shortcutId,
                messages = style?.let(::messages) ?: emptyList(),
                actions = n.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            )
        }

        private fun messages(style: Notification.MessagingStyle): List<ConversationMessage> {
            val user = style.user
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString() ?: return@mapNotNull null
                val sender = m.senderPerson
                val fromUser = sender == null ||
                    (sender.key != null && sender.key == user.key) ||
                    (sender.name != null && sender.name == user.name)
                ConversationMessage(
                    sender = sender?.name?.toString(),
                    text = text,
                    ts = m.timestamp,
                    isFromUser = fromUser,
                )
            }
        }

        private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()
    }
}
