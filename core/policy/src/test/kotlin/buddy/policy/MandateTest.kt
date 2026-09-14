package buddy.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mandate is what makes "continue rather than confirm" safe (docs/07, section 4).
 * These are its truth tables: inside the envelope the agent proceeds, at its edge the
 * person decides, and no autonomy level earned elsewhere changes that.
 */
class MandateTest {
    private val now = 1_700_000_000_000L
    private val deadline = now + 86_400_000L

    /** The most permissive profile the trust ladder can ever reach. */
    private val maxTrust = PolicyProfile(levels = Domain.entries.associateWith { Level.FULL })

    /** The same, with the restaurant already an approved payee, so the cap arithmetic is what is under test. */
    private val maxTrustKnownPayee = maxTrust.copy(limits = maxTrust.limits.copy(knownPayees = setOf("kiln@example.com")))

    private val booking = Mandate(
        domains = setOf(Domain.MESSAGING, Domain.SHOPPING),
        maxLevel = Level.ACT,
        spendCap = 60.0,
        currency = "GBP",
        allowedTargets = setOf("kiln@example.com"),
        deadlineTs = deadline,
        maxActions = 3,
    )

    private fun ctx(
        mandate: Mandate? = booking,
        nowTs: Long = now,
        taskSpend: Double = 0.0,
        taskActions: Int = 0,
        taskTokens: Long = 0,
        taskTargets: Set<String> = emptySet(),
    ) = PolicyContext(
        nowTs = nowTs,
        localHour = 14,
        mandate = mandate,
        taskTargets = taskTargets,
        taskSpend = taskSpend,
        taskActions = taskActions,
        taskTokens = taskTokens,
    )

    private fun proposal(
        spec: ActionSpec,
        target: String? = "kiln@example.com",
        amount: Double? = null,
        currency: String? = null,
    ) = Proposal("p1", spec, target, mapOf("text" to "hi"), amount, currency, "because", listOf("e1"))

    private val reply = ActionSpec("reply_email", Domain.EMAIL, Reversibility.SOFT, BlastRadius.KNOWN)
    private val message = ActionSpec("send_message", Domain.MESSAGING, Reversibility.SOFT, BlastRadius.KNOWN)
    private val pay = ActionSpec("pay_bill", Domain.MONEY, Reversibility.IRREVERSIBLE, BlastRadius.EXTERNAL)
    private val dismiss = ActionSpec("notification_dismiss", Domain.DEVICE, Reversibility.REVERSIBLE, BlastRadius.SELF)

    @Test
    fun `inside the envelope the action runs`() {
        val v = PolicyEngine(maxTrust).decide(proposal(message), ctx())
        assertTrue(v is Verdict.Run, "expected run, got $v")
    }

    @Test
    fun `a domain the task was not granted escalates, at any autonomy level`() {
        val v = PolicyEngine(maxTrust).decide(proposal(reply), ctx())
        assertEquals(listOf("mandate:domain_email"), v.reasons)
        assertTrue(v is Verdict.Escalate)
    }

    @Test
    fun `a target the task was not given escalates`() {
        val v = PolicyEngine(maxTrust).decide(proposal(message, target = "someone@else.example"), ctx())
        assertEquals(listOf("mandate:target_unknown"), v.reasons)
    }

    @Test
    fun `with no explicit targets, the task may reach whoever its own events introduced`() {
        val open = booking.copy(allowedTargets = emptySet())
        val known = PolicyEngine(maxTrust).decide(
            proposal(message, target = "sam@example.com"),
            ctx(mandate = open, taskTargets = setOf("sam@example.com")),
        )
        assertTrue(known is Verdict.Run, "expected run, got $known")

        val stranger = PolicyEngine(maxTrust).decide(
            proposal(message, target = "stranger@example.com"),
            ctx(mandate = open, taskTargets = setOf("sam@example.com")),
        )
        assertEquals(listOf("mandate:target_unknown"), stranger.reasons)
    }

    @Test
    fun `money needs an explicit cap, and the cap is the task total, not the transaction`() {
        val noMoney = PolicyEngine(maxTrustKnownPayee).decide(
            proposal(message, amount = 10.0, currency = "GBP"),
            ctx(mandate = booking.copy(spendCap = null)),
        )
        assertEquals(listOf("mandate:no_spend_authorised"), noMoney.reasons)

        // Two twenties are fine; the third thirty crosses the sixty the person granted.
        val within = PolicyEngine(maxTrustKnownPayee).decide(
            proposal(message, amount = 20.0, currency = "GBP"),
            ctx(taskSpend = 20.0),
        )
        assertTrue(within is Verdict.Run, "expected run, got $within")

        val over = PolicyEngine(maxTrustKnownPayee).decide(
            proposal(message, amount = 30.0, currency = "GBP"),
            ctx(taskSpend = 40.0),
        )
        assertEquals(listOf("mandate:over_task_cap"), over.reasons)
    }

