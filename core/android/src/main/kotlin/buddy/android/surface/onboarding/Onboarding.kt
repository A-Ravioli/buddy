package buddy.android.surface.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * The walk-through. One creature that floats between positions, one phrase per step,
 * one featured thing to look at. Buddy looks at whatever matters.
 */
@Composable
fun Onboarding(onFinished: () -> Unit, startAt: Int = -1) {
    var phase by rememberSaveable { mutableIntStateOf(if (startAt < 0) 0 else 1) } // 0 = wake, 1 = steps
    if (phase == 0) {
        WakeScreen(onDone = { phase = 1 })
    } else {
        Steps(onFinished, startAt.coerceAtLeast(0))
    }
}

@Composable
private fun Steps(onFinished: () -> Unit, startAt: Int) {
    val p = LocalPalette.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { Bootstrapper.start(context) }
    val people by Bootstrapper.people.collectAsState()
    val bootstrap by Bootstrapper.result.collectAsState()
    val readingDone by Bootstrapper.done.collectAsState()
    var index by rememberSaveable { mutableIntStateOf(startAt.coerceIn(0, steps.lastIndex)) }
    var voiceCount by rememberSaveable { mutableIntStateOf(0) }
    var careful by rememberSaveable { mutableStateOf(true) }
    val step = steps[index]

    fun next() {
        if (index < steps.lastIndex) index++ else onFinished()
    }

    val ctx = FeatureContext(
        voiceCount = voiceCount,
        people = people,
        quietHours = bootstrap?.let { "%02d:00 – %02d:00".format(it.quietStartHour, it.quietEndHour) } ?: "23:00 – 07:00",
        readingDone = readingDone,
        onReadingDone = { if (step.id == "reading") next() },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        val stageWidth = maxWidth
        val scale = stageWidth / 390.dp

        // Where buddy is, animated so he floats from step to step.
        val size by animateDpAsState(step.place.size * scale, tween(520, easing = FastOutSlowInEasing), label = "size")
        val x by animateDpAsState(stageWidth * step.place.x - size / 2, tween(520, easing = FastOutSlowInEasing), label = "x")
        val y by animateDpAsState(step.place.y * scale, tween(520, easing = FastOutSlowInEasing), label = "y")
        val topSpace by animateDpAsState(step.topSpace * scale, tween(520, easing = FastOutSlowInEasing), label = "top")
        val ambient = rememberInfiniteTransition(label = "float")
        val fx by ambient.animateFloat(-4f, 5f, infiniteRepeatable(tween(3100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fx")
        val fy by ambient.animateFloat(4f, -7f, infiniteRepeatable(tween(2700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fy")

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (fadeIn(tween(360, delayMillis = 120)) + slideInVertically(tween(360, delayMillis = 120)) { it / 14 })
                            .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 20 })
                    },
                    label = "step",
                ) { s ->
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(Modifier.height(topSpace))
                        if (s.feature != null && s.feature != Feature.Voice) {
                            FeatureView(s.feature, ctx)
                            Spacer(Modifier.height(14.dp))
                        }
                        BasicText(s.title, style = Type.display.copy(color = p.text, textAlign = TextAlign.Center))
                        if (s.feature == Feature.Voice) {
                            Spacer(Modifier.height(14.dp))
                            FeatureView(s.feature, ctx)
                        }
                        if (s.body != null) {
                            Spacer(Modifier.height(14.dp))
                            BasicText(
                                s.body,
                                style = Type.body.copy(color = p.muted, textAlign = TextAlign.Center),
                                modifier = Modifier.widthIn(max = 300.dp),
                            )
                        }
                    }
                }

                // Buddy, above the content, floating.
                Box(Modifier.offset(x = x + fx.dp, y = y + fy.dp)) {
                    Creature(
                        mood = step.mood,
                        gaze = step.gaze,
                        size = size,
                        glow = step.place.glow,
                        onTap = if (step.feature == Feature.Voice) {
                            { voiceCount++; if (voiceCount >= 3) next() }
                        } else null,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (step.primary != null) {
                    Cta(step.primary) {
                        if (step.id == "trust") { careful = true; Bootstrapper.applyTrust(context, careful = true) }
                        next()
                    }
                } else {
                    Spacer(Modifier.height(52.dp))
                }
                if (step.secondary != null) {
                    BasicText(
                        step.secondary,
                        style = Type.body.copy(color = p.muted),
                        modifier = Modifier
                            .height(44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                if (step.id == "trust") { careful = false; Bootstrapper.applyTrust(context, careful = false) }
                                next()
                            }
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun Cta(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "cta")
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(14.dp))
            .background(p.bot)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.cta.copy(color = p.onBot))
    }
}
