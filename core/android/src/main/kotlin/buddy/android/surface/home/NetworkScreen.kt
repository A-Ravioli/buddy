package buddy.android.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import buddy.android.surface.onboarding.WifiPicker
import buddy.android.surface.setup.WifiJoiner
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * Joining a network, asked for by name ("wifi", "get me online") rather than found in a
 * panel. Quick settings is gone with the rest of the viewer's chrome, and this is the one
 * thing it carried that needs a list and a keyboard rather than a sentence.
 *
 * It is the same picker buddy used to get the phone online during the walk-through.
 */
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val joiner = remember { WifiJoiner(context) }
    val networks by joiner.networks.collectAsState()
    val state by joiner.state.collectAsState()

    // Scanning is not free, so it runs only while this screen is up.
    DisposableEffect(Unit) {
        joiner.start()
        onDispose { joiner.stop() }
    }

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
            BasicText("Networks", style = Type.wordmark.copy(color = p.text))
            Spacer(Modifier.weight(1f))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            WifiPicker(networks = networks, state = state, onJoin = { ssid, password -> joiner.join(ssid, password) })
        }
    }
}
