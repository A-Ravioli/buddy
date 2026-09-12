package buddy.perception

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust

/**
 * Turns a posted notification into ledger events.
 *
 * - Group summaries carry no content of their own and are dropped.
 * - Ongoing notifications (media playback, navigation, foreground services) are noise,
 *   except an ongoing call, which becomes a CALL event.
 * - Messaging-style notifications become one MESSAGE event per message, keyed by the
 *   conversation, so a chat app with no API still lands in the ledger as a thread.
 * - Everything else becomes one NOTIFICATION event whose identity is the notification
 *   key plus its content, so an update with new content is a new event and a re-post
 *   of the same content is not.
 */
object NotificationNormalizer {
    const val CHANNEL_MESSAGING = "notification.messaging"
    const val CHANNEL_NOTIFICATION = "notification"
    const val CHANNEL_CALL = "notification.call"
    const val USER_ACTOR = "me"

    fun toEvents(n: NotificationSnapshot): List<Event> {
        if (n.isGroupSummary) return emptyList()
        if (n.messages.isNotEmpty()) return messages(n)
        if (n.isOngoing) return if (n.category == "call") listOf(call(n)) else emptyList()
        return listOfNotNull(plain(n))
    }

    private fun messages(n: NotificationSnapshot): List<Event> {
        val thread = threadId(n)
        return n.messages
            .filter { it.text.isNotBlank() }
            .map { m ->
                val actor = if (m.isFromUser) USER_ACTOR else m.sender?.trim()?.ifBlank { null }
                Event(
                    id = EventId.of(m.ts, n.packageName, CHANNEL_MESSAGING, thread, actor, m.text),
                    ts = m.ts,
                    sourceApp = n.packageName,
                    channel = CHANNEL_MESSAGING,
                    kind = EventKind.MESSAGE,
                    actor = actor,
                    threadId = thread,
                    text = m.text,
                    structured = buildMap {
                        n.conversationTitle?.let { put("conversation", it) }
                        put("group", n.isGroupConversation.toString())
                        if (n.actions.isNotEmpty()) put("actions", n.actions.joinToString("|"))
                    },
                    trust = if (m.isFromUser) Trust.USER else Trust.UNTRUSTED,
                    rawRef = n.key,
                )
            }
    }

    private fun plain(n: NotificationSnapshot): Event? {
        val body = (n.bigText ?: n.text)?.trim()?.ifBlank { null }
        val title = n.title?.trim()?.ifBlank { null }
        if (body == null && title == null) return null
        val text = listOfNotNull(title, body).joinToString(": ")
        val ts = n.whenTs?.takeIf { it > 0 } ?: n.postTime
        return Event(
            id = EventId.of(ts, n.packageName, CHANNEL_NOTIFICATION, n.key, title, body),
            ts = ts,
            sourceApp = n.packageName,
            channel = CHANNEL_NOTIFICATION,
            kind = EventKind.NOTIFICATION,
            actor = n.subText?.trim()?.ifBlank { null },
            threadId = null,
            text = text,
            structured = buildMap {
                n.category?.let { put("category", it) }
                n.channelId?.let { put("channel_id", it) }
                if (n.actions.isNotEmpty()) put("actions", n.actions.joinToString("|"))
            },
            trust = Trust.UNTRUSTED,
            rawRef = n.key,
        )
    }

    private fun call(n: NotificationSnapshot): Event {
        val ts = n.whenTs?.takeIf { it > 0 } ?: n.postTime
        val who = n.title?.trim()?.ifBlank { null }
        return Event(
            id = EventId.of(ts, n.packageName, CHANNEL_CALL, n.key, who),
            ts = ts,
            sourceApp = n.packageName,
            channel = CHANNEL_CALL,
            kind = EventKind.CALL,
            actor = who,
            text = n.text?.trim(),
            structured = mapOf("state" to "ongoing"),
            trust = Trust.UNTRUSTED,
            rawRef = n.key,
        )
    }

    /**
     * A stable conversation key. Shortcut ids are the platform's own conversation
     * identity and are preferred; a conversation title or the single sender is the
     * fallback for apps that do not set one.
     */
    fun threadId(n: NotificationSnapshot): String {
        val key = n.shortcutId?.ifBlank { null }
            ?: n.conversationTitle?.trim()?.ifBlank { null }
            ?: n.messages.firstOrNull { !it.isFromUser }?.sender?.trim()?.ifBlank { null }
            ?: n.key
        return "${n.packageName}:$key"
    }
}
