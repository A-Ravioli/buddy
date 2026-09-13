package buddy.android.surface.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.setup.LockCredential
import buddy.android.surface.setup.LockResult
import buddy.android.surface.setup.SetupController
import buddy.android.surface.setup.Transfer
import buddy.android.surface.setup.WifiState
import buddy.android.surface.theme.LocalPalette
import buddy.android.surface.theme.Type

/**
 * The walk-through. One creature that floats between positions, one phrase per step,
 * one featured thing to look at. Buddy looks at whatever matters.
 */
@Composable
fun Onboarding(onFinished: () -> Unit, includeSetup: Boolean = false) {
    var phase by rememberSaveable { mutableIntStateOf(0) } // 0 = wake, 1 = steps
    if (phase == 0) {
        WakeScreen(onDone = { phase = 1 })
    } else {
        Steps(onFinished, includeSetup)
    }
}

@Composable
private fun Steps(onFinished: () -> Unit, includeSetup: Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val setup = remember { SetupController(context) }
    val flow = remember(includeSetup) { steps(includeSetup) }
    LaunchedEffect(Unit) { Bootstrapper.start(context) }
    val people by Bootstrapper.people.collectAsState()
    val bootstrap by Bootstrapper.result.collectAsState()
    val readingDone by Bootstrapper.done.collectAsState()
    val networks by setup.wifi.networks.collectAsState()
    val wifiState by setup.wifi.state.collectAsState()
    var index by rememberSaveable { mutableIntStateOf(0) }
    var voiceCount by rememberSaveable { mutableIntStateOf(0) }
    var careful by rememberSaveable { mutableStateOf(true) }
    var pinFirst by rememberSaveable { mutableStateOf("") }
    var pinEntry by rememberSaveable { mutableStateOf("") }
    var pinConfirming by rememberSaveable { mutableStateOf(false) }
    var pinMismatch by rememberSaveable { mutableStateOf(false) }
    val step = flow[index]

    // What is actually on the phone. Re-read when a step comes up, so an account added in
    // the framework's flow is on the screen when the user comes back.
    var accounts by remember { mutableStateOf(emptyList<String>()) }
    var messaging by remember { mutableStateOf(emptyList<String>()) }
    var appCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(step.id) {
        when (step.id) {
            "old", "accounts" -> accounts = Transfer.accounts(context)
            "messages" -> messaging = Transfer.messaging(context)
            "apps" -> appCount = Transfer.appCount(context)
        }
    }

    fun next() {
        if (index < flow.lastIndex) {
            index++
        } else {
            // Hands the phone over: from here the lock screen comes up and the status bar
            // appears, because buddy has told the framework setup is done.
            setup.finish()
            onFinished()
        }
    }

    // The Wi-Fi step scans only while it is on screen, and skips itself when something
    // already reaches the internet (a SIM, or a reflash onto a configured phone).
    LaunchedEffect(step.id) {
        if (step.id == "wifi") {
            if (setup.wifi.online()) next() else setup.wifi.start()
        } else {
            setup.wifi.stop()
        }
    }
    DisposableEffect(Unit) { onDispose { setup.wifi.stop() } }

    // Once the second entry matches, the credential is set for real: the ledger's storage
    // key is bound to it from this moment.
    fun pinKey(c: Char) {
        if (pinEntry.length >= PIN_LENGTH) return
        pinMismatch = false
        pinEntry += c
        if (pinEntry.length < PIN_LENGTH) return
        when {
            !pinConfirming -> {
                pinFirst = pinEntry
                pinEntry = ""
                pinConfirming = true
            }
            pinEntry == pinFirst -> when (setup.setPin(pinEntry)) {
                LockResult.SET -> next()
                // No route to the credential from here, so hand off to the platform's own
                // chooser rather than pretending the phone is locked.
                LockResult.UNAVAILABLE -> {
                    runCatching { context.startActivity(LockCredential.settingsIntent()) }
                    next()
                }
                LockResult.REFUSED -> {
                    pinEntry = ""; pinFirst = ""; pinConfirming = false; pinMismatch = true
                }
            }
            else -> {
                pinEntry = ""; pinFirst = ""; pinConfirming = false; pinMismatch = true
            }
        }
    }

    val ctx = FeatureContext(
        voiceCount = voiceCount,
        people = people,
        quietHours = bootstrap?.let { "%02d:00 – %02d:00".format(it.quietStartHour, it.quietEndHour) } ?: "23:00 – 07:00",
        readingDone = readingDone,
        onReadingDone = { if (step.id == "reading") next() },
        networks = networks,
        wifiState = wifiState,
        onJoin = { ssid, password -> setup.wifi.join(ssid, password) },
        pin = pinEntry,
        pinConfirming = pinConfirming,
        pinMismatch = pinMismatch,
        onPinKey = { pinKey(it) },
        onPinDelete = { if (pinEntry.isNotEmpty()) pinEntry = pinEntry.dropLast(1) },
        accounts = accounts,
        messaging = messaging,
        appCount = appCount,
        onAddAccount = { Transfer.addAccount(context) },
        onMoveSim = { Transfer.moveSim(context) },
    )

    // Joining lands the user on the next step on its own, the way a wizard would.
    LaunchedEffect(wifiState) {
        if (step.id == "wifi" && wifiState is WifiState.Joined) {
            kotlinx.coroutines.delay(900)
            next()
        }
    }

    StepStage(
        step = step,
        ctx = ctx,
        onPrimary = {
            if (step.id == "trust") { careful = true; Bootstrapper.applyTrust(context, careful = true) }
            // Signing in leaves buddy for the framework's own flow and comes back here, so
            // this one step does not move on: the account list behind it is the answer.
            if (step.id == "accounts") Transfer.addAccount(context) else next()
        },
        onSecondary = {
            if (step.id == "trust") { careful = false; Bootstrapper.applyTrust(context, careful = false) }
            next()
        },
        onCreatureTap = if (step.feature == Feature.Voice) {
            { voiceCount++; if (voiceCount >= 3) next() }
        } else null,
    )
}

