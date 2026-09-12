package buddy.android.surface.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The first thing the phone ever shows. The screen is all buddy. Two eyes appear, as if for
 * the first time. Then the dark closes in from the edges and leaves him in the middle.
 *
 * Runs once, about four seconds, then hands over to the flow.
 */
@Composable
fun WakeScreen(onDone: () -> Unit) {
    val palette = LocalPalette.current
    val eyeScale = remember { Animatable(0f) }
    val radiusFraction = remember { Animatable(1f) } // 1 = covers the screen, 0 = the formed body
    val hi = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(1000)
        launch { eyeScale.animateTo(1.18f, tween(320, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.3f))) }
        delay(320)
        eyeScale.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        delay(700)
        radiusFraction.animateTo(0f, tween(1300, easing = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)))
        delay(300)
        hi.animateTo(1f, tween(500))
        delay(1400)
        onDone()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background)) {
        val density = LocalDensity.current
        val bodyDp = 120.dp
        val bodyRadiusPx = with(density) { bodyDp.toPx() } / 2f * (19f / 20f)
        val coverPx = with(density) { hypot(maxWidth.toPx(), maxHeight.toPx()) } / 2f + 8f
        val centreY = maxHeight / 2f - 60.dp

        Canvas(Modifier.fillMaxSize()) {
            val r = bodyRadiusPx + (coverPx - bodyRadiusPx) * radiusFraction.value
            drawCircle(
                color = palette.bot,
                radius = r,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, with(density) { centreY.toPx() }),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = centreY - bodyDp / 2)
                .size(bodyDp)
                .scale(eyeScale.value),
        ) {
            Creature(mood = Mood.RESTING, size = bodyDp, body = false, color = palette.bot, eyeColor = palette.eye)
        }
        BasicText(
            text = "Hi.",
            style = Type.display.copy(color = palette.text, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = centreY + bodyDp / 2 + 28.dp)
                .padding(horizontal = 36.dp)
                .alpha(hi.value),
        )
    }
}
