package buddy.android.surface.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/** What the featured elements need from the run. */
data class FeatureContext(
    val voiceCount: Int,
    val people: Int,
    val quietHours: String,
    val readingDone: Boolean,
    val onReadingDone: () -> Unit,
)

/** The one thing a step shows, kept small so the phrase stays the point. */
@Composable
fun FeatureView(feature: Feature, ctx: FeatureContext) {
    val p = LocalPalette.current
    val voiceCount = ctx.voiceCount
    when (feature) {
        Feature.OldPhone -> Box(
            Modifier
                .padding(start = 140.dp)
                .size(104.dp, 196.dp)
                .border(2.dp, p.line, RoundedCornerShape(20.dp))
                .padding(top = 10.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.size(84.dp, 160.dp).clip(RoundedCornerShape(12.dp)).background(p.card))
        }

        Feature.People -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("S", "P", "M").forEach { initial ->
                Box(Modifier.size(60.dp).clip(CircleShape).background(p.userBubble), contentAlignment = Alignment.Center) {
                    BasicText(initial, style = Type.cta.copy(color = p.text))
                }
            }
            BasicText(if (ctx.people > 3) "+${ctx.people - 3}" else "", style = Type.bodyMuted.copy(color = p.muted))
        }

        Feature.Accounts -> FeatureRows(listOf("Gmail" to "✓", "Google Calendar" to "✓", "Monzo" to "later"))
        Feature.Messages -> FeatureRows(listOf("WhatsApp" to "✓", "Signal" to "✓", "Messages" to "✓"))

        Feature.MoneyCap -> Row(verticalAlignment = Alignment.Bottom) {
            BasicText("£60", style = Type.number.copy(color = p.text))
            BasicText(" a day", style = Type.body.copy(color = p.muted), modifier = Modifier.padding(bottom = 8.dp))
        }

        Feature.Apps -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(3) { Box(Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)).background(p.card)) }
                }
            }
        }

        Feature.Voice -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText("“Hey buddy, it's me.”", style = Type.quote.copy(color = p.muted, textAlign = TextAlign.Center))
            VoiceBars(active = voiceCount < 3)
            BasicText("${voiceCount.coerceAtMost(3)} of 3", style = Type.small.copy(color = p.dim))
        }

        Feature.Trust -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TrustOption("Start careful", "I draft, you approve. I file email on my own. Nothing irreversible without you.", recommended = true)
            TrustOption("Let me act", "Reversible things straight away, shown in the brief. Money and strangers still wait for you.", recommended = false)
        }

        Feature.QuietHours -> BasicText(ctx.quietHours, style = Type.number.copy(color = p.text))

        Feature.OffLimits -> Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Bedroom", style = Type.body.copy(color = p.text))
            BasicText("·", style = Type.body.copy(color = p.dim))
            BasicText("Doctor's", style = Type.body.copy(color = p.text))
        }

        Feature.BriefTimes -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
            BasicText("08:00", style = Type.number.copy(color = p.text))
            BasicText("18:30", style = Type.number.copy(color = p.muted))
        }

        Feature.Undo -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText("Archived 23 emails", style = Type.body.copy(color = p.text))
                BasicText("Receipts and newsletters you never open.", style = Type.small.copy(color = p.muted))
            }
            BasicText("Undo", style = Type.button.copy(color = p.text))
        }

        Feature.Reading -> ReadingRing(done = ctx.readingDone, onDone = ctx.onReadingDone)
    }
}

@Composable
private fun FeatureRows(rows: List<Pair<String, String>>) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card).border(1.dp, p.line, RoundedCornerShape(16.dp))) {
        rows.forEachIndexed { i, (name, mark) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(name, style = Type.body.copy(color = p.text), modifier = Modifier.weight(1f))
                BasicText(mark, style = Type.small.copy(color = if (mark == "✓") p.text else p.dim))
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
    }
}

@Composable
private fun TrustOption(title: String, body: String, recommended: Boolean) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .border(1.dp, if (recommended) p.bot else p.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(title, style = Type.cta.copy(color = p.text))
        BasicText(body, style = Type.bodyMuted.copy(color = p.muted))
    }
}

@Composable
private fun VoiceBars(active: Boolean) {
    val p = LocalPalette.current
    val heights = listOf(10, 22, 34, 18, 42, 26, 14, 30, 20, 38, 16, 24)
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "phase")
    Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        heights.forEachIndexed { i, h ->
            val scale = if (active) 0.6f + 0.4f * ((phase + i * 0.13f) % 1f) else 0.4f
            Box(Modifier.width(5.dp).height((h * scale).dp).clip(RoundedCornerShape(3.dp)).background(p.bot))
        }
    }
}

/** Creeps to 90% while the bootstrap runs, then completes when it is done. */
@Composable
private fun ReadingRing(done: Boolean, onDone: () -> Unit) {
    val p = LocalPalette.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(done) {
        if (!done) {
            progress.animateTo(0.9f, tween(20_000, easing = LinearEasing))
        } else {
            progress.animateTo(1f, tween(600))
            onDone()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(150.dp)) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val inset = 9.dp.toPx()
            drawArc(p.line, 0f, 360f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = stroke)
            drawArc(p.bot, -90f, 360f * progress.value, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = stroke)
        }
        Spacer(Modifier.height(4.dp))
    }
}
