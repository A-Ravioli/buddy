package buddy.entities

/**
 * Identity resolution across apps, version 0: exact matches on normalised phone
 * numbers and email addresses, and a conservative name match that only fires when the
 * name is unambiguous in the graph. Ambiguous cases stay separate until the idle
 * cycle's model pass merges them (docs/02-context-and-memory.md), and every merge is
 * an event the user can undo.
 */
object Identity {
    private val emailRe = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    /** Normalised form of an actor string: `tel:+447700900123`, `mailto:sam@x.com`, or `name:sam smith`. */
    fun key(raw: String, defaultRegion: String = "GB"): String {
        val s = raw.trim()
        if (s.isEmpty()) return "name:"
        if (s == "me") return "me"
        emailRe.matchEntire(s.lowercase())?.let { return "mailto:${s.lowercase()}" }
        normalizePhone(s, defaultRegion)?.let { return "tel:$it" }
        return "name:${normalizeName(s)}"
    }

    /**
     * E.164-ish normalisation without a phone library: strips formatting, converts a
     * leading 00 to +, and prefixes the default region's country code to national
     * numbers starting with a trunk 0. Alphanumeric sender ids return null.
     */
    fun normalizePhone(s: String, defaultRegion: String = "GB"): String? {
        var d = s.replace(Regex("[\\s().-]"), "")
        if (!d.matches(Regex("\\+?\\d{6,15}"))) return null
        if (d.startsWith("00")) d = "+" + d.substring(2)
        if (!d.startsWith("+")) {
            val cc = COUNTRY_CODES[defaultRegion] ?: return "+$d"
            d = if (d.startsWith("0")) "+$cc${d.substring(1)}" else "+$cc$d"
        }
        return d
    }

    fun normalizeName(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val COUNTRY_CODES = mapOf("GB" to "44", "US" to "1", "CA" to "1", "DE" to "49", "FR" to "33", "IN" to "91", "AU" to "61")
}
