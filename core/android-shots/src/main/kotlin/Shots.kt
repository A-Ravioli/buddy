import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
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
import buddy.android.surface.onboarding.Onboarding
import buddy.android.surface.theme.BuddyTheme
import buddy.android.surface.theme.Palette
import buddy.android.surface.theme.Palettes
import buddy.android.surface.theme.SurfaceDomain
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders the app's screens on the JVM, the way the phone would draw them. */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "shots").apply { mkdirs() }
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
    for ((i, name) in listOf(0 to "hi", 3 to "old-phone", 4 to "people", 9 to "voice", 11 to "trust", 12 to "quiet-hours", 15 to "undo", 17 to "pocket")) {
        shot("2${i.toString().padStart(2, '0')}-onboarding-$name", Palettes.mono, frames = 6) { Onboarding(onFinished = {}, startAt = i) }
    }
}
