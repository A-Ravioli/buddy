import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import buddy.android.surface.call.CallScreen
import buddy.android.surface.call.CallUi
import buddy.android.surface.call.Phase
import buddy.android.surface.creature.Creature
import buddy.android.surface.creature.Mood
import buddy.android.surface.home.Affordance
import buddy.android.surface.home.BuddySays
import buddy.android.surface.home.Decision
import buddy.android.surface.home.Handled
import buddy.android.surface.home.HandledRow
import buddy.android.surface.home.HomeScreen
import buddy.android.surface.home.PlaygroundScreen
import buddy.android.surface.home.SuggestedReply
import buddy.android.surface.home.SurfaceState
import buddy.android.surface.home.TimelineEntry
import buddy.android.surface.home.TimelineScreen
import buddy.android.surface.onboarding.FeatureContext
import buddy.android.surface.onboarding.Onboarding
import buddy.android.surface.onboarding.StepStage
import buddy.android.surface.onboarding.steps
import buddy.android.surface.setup.Network
import buddy.android.surface.setup.WifiState
import buddy.android.surface.theme.BuddyTheme
import buddy.android.surface.theme.Palette
import buddy.android.surface.theme.Palettes
import buddy.android.surface.theme.SurfaceDomain
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the app's screens on the JVM, the way the phone would draw them.
 *
 * A second argument of `--frames` also writes the wake-up as a dense frame sequence into
 * `wake-frames/`, which is what the README's animation is made of. It is off by default
 * because it renders in real time and writes a hundred files.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "shots").apply { mkdirs() }
    val frames = args.contains("--frames")
    val ctx: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(out, "files").apply { mkdirs() }
    }
    val morning = SurfaceState(
        status = "brief 7:30",
        handledToday = 31,
        nextUp = "Sam, Sunday around 3",
        items = listOf(
            BuddySays("b1", "Morning. Quiet night. Two things need you, everything else is handled."),
            Decision("d1", SurfaceDomain.CALENDAR, "Calendar · from the dentist", "Move Thursday's dentist to 3:15pm?", "Reception asked to shift it. You're free then, and it clears the 4pm with Priya.", "Move it", "Keep 11am"),
            SuggestedReply("s1", SurfaceDomain.MESSAGES, "Sam · WhatsApp · close contact", "are we still on for sunday? and can you bring the drill", "yep, 2ish. drill's already in the car"),
            Handled("h1", "since last night", listOf(
                HandledRow(SurfaceDomain.EMAIL, "Email", "23 filed, 4 unsubscribed"),
                HandledRow(SurfaceDomain.MESSAGES, "Messages", "6 logistics replies"),
                HandledRow(SurfaceDomain.DELIVERIES, "Parcel", "Needed a signature, so it comes Monday when you're home"),
            )),
        ),
        timeline = listOf(
            TimelineEntry("t1", "8:04", SurfaceDomain.MESSAGES, "Sent a message to Sam: “closer to 3 actually, see you then”", "spoken command", Affordance.UNDO),
            TimelineEntry("t2", "7:52", SurfaceDomain.EMAIL, "Archived an email", "receipts and newsletters you never open", Affordance.UNDO),
            TimelineEntry("t3", "7:51", SurfaceDomain.EMAIL, "Unsubscribed", "10+ emails archived unread", Affordance.UNDO),
            TimelineEntry("t4", "7:40", SurfaceDomain.DELIVERIES, "Holding: Reschedule delivery to Monday", "needs a signature; you're out all day", null),
            TimelineEntry("t5", "7:12", SurfaceDomain.MONEY, "Filed a Monzo login code", "no flow in progress, never forwarded", Affordance.KEPT),
            TimelineEntry("t6", "23:10", SurfaceDomain.CALLS, "Took a message from an unknown caller", "quiet hours, not on the emergency list", null),
        ),
    )
    val quiet = SurfaceState(status = "brief 7:30", handledToday = 31, nextUp = "Sam, Sunday around 3", items = listOf(BuddySays("brief", "Nothing needs you.")))

    fun shot(name: String, palette: Palette, frames: Int = 3, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            CompositionLocalProvider(LocalContext provides ctx) { BuddyTheme(palette) { content() } }
        }
        var t = 0L
        repeat(frames) { t += 16_000_000L; scene.render(t) }
        val img = scene.render(t + 16_000_000L)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("wrote $name")
    }

    val noop: (String, Boolean) -> Unit = { _, _ -> }
    fun home(state: SurfaceState, mood: Mood) = @Composable {
        HomeScreen(state, mood, prefill = "", onPrefillConsumed = {}, onResolve = noop, onFix = {}, onHoldStart = {}, onHoldEnd = {}, onSend = {}, onTimeline = {}, onPlayground = {})
    }
    shot("01-home-colour", Palettes.colour(), content = home(morning, Mood.NEEDS_YOU))
    shot("02-quiet-colour", Palettes.colour(), content = home(quiet, Mood.RESTING))
    shot("03-timeline-colour", Palettes.colour()) { TimelineScreen(morning.timeline, onBack = {}, onUndo = {}) }
    shot("04-playground", Palettes.colour()) { PlaygroundScreen(onBack = {}, onRawTimeline = {}) }
    shot("05-home-mono", Palettes.mono, content = home(morning, Mood.NEEDS_YOU))
    shot("06-home-green", Palettes.green, content = home(morning, Mood.NEEDS_YOU))
    shot("07-quiet-mono", Palettes.mono, content = home(quiet, Mood.RESTING))

    // The call. buddy holds the dialer role and the phone app is not in the image, so this
    // is the only call screen on the device.
    shot("40-call-ringing", Palettes.colour()) {
        CallScreen(
            CallUi("Priya", "+44 7700 900123", Phase.RINGING, connectedAt = 0L, muted = false, speaker = false),
            onAnswer = {}, onEnd = {}, onMute = {}, onSpeaker = {},
        )
    }
    shot("41-call-active", Palettes.colour()) {
        CallScreen(
            CallUi("Sam", "+44 7700 900456", Phase.ACTIVE, connectedAt = System.currentTimeMillis() - 192_000L, muted = false, speaker = true),
            onAnswer = {}, onEnd = {}, onMute = {}, onSpeaker = {},
        )
    }


    // The wake-up, sampled over real time: the effect uses delays, the animations use the frame clock.
    run {
        val scene = ImageComposeScene(width = 780, height = 1688, density = Density(2f)) {
            CompositionLocalProvider(LocalContext provides ctx) { BuddyTheme(Palettes.mono) { Onboarding(onFinished = {}) } }
        }
        val start = System.nanoTime()
        val wanted = sortedMapOf(400L to "a", 1500L to "b", 2900L to "c", 4400L to "d")
        while (wanted.isNotEmpty()) {
            val ms = (System.nanoTime() - start) / 1_000_000L
            val img = scene.render(System.nanoTime() - start)
            val due = wanted.headMap(ms + 1)
            if (due.isNotEmpty()) {
                val (at, tag) = due.entries.first()
                File(out, "10-wake-$tag-${at}ms.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                wanted.remove(at)
                println("wrote wake $tag")
            }
            Thread.sleep(16)
        }
        scene.close()
    }

    // The same wake-up as a frame sequence, for the animation in the README. One scene
    // rendered against the wall clock: the sequence uses delays as well as the frame
    // clock, so it cannot be stepped faster than it really runs. A frame costs more than
    // the interval asked for, so each one carries the millisecond it was taken at and
    // whatever assembles them can play them back at the speed they happened.
    if (frames) {
        val dir = File(out, "wake-frames").apply { mkdirs() }
        // Half the size of a still, and one pixel per dp: a frame then costs about a
        // third of what a still does, which is the difference between a sequence at 9 and
        // at 25 a second. The animation is shown small anyway.
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f)) {
            CompositionLocalProvider(LocalContext provides ctx) { BuddyTheme(Palettes.mono) { Onboarding(onFinished = {}) } }
        }
        val start = System.nanoTime()
        var i = 0
        var nextAt = 0L
        while (true) {
            val ms = (System.nanoTime() - start) / 1_000_000L
            val img = scene.render(System.nanoTime() - start)
            if (ms >= nextAt) {
                File(dir, "%03d-%dms.png".format(i++, ms)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                nextAt = ms + 60
            }
            if (ms > 5_400) break
            Thread.sleep(8)
        }
        scene.close()
        println("wrote $i wake frames")
    }

    // The lock screen (patch 0015): what a locked phone shows once SystemUI hosts
    // BuddyFaceView. Drawn here with the Compose creature, which reads the same
    // FaceGeometry the View does, so this is the face the keyguard will draw.
    for ((name, mood) in listOf(
        "30-lock-resting" to Mood.RESTING,
        "31-lock-needs-you" to Mood.NEEDS_YOU,
        "32-lock-quiet-hours" to Mood.ASLEEP,
    )) {
        shot(name, Palettes.mono) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Creature(mood = mood, size = 168.dp, glow = mood == Mood.NEEDS_YOU)
            }
        }
    }

    // The walk-through, drawn a step at a time through the same StepStage the live flow
    // uses, with sample state where the step would read the phone.
    val flow = steps(includeSetup = true)
    val nearby = listOf(
        Network("Pantry", secured = true, level = -46),
        Network("Pantry 5G", secured = true, level = -58),
        Network("BT-KQ7R9M", secured = true, level = -67),
        Network("The Larch", secured = true, level = -71),
        Network("virginmedia-guest", secured = false, level = -79),
    )
    fun sample(
        voiceCount: Int = 0,
        networks: List<Network> = emptyList(),
        wifiState: WifiState = WifiState.Off,
        pin: String = "",
        pinConfirming: Boolean = false,
    ) = FeatureContext(
        voiceCount = voiceCount,
        people = 412,
        quietHours = "23:00 – 07:00",
        readingDone = false,
        onReadingDone = {},
        networks = networks,
        wifiState = wifiState,
        pin = pin,
        pinConfirming = pinConfirming,
    )
    fun step(name: String, id: String, ctx: FeatureContext = sample()) {
        val s = flow.first { it.id == id }
        shot(name, Palettes.mono, frames = 6) { StepStage(s, ctx, onPrimary = {}, onSecondary = {}) }
    }
    step("200-onboarding-hi", "hi")
    step("203-onboarding-wifi", "wifi", sample(networks = nearby, wifiState = WifiState.Scanning))
    step("204-onboarding-pin", "pin", sample(pin = "123", pinConfirming = true))
    step("205-onboarding-old-phone", "old")
    step("206-onboarding-people", "people")
    step("209-onboarding-voice", "voice", sample(voiceCount = 1))
    step("211-onboarding-trust", "trust")
    step("212-onboarding-quiet-hours", "quiet")
    step("215-onboarding-undo", "wrong")
    step("217-onboarding-pocket", "pocket")
}
