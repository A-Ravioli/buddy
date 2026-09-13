package buddy.android.surface.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import buddy.android.surface.SurfacePrefs
import buddy.android.surface.theme.BuddyTheme
import buddy.android.surface.theme.Palettes

/**
 * The call, on top of everything including the lock screen — a ringing phone is the one
 * thing that may interrupt without asking, and it is over in a minute.
 *
 * It closes itself when the call ends rather than leaving a screen nobody dismissed.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.setDecorFitsSystemWindows(false)
        val prefs = SurfacePrefs(this)
        setContent {
            val call by CallStore.call.collectAsState()
            BuddyTheme(Palettes.of(prefs.scheme, prefs.accentIndex)) {
                val c = call
                if (c == null) {
                    finish()
                } else {
                    CallScreen(
                        call = c,
                        onAnswer = CallStore::answer,
                        onEnd = CallStore::end,
                        onMute = CallStore::toggleMute,
                        onSpeaker = CallStore::toggleSpeaker,
                    )
                }
            }
        }
    }
}
