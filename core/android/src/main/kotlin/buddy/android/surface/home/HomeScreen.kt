package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * The whole app, most of the time. A chat with buddy where escalations are cards. When
 * nothing needs you, the screen is empty and buddy is all there is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: SurfaceState,
    mood: Mood,
    prefill: String,
    onPrefillConsumed: () -> Unit,
    onResolve: (String, Boolean) -> Unit,
    onFix: (String) -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSend: (String) -> Unit,
    onTimeline: () -> Unit,
    onPlayground: () -> Unit,
) {
    val p = LocalPalette.current
    val quiet = !state.locked && state.needsYou == 0 && state.items.none { it is UserSays }

    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "buddy",
                style = Type.wordmark.copy(color = p.text),
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = onPlayground,
                ),
            )
            Spacer(Modifier.weight(1f))
            BasicText(state.status, style = Type.small.copy(color = p.muted))
        }

        if (state.locked) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Creature(mood = Mood.ASLEEP, size = 120.dp)
                Spacer(Modifier.height(18.dp))
                BasicText("Unlock me once and I'll wake up.", style = Type.body.copy(color = p.muted, textAlign = TextAlign.Center))
            }
        } else if (quiet) {
            QuietBody(state, mood, onTimeline, Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.items, key = { it.id }) { item -> ChatItemView(item, onResolve, onFix) }
            }
        }

        Composer(
            mood = mood,
            showFace = !quiet,
            prefill = prefill,
            onPrefillConsumed = onPrefillConsumed,
            onHoldStart = onHoldStart,
            onHoldEnd = onHoldEnd,
            onSend = onSend,
        )
    }
}

@Composable
private fun QuietBody(state: SurfaceState, mood: Mood, onTimeline: () -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Creature(mood = mood, size = 120.dp, glow = true)
        Spacer(Modifier.height(18.dp))
        BasicText("Nothing needs you.", style = Type.display.copy(color = p.text, textAlign = TextAlign.Center))
        Spacer(Modifier.height(14.dp))
        BasicText(
            buildString {
                append(if (state.handledToday == 1) "1 thing handled today." else "${state.handledToday} things handled today.")
                if (state.nextUp.isNotEmpty()) append("\nNext up: ${state.nextUp}")
            },
            style = Type.body.copy(color = p.muted, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, p.line, RoundedCornerShape(22.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTimeline)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("What did you do?", style = Type.body.copy(color = p.text))
        }
    }
}

@Composable
private fun ChatItemView(item: ChatItem, onResolve: (String, Boolean) -> Unit, onFix: (String) -> Unit) {
    val p = LocalPalette.current
    when (item) {
        is BuddySays -> BasicText(item.text, style = Type.voice.copy(color = p.text), modifier = Modifier.padding(horizontal = 4.dp))
        is UserSays -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                        .background(p.userBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { BasicText(item.text, style = Type.body.copy(color = p.text)) }
                if (item.viaEarbuds) BasicText("via earbuds", style = Type.chip.copy(color = p.dim), modifier = Modifier.padding(top = 4.dp, end = 4.dp))
            }
        }
        is Decision -> DecisionCard(item) { onResolve(item.id, it) }
        is SuggestedReply -> SuggestedReplyCard(item) { onResolve(item.id, it) }
        is Hold -> HoldCard(item, onStop = { onResolve(item.id, false) }, onNow = { onResolve(item.id, true) })
        is Recall -> RecallCard(item) { }
        is Handled -> HandledCard(item)
        is Receipt -> ReceiptRow(item) { onFix(item.id) }
        is ComingUp -> ComingUpCard(item)
    }
}
