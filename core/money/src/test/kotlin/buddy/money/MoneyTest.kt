package buddy.money

import buddy.entities.Recurring
import buddy.policy.HardLimits
import buddy.policy.PolicyContext
import buddy.policy.PolicyEngine
import buddy.policy.PolicyProfile
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.Verdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoneyTest {
    private val day = 86_400_000L
    private fun tx(id: String, cp: String, amount: Double, ts: Long = 1_700_000_000_000, cur: String = "GBP", text: String = "") = Transaction(id, ts, cp, amount, cur, text)

    @Test
    fun `categoriser uses rules and overrides`() {
        val c = Categoriser(overrides = mapOf("pete's" to "housing"))
        assertEquals("groceries", c.categorise(tx("a", "TESCO STORES 1234", 32.1)))
        assertEquals("eating_out", c.categorise(tx("b", "Coffee Co", 3.2)))
        assertEquals("subscriptions", c.categorise(tx("c", "PureGym", 29.99)))
        assertEquals("housing", c.categorise(tx("d", "Pete's", 1200.0)))
        assertEquals("other", c.categorise(tx("e", "XYZ LTD", 10.0)))
        assertEquals("transport", c.categorise(tx("f", "PAYMENT", 40.0, text = "Uber trip")))
    }

    @Test
    fun `anomalies per counterparty`() {
        val d = AnomalyDetector()
        val history = listOf(tx("h1", "GYM", 29.99, 1_700_000_000_000 - 90 * day), tx("h2", "GYM", 29.99, 1_700_000_000_000 - 60 * day), tx("h3", "GYM", 29.99, 1_700_000_000_000 - 30 * day))
        assertEquals(emptyList(), d.check(tx("n", "GYM", 29.99), history).flags)
        assertEquals(listOf("out_of_pattern"), d.check(tx("n", "GYM", 150.0), history).flags)
        assertEquals(listOf("out_of_pattern", "large"), d.check(tx("n", "GYM", 299.0), history).flags)
        assertEquals(listOf("unknown_merchant"), d.check(tx("n", "NEW SHOP", 12.0), history).flags)
        assertEquals(listOf("duplicate"), d.check(tx("n", "GYM", 29.99, 1_700_000_000_000 - 30 * day + 3_600_000), history).flags)
        assertEquals(listOf("unknown_merchant", "foreign"), d.check(tx("n", "CAFE PARIS", 9.0, cur = "EUR"), history).flags)
        assertEquals(listOf("unknown_merchant"), d.check(tx("n", "CAFE PARIS", 9.0, cur = "EUR"), history, travelling = true).flags)
        assertTrue("large" in d.check(tx("n", "GYM", 250.0), history).flags)
    }

    @Test
    fun `bills due become proposals that policy still gates`() {
        val now = 1_700_000_000_000
        val gym = Recurring("GYM", "29.99", "GBP", 4, 30.0, lastTs = now - 29 * day)
        val notYet = Recurring("RENT", "1200", "GBP", 6, 30.0, lastTs = now - 10 * day)
        val proposals = BillProposer.due(listOf(gym, notYet), now, sourceEvents = mapOf("GYM" to "e1"))
        assertEquals(1, proposals.size)
        val p = proposals[0]
        assertEquals("pay_bill", p.spec.name)
        assertEquals(29.99, p.amount)
        assertEquals(listOf("e1"), p.sourceEventIds)

        val ctx = PolicyContext(now, 12, typicalAmount = 29.99)
        val unknownPayee = PolicyEngine(PolicyProfile(levels = mapOf(Domain.MONEY to Level.ACT)))
        assertEquals(listOf("new_payee"), (unknownPayee.decide(p, ctx) as Verdict.Escalate).reasons)
        val knownPayee = PolicyEngine(PolicyProfile(levels = mapOf(Domain.MONEY to Level.ACT), limits = HardLimits(knownPayees = setOf("GYM"))))
        assertIs<Verdict.Run>(knownPayee.decide(p, ctx))
        // Even at FULL the money ceiling holds it at ACT, and the level-hold default escalates irreversible.
        val hold = PolicyEngine(PolicyProfile(levels = mapOf(Domain.MONEY to Level.HOLD), limits = HardLimits(knownPayees = setOf("GYM"))))
        assertIs<Verdict.Escalate>(hold.decide(p, ctx))
    }
}
