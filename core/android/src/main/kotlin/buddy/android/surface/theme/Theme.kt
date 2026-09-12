package buddy.android.surface.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.buddy.R

val LocalPalette = staticCompositionLocalOf { Palettes.colour() }

/** buddy speaks in a serif. The interface acts in a sans. */
object Type {
    val serif = FontFamily(
        Font(R.font.newsreader_400, FontWeight.Normal),
        Font(R.font.newsreader_400_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val sans = FontFamily(
        Font(R.font.dm_400, FontWeight.Normal),
        Font(R.font.dm_600, FontWeight.SemiBold),
    )

    /** What buddy says in the chat. */
    val voice = TextStyle(fontFamily = serif, fontSize = 19.sp, lineHeight = 26.sp)
    /** A card's one-line question. */
    val cardTitle = TextStyle(fontFamily = serif, fontSize = 18.sp, lineHeight = 24.sp)
    /** A quoted message. */
    val quote = TextStyle(fontFamily = serif, fontSize = 17.sp, lineHeight = 22.sp)
    /** The big line on an empty or onboarding screen. */
    val display = TextStyle(fontFamily = serif, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp)
    val number = TextStyle(fontFamily = serif, fontSize = 40.sp, lineHeight = 44.sp)

    val body = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyMuted = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp)
    val chip = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val button = TextStyle(fontFamily = sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val cta = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val wordmark = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = sans, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
fun BuddyTheme(palette: Palette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            content()
        }
    }
}
