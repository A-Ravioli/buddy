package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import buddy.android.surface.theme.SurfaceDomain
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

@Composable
fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** Where a card came from. Apps are backends, so they are a line, never a screen. */
@Composable
fun SourceChip(domain: SurfaceDomain?, text: String) {
    val p = LocalPalette.current
    val colour = domain?.let { p.domain(it) } ?: p.muted
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (domain != null) Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
        BasicText(text, style = Type.chip.copy(color = colour))
    }
}

@Composable
fun RowScope.Button(label: String, filled: Color?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier
        .weight(1f)
        .height(44.dp)
        .clip(shape)
    val styled = if (filled != null) base.background(filled) else base.border(1.dp, p.line, shape)
    Box(
        styled.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.button.copy(color = if (filled != null) p.onBot else p.text))
    }
}

@Composable
fun DecisionCard(item: Decision, onChoose: (accepted: Boolean) -> Unit) {
    val p = LocalPalette.current
    Card {
        SourceChip(item.domain, item.source)
        BasicText(item.title, style = Type.cardTitle.copy(color = p.text))
        BasicText(item.why, style = Type.bodyMuted.copy(color = p.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(item.accept, p.domain(item.domain)) { onChoose(true) }
            Button(item.decline, null) { onChoose(false) }
        }
    }
}

/** buddy cannot finish this one. The app is the only place it can be done. */
@Composable
fun HandOverCard(item: HandOver, onChoose: (open: Boolean) -> Unit) {
    val p = LocalPalette.current
    Card {
        SourceChip(item.domain, item.app)
        BasicText(item.title, style = Type.cardTitle.copy(color = p.text))
        BasicText(item.why, style = Type.bodyMuted.copy(color = p.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(item.open, p.domain(item.domain)) { onChoose(true) }
            Button(item.later, null) { onChoose(false) }
        }
    }
}

@Composable
fun SuggestedReplyCard(item: SuggestedReply, onChoose: (send: Boolean) -> Unit) {
    val p = LocalPalette.current
    val tint = p.domain(item.domain)
    Card {
        SourceChip(item.domain, item.source)
        BasicText("“${item.quote}”", style = Type.quote.copy(color = p.text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            BasicText("reply", style = Type.chip.copy(color = p.muted), modifier = Modifier.padding(top = 10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.16f).compositeOver(p.card))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                BasicText(item.reply, style = Type.body.copy(color = p.text))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Send", tint) { onChoose(true) }
            Button("Change it", null) { onChoose(false) }
        }
    }
}

@Composable
fun HoldCard(item: Hold, onStop: () -> Unit, onNow: () -> Unit) {
    val p = LocalPalette.current
    val tint = p.domain(item.domain)
    Card {
        SourceChip(item.domain, item.source)
        BasicText(item.title, style = Type.cardTitle.copy(color = p.text))
        BasicText(item.why, style = Type.bodyMuted.copy(color = p.muted))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.line)) {
            Box(Modifier.fillMaxWidth(item.fraction.coerceIn(0f, 1f)).height(4.dp).background(tint))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(item.stop, null, onStop)
            Button(item.now, null, onNow)
        }
    }
}

@Composable
fun RecallCard(item: Recall, onAction: (String) -> Unit) {
    val p = LocalPalette.current
    Card {
        item.lines.forEachIndexed { i, line ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(line.text, style = Type.quote.copy(color = p.text))
                SourceChip(SurfaceDomain.CALLS, line.source)
            }
            if (i < item.lines.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.actions.forEach { a -> Button(a, null) { onAction(a) } }
        }
    }
}

@Composable
fun HandledCard(item: Handled) {
    val p = LocalPalette.current
    Card {
        SourceChip(null, "Handled ${item.since}")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(p.domain(row.domain)))
                    BasicText(row.label, style = Type.bodyMuted.copy(color = p.muted), modifier = Modifier.width(66.dp))
                    BasicText(row.text, style = Type.bodyMuted.copy(color = p.text))
                }
            }
        }
    }
}

@Composable
fun ReceiptRow(item: Receipt, onFix: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(item.text, style = Type.voice.copy(color = p.text))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceChip(item.domain, "Done")
            BasicText(
                item.fix,
                style = Type.chip.copy(color = p.bot),
                modifier = Modifier
                    .height(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onFix)
                    .padding(top = 14.dp),
            )
        }
    }
}

@Composable
fun ComingUpCard(item: ComingUp) {
    val p = LocalPalette.current
    Card {
        item.rows.forEach { (time, what) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(time, style = Type.bodyMuted.copy(color = p.muted), modifier = Modifier.width(70.dp))
                BasicText(what, style = Type.bodyMuted.copy(color = p.text))
            }
        }
    }
}
