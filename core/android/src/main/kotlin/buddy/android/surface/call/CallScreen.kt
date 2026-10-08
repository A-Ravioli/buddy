package buddy.android.surface.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Gaze
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.SurfaceDomain
import buddy.android.surface.theme.Type

/**
 * A call, in buddy's language rather than a keypad's. One name, one line saying where the
 * call has got to, and the two or three things a person actually does.
 *
 * buddy holds the dialer role, so this is the only call screen on the phone. It is also
 * the one place the creature is not the point: someone is calling, and the name is what
 * matters. He watches from the top, looking down at it.
 */
@Composable
fun CallScreen(call: CallUi, onAnswer: () -> Unit, onEnd: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit) {
    val p = LocalPalette.current
    val ringing = call.phase == Phase.RINGING

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == Phase.ACTIVE) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The name floats a little above the middle rather than sitting at the top: on a
        // ringing phone it is the only thing being read, and a screen with a hole in the
        // middle of it reads as something still loading.
        Spacer(Modifier.weight(0.85f))
        Creature(
            mood = if (ringing) Mood.NEEDS_YOU else Mood.HOLDING,
            gaze = Gaze.DOWN,
            size = 84.dp,
            glow = ringing,
        )
        Spacer(Modifier.size(36.dp))

        BasicText(
            call.name,
            style = Type.display.copy(color = p.text, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(10.dp))
        BasicText(
            line(call, now),
            style = Type.body.copy(color = p.muted, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        if (call.phase == Phase.ACTIVE || call.phase == Phase.HOLDING) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Toggle("Mute", call.muted, onMute)
                Toggle("Speaker", call.speaker, onSpeaker)
            }
            Spacer(Modifier.size(32.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 48.dp),
            horizontalArrangement = if (ringing) Arrangement.SpaceEvenly else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ringing) {
                Big("Decline", p.domain(SurfaceDomain.EMERGENCY), onEnd)
                Big("Answer", p.bot, onAnswer, pulse = true)
            } else {
                Big("End", p.domain(SurfaceDomain.EMERGENCY), onEnd)
            }
        }
    }
}

/** Where the call has got to, in words rather than a state name. */
private fun line(call: CallUi, now: Long): String = when (call.phase) {
    Phase.RINGING -> if (call.number != null && call.name != call.number) call.number else "Calling you"
    Phase.DIALING -> "Ringing…"
    Phase.HOLDING -> "On hold"
    Phase.ACTIVE -> CallStore.elapsed(call.connectedAt, now).ifEmpty { "Connected" }
}

@Composable
private fun Big(label: String, color: Color, onClick: () -> Unit, pulse: Boolean = false) {
    val p = LocalPalette.current
    val breath = if (pulse) {
        val transition = rememberInfiniteTransition(label = "answer")
        transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "answer-alpha",
        ).value
    } else {
        1f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).alpha(breath).clip(CircleShape).background(color)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        )
        Spacer(Modifier.size(10.dp))
        BasicText(label, style = Type.button.copy(color = p.muted))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(60.dp).clip(CircleShape)
                .then(if (on) Modifier.background(p.bot) else Modifier.border(1.dp, p.line, CircleShape))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        )
        Spacer(Modifier.size(8.dp))
        BasicText(label, style = Type.small.copy(color = if (on) p.text else p.muted))
    }
}
