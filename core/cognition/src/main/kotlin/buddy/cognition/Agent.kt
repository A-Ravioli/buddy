package buddy.cognition

import buddy.actuation.Actions
import buddy.actuation.Executor
import buddy.entities.EntityStore
import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.Mandate
import buddy.policy.PolicyContext
import buddy.policy.PolicyEngine
import buddy.policy.Proposal
import buddy.policy.Verdict
import buddy.style.StyleBook
import buddy.tasks.Opener
import buddy.tasks.Signal
import buddy.tasks.Task
import buddy.tasks.TaskState
import buddy.tasks.TaskStore
import buddy.tasks.Wake
import buddy.tasks.WakeReason
import buddy.triage.TriageDecision
import java.time.ZoneId

/** What one wake did. */
data class WakeReport(
    val wake: Wake,
    val outcomes: List<ActOutcome>,
    val agent: AgentResult?,
    /** Ids of tasks this wake opened, moved, or finished. */
    val tasksTouched: List<String> = emptyList(),
    val detail: String? = null,
)

/**
 * The holistic agent (docs/07).
 *
 * One loop, woken for a reason, working the tasks it owns. It replaces the two-cycle
 * act loop rather than sitting beside it: the cycle is now just one of the reasons to
 * wake up.
 *
 * The shape that matters is what the model can and cannot do. It can read (`recall`,
 * `read_thread`) and it can manage its own jobs (`open_task`, `note_state`,
 * `wait_for`, `block`, `finish`) — none of which touch the world. To touch the world
 * it has exactly one tool, `propose_action`, and every call goes through the mandate,
 * the style book, the second opinion, and the policy engine before a connector sees
 * it. Read tools are free, bookkeeping is free, and the blast radius is unchanged.
 */
