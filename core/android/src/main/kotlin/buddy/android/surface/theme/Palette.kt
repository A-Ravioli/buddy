package buddy.android.surface.theme

import androidx.compose.ui.graphics.Color

/**
 * The domains buddy acts in. Each keeps its colour everywhere it appears, so a blue card
 * is always calendar and amber is always money. Red is reserved for emergencies.
 */
enum class SurfaceDomain { CALENDAR, MESSAGES, EMAIL, MONEY, DELIVERIES, CALLS, EMERGENCY, HEARD }

enum class Scheme { COLOUR, MONO, GREEN }

data class Palette(
    val scheme: Scheme,
    val background: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    /** The creature. Also the primary button. Yours. */
    val bot: Color,
    /** The two strokes. */
    val eye: Color,
    /** Text on the bot colour. */
    val onBot: Color,
    val userBubble: Color,
    private val domains: Map<SurfaceDomain, Color>,
    /** What the colour picker offers. Empty on schemes where the creature has one colour. */
    val choices: List<Color>,
) {
    fun domain(domain: SurfaceDomain): Color = domains[domain] ?: text
}

object Palettes {
    val blue = Color(0xFF3D82F0)
    val orange = Color(0xFFEF7A2F)
    val teal = Color(0xFF52B8A3)
    val red = Color(0xFFE5443F)
    val pink = Color(0xFFEC4B93)
    val purple = Color(0xFF8B5CF6)
    val amber = Color(0xFFF4A233)
    val brown = Color(0xFF9A7448)

    val eight = listOf(blue, orange, teal, red, pink, purple, amber, brown)

    /** Dark ground, eight colours. The default. */
    fun colour(bot: Color = blue) = Palette(
        scheme = Scheme.COLOUR,
        background = Color(0xFF1C1C1C),
        card = Color(0xFF262626),
        line = Color(0xFF343434),
        text = Color(0xFFF2EFE9),
        muted = Color(0xFFA39E96),
        dim = Color(0xFF6E6A64),
        bot = bot,
        eye = Color(0xFF1C1C1C),
        onBot = Color(0xFF1C1C1C),
        userBubble = Color(0xFF303030),
        domains = mapOf(
            SurfaceDomain.CALENDAR to blue,
            SurfaceDomain.MESSAGES to teal,
            SurfaceDomain.EMAIL to purple,
            SurfaceDomain.MONEY to amber,
            SurfaceDomain.DELIVERIES to orange,
            SurfaceDomain.CALLS to pink,
            SurfaceDomain.EMERGENCY to red,
            SurfaceDomain.HEARD to brown,
        ),
        choices = eight,
    )

    /** Pure black, white buddy, nothing else. */
    val mono = Palette(
        scheme = Scheme.MONO,
        background = Color(0xFF000000),
        card = Color(0xFF141414),
        line = Color(0xFF262626),
        text = Color(0xFFF5F5F5),
        muted = Color(0xFF9A9A9A),
        dim = Color(0xFF5C5C5C),
        bot = Color(0xFFFFFFFF),
        eye = Color(0xFF000000),
        onBot = Color(0xFF000000),
        userBubble = Color(0xFF1F1F1F),
        domains = SurfaceDomain.entries.associateWith { Color(0xFFD4D4D4) },
        choices = emptyList(),
    )

    /** A green shade: dark green ground, green buddy. */
    val green = Palette(
        scheme = Scheme.GREEN,
        background = Color(0xFF07120C),
        card = Color(0xFF0F1F16),
        line = Color(0xFF1B3125),
        text = Color(0xFFE8F3EC),
        muted = Color(0xFF8FA89A),
        dim = Color(0xFF55705F),
        bot = Color(0xFF3DDC84),
        eye = Color(0xFF07120C),
        onBot = Color(0xFF07120C),
        userBubble = Color(0xFF16291E),
        domains = SurfaceDomain.entries.associateWith { Color(0xFF8FD7A8) },
        choices = emptyList(),
    )

    fun of(scheme: Scheme, accentIndex: Int): Palette = when (scheme) {
        Scheme.COLOUR -> colour(eight.getOrElse(accentIndex) { blue })
        Scheme.MONO -> mono
        Scheme.GREEN -> green
    }
}
