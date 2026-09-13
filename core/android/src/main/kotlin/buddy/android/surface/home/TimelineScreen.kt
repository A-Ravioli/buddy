package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/** Every action, with its reason and an undo. The trust surface. */
@Composable
fun TimelineScreen(entries: List<TimelineEntry>, onBack: () -> Unit, onUndo: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "‹",
                style = Type.display.copy(color = p.text),
                modifier = Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                    .padding(start = 8.dp),
            )
            BasicText("What I did", style = Type.wordmark.copy(color = p.text))
            Spacer(Modifier.weight(1f))
            BasicText("Since last night · ${entries.size} actions", style = Type.small.copy(color = p.muted))
        }
        BasicText("Tap anything to undo it or ask why.", style = Type.small.copy(color = p.muted), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText(e.time, style = Type.chip.copy(color = p.dim), modifier = Modifier.width(40.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(p.domain(e.domain)))
                            BasicText(e.what, style = Type.body.copy(color = p.text))
                        }
                        BasicText(e.why, style = Type.small.copy(color = p.muted))
                    }
                    val label = when (e.affordance) {
                        Affordance.UNDO -> "Undo"
                        Affordance.FIX -> "Fix it"
                        Affordance.LISTEN -> "Listen"
                        Affordance.KEPT -> "Kept"
                        null -> null
                    }
                    if (label != null) {
                        val active = e.affordance == Affordance.UNDO || e.affordance == Affordance.FIX
                        BasicText(
                            label,
                            style = Type.chip.copy(color = if (active) p.domain(e.domain) else p.muted),
                            modifier = Modifier
                                .height(44.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = active) { onUndo(e.id) }
                                .padding(top = 4.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            }
        }
    }
}
