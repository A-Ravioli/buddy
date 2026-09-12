package buddy.perception

import buddy.ledger.EventKind
import buddy.ledger.Trust
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NotificationNormalizerTest {
    private fun base(
        key: String = "0|com.chat|1|null|10001",
        pkg: String = "com.chat",
    ) = NotificationSnapshot(key = key, packageName = pkg, postTime = 1_700_000_000_000, whenTs = 1_700_000_000_000)

    @Test
    fun `group summaries and ongoing noise are dropped`() {
        assertEquals(emptyList(), NotificationNormalizer.toEvents(base().copy(title = "3 new messages", isGroupSummary = true)))
        assertEquals(emptyList(), NotificationNormalizer.toEvents(base().copy(title = "Now playing", isOngoing = true, category = "transport")))
    }

    @Test
    fun `an ongoing call becomes a call event`() {
        val events = NotificationNormalizer.toEvents(base().copy(title = "Sam", text = "Ongoing call", isOngoing = true, category = "call"))
        assertEquals(1, events.size)
        assertEquals(EventKind.CALL, events[0].kind)
        assertEquals("Sam", events[0].actor)
        assertEquals("ongoing", events[0].structured["state"])
    }

    @Test
    fun `messaging style yields one message event per message with the right trust`() {
        val n = base().copy(
            conversationTitle = "Flat chat",
            isGroupConversation = true,
            shortcutId = "flat-chat",
            actions = listOf("Reply", "Mark as read"),
            messages = listOf(
                ConversationMessage("Sam", "dinner thursday?", 1_700_000_000_000, isFromUser = false),
                ConversationMessage(null, "yes, 8 works", 1_700_000_001_000, isFromUser = true),
                ConversationMessage("Sam", "   ", 1_700_000_002_000, isFromUser = false),
            ),
        )
        val events = NotificationNormalizer.toEvents(n)
        assertEquals(2, events.size)
        assertTrue(events.all { it.kind == EventKind.MESSAGE && it.threadId == "com.chat:flat-chat" })
        assertEquals("Sam", events[0].actor)
        assertEquals(Trust.UNTRUSTED, events[0].trust)
        assertEquals("me", events[1].actor)
        assertEquals(Trust.USER, events[1].trust)
        assertEquals("Flat chat", events[0].structured["conversation"])
        assertEquals("true", events[0].structured["group"])
        assertEquals("Reply|Mark as read", events[0].structured["actions"])
    }

    @Test
    fun `re-posting a messaging notification with the same messages gives the same ids`() {
        val msgs = listOf(ConversationMessage("Sam", "hi", 1_700_000_000_000, false))
        val first = NotificationNormalizer.toEvents(base().copy(messages = msgs, shortcutId = "sam"))
        val again = NotificationNormalizer.toEvents(base().copy(messages = msgs, shortcutId = "sam", postTime = 1_700_000_009_000))
        assertEquals(first.map { it.id }, again.map { it.id })
    }

    @Test
    fun `thread id prefers shortcut then title then sender then key`() {
        assertEquals("com.chat:s1", NotificationNormalizer.threadId(base().copy(shortcutId = "s1", conversationTitle = "T")))
        assertEquals("com.chat:T", NotificationNormalizer.threadId(base().copy(conversationTitle = "T")))
        assertEquals(
            "com.chat:Sam",
            NotificationNormalizer.threadId(base().copy(messages = listOf(ConversationMessage("Sam", "x", 1, false)))),
        )
        assertEquals("com.chat:0|com.chat|1|null|10001", NotificationNormalizer.threadId(base()))
    }

    @Test
    fun `plain notifications carry title and body and de-duplicate on content`() {
        val n = base(pkg = "com.bank").copy(title = "Card payment", text = "£12.50 at Coffee Co", category = "msg", channelId = "alerts", subText = "Current account")
        val events = NotificationNormalizer.toEvents(n)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(EventKind.NOTIFICATION, e.kind)
        assertEquals("Card payment: £12.50 at Coffee Co", e.text)
        assertEquals("Current account", e.actor)
        assertEquals("msg", e.structured["category"])
        assertEquals(Trust.UNTRUSTED, e.trust)

        val repost = NotificationNormalizer.toEvents(n.copy(postTime = 1_700_000_500_000))[0]
        assertEquals(e.id, repost.id)
        val updated = NotificationNormalizer.toEvents(n.copy(text = "£13.00 at Coffee Co"))[0]
        assertNotEquals(e.id, updated.id)
    }

    @Test
    fun `big text wins over text and empty notifications yield nothing`() {
        val e = NotificationNormalizer.toEvents(base().copy(title = "T", text = "short", bigText = "the long version"))[0]
        assertEquals("T: the long version", e.text)
        assertEquals(emptyList(), NotificationNormalizer.toEvents(base().copy(title = " ", text = "")))
    }

    @Test
    fun `falls back to post time when the app sets no when`() {
        val e = NotificationNormalizer.toEvents(base().copy(whenTs = 0, title = "x"))[0]
        assertEquals(1_700_000_000_000, e.ts)
    }
}
