package buddy.cognition

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
import buddy.tasks.Opener
import buddy.tasks.TaskState
import buddy.tasks.TaskStore
import buddy.tasks.Wake
import buddy.tasks.WakeReason
import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private lateinit var entities: EntityStore
    private lateinit var tasks: TaskStore
    private val zone: ZoneId = ZoneId.of("UTC")
    private var clock = 1_700_000_000_000L
    private val sent = ArrayList<Proposal>()

    /** Runs a fixed script of tool calls and records what the harness answered. */
    private class ScriptedAgent(val calls: List<Pair<String, Map<String, Any?>>>) : CloudAgent {
        val results = ArrayList<String>()
        var lastOperator: String? = null
        var lastUser: String? = null
        var lastTools: List<ToolDef> = emptyList()

        override fun run(
            system: List<String>,
            user: String,
            operator: String?,
            tools: List<ToolDef>,
            maxTurns: Int,
            onTool: (String, Map<String, Any?>) -> String,
        ): AgentResult {
            lastOperator = operator
            lastUser = user
            lastTools = tools
            for ((name, input) in calls) results.add(onTool(name, input))
            return AgentResult("ok", finalText = "done", turns = 1, toolCalls = calls.size, inputTokens = 900, outputTokens = 100)
        }
    }

    private val messaging = object : Connector {
        override val actions = setOf("send_message", "notification_dismiss")
        override fun execute(p: Proposal): Outcome {
            sent.add(p)
            return Outcome(true, "sent", undoToken = "u1")
        }
    }

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
        entities = EntityStore(db)
        tasks = TaskStore(ledger, now = { clock })
        sent.clear()
    }

    private fun agent(
        cloud: CloudAgent?,
        levels: Map<Domain, Level> = Domain.entries.associateWith { Level.FULL },
    ) = Agent(
        ledger = ledger,
        entities = entities,
        tasks = tasks,
        policy = PolicyEngine(PolicyProfile(levels = levels)),
        executor = Executor(ledger, listOf(messaging), now = { clock }),
        cloud = cloud,
        zone = zone,
    )

    private fun message(text: String, actor: String = "+15550001111", thread: String = "sms:1"): Event {
        val e = Event(
            id = EventId.of(clock, "android.sms", "sms", text),
            ts = clock,
            sourceApp = "android.sms",
            channel = "sms",
            kind = EventKind.MESSAGE,
            actor = actor,
            threadId = thread,
            text = text,
            trust = Trust.UNTRUSTED,
        )
        ledger.append(e)
        entities.apply(e)
        return e
    }

    /** A message the user sent, so the counterparty stops being a first contact. */
    private fun userMessaged(actor: String = "+15550001111", thread: String = "sms:1") {
        val e = Event(
            id = EventId.of(clock - 10_000, "android.sms", "sms", "out-$actor"),
            ts = clock - 10_000,
            sourceApp = "android.sms",
            channel = "sms",
            kind = EventKind.MESSAGE,
            actor = "me",
            threadId = thread,
            text = "see you then",
            structured = mapOf("counterparty" to actor),
            trust = Trust.USER,
        )
        ledger.append(e)
        entities.apply(e)
    }

    private fun wake(reason: WakeReason = WakeReason.WORK, taskId: String? = null) =
        Wake(reason, clock, taskId, emptyList(), "a message from Sam")

    private fun decisions(e: Event) = mapOf(e.id to TriageDecision(e.id, TriageClass.ACT_NOW, reasons = listOf("question")))

    private fun run(
        script: ScriptedAgent,
        trigger: Event,
        levels: Map<Domain, Level> = Domain.entries.associateWith { Level.FULL },
        w: Wake = wake(),
    ): WakeReport = agent(script, levels).wake(w, listOf(trigger), decisions(trigger), clock, 14, emptySet())

    @Test
    fun `a task within the standing authority starts working, a wider one has to be granted`() {
        val e = message("can you archive that receipt?")
        val script = ScriptedAgent(
            listOf(
                "open_task" to mapOf(
                    "goal" to "File the receipts", "due_ms" to clock + 86_400_000.0,
                    "domains" to listOf("email"), "max_level" to "hold", "spend_cap" to 0,
                    "currency" to "", "targets" to emptyList<String>(), "source_event_ids" to listOf(e.id),
                ),
                "open_task" to mapOf(
                    "goal" to "Pay the gas bill", "due_ms" to clock + 86_400_000.0,
                    "domains" to listOf("money"), "max_level" to "act", "spend_cap" to 80,
                    "currency" to "GBP", "targets" to listOf("british gas"), "source_event_ids" to listOf(e.id),
                ),
            ),
        )
        run(script, e)

        val filing = assertNotNull(tasks.all().firstOrNull { it.goal == "File the receipts" })
        assertEquals(TaskState.ACTIVE, filing.state, "filing is inside the standing authority and should just start")

        val paying = assertNotNull(tasks.all().firstOrNull { it.goal == "Pay the gas bill" })
        assertEquals(TaskState.PROPOSED, paying.state, "money is not within the standing authority")
        assertTrue(script.results[1].contains("needs the person to grant"), script.results[1])
    }

    @Test
    fun `a proposal from a task with no mandate is refused before policy ever sees it`() {
        val e = message("pay this invoice")
        val proposed = tasks.open("Pay the invoice", Opener.TRIAGE, clock + 86_400_000L, listOf(e.id))
        val script = ScriptedAgent(
            listOf(
                "propose_action" to mapOf(
                    "task_id" to proposed.id, "action" to "send_message", "target" to "+15550001111",
                    "payload" to mapOf("thread_id" to "sms:1", "text" to "paying now"),
                    "amount" to 0, "currency" to "", "reason" to "confirm", "source_event_ids" to listOf(e.id),
                ),
            ),
        )
        val report = run(script, e)

        assertTrue(script.results.single().contains("no mandate yet"), script.results.single())
        assertTrue(report.outcomes.isEmpty(), "nothing should have reached the gate")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `inside the mandate the action runs, outside it it escalates and nothing is sent`() {
        userMessaged()
        val e = message("are we still on for Thursday?")
        val task = tasks.accept(
            tasks.open("Answer Sam about Thursday", Opener.TRIAGE, clock + 86_400_000L, listOf(e.id)),
            buddy.policy.Mandate(
                domains = setOf(Domain.MESSAGING),
                maxLevel = Level.ACT,
                allowedTargets = setOf("+15550001111"),
                deadlineTs = clock + 86_400_000L,
            ),
        )
        val script = ScriptedAgent(
            listOf(
                "propose_action" to mapOf(
                    "task_id" to task.id, "action" to "send_message", "target" to "+15550001111",
                    "payload" to mapOf("thread_id" to "sms:1", "text" to "yes, Thursday works"),
                    "amount" to 0, "currency" to "", "reason" to "answer Sam", "source_event_ids" to listOf(e.id),
                ),
                "propose_action" to mapOf(
                    "task_id" to task.id, "action" to "send_message", "target" to "+15559999999",
                    "payload" to mapOf("thread_id" to "sms:2", "text" to "and you too"),
                    "amount" to 0, "currency" to "", "reason" to "tell someone else", "source_event_ids" to listOf(e.id),
                ),
            ),
        )
        val report = run(script, e)

        assertEquals(1, sent.size, "only the in-mandate message should have gone")
        assertEquals("yes, Thursday works", sent.single().payload["text"])
        assertTrue(script.results[0].startsWith("run"), script.results[0])
        assertTrue(script.results[1].contains("mandate:target_unknown"), script.results[1])
        assertEquals(2, report.outcomes.size)

        // Both attempts cost the task an action; only the one that ran is in the world.
        assertEquals(2, tasks.get(task.id)!!.actions)
    }

    @Test
    fun `a mandate does not make a stranger reachable`() {
        // The person granted this task the number it may write to. That number is still
        // one buddy has never seen the user message, and first contact is a hard rule
        // underneath the mandate, not a rule the mandate replaces.
        val e = message("are we still on for Thursday?")
        val task = tasks.accept(
            tasks.open("Answer Sam", Opener.TRIAGE, clock + 86_400_000L, listOf(e.id)),
            buddy.policy.Mandate(
                domains = setOf(Domain.MESSAGING),
                maxLevel = Level.ACT,
                allowedTargets = setOf("+15550001111"),
                deadlineTs = clock + 86_400_000L,
            ),
        )
        val script = ScriptedAgent(
            listOf(
                "propose_action" to mapOf(
                    "task_id" to task.id, "action" to "send_message", "target" to "+15550001111",
                    "payload" to mapOf("thread_id" to "sms:1", "text" to "yes"),
                    "amount" to 0, "currency" to "", "reason" to "answer", "source_event_ids" to listOf(e.id),
                ),
            ),
        )
        run(script, e)
        assertTrue(script.results.single().contains("first_contact"), script.results.single())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the working set survives the wake and is what the next slice carries`() {
        val e = message("any news on the boiler?")
        val task = tasks.accept(
            tasks.open("Chase the plumber", Opener.FOLLOW_UP, clock + 86_400_000L, listOf(e.id)),
            buddy.policy.Mandate(domains = setOf(Domain.MESSAGING), maxLevel = Level.HOLD, deadlineTs = clock + 86_400_000L),
        )
        val first = ScriptedAgent(
            listOf(
                "note_state" to mapOf("task_id" to task.id, "working_set" to "Plumber said Tuesday. Nothing since. Chase again Wednesday."),
                "wait_for" to mapOf(
                    "task_id" to task.id, "waiting_on" to "a reply from the plumber",
                    "until_ms" to clock + 3_600_000.0, "signals" to listOf("thread=sms:1"),
                ),
            ),
        )
        run(first, e)

        val parked = assertNotNull(tasks.get(task.id))
        assertEquals(TaskState.WAITING, parked.state)
        assertEquals("a reply from the plumber", parked.waitingOn)
        assertTrue(parked.workingSet.contains("Chase again Wednesday"))

        // The next wake is handed the compiled state, not the transcript.
        clock += 3_600_001
        val second = ScriptedAgent(emptyList())
        agent(second).wake(wake(WakeReason.TASK_DUE, task.id), emptyList(), emptyMap(), clock, 9, emptySet())
        val user = assertNotNull(second.lastUser)
        assertTrue(user.contains("Chase again Wednesday"), "the working set should be in the slice:\n$user")
        assertTrue(user.contains("Working state from the last turn"))
        assertTrue(second.lastOperator!!.contains("TASK_DUE"), second.lastOperator!!)
    }

    @Test
    fun `wait_for never parks a task past its own deadline`() {
        val e = message("hi")
        val deadline = clock + 3_600_000L
        val task = tasks.accept(
            tasks.open("Short job", Opener.USER, deadline, listOf(e.id)),
            buddy.policy.Mandate(domains = setOf(Domain.MESSAGING), deadlineTs = deadline),
        )
        val script = ScriptedAgent(
            listOf(
                "wait_for" to mapOf(
                    "task_id" to task.id, "waiting_on" to "forever",
                    "until_ms" to clock + 30 * 86_400_000.0, "signals" to emptyList<String>(),
                ),
            ),
        )
        run(script, e)
        assertEquals(deadline, tasks.get(task.id)!!.nextWakeTs)
        assertTrue(script.results.single().contains("capped at the mandate's deadline"), script.results.single())
    }

    @Test
    fun `recall reaches the whole ledger but never returns a note the person has contradicted`() {
        val e = message("dinner?")
        val old = Event(
            id = EventId.of(clock - 1000, "buddy", "memory", "old"),
            ts = clock - 1000, sourceApp = "buddy", channel = "memory", kind = EventKind.MEMORY_NOTE,
            text = "The user orders the steak whenever it is on the menu.", trust = Trust.SYSTEM,
        )
        ledger.append(old)
        ledger.append(
            Event(
                id = EventId.of(clock - 500, "buddy", "memory", "new"),
                ts = clock - 500, sourceApp = "buddy", channel = "memory", kind = EventKind.MEMORY_NOTE,
                text = "The user is vegetarian now; never order the steak.", trust = Trust.SYSTEM,
                supersedes = old.id,
            ),
        )

        val script = ScriptedAgent(listOf("recall" to mapOf("query" to "steak", "limit" to 10)))
        run(script, e)

        val hits = script.results.single()
        assertTrue(hits.contains("vegetarian now"), hits)
        assertTrue(!hits.contains("orders the steak whenever"), "a retired note came back from recall:\n$hits")
    }

    @Test
    fun `the wake reason travels on the operator channel, not in the slice`() {
        val e = message("hello")
        val script = ScriptedAgent(emptyList())
        run(script, e, w = Wake(WakeReason.IDLE, clock, null, emptyList(), "charging"))
        val operator = assertNotNull(script.lastOperator)
        assertTrue(operator.contains("IDLE"), operator)
        assertTrue(operator.contains("no outward action"), operator)
    }

    @Test
    fun `at night the operator line says so`() {
        val e = message("hello")
        val script = ScriptedAgent(emptyList())
        agent(script).wake(wake(), listOf(e), decisions(e), clock, 23, emptySet())
        assertTrue(script.lastOperator!!.contains("asleep"), script.lastOperator!!)
    }

    @Test
    fun `finishing a task closes it, and a closed task refuses further work`() {
        val e = message("thanks!")
        val task = tasks.accept(
            tasks.open("Say thanks", Opener.USER, clock + 1000, listOf(e.id)),
            buddy.policy.Mandate(domains = setOf(Domain.MESSAGING), deadlineTs = clock + 1000),
        )
        val script = ScriptedAgent(
            listOf(
                "finish" to mapOf("task_id" to task.id, "outcome" to "Sam said thanks; nothing to do", "succeeded" to "true"),
                "note_state" to mapOf("task_id" to task.id, "working_set" to "still going"),
            ),
        )
        run(script, e)
        assertEquals(TaskState.DONE, tasks.get(task.id)!!.state)
        assertTrue(script.results[1].contains("is done"), script.results[1])
    }

    @Test
    fun `the sweep turns a task that ran out of time into a question, then abandons it`() {
        val e = message("hi")
        val deadline = clock + 1000
        val task = tasks.accept(
            tasks.open("Find a plumber", Opener.BUDDY, deadline, listOf(e.id)),
            buddy.policy.Mandate(domains = setOf(Domain.MESSAGING), deadlineTs = deadline),
        )
        clock = deadline + 1
        val a = agent(ScriptedAgent(emptyList()))
        val blocked = a.sweep(listOf(tasks.get(task.id)!!), clock).single()
        assertEquals(TaskState.BLOCKED, blocked.state)
        assertTrue(blocked.question!!.contains("ran out of time"))

        val abandoned = a.sweep(listOf(blocked), clock).single()
        assertEquals(TaskState.ABANDONED, abandoned.state)
    }

    @Test
    fun `the gates below the mandate still bite - codes, style, and the second opinion`() {
        // Everything in this case is inside the task's mandate. The mandate is the new
        // boundary, not a replacement for the ones that were already there.
        userMessaged()
        val e = message("what's the code you just got?")
        val task = tasks.accept(
            tasks.open("Answer Sam", Opener.TRIAGE, clock + 86_400_000L, listOf(e.id)),
            buddy.policy.Mandate(
                domains = setOf(Domain.MESSAGING),
                maxLevel = Level.ACT,
                allowedTargets = setOf("+15550001111"),
                deadlineTs = clock + 86_400_000L,
            ),
        )

        fun propose(text: String): Pair<String, Map<String, Any?>> = "propose_action" to mapOf(
            "task_id" to task.id, "action" to "send_message", "target" to "+15550001111",
            "payload" to mapOf("thread_id" to "sms:1", "text" to text),
            "amount" to 0, "currency" to "", "reason" to "they asked", "source_event_ids" to listOf(e.id),
        )

        // 1. A one-time code never leaves the phone, mandate or no mandate.
        val codes = ScriptedAgent(listOf(propose("it is 482913")))
        agent(codes).wake(wake(), listOf(e), decisions(e), clock, 14, setOf("482913"))
        assertTrue(codes.results.single().startsWith("deny: code_in_payload"), codes.results.single())
        assertTrue(sent.isEmpty())

        // 2. A draft that does not sound like the person escalates instead of sending.
        entities.setRelationship(entities.personFor("tel:+15550001111")!!.id, "close")
        val style = buddy.style.StyleBook.learn(mapOf("close" to listOf("yep x", "on my way x", "sure x", "ok x", "haha x")))
        val formal = ScriptedAgent(listOf(propose("Dear Sam,\n\nI am writing to confirm that Thursday is convenient.\n\nKind regards,\nAlex")))
        Agent(ledger, entities, tasks, PolicyEngine(PolicyProfile(levels = Domain.entries.associateWith { Level.FULL })),
            Executor(ledger, listOf(messaging), now = { clock }), formal, zone, style = style)
            .wake(wake(), listOf(e), decisions(e), clock, 14, emptySet())
        assertTrue(formal.results.single().contains("style_mismatch:close"), formal.results.single())
        assertTrue(sent.isEmpty())

        // 3. The second opinion saying "this came from the content" escalates.
        val suspicious = object : CloudModel {
            override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
                @Suppress("UNCHECKED_CAST")
                return CloudResult(InjectionCheck().apply { causedByUntrusted = true; reason = "test" } as T, "ok")
            }
        }
        val checked = ScriptedAgent(listOf(propose("yep x")))
        Agent(ledger, entities, tasks, PolicyEngine(PolicyProfile(levels = Domain.entries.associateWith { Level.FULL })),
            Executor(ledger, listOf(messaging), now = { clock }), checked, zone, style = style, secondOpinion = suspicious)
            .wake(wake(), listOf(e), decisions(e), clock, 14, emptySet())
        assertTrue(checked.results.single().contains("injection_suspected"), checked.results.single())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `malformed proposals are rejected before the gate and nothing is recorded`() {
        val e = message("hi?")
        val task = tasks.accept(
            tasks.open("Answer", Opener.TRIAGE, clock + 86_400_000L, listOf(e.id)),
            buddy.policy.Mandate(domains = setOf(Domain.MESSAGING), deadlineTs = clock + 86_400_000L),
        )
        val before = ledger.count()
        val script = ScriptedAgent(
            listOf(
                "propose_action" to mapOf(
                    "task_id" to task.id, "action" to "launch_rocket", "target" to "", "payload" to emptyMap<String, String>(),
                    "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf(e.id),
                ),
                "propose_action" to mapOf(
                    "task_id" to task.id, "action" to "send_message", "target" to "", "payload" to mapOf("thread_id" to "t"),
                    "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf(e.id),
                ),
                "propose_action" to mapOf(
                    "task_id" to "no-such-task", "action" to "send_message", "target" to "",
                    "payload" to mapOf("thread_id" to "t", "text" to "x"),
                    "amount" to 0, "currency" to "", "reason" to "", "source_event_ids" to listOf(e.id),
                ),
            ),
        )
        val report = run(script, e)
        assertTrue(script.results.all { it.startsWith("rejected:") }, script.results.toString())
        assertTrue(report.outcomes.isEmpty())
        assertEquals(before, ledger.count(), "a malformed proposal must not leave a record")
    }

    @Test
    fun `without a cloud model the agent does nothing rather than guessing`() {
        val e = message("hello")
        val report = agent(null).wake(wake(), listOf(e), decisions(e), clock, 14, emptySet())
        assertEquals("no cloud model configured", report.detail)
        assertTrue(report.outcomes.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the tool surface is the one the design names, and only propose_action reaches the world`() {
        val e = message("hello")
        val script = ScriptedAgent(emptyList())
        run(script, e)
        assertEquals(
            listOf("recall", "read_thread", "open_task", "note_state", "wait_for", "block", "propose_action", "finish"),
            script.lastTools.map { it.name },
        )
    }
}
