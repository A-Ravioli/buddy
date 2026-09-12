package buddy.triage

import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleTriageTest {
    private val t = RuleTriage()
    private val profile = TriageProfile(
        emergencyActors = setOf("+15550001111"),
        closeActors = setOf("+15552222222", "Sam"),
        mutedActors = setOf("SPAMCO"),
        mutedPackages = setOf("com.game.noisy"),
    )

    private fun msg(
        text: String,
        actor: String? = "+15559999999",
        pkg: String = "com.chat",
        kind: EventKind = EventKind.MESSAGE,
        trust: Trust = Trust.UNTRUSTED,
        structured: Map<String, String> = emptyMap(),
    ) = Event(
        id = EventId.of(1_700_000_000_000, pkg, "x", actor, text),
        ts = 1_700_000_000_000, sourceApp = pkg, channel = "x", kind = kind, actor = actor,
        text = text, structured = structured, trust = trust,
    )

    private fun klass(e: Event) = t.triage(e, profile).klass

    @Test
    fun `emergency actors and urgent words interrupt`() {
        val d = t.triage(msg("hey", actor = "+15550001111"), profile)
        assertEquals(TriageClass.ESCALATE, d.klass)
        assertTrue(d.urgent)
        assertTrue("emergency_actor" in d.reasons)
        assertTrue(t.triage(msg("It's an emergency, call me"), profile).urgent)
    }

    @Test
    fun `close contacts always escalate, never urgent by default`() {
        val d = t.triage(msg("lol", actor = "Sam"), profile)
        assertEquals(TriageClass.ESCALATE, d.klass)
        assertFalse(d.urgent)
    }

    @Test
    fun `muted and noise are dropped`() {
        assertEquals(TriageClass.DROP, klass(msg("buy now", actor = "SPAMCO")))
        assertEquals(TriageClass.DROP, klass(msg("level up!", pkg = "com.game.noisy")))
        assertEquals(TriageClass.DROP, klass(msg("Updating 3 apps", pkg = "com.android.vending", kind = EventKind.NOTIFICATION)))
    }

    @Test
    fun `one-time codes are filed with the code extracted and never escalated`() {
        val d = t.triage(msg("Your verification code is 482913. Do not share it.", actor = "AMAZON"), profile)
        assertEquals(TriageClass.FILE, d.klass)
        assertEquals("482913", d.extracted["otp"])
    }

    @Test
    fun `marketing is dropped but receipts inside automated mail are filed`() {
        assertEquals(TriageClass.DROP, klass(msg("Flash sale! 40% off everything. Unsubscribe here.", actor = "noreply@shop.com", kind = EventKind.NOTIFICATION)))
        val receipt = t.triage(msg("Your order confirmation: total £42.10. Unsubscribe from these emails.", actor = "noreply@shop.com", kind = EventKind.NOTIFICATION), profile)
        assertEquals(TriageClass.FILE, receipt.klass)
        assertEquals("42.10", receipt.extracted["amount"])
        assertEquals("GBP", receipt.extracted["currency"])
    }

    @Test
    fun `deliveries are filed unless they ask for something`() {
        assertEquals(TriageClass.FILE, klass(msg("Your parcel 1Z999AA10123456784 has been delivered", actor = "UPS", kind = EventKind.NOTIFICATION)))
        assertEquals(TriageClass.ACT_LATER, klass(msg("We missed you. Please reschedule your delivery", actor = "DPD", kind = EventKind.NOTIFICATION)))
    }

    @Test
    fun `questions from ordinary contacts become work, time-sensitive ones now`() {
        assertEquals(TriageClass.ACT_LATER, klass(msg("Can you send me the doc when you get a chance?")))
        assertEquals(TriageClass.ACT_NOW, klass(msg("Are you coming tonight?")))
        assertEquals(TriageClass.FILE, klass(msg("haha nice")))
        assertEquals(TriageClass.FILE, klass(msg("anyone up for lunch", structured = mapOf("group" to "true"))))
    }

    @Test
    fun `calls and calendar`() {
        assertEquals(TriageClass.ESCALATE, klass(msg("", kind = EventKind.CALL, structured = mapOf("outcome" to "missed"))))
        assertEquals(TriageClass.FILE, klass(msg("", kind = EventKind.CALL, structured = mapOf("outcome" to "answered"))))
        assertEquals(TriageClass.ESCALATE, klass(msg("Standup", kind = EventKind.CALENDAR_CHANGE, structured = mapOf("status" to "canceled"))))
        assertEquals(TriageClass.ACT_LATER, klass(msg("Lunch?", kind = EventKind.CALENDAR_CHANGE, structured = mapOf("status" to "confirmed", "my_response" to "invited"))))
        assertEquals(TriageClass.FILE, klass(msg("Standup", kind = EventKind.CALENDAR_CHANGE, structured = mapOf("status" to "confirmed", "my_response" to "accepted"))))
    }

    @Test
    fun `own words, own records and sensors are filed`() {
        assertEquals(TriageClass.FILE, klass(msg("on my way", trust = Trust.USER)))
        assertEquals(TriageClass.FILE, klass(msg("lat=1", kind = EventKind.LOCATION, trust = Trust.SYSTEM)))
        assertEquals(TriageClass.FILE, klass(msg("Orders\nKettle", kind = EventKind.SCREEN_STATE)))
    }

    @Test
    fun `decisions are deterministic`() {
        val e = msg("Can you confirm for Thursday?")
        assertEquals(t.triage(e, profile), t.triage(e, profile))
    }
}