/**
 * One step on screen: the creature floating where the step puts him, the phrase, and the
 * one thing to look at. Holds no state of its own, so the same code draws the live flow
 * and the rendered screens in `core/android-shots`.
 */
@Composable
fun StepStage(
    step: Step,
    ctx: FeatureContext,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onCreatureTap: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(p.background).statusBarsPadding().navigationBarsPadding()) {
        val stageWidth = maxWidth
        val scale = stageWidth / 390.dp

        // Where buddy is, animated so he floats from step to step.
        val size by animateDpAsState(step.place.size * scale, tween(520, easing = FastOutSlowInEasing), label = "size")
        val x by animateDpAsState(stageWidth * step.place.x - size / 2, tween(520, easing = FastOutSlowInEasing), label = "x")
        val y by animateDpAsState(step.place.y * scale, tween(520, easing = FastOutSlowInEasing), label = "y")
        val topSpace by animateDpAsState(step.topSpace * scale, tween(520, easing = FastOutSlowInEasing), label = "top")
        val ambient = rememberInfiniteTransition(label = "float")
        val fx by ambient.animateFloat(-4f, 5f, infiniteRepeatable(tween(3100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fx")
        val fy by ambient.animateFloat(4f, -7f, infiniteRepeatable(tween(2700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fy")

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (fadeIn(tween(360, delayMillis = 120)) + slideInVertically(tween(360, delayMillis = 120)) { it / 14 })
                            .togetherWith(fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 20 })
                    },
                    label = "step",
                ) { s ->
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(Modifier.height(topSpace))
                        if (s.feature != null && !s.featureBelow) {
                            FeatureView(s.feature, ctx)
                            Spacer(Modifier.height(14.dp))
                        }
                        BasicText(s.title, style = Type.display.copy(color = p.text, textAlign = TextAlign.Center))
                        if (s.body != null) {
                            Spacer(Modifier.height(14.dp))
                            BasicText(
                                s.body,
                                style = Type.body.copy(color = p.muted, textAlign = TextAlign.Center),
                                modifier = Modifier.widthIn(max = 300.dp),
                            )
                        }
                        if (s.feature != null && s.featureBelow) {
                            Spacer(Modifier.height(20.dp))
                            FeatureView(s.feature, ctx)
                        }
                    }
                }

                // Buddy, above the content, floating.
                Box(Modifier.offset(x = x + fx.dp, y = y + fy.dp)) {
                    Creature(
                        mood = step.mood,
                        gaze = step.gaze,
                        size = size,
                        glow = step.place.glow,
                        onTap = onCreatureTap,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (step.primary != null) {
                    Cta(step.primary, onClick = onPrimary)
                } else {
                    Spacer(Modifier.height(52.dp))
                }
                if (step.secondary != null) {
                    BasicText(
                        step.secondary,
                        style = Type.body.copy(color = p.muted),
                        modifier = Modifier
                            .height(44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSecondary)
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun Cta(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "cta")
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(14.dp))
            .background(p.bot)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.cta.copy(color = p.onBot))
    }
}
