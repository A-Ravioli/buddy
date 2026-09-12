package buddy.eval

import buddy.triage.TriageClass
import buddy.triage.TriageDecision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScorerTest {
    private fun d(id: String, k: TriageClass, urgent: Boolean = false) = TriageDecision(id, k, urgent)

    @Test
    fun `scores precision recall missed critical and wrong drops`() {
        val labels = Scorer.parseLabels(
            """
            # id,class,urgent
            a,ESCALATE,true
            b,ESCALATE
            c,DROP
            d,FILE
            e,ACT_LATER
            f,FILE
            """.trimIndent(),
        )
        val preds = mapOf(
            "a" to d("a", TriageClass.ESCALATE, urgent = false), // missed critical
            "b" to d("b", TriageClass.ESCALATE),
            "c" to d("c", TriageClass.DROP),
            "d" to d("d", TriageClass.DROP), // wrong drop
            "e" to d("e", TriageClass.ACT_LATER),
            // f: no prediction (event missing from ledger), not scored
        )
        val s = Scorer.score(labels, preds)
        assertEquals(5, s.total)
        assertEquals(4, s.correct)
        assertEquals(1, s.missedCritical)
        assertEquals(1, s.wrongDrops)
        assertEquals(0.5, s.perClass[TriageClass.DROP]!!.precision)
        assertEquals(1.0, s.perClass[TriageClass.DROP]!!.recall)
        assertEquals(0.0, s.perClass[TriageClass.FILE]!!.recall)
        assertEquals(1, s.confusion[TriageClass.FILE to TriageClass.DROP])
        val text = s.render()
        assert(text.contains("missed critical: 1"))
        assert(text.contains("FILE -> DROP: 1"))
    }
}
