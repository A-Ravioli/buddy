package buddy.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnboardingTest {
    private val fresh = PolicyProfile(levels = Domain.entries.associateWith { Level.DRAFT })

    @Test
    fun `steps unlock by day and only with a clean record`() {
        assertEquals(setOf(Domain.EMAIL, Domain.DEVICE), Onboarding.due(0, fresh, emptyMap()).map { it.domain }.toSet())
        val day3 = Onboarding.due(3, fresh, emptyMap()).map { it.domain to it.to }
        assertTrue((Domain.CALENDAR to Level.HOLD) in day3)
        val afterWeek = Onboarding.due(7, fresh, emptyMap())
        assertTrue(afterWeek.any { it.domain == Domain.EMAIL && it.to == Level.ACT })
        assertTrue(afterWeek.any { it.domain == Domain.MESSAGING && it.to == Level.HOLD })
        // A critical regret freezes everything.
        assertEquals(emptyList(), Onboarding.due(30, fresh, mapOf(Domain.EMAIL to DomainStats(actions = 10, criticalRegrets = 1))))
        // A noisy domain does not unlock while others still can.
        val noisy = Onboarding.due(7, fresh, mapOf(Domain.EMAIL to DomainStats(actions = 100, regrets = 5)))
        assertTrue(noisy.none { it.domain == Domain.EMAIL })
        assertTrue(noisy.any { it.domain == Domain.MESSAGING })
    }

    @Test
    fun `applied steps stop being due and ceilings are respected`() {
        var p = fresh
        for (s in Onboarding.due(0, p, emptyMap())) p = Onboarding.apply(p, s)
        assertEquals(Level.HOLD, p.levels[Domain.EMAIL])
        assertTrue(Onboarding.due(0, p, emptyMap()).isEmpty())
        // Accounts is capped at DRAFT and never appears; money reaches HOLD at day 28 only.
        val all = Onboarding.due(100, fresh, emptyMap())
        assertTrue(all.none { it.domain == Domain.ACCOUNTS })
        assertTrue(all.any { it.domain == Domain.MONEY && it.to == Level.HOLD })
        assertTrue(Onboarding.due(27, fresh, emptyMap()).none { it.domain == Domain.MONEY })
    }
}
