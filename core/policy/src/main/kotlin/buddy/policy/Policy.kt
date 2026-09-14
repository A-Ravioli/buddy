package buddy.policy

/** The action classification from docs/03-autonomy-and-trust.md. */
enum class Domain { MESSAGING, EMAIL, CALENDAR, MONEY, SHOPPING, TRAVEL, CALLS, ACCOUNTS, DEVICE, SOCIAL }

enum class Reversibility { REVERSIBLE, SOFT, IRREVERSIBLE }

enum class BlastRadius { SELF, KNOWN, EXTERNAL }

/** What an action is, independent of any particular invocation. */
data class ActionSpec(
    val name: String,
    val domain: Domain,
    val reversibility: Reversibility,
    val blastRadius: BlastRadius,
    val description: String = "",
)

/** The five autonomy levels. */
enum class Level(val rank: Int) {
    OBSERVE(0), DRAFT(1), HOLD(2), ACT(3), FULL(4);

    fun next(): Level = entries.firstOrNull { it.rank == rank + 1 } ?: this
    fun previous(): Level = entries.firstOrNull { it.rank == rank - 1 } ?: this
}

/** A concrete action the model (or the user) wants to take. */
data class Proposal(
    val id: String,
    val spec: ActionSpec,
    /** Counterparty: a person key, an address, a payee. Null for self-only actions. */
    val target: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val amount: Double? = null,
    val currency: String? = null,
    /** The model's stated reason, kept for the timeline. */
    val reason: String = "",
    /** Events that led to this proposal. */
    val sourceEventIds: List<String> = emptyList(),
)

/** What the engine knows about the moment. Supplied by the caller so the engine stays pure. */
data class PolicyContext(
    val nowTs: Long,
    /** Local hour of day, 0 to 23. */
    val localHour: Int,
    /** Money already spent today by currency. */
    val spentToday: Map<String, Double> = emptyMap(),
    /** One-time codes seen recently; a payload containing one is never sent anywhere. */
    val knownCodes: Set<String> = emptySet(),
    /** The user has never contacted this target before. */
    val firstContact: Boolean = false,
    /** Typical amount for this counterparty, if there is a history. */
    val typicalAmount: Double? = null,
    /** The second-opinion check judged the proposal to be caused by untrusted content. */
    val suspectedInjection: Boolean = false,
    /** The proposal was triggered by an actor on the emergency list. */
    val emergency: Boolean = false,
    /** The mandate of the task this proposal belongs to, if it belongs to one (docs/07). */
    val mandate: Mandate? = null,
    /** Counterparties this task's own events introduced, for a mandate with no explicit list. */
    val taskTargets: Set<String> = emptySet(),
    /** Money this task has already committed, in the mandate's currency. */
    val taskSpend: Double = 0.0,
    /** Actions this task has already run. */
    val taskActions: Int = 0,
    /** Cloud tokens this task has already spent. */
    val taskTokens: Long = 0,
)

/** Limits enforced in code. The model cannot see, argue with, or change these. */
data class HardLimits(
    val perTransactionCap: Map<String, Double> = mapOf("GBP" to 100.0, "USD" to 120.0, "EUR" to 110.0),
    val perDayCap: Map<String, Double> = mapOf("GBP" to 250.0, "USD" to 300.0, "EUR" to 280.0),
    /** Payees money may go to without a human decision. Empty until the user adds some. */
    val knownPayees: Set<String> = emptySet(),
    /** Targets buddy never contacts on the user's behalf. */
    val neverContacts: Set<String> = emptySet(),
    /** Quiet hours, local, start inclusive to end exclusive; wraps midnight when start > end. */
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    /** A proposal whose amount exceeds this multiple of the typical amount escalates. */
    val anomalyMultiple: Double = 3.0,
    /** Domains whose level can never exceed ACT, whatever the ladder says. */
    val ceilings: Map<Domain, Level> = mapOf(Domain.MONEY to Level.ACT, Domain.ACCOUNTS to Level.DRAFT),
) {
    fun inQuietHours(hour: Int): Boolean =
        if (quietStartHour <= quietEndHour) hour in quietStartHour until quietEndHour
        else hour >= quietStartHour || hour < quietEndHour
}

