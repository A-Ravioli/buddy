package buddy.triage

/**
 * Structured field extraction from message text. Deterministic regexes, on purpose:
 * they run on every event on the device, they are auditable, and their misses are
 * what the on-device model is later trained to catch.
 *
 * Keys are stable strings the entity graph and the playbooks key off:
 * `otp`, `amount`, `currency`, `tracking`, `booking_ref`, `url`, `phone`, `date_hint`.
 */
object Extractors {
    private val otp = Regex(
        """(?i)(?:code|otp|passcode|pin|verification|verify|token|2fa|one[- ]time)[^\d]{0,40}?\b(\d{4,8})\b|\b(\d{4,8})\b[^\d]{0,40}?(?:is your|is the|as your)[^\n]{0,30}?(?:code|otp|passcode|pin)""",
    )
    private val amount = Regex(
        """(?i)(?:(£|€|\$|USD|GBP|EUR|CHF|INR|JPY|AUD|CAD)\s?(\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?))|(\d{1,3}(?:[,\s]\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)\s?(£|€|\$|USD|GBP|EUR|CHF|INR|JPY|AUD|CAD)\b""",
    )
    private val tracking = Regex(
        """(?i)\b(?:1Z[0-9A-Z]{16}|(?:94|93|92|95)\d{20}|[A-Z]{2}\d{9}[A-Z]{2}|JD\d{18}|TBA\d{12}|\d{12,14})\b""",
    )
    // The reference must contain both a digit and a letter, so that "reference" itself
    // and pure-digit codes (which are OTPs or tracking numbers) never match.
    private val bookingRef = Regex(
        """(?i)\b(?:booking|confirmation|reference|reservation|order|pnr|locator|record)\b(?:\s+(?:number|no\.?|ref\.?|reference|code))?\s*(?:is|:|#)?\s*\b((?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,10})\b""",
    )
    private val url = Regex("""https?://[^\s<>"']+""")
    private val phone = Regex("""(?<![\w.])(\+?\d[\d\s().-]{7,}\d)(?![\w])""")
    private val dateHint = Regex(
        """(?i)\b(today|tomorrow|tonight|this (?:morning|afternoon|evening|week|weekend)|next (?:week|month|mon|tue|wed|thu|fri|sat|sun)[a-z]*|(?:mon|tues|wednes|thurs|fri|satur|sun)day|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.? \d{1,2}(?:st|nd|rd|th)?|\d{1,2}(?:st|nd|rd|th)? (?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*|\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?|\d{1,2}(?::\d{2})?\s?(?:am|pm))\b""",
    )

    fun extract(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()

        otp.find(text)?.let { m ->
            val code = m.groups[1]?.value ?: m.groups[2]?.value
            if (code != null) out["otp"] = code
        }

        amount.find(text)?.let { m ->
            val cur = m.groups[1]?.value ?: m.groups[4]?.value
            val num = (m.groups[2]?.value ?: m.groups[3]?.value)?.replace(Regex("[,\\s]"), "")
            if (cur != null && num != null) {
                out["amount"] = num
                out["currency"] = normalizeCurrency(cur)
            }
        }

        // A tracking number must not be the OTP or part of the amount.
        tracking.find(text)?.value?.let { t ->
            if (t != out["otp"] && !(out["amount"]?.let { t.contains(it) } ?: false)) out["tracking"] = t
        }

        bookingRef.find(text)?.groups?.get(1)?.value?.let { r ->
            if (r != out["otp"] && r != out["tracking"]) out["booking_ref"] = r.uppercase()
        }

        url.find(text)?.value?.let { out["url"] = it.trimEnd('.', ',', ')') }

        phone.find(text)?.groups?.get(1)?.value?.let { p ->
            val digits = p.replace(Regex("[^\\d+]"), "")
            if (digits.length in 8..15 && digits != out["otp"] && digits != out["tracking"]) out["phone"] = digits
        }

        dateHint.find(text)?.value?.let { out["date_hint"] = it.lowercase() }

        return out
    }

    private fun normalizeCurrency(c: String): String = when (c.uppercase()) {
        "£", "GBP" -> "GBP"
        "€", "EUR" -> "EUR"
        "$", "USD" -> "USD"
        else -> c.uppercase()
    }
}
