package buddy.android.surface

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import buddy.android.BuddyApp
import buddy.android.surface.home.HomeScreen
import buddy.android.surface.home.NetworkScreen
import buddy.android.surface.home.PlaygroundScreen
import buddy.android.surface.home.TimelineScreen
import buddy.android.surface.onboarding.Onboarding
import buddy.android.surface.setup.Provisioning
import buddy.android.surface.theme.BuddyTheme
import buddy.android.surface.theme.Palettes
import buddy.android.ui.TimelineActivity
import java.util.Locale

/**
 * The home screen of the phone. There is nothing behind it.
 *
 * First run: buddy wakes up and walks the user through moving in. After that: the chat,
 * with the creature on the chin as the mic. Long-press the wordmark for the playground.
 */
class SurfaceActivity : ComponentActivity() {
    private lateinit var prefs: SurfacePrefs
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        prefs = SurfacePrefs(this)
        setContent {
            var onboarded by remember { mutableStateOf(prefs.onboarded) }
            if (!onboarded) {
                // The first run is black and white whatever the scheme; colour comes after.
                // On a phone that has never been set up buddy is also the setup wizard, so
                // the walk-through carries the Wi-Fi and lock steps (see SetupController).
                val needsSetup = remember { Provisioning.needsSetup(this) }
                BuddyTheme(Palettes.mono) {
                    Onboarding(
                        onFinished = {
                            prefs.onboarded = true
                            onboarded = true
                            SurfaceStore.refresh()
                        },
                        includeSetup = needsSetup,
                    )
                }
            } else {
                BuddyTheme(Palettes.of(prefs.scheme, prefs.accentIndex)) { Surface() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SurfaceStore.onScreen = true
        SurfaceStore.refresh()
    }

    override fun onPause() {
        SurfaceStore.onScreen = false
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private enum class Screen { HOME, TIMELINE, PLAYGROUND, NETWORK }

    @Composable
    private fun Surface() {
        val state by SurfaceStore.state.collectAsState()
        val mood by SurfaceStore.mood.collectAsState()
        val prefill by SurfaceStore.prefill.collectAsState()
        var screen by remember { mutableStateOf(Screen.HOME) }
        val asked by SurfaceStore.asked.collectAsState()

        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

        // "wifi", said or typed, is how the network list is reached now that quick
        // settings is gone. The request waits in the store until the surface is up.
        LaunchedEffect(asked) {
            when (asked) {
                SurfaceStore.Ask.NETWORK -> screen = Screen.NETWORK
                null -> Unit
            }
            if (asked != null) SurfaceStore.asked.value = null
        }

        when (screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                mood = mood,
                prefill = prefill,
                onPrefillConsumed = { SurfaceStore.prefill.value = "" },
                onResolve = SurfaceStore::resolve,
                onFix = SurfaceStore::undo,
                onHoldStart = { SurfaceStore.holdStart(); startListening() },
                onHoldEnd = { stopListening(); SurfaceStore.holdEnd() },
                onSend = SurfaceStore::say,
                onTimeline = { screen = Screen.TIMELINE },
                onPlayground = { screen = Screen.PLAYGROUND },
            )
            Screen.TIMELINE -> TimelineScreen(state.timeline, onBack = { screen = Screen.HOME }, onUndo = SurfaceStore::undo)
            Screen.NETWORK -> NetworkScreen(onBack = { screen = Screen.HOME })
            Screen.PLAYGROUND -> PlaygroundScreen(
                onBack = { screen = Screen.HOME },
                onRawTimeline = { startActivity(Intent(this, TimelineActivity::class.java)) },
            )
        }
    }

    // ---- hold to talk: the same on-device recogniser and the same command path as the earbuds

    private fun startListening() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.i(BuddyApp.TAG, "surface heard: $text")
                if (text.isNotBlank()) SurfaceStore.say(text)
            }
            override fun onError(error: Int) = Unit
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()),
        )
    }

    private fun stopListening() {
        recognizer?.stopListening()
    }
}
