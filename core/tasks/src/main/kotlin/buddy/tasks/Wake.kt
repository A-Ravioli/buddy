package buddy.tasks

import buddy.ledger.Event
import buddy.ledger.EventKind

/**
 * Why the agent is awake (docs/07, section 5). The reason goes in the operator
 * channel on the cloud call, so it carries operator authority rather than arriving as
 * content the model has to infer from.
 */
enum class WakeReason {
    /** The person said something. */
    USER_TURN,

    /** Triage returned ACT_NOW. The thing that used to wait twelve hours for a cycle. */
    WORK,

    /** A WAITING task's own clock came round. */
    TASK_DUE,

    /** An event arrived that a WAITING task said it was waiting for. */
    SIGNAL,

    /** A held action's window expired. */
    HOLD,

    /** A planning cycle: morning, evening. */
    CYCLE,

    /** Charging and on wifi: consolidation, sweeps, backfill. */
    IDLE,
}

/** One thing to wake up about. */
data class Wake(
    val reason: WakeReason,
    val ts: Long,
    val taskId: String? = null,
    /** Events that caused this wake. */
    val eventIds: List<String> = emptyList(),
    /** One line for the operator channel and the journal. */
    val detail: String = "",
)

/**
 * What a waiting task said would change its mind.
 *
 * Every field that is set must match, so a signal is an AND. Matching runs on the
 * perception path for every event, which is why it is field equality and nothing
 * else: the moment this needs the model, the design is wrong (docs/07, section 10).
 */
data class Signal(
    val threadId: String? = null,
    val actor: String? = null,
    val sourceApp: String? = null,
    val kind: EventKind? = null,
    /** A structured field that must be present, and optionally equal to [structuredValue]. */
    val structuredKey: String? = null,
    val structuredValue: String? = null,
) {
    val empty: Boolean
        get() = threadId == null && actor == null && sourceApp == null && kind == null && structuredKey == null

    fun matches(e: Event): Boolean {
        if (empty) return false
        if (threadId != null && !threadId.equals(e.threadId, ignoreCase = true)) return false
        if (actor != null && !actor.equals(e.actor, ignoreCase = true)) return false
        if (sourceApp != null && !sourceApp.equals(e.sourceApp, ignoreCase = true)) return false
        if (kind != null && kind != e.kind) return false
        if (structuredKey != null) {
            val v = e.structured[structuredKey] ?: return false
            if (structuredValue != null && !structuredValue.equals(v, ignoreCase = true)) return false
        }
        return true
    }

    /** `thread=sms:1;actor=sam;kind=MESSAGE;field=tracking=abc` — one line, so it fits a structured value. */
    fun encode(): String = buildList {
        threadId?.let { add("thread=$it") }
        actor?.let { add("actor=$it") }
        sourceApp?.let { add("app=$it") }
        kind?.let { add("kind=${it.name}") }
        structuredKey?.let { add("field=$it" + (structuredValue?.let { v -> "=$v" } ?: "")) }
    }.joinToString(";")

    companion object {
        fun decode(s: String): Signal {
            var threadId: String? = null
            var actor: String? = null
            var app: String? = null
            var kind: EventKind? = null
            var key: String? = null
            var value: String? = null
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val v = part.substring(i + 1)
                when (part.substring(0, i)) {
                    "thread" -> threadId = v
                    "actor" -> actor = v
                    "app" -> app = v
                    "kind" -> kind = runCatching { EventKind.valueOf(v) }.getOrNull()
                    "field" -> {
                        val j = v.indexOf('=')
                        if (j < 0) key = v else { key = v.substring(0, j); value = v.substring(j + 1) }
                    }
                }
            }
            return Signal(threadId, actor, app, kind, key, value)
        }

        fun encodeAll(signals: List<Signal>): String = signals.filter { !it.empty }.joinToString("|") { it.encode() }

        fun decodeAll(s: String?): List<Signal> =
            s?.split('|')?.filter { it.isNotBlank() }?.map(::decode)?.filter { !it.empty } ?: emptyList()
    }
}

/**
 * Turns the open tasks into wakes. Pure: tasks and a clock in, wakes out, so the
 * scheduler on the phone is a thin thing that calls this and the replay harness can
 * run a week of wakes in a millisecond.
 */
class Waker(
    /** A task that has done nothing for this long is swept, whatever its wake time. */
    private val staleAfterMs: Long = 7 * 86_400_000L,
) {
    /** WAITING tasks whose own clock has come round. */
    fun due(tasks: List<Task>, nowTs: Long): List<Wake> =
        tasks.filter { it.state == TaskState.WAITING && (it.nextWakeTs ?: Long.MAX_VALUE) <= nowTs }
            .sortedBy { it.nextWakeTs }
            .map { Wake(WakeReason.TASK_DUE, nowTs, it.id, emptyList(), "waiting on ${it.waitingOn ?: "nothing named"}") }

    /** Tasks an event speaks to. One pass over the open tasks; no model, no query. */
    fun signalled(tasks: List<Task>, event: Event, nowTs: Long): List<Wake> =
        tasks.filter { it.state == TaskState.WAITING && it.signals.any { s -> s.matches(event) } }
            .map { Wake(WakeReason.SIGNAL, nowTs, it.id, listOf(event.id), "signal from ${event.sourceApp}/${event.channel}") }

    /**
     * Jobs that have stopped being jobs: past their deadline, or untouched for a week.
     * Returned so the caller can block or abandon them. A task that can sit in WAITING
     * forever is how an agent accumulates silent debt (docs/07, section 10).
     */
    fun stale(tasks: List<Task>, nowTs: Long): List<Task> =
        tasks.filter { it.open }.filter {
            nowTs > it.mandate.deadlineTs ||
                (it.dueTs != null && nowTs > it.dueTs) ||
                nowTs - it.updatedTs > staleAfterMs
        }
}