/** The user's autonomy settings. Stored in the profile, edited by conversation. */
data class PolicyProfile(
    val levels: Map<Domain, Level> = Domain.entries.associateWith { Level.DRAFT } +
        mapOf(Domain.EMAIL to Level.HOLD, Domain.DEVICE to Level.HOLD),
    val limits: HardLimits = HardLimits(),
    val holdMinutes: Int = 10,
) {
    /** The effective level for a domain after ceilings. */
    fun level(domain: Domain, blastRadius: BlastRadius): Level {
        var l = levels[domain] ?: Level.OBSERVE
        limits.ceilings[domain]?.let { if (l.rank > it.rank) l = it }
        // Anything touching a stranger is never above ACT.
        if (blastRadius == BlastRadius.EXTERNAL && l.rank > Level.ACT.rank) l = Level.ACT
        return l
    }
}

sealed class Verdict {
    abstract val reasons: List<String>

    data class Run(override val reasons: List<String>) : Verdict()
    data class Hold(val untilTs: Long, override val reasons: List<String>) : Verdict()
    data class Escalate(override val reasons: List<String>) : Verdict()
    data class Deny(override val reasons: List<String>) : Verdict()

    val name: String get() = this::class.simpleName!!.lowercase()
}

/**
 * The policy engine. Pure: proposal plus context in, verdict out. Hard limits first,
 * then anomaly gating, then the autonomy level for the domain.
 */
class PolicyEngine(private val profile: PolicyProfile) {

    fun decide(p: Proposal, ctx: PolicyContext): Verdict {
        val reasons = ArrayList<String>()
        val limits = profile.limits

        // ---- Hard limits: deny outright. -------------------------------------------
        if (ctx.knownCodes.isNotEmpty() && p.payload.values.any { v -> ctx.knownCodes.any { v.contains(it) } }) {
            return Verdict.Deny(listOf("code_in_payload"))
        }
        if (p.target != null && p.target in limits.neverContacts) return Verdict.Deny(listOf("never_contact"))

        // ---- The task's mandate: outside it, the human decides (docs/07, section 4). --
        // Before everything else, because a mandate is the boundary the user drew for
        // this job, and no autonomy level earned in general overrides it here.
        ctx.mandate?.violations(p, ctx)?.takeIf { it.isNotEmpty() }?.let { return Verdict.Escalate(it) }

        // ---- Human decisions: escalate whatever the level. ----------------------------
        if (ctx.suspectedInjection) reasons += "injection_suspected"
        if (p.amount != null) {
            val cur = p.currency ?: "GBP"
            if (p.target == null || p.target !in limits.knownPayees) reasons += "new_payee"
            limits.perTransactionCap[cur]?.let { if (p.amount > it) reasons += "over_transaction_cap" }
            limits.perDayCap[cur]?.let { if ((ctx.spentToday[cur] ?: 0.0) + p.amount > it) reasons += "over_daily_cap" }
            ctx.typicalAmount?.let { if (it > 0 && p.amount > it * limits.anomalyMultiple) reasons += "amount_anomaly" }
        }
        if (ctx.firstContact && p.spec.blastRadius != BlastRadius.SELF) reasons += "first_contact"
        if (reasons.isNotEmpty()) return Verdict.Escalate(reasons)

        // ---- Quiet hours: hold until they end, unless it is an emergency. ------------
        if (limits.inQuietHours(ctx.localHour) && !ctx.emergency && p.spec.blastRadius != BlastRadius.SELF) {
            return Verdict.Hold(quietEnd(ctx), listOf("quiet_hours"))
        }

        // ---- Autonomy level for the domain, narrowed by the task's mandate. -----------
        var level = profile.level(p.spec.domain, p.spec.blastRadius)
        ctx.mandate?.let { if (level.rank > it.maxLevel.rank) level = it.maxLevel }
        val irreversible = p.spec.reversibility == Reversibility.IRREVERSIBLE
        return when (level) {
            Level.OBSERVE -> Verdict.Escalate(listOf("level_observe"))
            Level.DRAFT -> Verdict.Escalate(listOf("level_draft"))
            Level.HOLD -> if (irreversible) Verdict.Escalate(listOf("level_hold_irreversible"))
                else Verdict.Hold(ctx.nowTs + profile.holdMinutes * 60_000L, listOf("level_hold"))
            Level.ACT -> Verdict.Run(listOf(if (irreversible) "level_act_within_limits" else "level_act"))
            Level.FULL -> Verdict.Run(listOf("level_full"))
        }
    }

    private fun quietEnd(ctx: PolicyContext): Long {
        val hoursUntil = ((profile.limits.quietEndHour - ctx.localHour) + 24) % 24
        return ctx.nowTs + (if (hoursUntil == 0) 24 else hoursUntil) * 3_600_000L
    }
}
