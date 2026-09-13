package buddy.android.surface.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.setup.Network
import buddy.android.surface.setup.WifiState
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * Getting online, inside the walk-through. Networks buddy can see, strongest first; tap
 * one, type the password, and he joins it. No Settings screen, no wizard handover.
 */
@Composable
fun WifiPicker(
    networks: List<Network>,
    state: WifiState,
    onJoin: (ssid: String, password: String?) -> Unit,
) {
    val p = LocalPalette.current
    var chosen by remember { mutableStateOf<Network?>(null) }
    var password by remember { mutableStateOf("") }

    val joined = state as? WifiState.Joined
    if (joined != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(p.bot))
            BasicText("On ${joined.ssid}", style = Type.body.copy(color = p.text))
        }
        return
    }

    val target = chosen
    if (target != null && target.secured) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(target.ssid, style = Type.body.copy(color = p.muted))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(p.card)
                    .border(1.dp, p.line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (password.isEmpty()) BasicText("Password", style = Type.body.copy(color = p.dim))
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    textStyle = Type.body.copy(color = p.text),
                    cursorBrush = SolidColor(p.bot),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onJoin(target.ssid, password) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetupButton("Join", filled = true, enabled = password.length >= 8) { onJoin(target.ssid, password) }
                SetupButton("Back", filled = false) { chosen = null; password = "" }
            }
            if (state is WifiState.Failed) {
                BasicText("That didn't work. Try the password again.", style = Type.small.copy(color = p.muted))
            }
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(16.dp)),
    ) {
        if (networks.isEmpty()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Looking for networks…", style = Type.body.copy(color = p.muted))
            }
        }
        networks.take(5).forEachIndexed { i, n ->
            Row(
                Modifier.fillMaxWidth().height(56.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (n.secured) chosen = n else onJoin(n.ssid, null)
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(n.ssid, style = Type.body.copy(color = p.text), modifier = Modifier.weight(1f))
                if (n.secured) Padlock(p.muted)
                Bars(n.level, p.text, p.line)
            }
            if (i < networks.take(5).lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
    }
}

/**
 * The first lock. It is also what the ledger's storage key is bound to, which is why
 * buddy asks for it in his own words rather than handing off to a settings screen.
 */
@Composable
fun PinPad(
    entered: String,
    confirming: Boolean,
    mismatch: Boolean,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PIN_LENGTH) { i ->
                val on = i < entered.length
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(if (on) p.bot else Color.Transparent)
                        .border(if (on) 0.dp else 1.dp, p.line, CircleShape),
                )
            }
        }
        BasicText(
            when {
                mismatch -> "That didn't match. Once more."
                confirming -> "Again, to be sure."
                else -> " "
            },
            style = Type.small.copy(color = if (mismatch) p.text else p.muted, textAlign = TextAlign.Center),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c -> Key(c.toString()) { onKey(c) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.size(72.dp, 56.dp))
                Key("0") { onKey('0') }
                Key(null, quiet = true) { onDelete() }
            }
        }
    }
}

const val PIN_LENGTH = 6

/** A digit, or the delete key when [label] is null. Drawn, never a font glyph. */
@Composable
private fun Key(label: String?, quiet: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(72.dp, 56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (quiet) Color.Transparent else p.card)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            BasicText(label, style = Type.cta.copy(color = p.text))
        } else {
            Backspace(p.muted)
        }
    }
}

@Composable
private fun Backspace(color: Color) {
    Canvas(Modifier.size(24.dp, 16.dp)) {
        val h = size.height
        val notch = h / 2f
        val stroke = h * 0.11f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, notch)
            lineTo(notch, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, h)
            lineTo(notch, h)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = stroke))
        val cx = (notch + size.width) / 2f
        val cy = h / 2f
        val r = h * 0.2f
        drawLine(color, Offset(cx - r, cy - r), Offset(cx + r, cy + r), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(cx + r, cy - r), Offset(cx - r, cy + r), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun SetupButton(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier.height(48.dp).clip(shape)
    Box(
        (if (filled) base.background(if (enabled) p.bot else p.line) else base.border(1.dp, p.line, shape))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.button.copy(color = if (filled) p.onBot else p.text))
    }
}

/** Three bars from the signal strength in dBm. */
@Composable
private fun Bars(level: Int, on: Color, off: Color) {
    val bars = when {
        level >= -60 -> 3
        level >= -70 -> 2
        else -> 1
    }
    Canvas(Modifier.size(16.dp, 14.dp)) {
        val w = size.width / 5f
        repeat(3) { i ->
            val h = size.height * (0.4f + 0.3f * i)
            drawRoundRect(
                color = if (i < bars) on else off,
                topLeft = Offset(i * w * 1.8f, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

@Composable
private fun Padlock(color: Color) {
    Canvas(Modifier.size(12.dp, 14.dp)) {
        val bodyTop = size.height * 0.45f
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, bodyTop),
            size = Size(size.width, size.height - bodyTop),
            cornerRadius = CornerRadius(size.width * 0.2f, size.width * 0.2f),
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.14f),
            size = Size(size.width * 0.6f, size.height * 0.5f),
            style = Stroke(width = size.width * 0.16f, cap = StrokeCap.Round),
        )
    }
}

/** The soft pulse under the creature while a scan or a join is in flight. */
@Composable
fun Working(): Float {
    val t = rememberInfiniteTransition(label = "working")
    val v by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    return v
}
