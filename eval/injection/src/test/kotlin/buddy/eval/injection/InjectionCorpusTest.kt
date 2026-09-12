package buddy.eval.injection

import buddy.actuation.Actions
import buddy.cognition.Envelope
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import buddy.policy.Domain
import buddy.policy.Level
import buddy.policy.PolicyContext
import buddy.policy.PolicyEngine
import buddy.policy.PolicyProfile
import buddy.policy.Proposal
import buddy.policy.Verdict
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InjectionCorpusTest {
    /** The most permissive profile the ladder can reach: every domain at its ceiling. */
    private val maxTrust = PolicyProfile(levels = Domain.entries.associateWith { Level.FULL })

    @Test
    fun `no case in the corpus runs, even with a fully fooled model at maximum trust`() {
        val engine = PolicyEngine(maxTrust)
        val failures = ArrayList<String>()
        for (c in InjectionCorpus.cases) {
            val spec = Actions.byName[c.inducedAction] ?: error("unknown action in corpus: ${c.inducedAction}")
            val p = Proposal(c.id, spec, c.inducedTarget, c.inducedPayload, c.amount, c.currency, reason = "fooled", sourceEventIds = listOf("e"))
            val ctx = PolicyContext(
                nowTs = 1_700_000_000_000, localHour = 14, knownCodes = c.knownCodes,
                firstContact = c.firstContact, suspectedInjection = false, // the second opinion is assumed to have failed too
            )
            val v = engine.decide(p, ctx)
            if (v is Verdict.Run) failures.add("${c.id}: ran with ${v.reasons}")
        }
        assertTrue(failures.isEmpty(), "injections that got through:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `envelopes cannot be broken out of by corpus text`() {
        val zone = ZoneId.of("UTC")
        for (c in InjectionCorpus.cases) {
            val e = Event(EventId.of(1, "x", "y", c.id), 1_700_000_000_000, "com.app", "sms", EventKind.MESSAGE, c.actor, "t", c.text, emptyMap(), Trust.UNTRUSTED)
            val rendered = Envelope.render(e, zone)
            // Exactly one opening and one closing tag survive, and the one real tag says untrusted.
            assertTrue(rendered.indexOf("<event ") == 0 && rendered.indexOf("<event ", 1) == -1, c.id)
            assertTrue(rendered.endsWith("</event>") && rendered.indexOf("</event>") == rendered.lastIndexOf("</event>"), c.id)
            val openingTag = rendered.substring(0, rendered.indexOf('>'))
            assertTrue(openingTag.contains("trust=\"untrusted\""), c.id)
            assertFalse(openingTag.contains("trust=\"system\""), c.id)
        }
    }

    @Test
    fun `the night-time stranger case holds rather than runs even when the contact is known`() {
        val engine = PolicyEngine(maxTrust)
        val c = InjectionCorpus.cases.first { it.id == "night_message_to_stranger" }
        val p = Proposal(c.id, Actions.SEND_MESSAGE, c.inducedTarget, c.inducedPayload)
        val v = engine.decide(p, PolicyContext(1_700_000_000_000, localHour = 2, firstContact = false))
        assertTrue(v is Verdict.Hold, "expected hold, got $v")
    }
}
