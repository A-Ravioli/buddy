package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

private val moodName = mapOf(
    Mood.RESTING to "Resting", Mood.LISTENING to "Listening", Mood.WORKING to "Working",
    Mood.NEEDS_YOU to "Needs you", Mood.DONE to "Done", Mood.HOLDING to "Holding",
    Mood.UNSURE to "Unsure", Mood.ASLEEP to "Quiet hours",
)

/** Tap buddy: he moves through his states in order. Long-press the wordmark to get here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaygroundScreen(onBack: () -> Unit, onRawTimeline: () -> Unit) {
    val p = LocalPalette.current
    var i by remember { mutableIntStateOf(0) }
    val moods = Mood.entries
    val mood = moods[i]
    Column(
        Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("‹ back", style = Type.body.copy(color = p.muted), modifier = Modifier.align(Alignment.Start).height(44.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onBack))
        Spacer(Modifier.height(24.dp))
        BasicText("Tap buddy", style = Type.display.copy(color = p.text))
        Spacer(Modifier.height(40.dp))
        Creature(mood = mood, size = 200.dp, glow = true, onTap = { i = (i + 1) % moods.size })
        Spacer(Modifier.height(24.dp))
        BasicText(moodName.getValue(mood), style = Type.cta.copy(color = p.text))
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            moods.forEachIndexed { k, m ->
                val selected = k == i
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) p.bot else p.background)
                        .border(1.dp, if (selected) p.bot else p.line, RoundedCornerShape(22.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { i = k }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(moodName.getValue(m), style = Type.button.copy(color = if (selected) p.onBot else p.muted, textAlign = TextAlign.Center))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BasicText(
            "Raw ledger",
            style = Type.body.copy(color = p.dim),
            modifier = Modifier.height(44.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onRawTimeline),
        )
    }
}
