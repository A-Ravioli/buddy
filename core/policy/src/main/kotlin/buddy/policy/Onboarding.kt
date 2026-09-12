package buddy.policy

/**
 * The first-week trust ladder (docs/05-roadmap.md, Phase 4 onboarding). A new user
 * starts at the day-one defaults and buddy proposes, never applies, each step up,
 * conditioned on zero critical regrets so far. The schedule is deliberately slower
 * than the evidence ladder; a new user has not yet seen what buddy does.
 */
object Onboarding {
    data class Step(val dayFrom: Int, val domain: Domain, val to: Level, val why: String)

    val schedule: List<Step> = listOf(
        Step(0, Domain.EMAIL, Level.HOLD, "Filing and archiving with a ten-minute hold is the safest relief."),
        Step(0, Domain.DEVICE, Level.HOLD, "Notification tidying is reversible."),
        Step(3, Domain.CALENDAR, Level.HOLD, "Invites from known senders with a hold, once you have seen the brief a few times."),
        Step(7, Domain.EMAIL, Level.ACT, "Filing without the hold, if nothing was vetoed in week one."),
        Step(7, Domain.MESSAGING, Level.HOLD, "Logistics replies to friends and colleagues with a hold; close contacts stay at draft."),
        Step(14, Domain.SHOPPING, Level.HOLD, "Delivery reschedules with a hold."),
        Step(14, Domain.CALENDAR, Level.ACT, "Invites without the hold."),
        Step(28, Domain.MONEY, Level.HOLD, "Bill proposals visible with a hold; payment itself needs known payees."),
    )

    /** Steps due on [day] since onboarding that the profile has not yet reached, if the record is clean. */
    fun due(day: Int, profile: PolicyProfile, stats: Map<Domain, DomainStats>): List<Step> {
        if (stats.values.any { it.criticalRegrets > 0 }) return emptyList()
        return schedule.filter { s ->
            s.dayFrom <= day &&
                (profile.levels[s.domain]?.rank ?: 0) < s.to.rank &&
                (profile.limits.ceilings[s.domain]?.rank ?: Level.FULL.rank) >= s.to.rank &&
                (stats[s.domain]?.regretRate ?: 0.0) <= 0.01
        }
    }

    /** Applies one accepted step. */
    fun apply(profile: PolicyProfile, step: Step): PolicyProfile =
        profile.copy(levels = profile.levels + (step.domain to step.to))
}
