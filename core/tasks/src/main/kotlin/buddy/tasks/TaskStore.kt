package buddy.tasks

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Ledger
import buddy.ledger.Trust
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.Mandate

/**
 * Tasks, persisted as ledger events (docs/07, decision 15).
 *
 * There is no task table. A task is the highest-revision snapshot event for its id,
 * and its journal is the journal events in the same thread. That buys three things
 * for free: a task survives the process dying, its whole history replays like
 * everything else, and the timeline needs no second source of truth.
 *
 * Snapshots supersede their predecessor, the way a correction supersedes the event it
 * corrects, so the ledger stays append-only.
 */
class TaskStore(
    private val ledger: Ledger,
    private val now: () -> Long = System::currentTimeMillis,
    /** How far back to scan for snapshots. Tasks are few; this is generous. */
    private val scan: Int = 3_000,
) {
    // ---- Reading ---------------------------------------------------------------------

    /** Every task the ledger knows about, newest activity first. */
    fun all(): List<Task> =
        ledger.recent(scan)
            .filter { it.kind == EventKind.TASK && it.channel == CHANNEL }
            .mapNotNull(::toTask)
            .groupBy { it.id }
            .mapNotNull { (_, revs) -> revs.maxByOrNull { it.rev } }
            .sortedByDescending { it.updatedTs }

    fun open(): List<Task> = all().filter { it.open }

    fun get(id: String): Task? =
        ledger.thread(id, 500)
            .filter { it.kind == EventKind.TASK && it.channel == CHANNEL }
            .mapNotNull(::toTask)
            .maxByOrNull { it.rev }

    /**
     * The task's record, in the order it happened.
     *
     * Sorted by the entry's own sequence, not by timestamp: several lines are written
     * inside one millisecond all the time (a task is opened and accepted in the same
     * breath), and ordering those by id would order them by hash.
     */
    fun journal(id: String): List<JournalEntry> =
        ledger.thread(id, 500)
            .filter { it.kind == EventKind.TASK && it.channel == CHANNEL_JOURNAL }
            .sortedBy { it.structured["seq"]?.toIntOrNull() ?: 0 }
            .map {
                JournalEntry(
                    ts = it.ts,
                    kind = it.structured["entry"] ?: "note",
                    detail = it.text.orEmpty(),
                    eventId = it.structured["about_event"],
                    proposalId = it.structured["proposal_id"],
                )
            }

    // ---- Writing ---------------------------------------------------------------------

    /**
     * Opens a task. It starts PROPOSED with an authority that can do nothing outward;
     * [accept] is where the user's mandate arrives.
     */
    fun open(
        goal: String,
        opener: Opener,
        dueTs: Long?,
        sourceEventIds: List<String> = emptyList(),
        mandate: Mandate? = null,
    ): Task {
        val ts = now()
        val id = EventId.of(ts, "buddy", "task-id", goal.trim().lowercase(), opener.name)
        val deadline = dueTs ?: (ts + 7 * 86_400_000L)
        val task = Task(
            id = id,
            goal = goal.trim(),
            state = TaskState.PROPOSED,
            opener = opener,
            mandate = mandate ?: Task.proposedMandate(deadline),
            sourceEventIds = sourceEventIds,
            dueTs = dueTs,
            createdTs = ts,
            updatedTs = ts,
        )
        write(task, ts)
        note(id, "opened", goal.trim())
        return task
    }

    /** The user grants the mandate. This is the only way a task becomes able to act. */
    fun accept(task: Task, mandate: Mandate, dueTs: Long? = task.dueTs): Task =
        save(task.copy(state = TaskState.ACTIVE, mandate = mandate, dueTs = dueTs ?: mandate.deadlineTs))
            .also { note(it.id, "accepted", "mandate: ${describe(mandate)}") }

    /** Rewrites the compiled state the next wake resumes from. Whole, never appended. */
    fun noteState(task: Task, workingSet: String): Task =
        save(task.copy(workingSet = workingSet.take(Task.MAX_WORKING_SET)))

    /** Parks the task until [untilTs], or until one of [signals] arrives. */
    fun wait(task: Task, waitingOn: String, untilTs: Long, signals: List<Signal> = emptyList()): Task =
        save(task.copy(state = TaskState.WAITING, waitingOn = waitingOn, nextWakeTs = untilTs, signals = signals))
            .also { note(it.id, "waiting", "$waitingOn until ${untilTs}") }

    /** Wakes a parked task: the clock came round, or a signal arrived. */
    fun resume(task: Task, why: String): Task =
        if (task.state == TaskState.WAITING || task.state == TaskState.BLOCKED) {
            save(task.copy(state = TaskState.ACTIVE, nextWakeTs = null, waitingOn = null)).also { note(it.id, "resumed", why) }
        } else {
            task
        }

    /** The only way to reach the person: a question, the options, and buddy's pick. */
    fun block(task: Task, question: String, options: List<String>, recommendation: String): Task =
        save(
            task.copy(
                state = TaskState.BLOCKED,
                question = question,
                options = options,
                recommendation = recommendation,
                nextWakeTs = null,
            ),
        ).also { note(it.id, "blocked", question) }

    /** The person answered a blocked task. Their answer is the next thing the agent reads. */
    fun answer(task: Task, answer: String): Task =
        save(
            task.copy(
                state = TaskState.ACTIVE,
                question = null,
                options = emptyList(),
                recommendation = null,
                workingSet = (task.workingSet + "\nThe person answered: $answer").takeLast(Task.MAX_WORKING_SET),
            ),
        ).also { note(it.id, "answered", answer) }

    fun finish(task: Task, outcome: String): Task =
        save(task.copy(state = TaskState.DONE, outcome = outcome, nextWakeTs = null, signals = emptyList()))
            .also { note(it.id, "finished", outcome) }

    fun abandon(task: Task, why: String): Task =
        save(task.copy(state = TaskState.ABANDONED, outcome = why, nextWakeTs = null, signals = emptyList()))
            .also { note(it.id, "abandoned", why) }

    /** Records what an action cost the task, so the mandate's caps mean something. */
    fun spend(task: Task, amount: Double = 0.0, actions: Int = 0, tokens: Long = 0): Task =
        save(task.copy(spend = task.spend + amount, actions = task.actions + actions, tokens = task.tokens + tokens))

    /** Adds a line to the task's record. */
    fun note(taskId: String, kind: String, detail: String, aboutEventId: String? = null, proposalId: String? = null): Event {
        val ts = now()
        val seq = journal(taskId).size
        val e = Event(
            id = EventId.of(ts, "buddy", CHANNEL_JOURNAL, taskId, seq.toString(), kind, detail, aboutEventId),
            ts = ts,
            sourceApp = "buddy",
            channel = CHANNEL_JOURNAL,
            kind = EventKind.TASK,
            threadId = taskId,
            text = detail,
            structured = buildMap {
                put("task_id", taskId)
                put("seq", seq.toString())
                put("entry", kind)
                aboutEventId?.let { put("about_event", it) }
                proposalId?.let { put("proposal_id", it) }
            },
            trust = Trust.SYSTEM,
        )
        ledger.append(e)
        return e
    }

    /**
     * Writes the next snapshot. Refuses a transition the state machine does not allow,
     * because an agent that can move a task anywhere has no state machine.
     */
    private fun save(next: Task): Task {
        val current = get(next.id)
        if (current != null && current.state != next.state) {
            require(current.state.canMoveTo(next.state)) {
                "task ${next.id}: ${current.state} cannot move to ${next.state}"
            }
        }
        val ts = now()
        val bumped = next.copy(rev = (current?.rev ?: next.rev) + 1, updatedTs = ts)
        write(bumped, ts, supersedes = current?.let { snapshotId(it) })
        return bumped
    }

    private fun write(task: Task, ts: Long, supersedes: String? = null) {
        ledger.append(
            Event(
                id = snapshotId(task),
                ts = ts,
                sourceApp = "buddy",
                channel = CHANNEL,
                kind = EventKind.TASK,
                threadId = task.id,
                text = task.workingSet.ifBlank { null },
                structured = encode(task),
                trust = Trust.SYSTEM,
                supersedes = supersedes,
            ),
        )
    }

    private fun snapshotId(task: Task) = EventId.of(task.createdTs, "buddy", CHANNEL, task.id, task.rev.toString())

    // ---- Encoding --------------------------------------------------------------------

    private fun encode(t: Task): Map<String, String> = buildMap {
        put("task_id", t.id)
        put("rev", t.rev.toString())
        put("goal", t.goal)
        put("state", t.state.name)
        put("opener", t.opener.name)
        put("created", t.createdTs.toString())
        put("updated", t.updatedTs.toString())
        t.dueTs?.let { put("due", it.toString()) }
        t.nextWakeTs?.let { put("next_wake", it.toString()) }
        t.waitingOn?.let { put("waiting_on", it) }
        t.question?.let { put("question", it) }
        if (t.options.isNotEmpty()) put("options", t.options.joinToString("|"))
        t.recommendation?.let { put("recommendation", it) }
        t.outcome?.let { put("outcome", it) }
        if (t.sourceEventIds.isNotEmpty()) put("source_events", t.sourceEventIds.joinToString("|"))
        if (t.signals.isNotEmpty()) put("signals", Signal.encodeAll(t.signals))
        if (t.spend != 0.0) put("spend", t.spend.toString())
        if (t.actions != 0) put("actions", t.actions.toString())
        if (t.tokens != 0L) put("tokens", t.tokens.toString())
        // The mandate, flattened. It is part of the snapshot on purpose: what the task
        // was allowed to do at the time must be readable from the record, for ever.
        put("m_domains", t.mandate.domains.joinToString(",") { it.name })
        put("m_level", t.mandate.maxLevel.name)
        put("m_deadline", t.mandate.deadlineTs.toString())
        put("m_max_actions", t.mandate.maxActions.toString())
        put("m_max_tokens", t.mandate.maxCloudTokens.toString())
        t.mandate.spendCap?.let { put("m_spend_cap", it.toString()) }
        t.mandate.currency?.let { put("m_currency", it) }
        if (t.mandate.allowedTargets.isNotEmpty()) put("m_targets", t.mandate.allowedTargets.joinToString("|"))
        if (t.mandate.neverWithoutAsking.isNotEmpty()) put("m_reserved", t.mandate.neverWithoutAsking.joinToString("|"))
    }

    private fun toTask(e: Event): Task? {
        val s = e.structured
        val id = s["task_id"] ?: return null
        val state = runCatching { TaskState.valueOf(s["state"] ?: "") }.getOrNull() ?: return null
        val mandate = Mandate(
            domains = Task.domains(s["m_domains"].orEmpty()),
            maxLevel = runCatching { Level.valueOf(s["m_level"] ?: "") }.getOrNull() ?: Level.OBSERVE,
            spendCap = s["m_spend_cap"]?.toDoubleOrNull(),
            currency = s["m_currency"],
            allowedTargets = s["m_targets"]?.split('|')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            deadlineTs = s["m_deadline"]?.toLongOrNull() ?: e.ts,
            maxCloudTokens = s["m_max_tokens"]?.toLongOrNull() ?: 200_000,
            maxActions = s["m_max_actions"]?.toIntOrNull() ?: 20,
            neverWithoutAsking = s["m_reserved"]?.split('|')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        )
        return Task(
            id = id,
            goal = s["goal"].orEmpty(),
            state = state,
            opener = runCatching { Opener.valueOf(s["opener"] ?: "") }.getOrNull() ?: Opener.BUDDY,
            mandate = mandate,
            sourceEventIds = s["source_events"]?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
            workingSet = e.text.orEmpty().take(Task.MAX_WORKING_SET),
            dueTs = s["due"]?.toLongOrNull(),
            nextWakeTs = s["next_wake"]?.toLongOrNull(),
            waitingOn = s["waiting_on"],
            signals = Signal.decodeAll(s["signals"]),
            question = s["question"],
            options = s["options"]?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
            recommendation = s["recommendation"],
            spend = s["spend"]?.toDoubleOrNull() ?: 0.0,
            actions = s["actions"]?.toIntOrNull() ?: 0,
            tokens = s["tokens"]?.toLongOrNull() ?: 0,
            outcome = s["outcome"],
            rev = s["rev"]?.toIntOrNull() ?: 0,
            createdTs = s["created"]?.toLongOrNull() ?: e.ts,
            updatedTs = s["updated"]?.toLongOrNull() ?: e.ts,
        )
    }

    companion object {
        const val CHANNEL = "task"
        const val CHANNEL_JOURNAL = "task.journal"

        /** The mandate in one line, as the agent restates it and the timeline shows it. */
        fun describe(m: Mandate): String = buildList {
            add(if (m.domains.isEmpty()) "nothing" else m.domains.joinToString(", ") { it.name.lowercase() })
            add("up to ${m.maxLevel.name.lowercase()}")
            m.spendCap?.let { add("spend $it ${m.currency ?: ""}".trim()) }
            if (m.allowedTargets.isNotEmpty()) add("only ${m.allowedTargets.joinToString(", ")}")
            add("${m.maxActions} actions")
        }.joinToString("; ")

        /** Domains a mandate may name, for the tool schema. */
        val GRANTABLE_DOMAINS: List<Domain> = Domain.entries
    }
}
