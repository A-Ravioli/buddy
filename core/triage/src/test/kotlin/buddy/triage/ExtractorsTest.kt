package buddy.triage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtractorsTest {
    @Test
    fun `otp in common phrasings`() {
        assertEquals("482913", Extractors.extract("Your verification code is 482913")["otp"])
        assertEquals("1234", Extractors.extract("1234 is your Uber code")["otp"])
        assertEquals("55667788", Extractors.extract("Use OTP 55667788 to log in")["otp"])
        assertNull(Extractors.extract("Order #482913 shipped")["otp"])
    }

    @Test
    fun `amounts with symbols before or after`() {
        val a = Extractors.extract("Card payment of £1,234.50 at Coffee Co")
        assertEquals("1234.50", a["amount"]); assertEquals("GBP", a["currency"])
        val b = Extractors.extract("Total: 19.99 EUR")
        assertEquals("19.99", b["amount"]); assertEquals("EUR", b["currency"])
        val c = Extractors.extract("You paid $5")
        assertEquals("5", c["amount"]); assertEquals("USD", c["currency"])
    }

    @Test
    fun `tracking numbers and booking references`() {
        assertEquals("1Z999AA10123456784", Extractors.extract("Track 1Z999AA10123456784 online")["tracking"])
        assertEquals("RB123456789GB", Extractors.extract("Royal Mail RB123456789GB")["tracking"])
        assertEquals("X7K9Q2", Extractors.extract("Your booking reference is X7K9Q2 for flight BA123")["booking_ref"])
        // A pure-digit reference is not a booking ref (would collide with codes and tracking).
        assertNull(Extractors.extract("Confirmation number 123456")["booking_ref"])
    }

    @Test
    fun `urls, phones and date hints`() {
        val m = Extractors.extract("See https://example.com/a?b=1. Call +44 7700 900123 tomorrow at 3pm")
        assertEquals("https://example.com/a?b=1", m["url"])
        assertEquals("+447700900123", m["phone"])
        assertEquals("tomorrow", m["date_hint"])
        assertEquals("thursday", Extractors.extract("dinner Thursday?")["date_hint"])
        assertEquals("12/03", Extractors.extract("due 12/03")["date_hint"])
    }

    @Test
    fun `empty and null`() {
        assertEquals(emptyMap(), Extractors.extract(null))
        assertEquals(emptyMap(), Extractors.extract("   "))
    }
}
