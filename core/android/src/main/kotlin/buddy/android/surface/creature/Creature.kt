package buddy.android.surface.creature

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import buddy.android.surface.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * buddy. A circle in your colour and two strokes, no mouth.
 *
 * Reacts in about a quarter second and settles in about half. At rest it only breathes
 * and blinks. Nothing here loops for decoration.
 *
 * @param body draw the circle; false draws the eyes alone (the chin under the screen,
 *   or the wake-up sequence where the whole screen is the body).
 */
@Composable
fun Creature(
    mood: Mood,
    modifier: Modifier = Modifier,
    gaze: Gaze = Gaze.AHEAD,
    size: Dp = 52.dp,
    color: Color = LocalPalette.current.bot,
    eyeColor: Color = LocalPalette.current.eye,
    body: Boolean = true,
    glow: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val spec = eyeSpec(mood, gaze)
    val ease = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)
    val tiltLeft by animateFloatAsState(spec.tiltLeft, ease, label = "tiltLeft")
    val tiltRight by animateFloatAsState(spec.tiltRight, ease, label = "tiltRight")
    val width by animateFloatAsState(spec.width, ease, label = "width")
    val height by animateFloatAsState(spec.height, ease, label = "height")
    val dx by animateFloatAsState(spec.dx, ease, label = "dx")
    val dy by animateFloatAsState(spec.dy, ease, label = "dy")
    val arc by animateFloatAsState(spec.arc, ease, label = "arc")
    val glowAmount by animateFloatAsState(if (glow) mood.glow else 0f, tween(400), label = "glow")

    // Blink: every three to six seconds, unless the eyes are closed or arcs.
    val blink = remember { Animatable(1f) }
    val currentMood by rememberUpdatedState(mood)
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(3000L, 6000L))
            if (currentMood != Mood.ASLEEP && currentMood != Mood.DONE) {
                blink.animateTo(0.08f, tween(70, easing = LinearEasing))
                blink.animateTo(1f, tween(90, easing = FastOutSlowInEasing))
            }
        }
    }

    // Ambient motion for the mood.
    val ambient = rememberInfiniteTransition(label = "ambient")
    val breathe by ambient.animateFloat(
        1f, 1.035f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val drift by ambient.animateFloat(
        -1.5f, 1.5f,
        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val bob by ambient.animateFloat(
        0f, -2.5f,
        infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val ring by ambient.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    val motion = mood.motion
    val bodyScale = if (motion == Motion.BREATHE) breathe else 1f
    val eyeDx = if (motion == Motion.DRIFT) drift else 0f
    val eyeDy = if (motion == Motion.BOB) bob else 0f
    val listening = mood == Mood.LISTENING

    val tap = if (onTap != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap,
        )
    } else Modifier

    Box(modifier.size(size).then(tap)) {
        Canvas(Modifier.size(size)) {
            val u = this.size.minDimension / 40f
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)

            if (glowAmount > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = glowAmount), color.copy(alpha = 0f)),
                        center = centre,
                        radius = 32f * u,
                    ),
                    radius = 32f * u,
                    center = centre,
                )
            }
            if (listening) {
                val r = 19f * u * (1f + 0.6f * ring)
                drawCircle(color.copy(alpha = 0.5f * (1f - ring)), radius = r, center = centre)
            }
            scale(bodyScale, pivot = centre) {
                if (body) drawCircle(color, radius = 19f * u, center = centre)
                translate(left = (dx + eyeDx) * u, top = (dy + eyeDy) * u) {
                    eye(u, 14.5f, tiltLeft, width, height, blink.value, arc, eyeColor)
                    eye(u, 25.5f, tiltRight, width, height, blink.value, arc, eyeColor)
                }
            }
        }
    }
}

private fun DrawScope.eye(
    u: Float,
    cx40: Float,
    tilt: Float,
    width: Float,
    height: Float,
    blink: Float,
    arc: Float,
    color: Color,
) {
    val cx = cx40 * u
    val cy = 19f * u
    val centre = Offset(cx, cy)
    if (arc < 1f) {
        rotate(tilt, pivot = centre) {
            scale(scaleX = 1f, scaleY = blink, pivot = centre) {
                val w = width * u
                val h = height * u
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * (1f - arc)),
                    topLeft = Offset(cx - w / 2f, cy - h / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 2f, w / 2f),
                )
            }
        }
    }
    if (arc > 0f) {
        val path = Path().apply {
            moveTo(cx - 4f * u, cy + 2.5f * u)
            quadraticBezierTo(cx, cy - 4.5f * u, cx + 4f * u, cy + 2.5f * u)
        }
        drawPath(
            path,
            color = color.copy(alpha = color.alpha * arc),
            style = Stroke(width = 4.5f * u, cap = StrokeCap.Round),
        )
    }
}
