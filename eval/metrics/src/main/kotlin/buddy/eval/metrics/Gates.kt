package buddy.eval.metrics

import buddy.cognition.TriageRecorder
import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.triage.TriageClass
import java.time.Instant
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * The success metrics from docs/00-vision.md, computed from the ledger alone. Every
 * number here is derivable from events, which is the point: the phone keeps the record
 * and the record decides.
 */
data class DailyMetrics(
    val day: String,
    val unlocks: Int,
    val screenMinutes: Double,
    val autonomousActions: Int,
    val escalations: Int,
    val urgentEscalations: Int,
    val regrets: Int,
    val criticalRegrets: Int,
    val missedCritical: Int,
    val transcriptionMinutes: Double,
    val batteryDrainPctPerHour: Double?,
    val briefs: Int,
    val cloudInputTokens: Long,
)

data class Summary(
    val days: List<DailyMetrics>,
) {
    val n: Int get() = days.size
    val unlocksPerDay: Double get() = avg { it.unlocks.toDouble() }
    val screenMinutesPerDay: Double get() = avg { it.screenMinutes }
    val actionsPerDay: Double get() = avg { it.autonomousActions.toDouble() }
    val escalationsPerDay: Double get() = avg { it.escalations.toDouble() }
    val regretRate: Double get() = days.sumOf { it.regrets }.let { r -> days.sumOf { it.autonomousActions }.let { a -> if (a == 0) 0.0 else r.toDouble() / a } }
    val criticalRegrets: Int get() = days.sumOf { it.criticalRegrets }
    val missedCritical: Int get() = days.sumOf { it.missedCritical }
    val transcriptionMinutesPerDay: Double get() = avg { it.transcriptionMinutes }
    val batteryDrain: Double? get() = days.mapNotNull { it.batteryDrainPctPerHour }.takeIf { it.isNotEmpty() }?.average()
    private fun avg(f: (DailyMetrics) -> Double) = if (days.isEmpty()) 0.0 else days.sumOf(f) / days.size
}

/** One gate from docs/05-roadmap.md, with the threshold that decides it. */
data class Gate(val name: String, val pass: Boolean, val detail: String)

object Metrics {
    /** Conventions for correction events the metrics read. */
    const val REASON_REGRET = "regret"
    const val REASON_CRITICAL = "critical_regret"
    const val REASON_MISSED_CRITICAL = "missed_critical"

