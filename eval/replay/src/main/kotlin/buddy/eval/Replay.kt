package buddy.eval

import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.SqliteLedger
import buddy.ledger.jdbc.JdbcSqlDriver
import buddy.triage.RuleTriage
import buddy.triage.Triage
import buddy.triage.TriageClass
import buddy.triage.TriageProfile
import java.io.File
import kotlin.system.exitProcess

/**
 * Scores triage against labels.
 *
 * Labels are a CSV of `event_id,class[,urgent]` written by the founder from the
 * timeline (the timeline gets a "this should have been X" affordance in the app).
 * The score is per-class precision and recall plus the two numbers the roadmap gates
 * on: missed-critical (labelled ESCALATE urgent, predicted anything else) and the drop
 * error rate (labelled anything but DROP, predicted DROP).
 */
data class Label(val eventId: String, val klass: TriageClass, val urgent: Boolean)

data class Score(
    val total: Int,
    val correct: Int,
    val perClass: Map<TriageClass, ClassScore>,
    val missedCritical: Int,
    val wrongDrops: Int,
    val confusion: Map<Pair<TriageClass, TriageClass>, Int>,
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    val wrongDropRate: Double get() = if (total == 0) 0.0 else wrongDrops.toDouble() / total

    fun render(): String = buildString {
        appendLine("events scored: $total, accuracy ${"%.3f".format(accuracy)}")
        appendLine("missed critical: $missedCritical (must be 0)")
        appendLine("wrong drops: $wrongDrops (${"%.3f".format(wrongDropRate)})")
        appendLine()
        appendLine("class       precision  recall  support")
        for (k in TriageClass.values()) {
            val c = perClass[k] ?: continue
            appendLine("%-11s %9.3f %7.3f %8d".format(k.name, c.precision, c.recall, c.support))
        }
        appendLine()
        appendLine("confusion (labelled -> predicted):")
        confusion.entries.sortedByDescending { it.value }.forEach { (k, n) -> if (k.first != k.second) appendLine("  ${k.first} -> ${k.second}: $n") }
    }
}

data class ClassScore(val precision: Double, val recall: Double, val support: Int)

object Scorer {
    fun score(labels: List<Label>, predictions: Map<String, buddy.triage.TriageDecision>): Score {
        var correct = 0
        var missed = 0
        var wrongDrops = 0
        val confusion = HashMap<Pair<TriageClass, TriageClass>, Int>()
        val tp = HashMap<TriageClass, Int>(); val fp = HashMap<TriageClass, Int>(); val fn = HashMap<TriageClass, Int>(); val support = HashMap<TriageClass, Int>()
        for (l in labels) {
            val p = predictions[l.eventId] ?: continue
            support.merge(l.klass, 1, Int::plus)
            confusion.merge(l.klass to p.klass, 1, Int::plus)
            if (p.klass == l.klass) { correct++; tp.merge(l.klass, 1, Int::plus) } else { fp.merge(p.klass, 1, Int::plus); fn.merge(l.klass, 1, Int::plus) }
            if (l.klass == TriageClass.ESCALATE && l.urgent && !(p.klass == TriageClass.ESCALATE && p.urgent)) missed++
            if (l.klass != TriageClass.DROP && p.klass == TriageClass.DROP) wrongDrops++
        }
        val scored = labels.count { it.eventId in predictions }
        val per = TriageClass.values().associateWith { k ->
            val t = tp[k] ?: 0; val f = fp[k] ?: 0; val n = fn[k] ?: 0
            ClassScore(if (t + f == 0) 0.0 else t.toDouble() / (t + f), if (t + n == 0) 0.0 else t.toDouble() / (t + n), support[k] ?: 0)
        }.filterValues { it.support > 0 || it.precision > 0 }
        return Score(scored, correct, per, missed, wrongDrops, confusion)
    }

    fun parseLabels(text: String): List<Label> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("event_id") }
        .map { line ->
            val parts = line.split(',').map { it.trim() }
            require(parts.size >= 2) { "bad label line: $line" }
            Label(parts[0], TriageClass.valueOf(parts[1].uppercase()), parts.getOrNull(2)?.equals("true", ignoreCase = true) ?: false)
        }.toList()
}

/** `replay <ledger.db> <labels.csv> [profile.properties]` */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: replay <ledger.db> <labels.csv> [profile.properties]")
        exitProcess(2)
    }
    val driver = JdbcSqlDriver.sqlite(args[0])
    val ledger = SqliteLedger(driver)
    val labels = Scorer.parseLabels(File(args[1]).readText())
    val profile = args.getOrNull(2)?.let { path ->
        val p = java.util.Properties().apply { File(path).inputStream().use(::load) }
        fun set(k: String) = p.getProperty(k, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        TriageProfile(emergencyActors = set("emergency"), closeActors = set("close"), mutedActors = set("muted"), mutedPackages = set("muted_packages"), urgentKeywords = set("urgent_keywords"))
    } ?: TriageProfile()

    val triage: Triage = RuleTriage()
    val events = HashMap<String, Event>()
    var before: Long? = null
    while (true) {
        val page = ledger.recent(1000, before)
        if (page.isEmpty()) break
        page.filter { it.kind != EventKind.TRIAGE && it.kind != EventKind.BRIEF }.forEach { events[it.id] = it }
        before = page.last().ts
        if (page.size < 1000) break
    }
    val predictions = labels.mapNotNull { l -> events[l.eventId]?.let { e -> e.id to triage.triage(e, profile) } }.toMap()
    val score = Scorer.score(labels, predictions)
    println(score.render())
    driver.close()
    if (score.missedCritical > 0) exitProcess(1)
}
