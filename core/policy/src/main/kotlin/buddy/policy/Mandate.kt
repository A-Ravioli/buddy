package buddy.policy

/**
 * The envelope of authority one task carries (docs/07, section 4).
 *
 * The product buddy is trying to be keeps going rather than asking after every step.
 * The failures that come with that posture — a payment nobody sanctioned, a message to
 * a party the thread introduced — are not failures of judgement that a better prompt
 * fixes; they are the absence of a boundary. A mandate is that boundary, drawn once,
 * in the user's words, before the work starts: inside it the agent proceeds, at its
 * edge it stops and asks.
 *
 * A mandate can only narrow. It never raises an autonomy level, never lifts a hard
 * limit, and never widens a domain ceiling; [PolicyEngine] applies it before the rules
 * it already had, and those still run underneath.
 */
data class Mandate(
    /** Domains this task may touch at all. */
    val domains: Set<Domain>,
    /** Ceiling for this task, applied on top of the profile's level for the domain. */
    val maxLevel: Level = Level.FULL,
    /** Total this task may spend, across every action in it. Null means no money at all. */
    val spendCap: Double? = null,
    val currency: String? = null,
    /**
     * Counterparties this task may reach. Empty means "whoever this task's own events
     * introduced", which the caller supplies as [PolicyContext.taskTargets]: a task
     * about a thread with Sam may answer Sam, and nobody else.
     */
    val allowedTargets: Set<String> = emptySet(),
    /** After this, the task may not act at all. Every task has one (docs/07, section 3). */
    val deadlineTs: Long,
    val maxCloudTokens: Long = 200_000,
    val maxActions: Int = 20,
    /** Actions that always stop for a human inside this task, whatever the level says. */
    val neverWithoutAsking: Set<String> = emptySet(),
) {
    /**
     * Everything about this proposal that falls outside the envelope. Empty means the
     * agent may proceed without asking, as far as the mandate is concerned.
     */
    fun violations(p: Proposal, ctx: PolicyContext): List<String> {
        val out = ArrayList<String>(2)
        if (ctx.nowTs > deadlineTs) out += "mandate:deadline_passed"
        if (p.spec.domain !in domains) out += "mandate:domain_${p.spec.domain.name.lowercase()}"
        if (p.spec.name in neverWithoutAsking) out += "mandate:action_reserved"
        if (ctx.taskActions >= maxActions) out += "mandate:actions_exhausted"
        if (ctx.taskTokens > maxCloudTokens) out += "mandate:tokens_exhausted"

        val target = p.target
        if (target != null && p.spec.blastRadius != BlastRadius.SELF) {
            val permitted = if (allowedTargets.isNotEmpty()) allowedTargets else ctx.taskTargets
            if (!permitted.any { it.equals(target, ignoreCase = true) }) out += "mandate:target_unknown"
        }

        val amount = p.amount
        if (amount != null) {
            val cap = spendCap
            when {
                cap == null -> out += "mandate:no_spend_authorised"
                currency != null && p.currency != null && !currency.equals(p.currency, ignoreCase = true) ->
                    out += "mandate:wrong_currency"
                ctx.taskSpend + amount > cap -> out += "mandate:over_task_cap"
            }
        }
        return out
    }

    companion object {
        /**
         * What a task gets before the user has said anything: read, file, tidy the
         * user's own device, nothing outward, nothing spent. A task that needs more
         * asks for more.
         */
        fun default(deadlineTs: Long) = Mandate(
            domains = setOf(Domain.DEVICE, Domain.EMAIL, Domain.CALENDAR),
            maxLevel = Level.HOLD,
            spendCap = null,
            deadlineTs = deadlineTs,
            maxActions = 10,
        )
    }
}
