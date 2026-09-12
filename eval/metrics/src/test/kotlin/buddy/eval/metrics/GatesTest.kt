package buddy.eval.metrics

import buddy.cognition.TriageRecorder
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatesTest {
    private val zone = ZoneId.of("UTC")
    private var seq = 0L
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, 1 + day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun device(ts: Long, aspect: String, value: String) =
        Event(EventId.of(ts, "android", "device", (seq++).toString()), ts, "android", "device", EventKind.DEVICE_STATE, null, null, "$aspect=$value", mapOf("aspect" to aspect, "value" to value), Trust.SYSTEM)
    private fun action(ts: Long, state: String = "done") =
        Event(EventId.of(ts, "buddy", "action", (seq++).toString()), ts, "buddy", "action", EventKind.ACTION, null, null, null, mapOf("state" to state, "action" to "archive_email"), Trust.SYSTEM)
    private fun correction(ts: Long, reason: String) =
        Event(EventId.of(ts, "buddy", "action", (seq++).toString()), ts, "buddy", "action", EventKind.CORRECTION, "me", null, null, mapOf("state" to "undone", "reasons" to reason), Trust.USER)
    private fun triage(ts: Long, k: TriageClass, urgent: Boolean = false) = TriageRecorder.toEvent(TriageDecision("e${seq++}", k, urgent), ts)
    private fun brief(ts: Long) = Event(EventId.of(ts, "buddy", "brief", (seq++).toString()), ts, "buddy", "brief", EventKind.BRIEF, null, null, "ok", mapOf("input_tokens" to "12000"), Trust.SYSTEM)

    private fun goodDay(d: Int): List<Event> = buildList {
        // Three unlocks of ten minutes each; battery 100 to 76 over 24h; 90 min transcription; 20 actions; 3 escalations.
        for (h in listOf(8, 13, 20)) { add(device(at(d, h), "screen", "on")); add(device(at(d, h, 10), "screen", "off")) }
        add(device(at(d, 0), "battery", "100")); add(device(at(d, 23, 59), "battery", "76"))
        add(device(at(d, 12), "transcription_minutes", "90"))
        repeat(20) { add(action(at(d, 9, it))) }
        repeat(3) { add(triage(at(d, 10, it), TriageClass.ESCALATE)) }
        repeat(30) { add(triage(at(d, 11, it), TriageClass.FILE)) }
        add(brief(at(d, 7, 30))); add(brief(at(d, 19, 30)))
    }

    @Test
    fun `daily metrics are computed from events`() {
        val m = Metrics.daily(goodDay(0), zone)
        assertEquals(1, m.size)
        val d = m[0]
        assertEquals(3, d.unlocks)
        assertEquals(30.0, d.screenMinutes)
        assertEquals(20, d.autonomousActions)
        assertEquals(3, d.escalations)
        assertEquals(90.0, d.transcriptionMinutes)
        assertEquals(1.0, d.batteryDrainPctPerHour!!, 0.01)
        assertEquals(2, d.briefs)
        assertEquals(24_000, d.cloudInputTokens)
    }

    @Test
    fun `gates pass on a clean month and fail on the first missed critical`() {
        val month = (0 until 28).flatMap(::goodDay)
        val s = Summary(Metrics.daily(month, zone))
        val gates = Metrics.gates(s)
        assertTrue(gates.all { it.pass }, gates.filter { !it.pass }.joinToString { it.detail })
        assertTrue(Metrics.render(s, gates).contains("GATES PASS"))

        val bad = month + correction(at(5, 12), "missed_critical")
        val g2 = Metrics.gates(Summary(Metrics.daily(bad, zone)))
        assertFalse(g2.first { it.name == "missed critical" }.pass)
        assertTrue(Metrics.render(Summary(Metrics.daily(bad, zone)), g2).contains("GATES FAIL: missed critical"))
    }

    @Test
    fun `regret rate and days gate`() {
        val week = (0 until 7).flatMap(::goodDay) + (0 until 3).map { correction(at(it, 15), "regret") }
        val s = Summary(Metrics.daily(week, zone))
        assertEquals(3.0 / 140.0, s.regretRate, 1e-9)
        val gates = Metrics.gates(s)
        assertFalse(gates.first { it.name == "enough days" }.pass)
        assertFalse(gates.first { it.name == "regret rate" }.pass)
        assertTrue(gates.first { it.name == "missed critical" }.pass)
    }
}
