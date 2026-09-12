package buddy.actuation

import buddy.actuation.mail.MailMessages
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.policy.Proposal
import buddy.policy.Verdict
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutorTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private var clock = 1_700_000_000_000L

    /** A connector that remembers what it did and can undo it. */
    private class FakeConnector : Connector {
        val sent = ArrayList<String>()
        val undone = ArrayList<String>()
        var fail = false
        var verifyFails = false
        override val actions = setOf(Actions.SEND_MESSAGE.name, Actions.ARCHIVE_EMAIL.name)
        override fun execute(p: Proposal): Outcome {
            if (fail) return Outcome(false, "boom")
            sent.add(p.payload["text"] ?: p.payload["message_id"] ?: "?")
            return Outcome(true, "sent", undoToken = if (p.spec.name == Actions.ARCHIVE_EMAIL.name) "tok:${p.payload["message_id"]}" else null)
        }
        override fun verify(p: Proposal, o: Outcome) = !verifyFails
        override fun undo(p: Proposal, o: Outcome): Boolean { undone.add(o.undoToken!!); return true }
    }

    private lateinit var fake: FakeConnector
    private lateinit var executor: Executor

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
        fake = FakeConnector()
        executor = Executor(ledger, listOf(fake)) { clock++ }
    }

    @AfterEach
    fun tearDown() = db.close()

    private val send = Proposal("p1", Actions.SEND_MESSAGE, "+15550000000", mapOf("thread_id" to "sms:1", "text" to "on my way"), reason = "they asked", sourceEventIds = listOf("e1"))
    private val archive = Proposal("p2", Actions.ARCHIVE_EMAIL, payload = mapOf("message_id" to "<m1@x>"))

    @Test
    fun `run executes, verifies, and records done with the proposal round-tripping`() {
        val e = executor.apply(send, Verdict.Run(listOf("level_act")))
        assertEquals(EventKind.ACTION, e.kind)
        assertEquals("done", e.structured["state"])
        assertEquals("sms:1", e.threadId)
        assertEquals("they asked", e.text)
        assertEquals(listOf("on my way"), fake.sent)
        assertEquals(send, executor.proposalOf(e))
        assertEquals(1, ledger.count())
    }

    @Test
    fun `failures and unverified outcomes are recorded, never retried`() {
        fake.fail = true
        assertEquals("failed", executor.apply(send, Verdict.Run(emptyList())).structured["state"])
        fake.fail = false; fake.verifyFails = true
        assertEquals("unverified", executor.apply(send, Verdict.Run(emptyList())).structured["state"])
        assertEquals(1, fake.sent.size)
        val noConnector = Proposal("p3", Actions.CREATE_EVENT, payload = mapOf("title" to "x"))
        assertTrue(executor.apply(noConnector, Verdict.Run(emptyList())).structured["reasons"]!!.contains("no_connector"))
    }

    @Test
    fun `holds wait, release when due, and can be vetoed`() {
        val held = executor.apply(send, Verdict.Hold(untilTs = clock + 1000, reasons = listOf("level_hold")))
        assertEquals("held", held.structured["state"])
        assertEquals(emptyList(), executor.dueHolds(clock))
        assertEquals(listOf(held.id), executor.dueHolds(clock + 5000).map { it.first.id })
        val done = executor.release(held)
        assertEquals("done", done.structured["state"])
        assertEquals(held.id, done.supersedes)
        assertEquals(emptyList(), executor.dueHolds(clock + 5000)) // superseded holds are no longer due

        val held2 = executor.apply(send.copy(id = "p9"), Verdict.Hold(clock + 1, listOf("level_hold")))
        val veto = executor.veto(held2)
        assertEquals("vetoed", veto.structured["state"])
        assertEquals(Trust.USER, veto.trust)
        assertEquals(emptyList(), executor.dueHolds(clock + 5000))
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `escalate and deny are recorded without executing`() {
        assertEquals("escalated", executor.apply(send, Verdict.Escalate(listOf("level_draft"))).structured["state"])
        assertEquals("denied", executor.apply(send, Verdict.Deny(listOf("code_in_payload"))).structured["state"])
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `undo calls the connector and records a correction`() {
        val done = executor.apply(archive, Verdict.Run(listOf("level_act")))
        assertEquals("tok:<m1@x>", done.structured["undo_token"])
        val undo = executor.undo(done, critical = true)
        assertEquals(EventKind.CORRECTION, undo.kind)
        assertEquals("undone", undo.structured["state"])
        assertEquals("critical_regret", undo.structured["reasons"])
        assertEquals(done.id, undo.supersedes)
        assertEquals(listOf("tok:<m1@x>"), fake.undone)
        assertEquals(listOf(undo), ledger.corrections(done.id))

        val sent = executor.apply(send, Verdict.Run(emptyList()))
        assertEquals("undo_failed", executor.undo(sent).structured["state"]) // no undo token for a sent message
    }

    @Test
    fun `mail reply headers thread correctly and unsubscribe targets parse`() {
        val d = MailMessages.reply("a@x.com", null, "Invoice", "<1@x>", null, "Thanks")
        assertEquals(listOf("a@x.com"), d.to); assertEquals("Re: Invoice", d.subject)
        assertEquals("<1@x>", d.inReplyTo); assertEquals("<1@x>", d.references)
        val d2 = MailMessages.reply("a@x.com", "replies@x.com", "Re: Invoice", "<2@x>", "<1@x>", "ok")
        assertEquals(listOf("replies@x.com"), d2.to); assertEquals("Re: Invoice", d2.subject); assertEquals("<1@x> <2@x>", d2.references)
        assertNull(MailMessages.reply("a@x.com", null, null, null, null, "x").inReplyTo)
        assertEquals(listOf("mailto:u@x.com?subject=unsub", "https://x.com/u"), MailMessages.unsubscribeTargets("<mailto:u@x.com?subject=unsub>, <https://x.com/u>"))
        assertEquals(emptyList(), MailMessages.unsubscribeTargets(null))
    }
}
