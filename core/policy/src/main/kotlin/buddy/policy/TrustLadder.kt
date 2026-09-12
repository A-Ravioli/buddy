package buddy.policy

/** Outcomes the ladder learns from, per domain. */
data class DomainStats(
    val actions: Int = 0,
    /** Actions the user undid or corrected. */
    val regrets: Int = 0,
    /** Actions the user marked as "should never have happened". */
    val criticalRegrets: Int = 0,
    /** Escalations where the user chose something other than buddy's recommendation. */
    val overrides: Int = 0,
    val escalations: Int = 0,
) {
    val regretRate: Double get() = if (actions == 0) 0.0 else regrets.toDouble() / actions
    val overrideRate: Double get() = if (escalations == 0) 0.0 else overrides.toDouble() / escalations
}

sealed class LadderMove {
    abstract val domain: Domain
    abstract val from: Level
    abstract val to: Level
    abstract val reason: String

    /** Proposed to the user in the brief; applied only when they confirm. */
    data class Promote(override val domain: Domain, override val from: Level, override val to: Level, override val reason: String) : LadderMove()

    /** Applied immediately and reported in the next brief. */
    data class Demote(override val domain: Domain, override val from: Level, override val to: Level, override val reason: String) : LadderMove()
}

/**
 * The trust ladder (docs/03-autonomy-and-trust.md). Up on sustained evidence, with the
 * user's confirmation; down immediately on a critical regret, a missed critical item,
 * a limit hit, or a suspected injection.
 */
class TrustLadder(
    private val minActions: Int = 50,
    private val maxRegretRate: Double = 0.01,
    private val maxOverrideRate: Double = 0.05,
) {
    fun proposePromotion(domain: Domain, current: Level, stats: DomainStats, limits: HardLimits): LadderMove.Promote? {
        if (stats.actions < minActions) return null
        if (stats.regretRate > maxRegretRate || stats.overrideRate > maxOverrideRate) return null
        if (stats.criticalRegrets > 0) return null
        val ceiling = limits.ceilings[domain] ?: Level.FULL
        val next = current.next()
        if (next == current || next.rank > ceiling.rank) return null
        return LadderMove.Promote(domain, current, next, "${stats.actions} actions, regret ${"%.3f".format(stats.regretRate)}, overrides ${"%.3f".format(stats.overrideRate)}")
    }

    /** Events that force a demotion. Returns the move to apply now, or null. */
    fun demoteFor(domain: Domain, current: Level, trigger: String): LadderMove.Demote? {
        if (trigger !in DEMOTION_TRIGGERS) return null
        val to = current.previous()
        if (to == current) return null
        return LadderMove.Demote(domain, current, to, trigger)
    }

    companion object {
        val DEMOTION_TRIGGERS = setOf("critical_regret", "missed_critical", "limit_hit", "injection_suspected")
    }
}
