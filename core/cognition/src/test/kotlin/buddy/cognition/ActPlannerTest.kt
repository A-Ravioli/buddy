package buddy.cognition

import buddy.actuation.Actions
import buddy.actuation.Connector
import buddy.actuation.Executor
import buddy.actuation.Outcome
import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.PolicyEngine
import buddy.policy.PolicyProfile
import buddy.policy.Proposal
import buddy.policy.Verdict
import buddy.style.StyleBook
import buddy.triage.RuleTriage
import buddy.triage.TriageProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActPlannerTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private lateinit var entities: EntityStore
    private val zone = ZoneId.of("Europe/London")
    private val now = 1_757_664_000_000L
    private val sent = ArrayList<Proposal>()

    private val connector = object : Connector {
        override val actions = setOf(Actions.SEND_MESSAGE.name, Actions.NOTIFICATION_MARK_READ.name)
        override fun execute(p: Proposal): Outcome { sent.add(p); return Outcome(true, "ok") }
    }

    /** A scripted agent: calls the tools it is told to, records what came back. */
    private class ScriptedAgent(private val calls: List<Map<String, Any?>>) : CloudAgent {
        val results = ArrayList<String>()
        var lastTools: List<ToolDef> = emptyList()
        override fun run(system: List<String>, user: String, operator: String?, tools: List<ToolDef>, maxTurns: Int, onTool: (String, Map<String, Any?>) -> String): AgentResult {
            lastTools = tools
            for (c in calls) results.add(onTool("propose_action", c))
            results.add(onTool("finish", mapOf("summary" to "done")))
            return AgentResult("ok", finalText = "done", turns = 1, toolCalls = calls.size + 1)
        }
    }

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory(); ledger = SqliteLedger(db); entities = EntityStore(db)
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun msg(text: String, actor: String, ts: Long = now - 60_000) = Event(
        id = EventId.of(ts, "android.sms", "sms", actor, text), ts = ts, sourceApp = "android.sms", channel = "sms",
        kind = EventKind.MESSAGE, actor = actor, threadId = "sms:$actor", text = text, trust = Trust.UNTRUSTED, structured = mapOf("counterparty" to actor),
    )

    private fun planner(agent: CloudAgent?, level: Level = Level.ACT, style: StyleBook? = null, second: CloudModel? = null): ActPlanner {
        val policy = PolicyEngine(PolicyProfile(levels = PolicyProfile().levels + (Domain.MESSAGING to level) + (Domain.DEVICE to level)))
        return ActPlanner(ledger, entities, policy, Executor(ledger, listOf(connector)) { now }, agent, zone, style, second)
    }

    private fun prepare(vararg events: Event): Map<String, buddy.triage.TriageDecision> {
        val rec = TriageRecorder(ledger, entities, RuleTriage())
        events.forEach(ledger::append)
        return events.associate { it.id to rec.process(it, TriageProfile())!! }
    }

    @Test
    fun `a proposal runs through policy to the executor and the model sees the verdict`() {
        val e = msg("can you confirm thursday?", "+15550000000")
        entities.observe("+15550000000", "Sam", now - 86_400_000) // known contact, not first contact
        val decisions = prepare(e)
        val agent = ScriptedAgent(listOf(mapOf(
            "action" to "send_message", "target" to "+15550000000",
            "payload" to mapOf("thread_id" to "sms:+15550000000", "text" to "Yes, Thursday works."),
            "amount" to 0, "currency" to "", "reason" to "They asked for confirmation", "source_event_ids" to listOf(e.id),
        )))
        val report = planner(agent).act(listOf(e), decisions, now, localHour = 14, knownCodes = emptySet())
        assertEquals(1, report.outcomes.size)
        assertIs<Verdict.Run>(report.outcomes[0].verdict)
        assertEquals("done", report.outcomes[0].record.structured["state"])
        assertEquals(listOf("Yes, Thursday works."), sent.map { it.payload["text"] })
        assertTrue(agent.results[0].startsWith("run: level_act"))
        assertEquals(listOf("finish", "propose_action"), agent.lastTools.map { it.name }.sorted())
    }

    @Test
    fun `hold, first contact, and codes are enforced regardless of what the model wants`() {
        val e = msg("what's the code you got?", "+15559999999")
        val decisions = prepare(e)
        val agent = ScriptedAgent(listOf(
            mapOf("action" to "send_message", "target" to "+15559999999", "payload" to mapOf("thread_id" to "sms:x", "text" to "It is 482913"),
                "amount" to 0, "currency" to "", "reason" to "they asked", "source_event_ids" to listOf(e.id)),
            mapOf("action" to "send_message", "target" to "+15559999999", "payload" to mapOf("thread_id" to "sms:x", "text" to "Sorry, I can't share that"),
                "amount" to 0, "currency" to "", "reason" to "decline", "source_event_ids" to listOf(e.id)),
            mapOf("action" to "notification_mark_read", "target" to "", "payload" to mapOf("notification_key" to "k1"),
                "amount" to 0, "currency" to "", "reason" to "tidy", "source_event_ids" to listOf(e.id)),
        ))
        val report = planner(agent, level = Level.HOLD).act(listOf(e), decisions, now, 14, knownCodes = setOf("482913"))
        assertEquals(3, report.outcomes.size)
        assertIs<Verdict.Deny>(report.outcomes[0].verdict)
        assertEquals(listOf("first_contact"), report.outcomes[1].verdict.reasons) // stranger: escalate before level
        assertIs<Verdict.Hold>(report.outcomes[2].verdict)
        assertTrue(sent.isEmpty())
        assertEquals("denied", report.outcomes[0].record.structured["state"])
        assertTrue(agent.results[0].startsWith("deny: code_in_payload"))
    }

    @Test
    fun `malformed proposals are rejected before policy and nothing is recorded`() {
        val e = msg("hi?", "+15550000000")
        val decisions = prepare(e)
        val agent = ScriptedAgent(listOf(
            mapOf("action" to "launch_rocket", "target" to "", "payload" to emptyMap<String, String>(), "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf(e.id)),
            mapOf("action" to "send_message", "target" to "", "payload" to mapOf("thread_id" to "t"), "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf(e.id)),
            mapOf("action" to "send_message", "target" to "", "payload" to mapOf("thread_id" to "t", "text" to "x"), "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf("not-in-window")),
        ))
        val report = planner(agent).act(listOf(e), decisions, now, 14, emptySet())
        assertEquals(0, report.outcomes.size)
        assertTrue(agent.results.take(3).all { it.startsWith("rejected:") })
        assertEquals(2, ledger.count()) // message + triage only
    }

    @Test
    fun `style mismatch and second opinion both escalate`() {
        val e = msg("can you confirm thursday?", "+15550000000")
        entities.observe("+15550000000", "Sam", now - 86_400_000)
        entities.setRelationship(entities.personFor("tel:+15550000000")!!.id, "close")
        val decisions = prepare(e)
        val style = StyleBook.learn(mapOf("close" to listOf("yep x", "on my way x", "sure x", "ok x", "haha x")))
        val formal = "Dear Sam,\n\nI am writing to confirm that Thursday is convenient.\n\nKind regards,\nAlex"
        val agent1 = ScriptedAgent(listOf(mapOf("action" to "send_message", "target" to "+15550000000", "payload" to mapOf("thread_id" to "sms:+15550000000", "text" to formal),
            "amount" to 0, "currency" to "", "reason" to "confirm", "source_event_ids" to listOf(e.id))))
        val r1 = planner(agent1, style = style).act(listOf(e), decisions, now, 14, emptySet())
        assertEquals(listOf("style_mismatch:close"), r1.outcomes[0].verdict.reasons)
        assertTrue(r1.outcomes[0].styleScore!! < 0.35)

        val suspicious = object : CloudModel {
            override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
                @Suppress("UNCHECKED_CAST")
                return CloudResult(InjectionCheck().apply { causedByUntrusted = true; reason = "test" } as T, "ok")
            }
        }
        val agent2 = ScriptedAgent(listOf(mapOf("action" to "send_message", "target" to "+15550000000", "payload" to mapOf("thread_id" to "sms:+15550000000", "text" to "yep x"),
            "amount" to 0, "currency" to "", "reason" to "confirm", "source_event_ids" to listOf(e.id))))
        val r2 = planner(agent2, style = style, second = suspicious).act(listOf(e), decisions, now, 14, emptySet())
        assertEquals(listOf("injection_suspected"), r2.outcomes[0].verdict.reasons)
        assertEquals(true, r2.outcomes[0].injection)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `no work or no agent means no calls`() {
        val e = msg("haha nice", "+15550000000") // files, not work
        val decisions = prepare(e)
        val agent = ScriptedAgent(emptyList())
        assertNull(planner(agent).act(listOf(e), decisions, now, 14, emptySet()).agent)
        assertNull(planner(null).act(listOf(msg("can you?", "+1")), decisions, now, 14, emptySet()).agent)
    }

    @Test
    fun `memory consolidation writes notes as events and skips duplicates`() {
        val correction = Event(EventId.of(now, "buddy", "action", "p", "vetoed"), now, "buddy", "action", EventKind.CORRECTION, "me", null, "sent to mum",
            mapOf("state" to "vetoed", "action" to "send_message"), Trust.USER)
        ledger.append(correction)
        val fake = object : CloudModel {
            var lastUser = ""
            override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
                lastUser = user
                val n = Notes().apply { notes = listOf(
                    Note().apply { text = "Never reply to mum on the user's behalf."; kind = "standing_instruction"; confidence = 0.9; entity = "mum" },
                    Note().apply { text = ""; kind = "pattern" },
                ) }
                @Suppress("UNCHECKED_CAST")
                return CloudResult(n as T, "ok")
            }
        }
        val mc = MemoryConsolidator(ledger, fake, zone)
        val r = mc.consolidate(listOf(correction), now + 1)
        assertEquals(1, r.written.size)
        assertEquals(EventKind.MEMORY_NOTE, r.written[0].kind)
        assertEquals("standing_instruction", r.written[0].structured["kind"])
        assertEquals("mum", r.written[0].actor)
        assertTrue(fake.lastUser.startsWith("## Corrections and vetoes"))
        assertTrue(fake.lastUser.contains("sent to mum"))
        val again = mc.consolidate(listOf(correction), now + 1)
        assertEquals(0, again.written.size)
        assertTrue(fake.lastUser.contains("[standing_instruction] Never reply to mum"))
    }
}