    fun daily(events: List<Event>, zone: ZoneId): List<DailyMetrics> {
        val byDay = events.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate().toString() }
        return byDay.entries.sortedBy { it.key }.map { (day, es) -> compute(day, es.sortedBy { it.ts }) }
    }

    private fun compute(day: String, es: List<Event>): DailyMetrics {
        val screen = es.filter { it.kind == EventKind.DEVICE_STATE && it.structured["aspect"] == "screen" }
        val unlocks = screen.count { it.structured["value"] == "on" }
        var screenMs = 0L
        var onSince: Long? = null
        for (s in screen) {
            if (s.structured["value"] == "on") onSince = s.ts
            else onSince?.let { screenMs += s.ts - it; onSince = null }
        }
        val actions = es.filter { it.kind == EventKind.ACTION && it.structured["state"] == "done" }
        val corrections = es.filter { it.kind == EventKind.CORRECTION }
        val reasons = corrections.map { it.structured["reasons"].orEmpty() }
        val triage = es.filter { it.kind == EventKind.TRIAGE }.mapNotNull(TriageRecorder::fromEvent)
        val escalations = triage.filter { it.klass == TriageClass.ESCALATE }
        val transcription = es.filter { it.kind == EventKind.DEVICE_STATE && it.structured["aspect"] == "transcription_minutes" }
            .sumOf { it.structured["value"]?.toDoubleOrNull() ?: 0.0 }
        val battery = es.filter { it.kind == EventKind.DEVICE_STATE && it.structured["aspect"] == "battery" }
            .mapNotNull { e -> e.structured["value"]?.toIntOrNull()?.let { e.ts to it } }
        val drain = if (battery.size >= 2) {
            val hours = (battery.last().first - battery.first().first) / 3_600_000.0
            if (hours >= 1.0) (battery.first().second - battery.last().second) / hours else null
        } else null
        val briefs = es.filter { it.kind == EventKind.BRIEF }
        return DailyMetrics(
            day = day,
            unlocks = unlocks,
            screenMinutes = screenMs / 60_000.0,
            autonomousActions = actions.size,
            escalations = escalations.size,
            urgentEscalations = escalations.count { it.urgent },
            regrets = reasons.count { it.contains(REASON_REGRET) },
            criticalRegrets = reasons.count { it.contains(REASON_CRITICAL) },
            missedCritical = reasons.count { it.contains(REASON_MISSED_CRITICAL) },
            transcriptionMinutes = transcription,
            batteryDrainPctPerHour = drain,
            briefs = briefs.size,
            cloudInputTokens = briefs.sumOf { it.structured["input_tokens"]?.toLongOrNull() ?: 0L },
        )
    }

    /** The gates between phases (docs/05-roadmap.md) and the six-month targets (docs/00-vision.md). */
    fun gates(s: Summary, minDays: Int = 28, batteryBudgetPctPerHour: Double = 4.0, regretTarget: Double = 0.01, transcriptionBudgetMinutes: Double = 180.0): List<Gate> = listOf(
        Gate("enough days", s.n >= minDays, "${s.n} days of data, need $minDays"),
        Gate("missed critical", s.missedCritical == 0, "${s.missedCritical} missed critical items, must be 0"),
        Gate("critical regrets", s.criticalRegrets == 0, "${s.criticalRegrets} critical regrets, must be 0"),
        Gate("regret rate", s.regretRate <= regretTarget, "regret ${"%.3f".format(s.regretRate)} over ${s.days.sumOf { it.autonomousActions }} actions, target ${"%.3f".format(regretTarget)}"),
        Gate("battery", (s.batteryDrain ?: 0.0) <= batteryBudgetPctPerHour, "drain ${s.batteryDrain?.let { "%.2f".format(it) } ?: "n/a"} %/h, budget $batteryBudgetPctPerHour"),
        Gate("transcription budget", s.transcriptionMinutesPerDay <= transcriptionBudgetMinutes, "${"%.0f".format(s.transcriptionMinutesPerDay)} min/day, budget ${"%.0f".format(transcriptionBudgetMinutes)}"),
        Gate("unlocks target", s.unlocksPerDay < 10.0, "${"%.1f".format(s.unlocksPerDay)} unlocks/day, target under 10 (six-month target, informational)"),
        Gate("escalations target", s.escalationsPerDay < 10.0, "${"%.1f".format(s.escalationsPerDay)} escalations/day, target under 10 (six-month target, informational)"),
    )

    fun render(s: Summary, gates: List<Gate>): String = buildString {
        appendLine("days: ${s.n}")
        appendLine("unlocks/day %.1f, screen min/day %.1f, actions/day %.1f, escalations/day %.1f".format(s.unlocksPerDay, s.screenMinutesPerDay, s.actionsPerDay, s.escalationsPerDay))
        appendLine("regret rate %.3f, critical regrets %d, missed critical %d".format(s.regretRate, s.criticalRegrets, s.missedCritical))
        appendLine("transcription min/day %.0f, battery drain %s %%/h".format(s.transcriptionMinutesPerDay, s.batteryDrain?.let { "%.2f".format(it) } ?: "n/a"))
        appendLine()
        for (g in gates) appendLine("${if (g.pass) "PASS" else "FAIL"}  ${g.name}: ${g.detail}")
        val hard = gates.take(6)
        appendLine()
        appendLine(if (hard.all { it.pass }) "GATES PASS: a second user may be added." else "GATES FAIL: ${hard.filter { !it.pass }.joinToString { it.name }}.")
    }
}

/** `gates <ledger.db> [days=28]` */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: gates <ledger.db> [days]")
        exitProcess(2)
    }
    val days = args.getOrNull(1)?.toIntOrNull() ?: 28
    val driver = JdbcSqlDriver.sqlite(args[0])
    val ledger = SqliteLedger(driver)
    val since = System.currentTimeMillis() - days * 86_400_000L
    val events = ArrayList<Event>()
    var before: Long? = null
    while (true) {
        val page = ledger.recent(1000, before)
        if (page.isEmpty()) break
        events.addAll(page.filter { it.ts >= since })
        if (page.last().ts < since || page.size < 1000) break
        before = page.last().ts
    }
    val summary = Summary(Metrics.daily(events, ZoneId.systemDefault()))
    val gates = Metrics.gates(summary, minDays = days)
    println(Metrics.render(summary, gates))
    driver.close()
    if (gates.take(6).any { !it.pass }) exitProcess(1)
}