    @Test
    fun `a spend cap does not make an unknown payee known`() {
        // The person authorising "up to sixty at Kiln" narrows what this task may do.
        // It does not add Kiln to the payees money may reach without a decision: a
        // mandate only ever narrows, so the hard limit underneath still escalates.
        val v = PolicyEngine(maxTrust).decide(proposal(message, amount = 20.0, currency = "GBP"), ctx())
        assertEquals(listOf("new_payee"), v.reasons)
    }

    @Test
    fun `a mandate in the wrong currency is not a mandate for this spend`() {
        val v = PolicyEngine(maxTrust).decide(proposal(message, amount = 10.0, currency = "USD"), ctx())
        assertEquals(listOf("mandate:wrong_currency"), v.reasons)
    }

    @Test
    fun `budgets stop a task that thrashes`() {
        assertEquals(listOf("mandate:actions_exhausted"), PolicyEngine(maxTrust).decide(proposal(message), ctx(taskActions = 3)).reasons)
        assertEquals(
            listOf("mandate:tokens_exhausted"),
            PolicyEngine(maxTrust).decide(proposal(message), ctx(taskTokens = booking.maxCloudTokens + 1)).reasons,
        )
    }

    @Test
    fun `past the deadline the task cannot act at all`() {
        val v = PolicyEngine(maxTrust).decide(proposal(message), ctx(nowTs = deadline + 1))
        assertTrue(v.reasons.contains("mandate:deadline_passed"))
    }

    @Test
    fun `a reserved action always asks, even inside the envelope`() {
        val v = PolicyEngine(maxTrust).decide(
            proposal(message),
            ctx(mandate = booking.copy(neverWithoutAsking = setOf("send_message"))),
        )
        assertEquals(listOf("mandate:action_reserved"), v.reasons)
    }

    @Test
    fun `a mandate narrows the level but never raises it`() {
        // The profile says DRAFT for messaging; a mandate asking for FULL cannot lift it.
        val cautious = PolicyProfile(levels = Domain.entries.associateWith { Level.DRAFT })
        val raised = PolicyEngine(cautious).decide(proposal(message), ctx(mandate = booking.copy(maxLevel = Level.FULL)))
        assertEquals(listOf("level_draft"), raised.reasons)

        // The other way round it bites: maximum trust, mandate capped at HOLD.
        val held = PolicyEngine(maxTrust).decide(proposal(message), ctx(mandate = booking.copy(maxLevel = Level.HOLD)))
        assertTrue(held is Verdict.Hold, "expected hold, got $held")
    }

    @Test
    fun `the hard limits still run underneath the mandate`() {
        // A mandate cannot authorise sending a one-time code, whatever it says.
        val v = PolicyEngine(maxTrust).decide(
            Proposal("p", message, "kiln@example.com", mapOf("text" to "the code is 482913"), reason = "asked"),
            ctx().copy(knownCodes = setOf("482913")),
        )
        assertEquals(Verdict.Deny(listOf("code_in_payload")), v)
    }

    @Test
    fun `the standing mandate can tidy the device and the inbox, and nothing else`() {
        val standing = Mandate.default(deadline)
        val tidy = PolicyEngine(maxTrust).decide(proposal(dismiss, target = null), ctx(mandate = standing))
        assertTrue(tidy is Verdict.Hold, "reversible device work holds rather than runs: $tidy")
        // Money is outside the standing authority three times over: the domain, the
        // payee, and the fact that no spend was authorised at all.
        assertEquals(
            listOf("mandate:domain_money", "mandate:target_unknown", "mandate:no_spend_authorised"),
            PolicyEngine(maxTrust).decide(proposal(pay, amount = 5.0, currency = "GBP"), ctx(mandate = standing)).reasons,
        )
    }

    @Test
    fun `without a task there is no mandate and the engine behaves as it always did`() {
        val v = PolicyEngine(maxTrust).decide(proposal(reply, target = "anyone@example.com"), ctx(mandate = null))
        assertTrue(v is Verdict.Run, "expected run, got $v")
    }
}
