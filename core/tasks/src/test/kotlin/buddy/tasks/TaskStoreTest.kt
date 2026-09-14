package buddy.tasks

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.Trust
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.Mandate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStoreTest {
    private lateinit var db: JdbcSqlDriver
    private lateinit var ledger: SqliteLedger
    private var clock = 1_700_000_000_000L

    private fun store() = TaskStore(ledger, now = { clock })

    private fun mandate(deadline: Long = clock + 86_400_000L) = Mandate(
        domains = setOf(Domain.MESSAGING),
        maxLevel = Level.HOLD,
        deadlineTs = deadline,
    )

    @BeforeEach
    fun setUp() {
        db = JdbcSqlDriver.inMemory()
        ledger = SqliteLedger(db)
    }

    @Test
    fun `the state machine allows exactly the transitions in the design`() {
        val allowed = mapOf(
            TaskState.PROPOSED to setOf(TaskState.ACTIVE, TaskState.ABANDONED),
            TaskState.ACTIVE to setOf(TaskState.WAITING, TaskState.BLOCKED, TaskState.DONE, TaskState.ABANDONED, TaskState.ACTIVE),
            TaskState.WAITING to setOf(TaskState.ACTIVE, TaskState.BLOCKED, TaskState.DONE, TaskState.ABANDONED),
            TaskState.BLOCKED to setOf(TaskState.ACTIVE, TaskState.DONE, TaskState.ABANDONED),
            TaskState.DONE to emptySet(),
            TaskState.ABANDONED to emptySet(),
        )
        for (from in TaskState.entries) {
            for (to in TaskState.entries) {
                assertEquals(to in allowed.getValue(from), from.canMoveTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `a task survives the process dying and resumes from its working set`() {
        val opened = store().open("Book a table for Thursday", Opener.USER, clock + 172_800_000L)
        val accepted = store().accept(opened, mandate())
        clock += 1000
        store().noteText(accepted, "Asked Sam for a time. Two places left to try: Kiln, Brat.")
        clock += 1000
        store().let { s -> s.wait(s.get(accepted.id)!!, "a reply from Sam", clock + 3_600_000L, listOf(Signal(threadId = "sms:1"))) }

        // A new store on a new connection to the same database: the process died and
        // came back, and nothing was held in memory.
        val reopened = TaskStore(SqliteLedger(db), now = { clock })
        val task = assertNotNull(reopened.get(accepted.id))
        assertEquals(TaskState.WAITING, task.state)
        assertEquals("a reply from Sam", task.waitingOn)
        assertTrue(task.workingSet.contains("Kiln"))
        assertEquals(listOf(Signal(threadId = "sms:1")), task.signals)
        assertEquals(Level.HOLD, task.mandate.maxLevel)
        assertEquals(setOf(Domain.MESSAGING), task.mandate.domains)
    }

    @Test
    fun `an illegal transition is refused rather than written`() {
        val s = store()
        val t = s.accept(s.open("x", Opener.USER, clock + 1000), mandate())
        val done = s.finish(t, "done")
        assertFailsWith<IllegalArgumentException> { s.resumeForTest(done) }
        assertEquals(TaskState.DONE, s.get(t.id)!!.state)
    }

    @Test
    fun `the working set is capped so it stays state rather than a transcript`() {
        val s = store()
        val t = s.accept(s.open("x", Opener.USER, clock + 1000), mandate())
        val capped = s.noteState(t, "x".repeat(Task.MAX_WORKING_SET * 2))
        assertEquals(Task.MAX_WORKING_SET, capped.workingSet.length)
    }

    @Test
    fun `the journal records every move, in order`() {
        val s = store()
        val t = s.accept(s.open("Chase the landlord", Opener.FOLLOW_UP, clock + 1000), mandate())
        clock += 1
        s.block(s.get(t.id)!!, "Chase now or wait?", listOf("Chase", "Wait"), "Chase")
        clock += 1
        s.answer(s.get(t.id)!!, "Chase")
        clock += 1
        s.finish(s.get(t.id)!!, "Landlord replied")
        assertEquals(
            listOf("opened", "accepted", "blocked", "answered", "finished"),
            s.journal(t.id).map { it.kind },
        )
    }

    @Test
    fun `open lists only live tasks and the newest revision of each`() {
        val s = store()
        val a = s.accept(s.open("a", Opener.USER, clock + 1000), mandate())
        clock += 1
        val b = s.accept(s.open("b", Opener.USER, clock + 1000), mandate())
        clock += 1
        s.finish(s.get(a.id)!!, "done")
        val open = s.open()
        assertEquals(listOf(b.id), open.map { it.id })
        assertEquals(TaskState.DONE, s.get(a.id)!!.state)
        assertEquals(2, s.all().size)
    }

    @Test
    fun `signals match on every field and only when all of them agree`() {
        val e = Event(
            id = EventId.of(clock, "app", "sms", "1"),
            ts = clock,
            sourceApp = "android.sms",
            channel = "sms",
            kind = EventKind.MESSAGE,
            actor = "+447700900123",
            threadId = "sms:7",
            text = "on its way",
            structured = mapOf("tracking" to "ZX1"),
            trust = Trust.UNTRUSTED,
        )
        assertTrue(Signal(threadId = "sms:7").matches(e))
        assertTrue(Signal(actor = "+447700900123", kind = EventKind.MESSAGE).matches(e))
        assertTrue(Signal(structuredKey = "tracking", structuredValue = "ZX1").matches(e))
        assertFalse(Signal(threadId = "sms:7", actor = "someone else").matches(e))
        assertFalse(Signal(structuredKey = "tracking", structuredValue = "OTHER").matches(e))
        assertFalse(Signal(sourceApp = "com.other").matches(e))
        // An empty signal would match everything, which is how a task wakes on every
        // event in the ledger. It matches nothing instead.
        assertFalse(Signal().matches(e))
    }

    @Test
    fun `signals survive the round trip through the ledger`() {
        val signals = listOf(
            Signal(threadId = "sms:7", kind = EventKind.MESSAGE),
            Signal(structuredKey = "status", structuredValue = "delivered"),
        )
        assertEquals(signals, Signal.decodeAll(Signal.encodeAll(signals)))
    }

    @Test
    fun `the waker fires due tasks, signalled tasks, and sweeps stale ones`() {
        val s = store()
        val waker = Waker()
        val t = s.accept(s.open("wait for the parcel", Opener.TRIAGE, clock + 86_400_000L), mandate(clock + 86_400_000L))
        s.wait(s.get(t.id)!!, "the parcel", clock + 3_600_000L, listOf(Signal(structuredKey = "tracking")))

        assertTrue(waker.due(s.open(), clock).isEmpty(), "not due yet")
        assertEquals(listOf(t.id), waker.due(s.open(), clock + 3_600_001L).map { it.taskId })

        val parcel = Event(
            id = EventId.of(clock, "carrier", "push", "1"), ts = clock, sourceApp = "com.carrier", channel = "push",
            kind = EventKind.NOTIFICATION, text = "out for delivery", structured = mapOf("tracking" to "ZX1"), trust = Trust.UNTRUSTED,
        )
        val woken = waker.signalled(s.open(), parcel, clock)
        assertEquals(listOf(t.id), woken.map { it.taskId })
        assertEquals(WakeReason.SIGNAL, woken.single().reason)

        // Past the mandate's deadline it is stale, whatever its wake time says.
        assertTrue(waker.stale(s.open(), clock).isEmpty())
        assertEquals(listOf(t.id), waker.stale(s.open(), clock + 86_400_001L).map { it.id })
    }

    @Test
    fun `a mandate written a week ago is still readable from the record`() {
        val s = store()
        val granted = Mandate(
            domains = setOf(Domain.SHOPPING, Domain.MESSAGING),
            maxLevel = Level.ACT,
            spendCap = 60.0,
            currency = "GBP",
            allowedTargets = setOf("kiln@example.com"),
            deadlineTs = clock + 86_400_000L,
            maxActions = 5,
            neverWithoutAsking = setOf("send_message"),
        )
        val t = s.accept(s.open("book the table", Opener.USER, clock + 86_400_000L), granted)
        assertEquals(granted, TaskStore(SqliteLedger(db), now = { clock }).get(t.id)!!.mandate)
    }
}

/** Test-only helpers that keep the intent of each case readable. */
private fun TaskStore.noteText(task: Task, text: String): Task = noteState(task, text)

/** Forces the illegal move the state machine must refuse. */
private fun TaskStore.resumeForTest(task: Task): Task = wait(task, "nothing", task.mandate.deadlineTs)