class Agent(
    private val ledger: Ledger,
    private val entities: EntityStore,
    private val tasks: TaskStore,
    policy: PolicyEngine,
    private val executor: Executor,
    private val cloud: CloudAgent?,
    private val zone: ZoneId,
    style: StyleBook? = null,
    secondOpinion: CloudModel? = null,
    private val sliceBudgetChars: Int = 40_000,
    private val maxTurns: Int = 12,
) {
    private val slices = SliceBuilder(ledger, entities, zone, sliceBudgetChars)
    private val gate = ProposalGate(entities, policy, executor, zone, style, secondOpinion)

    /**
     * Handles one wake. [triggers] are the events that caused it (a new message, a
     * signal, the window since the last cycle); [decisions] their triage, where there
     * is any.
     */
    fun wake(
        w: Wake,
        triggers: List<Event>,
        decisions: Map<String, TriageDecision>,
        nowTs: Long,
        localHour: Int,
        knownCodes: Set<String>,
        spentToday: Map<String, Double> = emptyMap(),
        userTurn: String? = null,
    ): WakeReport {
        if (cloud == null) return WakeReport(w, emptyList(), null, emptyList(), "no cloud model configured")

        val open = tasks.open()
        val focus = w.taskId?.let { id -> open.firstOrNull { it.id == id } }
        val touched = LinkedHashSet<String>()
        val outcomes = ArrayList<ActOutcome>()
        // Tasks are re-read from the store by id on every tool call, so a tool that
        // moves a task is seen by the next one. Holding a stale copy across a turn is
        // how a state machine quietly stops being one.
        val byId = triggers.associateBy { it.id }

        val slice = slices.forWake(
            wake = w,
            tasks = open,
            focus = focus,
            triggers = triggers,
            decisions = decisions,
            nowTs = nowTs,
            localHour = localHour,
            userTurn = userTurn,
        )

        val result = cloud.run(
            system = listOf(Prompts.CONSTITUTION, Prompts.AGENT_PLAYBOOK),
            user = slice.render(),
            operator = operatorLine(w, localHour),
            tools = toolSet(),
            maxTurns = maxTurns,
        ) { name, input ->
            try {
                when (name) {
                    "recall" -> recall(input)
                    "read_thread" -> readThread(input)
                    "open_task" -> openTask(input, triggers, nowTs).also { touched += it.second }.first
                    "note_state" -> withTask(input) { t -> tasks.noteState(t, str(input, "working_set")); "noted" }
                        .also { touched.addTaskFrom(input) }
                    "wait_for" -> waitFor(input, nowTs).also { touched.addTaskFrom(input) }
                    "block" -> blockTask(input).also { touched.addTaskFrom(input) }
                    "finish" -> finishTask(input).also { touched.addTaskFrom(input) }
                    "propose_action" -> propose(input, byId, nowTs, localHour, knownCodes, spentToday, outcomes)
                        .also { touched.addTaskFrom(input) }
                    else -> "unknown tool"
                }
            } catch (t: Throwable) {
                "error: ${t.message}"
            }
        }

        // The cloud's own cost lands on the task that caused the wake, so the mandate's
        // token cap is spent by the work it paid for.
        focus?.let { f ->
            tasks.get(f.id)?.let { current ->
                if (current.open) tasks.spend(current, tokens = result.inputTokens + result.outputTokens)
            }
        }
        return WakeReport(w, outcomes, result, touched.toList())
    }

    /**
     * The sweep. Tasks past their deadline or untouched for a week stop being jobs and
     * become either a question or a closed file; nothing is left silently waiting.
     */
    fun sweep(stale: List<Task>, nowTs: Long): List<Task> = stale.map { t ->
        val current = tasks.get(t.id) ?: t
        if (!current.open) {
            current
        } else if (current.state == TaskState.BLOCKED) {
            tasks.abandon(current, "no answer before the deadline")
        } else {
            tasks.block(
                current,
                question = "\"${current.goal}\" ran out of time. What should buddy do?",
                options = listOf("Give it longer", "Drop it", "Do it yourself"),
                recommendation = "Drop it unless it still matters; it has been open since ${java.time.Instant.ofEpochMilli(current.createdTs).atZone(zone).toLocalDate()}.",
            )
        }
    }

    // ---- Tools -----------------------------------------------------------------------

    private fun recall(input: Map<String, Any?>): String {
        val query = str(input, "query")
        if (query.isBlank()) return "query was empty"
        val limit = (input["limit"] as? Number)?.toInt()?.coerceIn(1, 25) ?: 8
        val hits = ledger.search(query, limit * 3)
            .filter { it.kind != EventKind.TRIAGE && it.kind != EventKind.TASK }
            // A note the user has since contradicted must not come back (docs/07, section 6).
            .filter { it.kind != EventKind.MEMORY_NOTE || ledger.corrections(it.id).isEmpty() }
            .take(limit)
        return if (hits.isEmpty()) "nothing in the ledger matches \"$query\"" else Envelope.renderAll(hits, zone)
    }

    private fun readThread(input: Map<String, Any?>): String {
        val id = str(input, "thread_id")
        val limit = (input["limit"] as? Number)?.toInt()?.coerceIn(1, 50) ?: 12
        val events = ledger.thread(id, 200).takeLast(limit)
        return if (events.isEmpty()) "no thread $id" else Envelope.renderAll(events, zone)
    }

    /**
     * Opens a job. A task asking for no more than the standing authority starts work
     * straight away; anything wider starts PROPOSED and has to be granted, which is
     * the user's one job in this design (docs/07, section 4).
     */
    private fun openTask(input: Map<String, Any?>, triggers: List<Event>, nowTs: Long): Pair<String, String> {
        val goal = str(input, "goal")
        if (goal.isBlank()) return "rejected: goal was empty" to ""
        val due = (input["due_ms"] as? Number)?.toLong()?.takeIf { it > nowTs } ?: (nowTs + 3 * 86_400_000L)
        val sources = (input["source_event_ids"] as? List<*>)?.mapNotNull { it as? String }
            ?.filter { id -> triggers.any { it.id == id } } ?: emptyList()
        val wanted = Mandate(
            domains = (input["domains"] as? List<*>)?.mapNotNull { d -> runCatching { Domain.valueOf((d as String).uppercase()) }.getOrNull() }?.toSet()
                ?: emptySet(),
            maxLevel = runCatching { Level.valueOf(str(input, "max_level").uppercase()) }.getOrNull() ?: Level.HOLD,
            spendCap = (input["spend_cap"] as? Number)?.toDouble()?.takeIf { it > 0 },
            currency = str(input, "currency").ifBlank { null },
            allowedTargets = (input["targets"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            deadlineTs = due,
        )
        val task = tasks.open(goal, Opener.BUDDY, due, sources, wanted)
        return if (withinStandingAuthority(wanted)) {
            val active = tasks.accept(task, wanted, due)
            "opened ${active.id} and started; mandate ${TaskStore.describe(wanted)}" to active.id
        } else {
            tasks.note(task.id, "note", "needs a wider mandate than the standing one: ${TaskStore.describe(wanted)}")
            "opened ${task.id} as proposed; it needs the person to grant ${TaskStore.describe(wanted)} before it can act — " +
                "use block to ask" to task.id
        }
    }

    /** Standing authority: the reversible, device-and-inbox work the profile already allows. */
    private fun withinStandingAuthority(m: Mandate): Boolean {
        val standing = Mandate.default(m.deadlineTs)
        return m.spendCap == null &&
            m.maxLevel.rank <= standing.maxLevel.rank &&
            standing.domains.containsAll(m.domains)
    }

    private fun waitFor(input: Map<String, Any?>, nowTs: Long): String = withTask(input) { t ->
        val until = (input["until_ms"] as? Number)?.toLong()?.takeIf { it > nowTs } ?: (nowTs + 86_400_000L)
        val signals = (input["signals"] as? List<*>)?.mapNotNull { it as? String }?.map(Signal::decode)?.filter { !it.empty }
            ?: emptyList()
        // Never park past the mandate's deadline: a task may not outlive its authority.
        val capped = minOf(until, t.mandate.deadlineTs)
        tasks.wait(t, str(input, "waiting_on").ifBlank { "something to happen" }, capped, signals)
        buildString {
            append("waiting until $capped")
            if (capped < until) append(" (capped at the mandate's deadline)")
            append(if (signals.isEmpty()) ", no signal registered, so buddy only looks again at that time" else ", ${signals.size} signal(s) registered")
        }
    }

    private fun blockTask(input: Map<String, Any?>): String = withTask(input) { t ->
        val options = (input["options"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() } ?: emptyList()
        tasks.block(t, str(input, "question"), options, str(input, "recommendation"))
        "blocked; the person sees this in the next brief"
    }

    private fun finishTask(input: Map<String, Any?>): String = withTask(input) { t ->
        val outcome = str(input, "outcome")
        if (str(input, "succeeded").equals("false", ignoreCase = true)) {
            tasks.abandon(t, outcome.ifBlank { "gave up" })
            "abandoned"
        } else {
            tasks.finish(t, outcome.ifBlank { "done" })
            "done"
        }
    }

    private fun propose(
        input: Map<String, Any?>,
        byId: Map<String, Event>,
        nowTs: Long,
        localHour: Int,
        knownCodes: Set<String>,
        spentToday: Map<String, Double>,
        outcomes: MutableList<ActOutcome>,
    ): String {
        val task = (input["task_id"] as? String)?.let { tasks.get(it) }
            ?: return "rejected: task_id must name a task from this wake; open one first"
        if (!task.open) return "rejected: ${task.id} is ${task.state.name.lowercase()}"
        if (task.state == TaskState.PROPOSED) {
            return "rejected: ${task.id} has no mandate yet — ask with block before acting"
        }

        val (named, error) = toProposal(input, byId)
        if (named == null) return "rejected: $error"
        // A wake with no triggers at all (a task's own clock coming round) still has
        // the events the task was opened about, so an action is never left unattributed.
        val proposal = if (named.sourceEventIds.isEmpty()) named.copy(sourceEventIds = task.sourceEventIds) else named

        val sources = proposal.sourceEventIds.mapNotNull { byId[it] ?: ledger.get(it) } +
            task.sourceEventIds.mapNotNull { ledger.get(it) }
        val outcome = gate.run(
            proposal,
            sources.distinctBy { it.id },
            PolicyContext(
                nowTs = nowTs,
                localHour = localHour,
                spentToday = spentToday,
                knownCodes = knownCodes,
                mandate = task.mandate,
                taskTargets = taskTargets(task),
                taskSpend = task.spend,
                taskActions = task.actions,
                taskTokens = task.tokens,
            ),
        )
        outcomes.add(outcome)

        // The task pays for what it did. A held or escalated action costs an action
        // slot too: a task that proposes twenty things nobody approved is a task that
        // has spent its budget, and should say so rather than keep going.
        tasks.get(task.id)?.let { current ->
            val committed = if (outcome.verdict is Verdict.Run) proposal.amount ?: 0.0 else 0.0
            tasks.spend(current, amount = committed, actions = 1)
        }
        tasks.note(
            task.id,
            "verdict",
            "${proposal.spec.name} -> ${outcome.verdict.name}: ${outcome.verdict.reasons.joinToString(", ")}",
            aboutEventId = outcome.record.id,
            proposalId = proposal.id,
        )
        return "${outcome.verdict.name}: ${outcome.verdict.reasons.joinToString(", ")}" +
            (outcome.styleScore?.let { " (style %.2f)".format(it) } ?: "")
    }

    private fun toProposal(input: Map<String, Any?>, byId: Map<String, Event>): Pair<Proposal?, String?> {
        val name = input["action"] as? String ?: return null to "missing action"
        val spec = Actions.byName[name] ?: return null to "unknown action $name"
        val payload = (input["payload"] as? Map<*, *>)?.entries
            ?.mapNotNull { (k, v) -> if (k is String && v != null) k to v.toString() else null }?.toMap() ?: emptyMap()
        val keys = Actions.payloadKeys[name] ?: emptyList()
        val missing = keys.filter { it !in payload && it in REQUIRED_KEYS }
        if (missing.isNotEmpty()) return null to "payload missing ${missing.joinToString()}"
        val sources = (input["source_event_ids"] as? List<*>)?.mapNotNull { it as? String }?.filter { it in byId.keys }
            ?: emptyList()
        return Proposal(
            id = "p-" + java.util.UUID.randomUUID().toString().take(8),
            spec = spec,
            target = (input["target"] as? String)?.ifBlank { null },
            payload = payload,
            amount = (input["amount"] as? Number)?.toDouble()?.takeIf { it > 0 },
            currency = (input["currency"] as? String)?.ifBlank { null },
            reason = (input["reason"] as? String).orEmpty(),
            sourceEventIds = sources,
        ) to null
    }

    /** Counterparties this task's own events introduced, for a mandate with no explicit list. */
    private fun taskTargets(task: Task): Set<String> =
        task.sourceEventIds.mapNotNull { ledger.get(it) }
            .flatMap { listOfNotNull(it.actor, it.structured["counterparty"], it.threadId) }
            .toSet()

    private fun withTask(input: Map<String, Any?>, block: (Task) -> String): String {
        val id = input["task_id"] as? String ?: return "rejected: missing task_id"
        val task = tasks.get(id) ?: return "rejected: no task $id"
        if (!task.open) return "rejected: $id is ${task.state.name.lowercase()}"
        return block(task)
    }

    private fun MutableSet<String>.addTaskFrom(input: Map<String, Any?>) {
        (input["task_id"] as? String)?.let { add(it) }
    }

    private fun str(input: Map<String, Any?>, key: String): String = (input[key] as? String).orEmpty().trim()

    /**
     * The operator line: why the agent is awake and what it may not do this turn. It
     * goes on the mid-conversation system channel, never in the user turn, so it
     * carries operator authority and leaves the cached prefix alone.
     */
    private fun operatorLine(w: Wake, localHour: Int): String = buildString {
        append("Wake: ${w.reason.name}.")
        if (w.detail.isNotBlank()) append(" ${w.detail}.")
        w.taskId?.let { append(" Focus: task $it.") }
        when (w.reason) {
            WakeReason.WORK, WakeReason.SIGNAL, WakeReason.TASK_DUE ->
                append(" Work the focused task or the trigger; do not re-plan the day.")
            WakeReason.CYCLE -> append(" Review every open task, then produce nothing outward that the tasks did not need.")
            WakeReason.IDLE -> append(" Consolidation only: no outward action this turn.")
            WakeReason.USER_TURN -> append(" The person is present; answer them first.")
            WakeReason.HOLD -> append(" A held action came due; the executor has already run it.")
        }
        if (localHour >= 22 || localHour < 8) append(" Local hour $localHour: the person is probably asleep, so nothing outward unless it is an emergency.")
    }

    // ---- Tool schemas ----------------------------------------------------------------

    private fun toolSet(): List<ToolDef> = listOf(
        ToolDef(
            name = "recall",
            description = "Search everything buddy has ever recorded: messages, notes, actions, people. Use it before asking the person something they have already told you.",
            properties = mapOf(
                "query" to mapOf("type" to "string", "description" to "Words to search for. Not a question; the words you expect to appear."),
                "limit" to mapOf("type" to "number", "description" to "How many events to return, 1 to 25."),
            ),
            required = listOf("query", "limit"),
        ),
        ToolDef(
            name = "read_thread",
            description = "Read the most recent messages in one conversation.",
            properties = mapOf(
                "thread_id" to mapOf("type" to "string", "description" to "The thread id from an event."),
                "limit" to mapOf("type" to "number", "description" to "How many messages, 1 to 50."),
            ),
            required = listOf("thread_id", "limit"),
        ),
        ToolDef(
            name = "open_task",
            description = "Open a job buddy will own until it is finished. Ask for the narrowest authority that could do it: " +
                "a task needing no more than filing, calendar and device work starts immediately, anything wider has to be granted by the person.",
            properties = mapOf(
                "goal" to mapOf("type" to "string", "description" to "What done looks like, in one sentence, in the person's terms."),
                "due_ms" to mapOf("type" to "number", "description" to "Epoch milliseconds by which the world needs this done."),
                "domains" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to Domain.entries.map { it.name.lowercase() }), "description" to "Domains this task may touch."),
                "max_level" to mapOf("type" to "string", "enum" to Level.entries.map { it.name.lowercase() }, "description" to "The most autonomy this task should have."),
                "spend_cap" to mapOf("type" to "number", "description" to "Total money this task may commit. 0 for none."),
                "currency" to mapOf("type" to "string", "description" to "ISO code if spend_cap is set, else empty."),
                "targets" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Who this task may contact. Empty means whoever its own events introduced."),
                "source_event_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Events this task is about."),
            ),
            required = listOf("goal", "due_ms", "domains", "max_level", "spend_cap", "currency", "targets", "source_event_ids"),
        ),
        ToolDef(
            name = "note_state",
            description = "Replace a task's working state with what the next wake needs to know: what is known, what has been tried, what is left, what it is waiting for. " +
                "Write it whole every time; it is the only thing carried forward, and it is capped at ${Task.MAX_WORKING_SET} characters.",
            properties = mapOf(
                "task_id" to mapOf("type" to "string"),
                "working_set" to mapOf("type" to "string", "description" to "The complete state. Not a diff, not a transcript."),
            ),
            required = listOf("task_id", "working_set"),
        ),
        ToolDef(
            name = "wait_for",
            description = "Park a task until a time, or until something happens. Register signals so buddy wakes the moment it does rather than at the deadline.",
            properties = mapOf(
                "task_id" to mapOf("type" to "string"),
                "waiting_on" to mapOf("type" to "string", "description" to "One phrase for the timeline: 'a reply from Sam'."),
                "until_ms" to mapOf("type" to "number", "description" to "Epoch milliseconds to wake at if nothing happens."),
                "signals" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Each signal is 'thread=<id>', 'actor=<who>', 'app=<package>', 'kind=<EVENT_KIND>', or 'field=<key>' / 'field=<key>=<value>'; " +
                        "join several with ';' to require all of them.",
                ),
            ),
            required = listOf("task_id", "waiting_on", "until_ms", "signals"),
        ),
        ToolDef(
            name = "block",
            description = "Ask the person. This is the only way to reach them. Use it when the decision is theirs, when the task needs authority it does not have, or when you are unsure.",
            properties = mapOf(
                "task_id" to mapOf("type" to "string"),
                "question" to mapOf("type" to "string", "description" to "One sentence, naming who and what."),
                "options" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Two to four short options."),
                "recommendation" to mapOf("type" to "string", "description" to "Which one you would pick and why, in one sentence."),
            ),
            required = listOf("task_id", "question", "options", "recommendation"),
        ),
        ToolDef(
            name = "propose_action",
            description = "Propose one action for buddy to take, on behalf of a task. Policy decides whether it runs, waits, or goes to the person; " +
                "the result tells you which. 'escalate' and 'hold' are normal outcomes, not errors. Actions:\n" +
                Actions.all.joinToString("\n") { "- ${it.name} (${it.domain.name.lowercase()}, ${it.reversibility.name.lowercase()}): ${it.description}" },
            properties = mapOf(
                "task_id" to mapOf("type" to "string", "description" to "The task this action serves."),
                "action" to mapOf("type" to "string", "enum" to Actions.all.map { it.name }),
                "target" to mapOf("type" to "string", "description" to "The counterparty. Empty for self-only actions."),
                "payload" to mapOf("type" to "object", "description" to "Action payload as documented. All values are strings.", "additionalProperties" to mapOf("type" to "string")),
                "amount" to mapOf("type" to "number", "description" to "Money involved, if any. 0 otherwise."),
                "currency" to mapOf("type" to "string", "description" to "ISO currency code if amount is set, else empty."),
                "reason" to mapOf("type" to "string", "description" to "One sentence for the timeline: why this action, now."),
                "source_event_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Ids of the events this action responds to."),
            ),
            required = listOf("task_id", "action", "target", "payload", "amount", "currency", "reason", "source_event_ids"),
        ),
        ToolDef(
            name = "finish",
            description = "Close a task: done, or given up on. Say what actually happened, including what did not.",
            properties = mapOf(
                "task_id" to mapOf("type" to "string"),
                "outcome" to mapOf("type" to "string", "description" to "One sentence. What happened, in the person's terms."),
                "succeeded" to mapOf("type" to "string", "enum" to listOf("true", "false")),
            ),
            required = listOf("task_id", "outcome", "succeeded"),
        ),
    )

    companion object {
        /** Payload keys without which an action does not mean anything. */
        val REQUIRED_KEYS = setOf(
            "thread_id", "text", "notification_key", "message_id", "label", "event_id",
            "response", "title", "begin", "end", "until",
        )
    }
}
