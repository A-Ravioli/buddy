package buddy.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyEngineTest {
    private val send = ActionSpec("send_message", Domain.MESSAGING, Reversibility.SOFT, BlastRadius.KNOWN)
    private val archive = ActionSpec("archive_email", Domain.EMAIL, Reversibility.REVERSIBLE, BlastRadius.SELF)
    private val pay = ActionSpec("pay_bill", Domain.MONEY, Reversibility.IRREVERSIBLE, BlastRadius.EXTERNAL)
    private val day = PolicyContext(nowTs = 1_700_000_000_000, localHour = 14)
    private val night = day.copy(localHour = 23)

    private fun engine(vararg levels: Pair<Domain, Level>, limits: HardLimits = HardLimits()) =
        PolicyEngine(PolicyProfile(levels = PolicyProfile().levels + levels.toMap(), limits = limits))

    @Test
    fun `codes never leave the phone whatever the level`() {
        val e = engine(Domain.MESSAGING to Level.FULL)
        val p = Proposal("p", send, "+15550000000", mapOf("text" to "the code is 482913"))
        assertEquals(Verdict.Deny(listOf("code_in_payload")), e.decide(p, day.copy(knownCodes = setOf("482913"))))
        assertIs<Verdict.Run>(e.decide(p, day)) // same text, no known code: not the engine's business
    }

    @Test
    fun `never-contact list denies`() {
        val e = engine(Domain.MESSAGING to Level.FULL, limits = HardLimits(neverContacts = setOf("ex@example.com")))
        assertEquals(Verdict.Deny(listOf("never_contact")), e.decide(Proposal("p", send, "ex@example.com"), day))
    }

    @Test
    fun `money escalates on new payee, caps and anomalies, and never exceeds ACT`() {
        val limits = HardLimits(knownPayees = setOf("GYM"))
        val e = engine(Domain.MONEY to Level.FULL, limits = limits)
        val bill = Proposal("p", pay, "GYM", amount = 29.99, currency = "GBP")
        assertIs<Verdict.Run>(e.decide(bill, day.copy(typicalAmount = 29.99)))
        assertEquals(listOf("new_payee"), (e.decide(bill.copy(target = "STRANGER"), day) as Verdict.Escalate).reasons)
        assertEquals(listOf("over_transaction_cap"), (e.decide(bill.copy(amount = 150.0), day) as Verdict.Escalate).reasons)
        assertEquals(listOf("over_daily_cap"), (e.decide(bill, day.copy(spentToday = mapOf("GBP" to 240.0))) as Verdict.Escalate).reasons)
        assertEquals(listOf("amount_anomaly"), (e.decide(bill.copy(amount = 99.0), day.copy(typicalAmount = 20.0)) as Verdict.Escalate).reasons)
        assertEquals(Level.ACT, PolicyProfile(levels = mapOf(Domain.MONEY to Level.FULL)).level(Domain.MONEY, BlastRadius.EXTERNAL))
    }

    @Test
    fun `suspected injection and first contact escalate`() {
        val e = engine(Domain.MESSAGING to Level.FULL)
        val p = Proposal("p", send, "+15550000000", mapOf("text" to "hi"))
        assertEquals(listOf("injection_suspected"), (e.decide(p, day.copy(suspectedInjection = true)) as Verdict.Escalate).reasons)
        assertEquals(listOf("first_contact"), (e.decide(p, day.copy(firstContact = true)) as Verdict.Escalate).reasons)
        // Self-only actions do not care about first contact.
        assertIs<Verdict.Run>(engine(Domain.EMAIL to Level.ACT).decide(Proposal("p", archive), day.copy(firstContact = true)))
    }

    @Test
    fun `quiet hours hold outward actions until morning unless emergency`() {
        val e = engine(Domain.MESSAGING to Level.ACT)
        val p = Proposal("p", send, "+15550000000", mapOf("text" to "hi"))
        val v = e.decide(p, night)
        assertIs<Verdict.Hold>(v)
        assertEquals(listOf("quiet_hours"), v.reasons)
        assertEquals(night.nowTs + 9 * 3_600_000L, v.untilTs) // 23:00 to 08:00
        assertIs<Verdict.Run>(e.decide(p, night.copy(emergency = true)))
        assertIs<Verdict.Run>(engine(Domain.EMAIL to Level.ACT).decide(Proposal("p", archive), night))
        assertTrue(HardLimits().inQuietHours(2))
        assertTrue(!HardLimits().inQuietHours(12))
    }

    @Test
    fun `levels map to verdicts`() {
        val p = Proposal("p", send, "+15550000000", mapOf("text" to "hi"))
        assertEquals(listOf("level_observe"), (engine(Domain.MESSAGING to Level.OBSERVE).decide(p, day) as Verdict.Escalate).reasons)
        assertEquals(listOf("level_draft"), (engine(Domain.MESSAGING to Level.DRAFT).decide(p, day) as Verdict.Escalate).reasons)
        val hold = engine(Domain.MESSAGING to Level.HOLD).decide(p, day)
        assertIs<Verdict.Hold>(hold)
        assertEquals(day.nowTs + 10 * 60_000L, hold.untilTs)
        assertIs<Verdict.Run>(engine(Domain.MESSAGING to Level.ACT).decide(p, day))
        // Irreversible at HOLD escalates; at ACT runs within limits.
        val payKnown = Proposal("p", pay, "GYM", amount = 10.0, currency = "GBP")
        val limits = HardLimits(knownPayees = setOf("GYM"))
        assertIs<Verdict.Escalate>(engine(Domain.MONEY to Level.HOLD, limits = limits).decide(payKnown, day))
        assertEquals(listOf("level_act_within_limits"), (engine(Domain.MONEY to Level.ACT, limits = limits).decide(payKnown, day) as Verdict.Run).reasons)
    }

    @Test
    fun `defaults are draft everywhere except email and device at hold`() {
        val p = PolicyProfile()
        assertEquals(Level.DRAFT, p.level(Domain.MESSAGING, BlastRadius.KNOWN))
        assertEquals(Level.HOLD, p.level(Domain.EMAIL, BlastRadius.SELF))
        assertEquals(Level.DRAFT, p.level(Domain.ACCOUNTS, BlastRadius.SELF))
    }

    @Test
    fun `trust ladder promotes on evidence and demotes on triggers`() {
        val l = TrustLadder(minActions = 10)
        val limits = HardLimits()
        assertNull(l.proposePromotion(Domain.EMAIL, Level.HOLD, DomainStats(actions = 5), limits))
        assertNull(l.proposePromotion(Domain.EMAIL, Level.HOLD, DomainStats(actions = 100, regrets = 5), limits))
        assertNull(l.proposePromotion(Domain.EMAIL, Level.HOLD, DomainStats(actions = 100, criticalRegrets = 1), limits))
        val up = l.proposePromotion(Domain.EMAIL, Level.HOLD, DomainStats(actions = 100, regrets = 0, escalations = 40, overrides = 1), limits)
        assertNotNull(up)
        assertEquals(Level.ACT, up.to)
        // Ceilings stop the ladder.
        assertNull(l.proposePromotion(Domain.MONEY, Level.ACT, DomainStats(actions = 500), limits))
        assertNull(l.proposePromotion(Domain.ACCOUNTS, Level.DRAFT, DomainStats(actions = 500), limits))
        val down = l.demoteFor(Domain.MESSAGING, Level.ACT, "critical_regret")
        assertEquals(Level.HOLD, down!!.to)
        assertNull(l.demoteFor(Domain.MESSAGING, Level.ACT, "ordinary_regret"))
        assertNull(l.demoteFor(Domain.MESSAGING, Level.OBSERVE, "missed_critical"))
    }
}
