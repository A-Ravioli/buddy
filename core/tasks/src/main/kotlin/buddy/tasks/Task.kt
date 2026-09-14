package buddy.tasks

import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.Mandate

/**
 * Where a task came from. Kept because the answer changes how buddy behaves: a task
 * the user asked for is theirs to cancel, one buddy opened off a commitment is one it
 * should be able to explain.
 */
enum class Opener { USER, COMMITMENT, TRIAGE, FOLLOW_UP, BUDDY }

/**
 * The five states a job can be in, plus the two ways it ends.
 *
 * ```
 * PROPOSED ──accept──> ACTIVE ──wait for world──> WAITING ──wake──> ACTIVE
 *     │                  │                                            │
 *     └──decline──┐      ├──needs a human──> BLOCKED ──answer─────────┘
 *                 ▼      └──finish──> DONE
 *            ABANDONED <──give up, with a reason──┘
 * ```
 */
enum class TaskState {
    PROPOSED, ACTIVE, WAITING, BLOCKED, DONE, ABANDONED;

    val terminal: Boolean get() = this == DONE || this == ABANDONED

    fun canMoveTo(next: TaskState): Boolean = when (this) {
        PROPOSED -> next == ACTIVE || next == ABANDONED
        ACTIVE -> next != PROPOSED
        WAITING -> next == ACTIVE || next == BLOCKED || next == DONE || next == ABANDONED
        BLOCKED -> next == ACTIVE || next == DONE || next == ABANDONED
        DONE, ABANDONED -> false
    }
}

/** One line in a task's record. Append-only, machine-readable, and what the timeline renders. */
data class JournalEntry(
    val ts: Long,
    /** "opened", "note", "proposed", "verdict", "waiting", "blocked", "answered", "finished", "abandoned". */
    val kind: String,
    val detail: String,
    /** The ledger event this line is about, when there is one. */
    val eventId: String? = null,
    val proposalId: String? = null,
)

/**
 * A job buddy owns from statement to completion, across wakes, restarts, and days
 * (docs/07, section 3).
 *
 * Two fields carry the design. [workingSet] is the compiled state the next wake
 * resumes from, rewritten whole every turn rather than appended to, so a long task
 * costs the same context as a short one. [mandate] is the authority it was given,
 * which the policy engine checks before its own rules.
 */
data class Task(
    val id: String,
    /** The goal in the user's words, or buddy's sentence when buddy opened it. */
    val goal: String,
    val state: TaskState,
    val opener: Opener,
    val mandate: Mandate,
    /** Events that caused this task and everything it has touched since. */
    val sourceEventIds: List<String> = emptyList(),
    /** Compiled state for the next wake. Never the transcript; what is known, tried, and left. */
    val workingSet: String = "",
    /** When the world needs this done. Null only while PROPOSED. */
    val dueTs: Long? = null,
    /** When buddy next wants to look at it, whatever the world does. */
    val nextWakeTs: Long? = null,
    /** What a WAITING task is waiting for, in one phrase, for the timeline. */
    val waitingOn: String? = null,
    /** Signals that would end the wait early (docs/07, section 5). */
    val signals: List<Signal> = emptyList(),
    /** The question a BLOCKED task needs answered, with the options and buddy's pick. */
    val question: String? = null,
    val options: List<String> = emptyList(),
    val recommendation: String? = null,
    /** Running totals the mandate is checked against. */
    val spend: Double = 0.0,
    val actions: Int = 0,
    val tokens: Long = 0,
    /** How it ended, for DONE and ABANDONED. */
    val outcome: String? = null,
    /** Snapshot revision; the highest one for an id is the current state. */
    val rev: Int = 0,
    val createdTs: Long = 0,
    val updatedTs: Long = 0,
) {
    val open: Boolean get() = !state.terminal

    init {
        require(workingSet.length <= MAX_WORKING_SET) {
            "working set is ${workingSet.length} characters; the cap is $MAX_WORKING_SET so that it stays compiled state rather than a transcript"
        }
    }

    companion object {
        /**
         * The working set is capped so compression is forced. An agent that may write
         * as much as it likes writes the transcript again, which is the thing the
         * working set exists to replace.
         */
        const val MAX_WORKING_SET = 2_000

        /** The mandate a task starts with when the user has not granted one yet. */
        fun defaultMandate(deadlineTs: Long): Mandate = Mandate.default(deadlineTs)

        /** A mandate that can do nothing outward, for a task that has only been proposed. */
        fun proposedMandate(deadlineTs: Long): Mandate =
            Mandate(domains = emptySet(), maxLevel = Level.OBSERVE, deadlineTs = deadlineTs)

        internal fun domains(csv: String): Set<Domain> =
            csv.split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { Domain.valueOf(it) }.getOrNull() }.toSet()
    }
}
