package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * The chin. The face is the mic: hold it to talk. One face, on every screen, never two.
 */
@Composable
fun Composer(
    mood: Mood,
    showFace: Boolean,
    prefill: String,
    onPrefillConsumed: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSend: (String) -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    val listening = mood == Mood.LISTENING
    LaunchedEffect(prefill) {
        if (prefill.isNotEmpty()) {
            text = prefill
            onPrefillConsumed()
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showFace) {
            Box(
                Modifier.size(52.dp).pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onHoldStart()
                            tryAwaitRelease()
                            onHoldEnd()
                        },
                    )
                },
            ) {
                Creature(mood = mood, size = 52.dp)
            }
        }
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(p.card)
                .border(1.dp, p.line, RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (listening) {
                BasicText("Listening…", style = Type.body.copy(color = p.text))
            } else {
                if (text.isEmpty()) {
                    BasicText(
                        "Hold buddy to talk, or type",
                        style = Type.body.copy(color = p.dim),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = Type.body.copy(color = p.text),
                    cursorBrush = SolidColor(p.bot),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) {
                            onSend(text.trim())
                            text = ""
                        }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
