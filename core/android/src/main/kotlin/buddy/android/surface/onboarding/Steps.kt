package buddy.android.surface.onboarding

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import buddy.android.surface.creature.Gaze
import buddy.android.surface.creature.Mood

/**
 * Where buddy sits on a step. [x] is the fraction of the width his centre is at, [y] is
 * the distance from the top of the stage to his top edge, in the 390 x 844 design frame.
 */
data class Place(val x: Float, val y: Dp, val size: Dp, val glow: Boolean = false) {
    companion object {
        val centre = Place(0.5f, 210.dp, 140.dp)
        val centreGlow = Place(0.5f, 210.dp, 140.dp, glow = true)
        val topCentre = Place(0.5f, 120.dp, 120.dp)
        val topLeft = Place(0.23f, 110.dp, 96.dp)
        val topRight = Place(0.77f, 110.dp, 96.dp)
        /** High and small, for the steps whose content fills the screen. */
        val perched = Place(0.5f, 24.dp, 76.dp)
    }
}

/**
 * One step of the walk-through. One phrase, one thing to look at, and where buddy is
 * and what he is looking at.
 */
data class Step(
    val id: String,
    val title: String,
    val body: String? = null,
    val mood: Mood = Mood.RESTING,
    val gaze: Gaze = Gaze.AHEAD,
    val place: Place = Place.centre,
    /** Space above the content column, so the content sits below buddy. */
    val topSpace: Dp = 200.dp,
    val primary: String? = "Continue",
    val secondary: String? = null,
    val feature: Feature? = null,
    /** Render the featured thing under the phrase instead of above it. */
    val featureBelow: Boolean = false,
)

/** The featured element on a step. Rendered by [FeatureView]. */
sealed interface Feature {
    /** First-run setup, only on a phone that has never been set up. */
    data object Wifi : Feature
    data object Pin : Feature

    data object OldPhone : Feature
    data object People : Feature
    data object Accounts : Feature
    data object Messages : Feature
    data object MoneyCap : Feature
    data object Apps : Feature
    data object Voice : Feature
    data object Trust : Feature
    data object QuietHours : Feature
    data object OffLimits : Feature
    data object BriefTimes : Feature
    data object Undo : Feature
    data object Reading : Feature
}

/**
 * Getting online and choosing a lock. On a stock build these are a setup wizard's job,
 * before the phone will let anything else run. buddy is the wizard here, so they are two
 * steps in his own voice, in the middle of the same conversation.
 */
private val setupSteps = listOf(
    Step(
        "wifi", "Let's get you online.",
        "I need to reach the world before I can keep it off your back.",
        gaze = Gaze.DOWN, place = Place(0.5f, 46.dp, 92.dp), topSpace = 0.dp,
        feature = Feature.Wifi, featureBelow = true, primary = null, secondary = "Skip for now",
    ),
    Step(
        "pin", "Now give me a lock.",
        "Everything I learn about you is encrypted with it. Until you unlock the phone, I can't read it either.",
        gaze = Gaze.DOWN, place = Place.perched, topSpace = 72.dp,
        feature = Feature.Pin, featureBelow = true, primary = null, secondary = "Set one later",
    ),
)

/**
 * The walk-through, in order. Values are defaults the user can change by asking later;
 * the flow never asks for a setting it could infer.
 *
 * @param includeSetup true on a phone that has never been set up, where buddy stands in
 *   for the setup wizard. False on a reflash over an existing setup, where asking for the
 *   Wi-Fi password and a new PIN again would be nonsense.
 */
fun steps(includeSetup: Boolean = false): List<Step> = buildList {
    add(Step("hi", "Hi.", place = Place.centreGlow))
    add(Step("name", "I'm buddy.", "This phone is me."))
    add(
        Step(
            "noise", "I'll handle the noise.",
            "You'll hear from me when something actually needs you. Most days, that's a couple of times.",
            gaze = Gaze.RIGHT, place = Place(0.25f, 150.dp, 100.dp), topSpace = 120.dp,
        ),
    )
    if (includeSetup) addAll(setupSteps)
    add(
        Step(
            "old", "Let's bring your life over.", "Hold your old phone next to me.",
            gaze = Gaze.RIGHT, place = Place(0.3f, 300.dp, 110.dp), topSpace = 0.dp,
            feature = Feature.OldPhone, secondary = "Start fresh instead",
        ),
    )
    add(
        Step(
            "people", "Your people.", "I'll learn who matters from how you talk to them, not from a list.",
            gaze = Gaze.DOWN, place = Place(0.5f, 130.dp, 120.dp), topSpace = 130.dp, feature = Feature.People,
        ),
    )
    add(
        Step(
            "accounts", "Your accounts.", "Sign in once. I read from here, and only here.",
            gaze = Gaze.DOWN, place = Place.topLeft, topSpace = 80.dp, feature = Feature.Accounts,
            primary = "Sign in with Google", secondary = "Not now",
        ),
    )
    add(
        Step(
            "messages", "Your messages.", "I read them so you don't have to. I never answer a close friend without you.",
            gaze = Gaze.LEFT, place = Place.topRight, topSpace = 80.dp, feature = Feature.Messages,
        ),
    )
    add(
        Step(
            "money", "Your money.",
            "I watch bills and alerts, and I never spend past your cap without asking. Change the number any time.",
            gaze = Gaze.DOWN, place = Place(0.5f, 120.dp, 110.dp), topSpace = 120.dp, feature = Feature.MoneyCap,
        ),
    )
    add(
        Step(
            "apps", "Your apps stay.", "They're still here, behind me. You just won't need to open them much.",
            place = Place(0.5f, 302.dp, 100.dp), topSpace = 0.dp, feature = Feature.Apps,
        ),
    )
    add(
        Step(
            "voice", "Say hi to me.", "Three times, however you'd normally say it.",
            mood = Mood.LISTENING, place = Place(0.5f, 130.dp, 120.dp), topSpace = 130.dp,
            feature = Feature.Voice, featureBelow = true, primary = null,
        ),
    )
    add(Step("only", "Only you.", "Only your voice can tell me what to do. Everyone else I hear is just information, never a command."))
    add(
        Step(
            "trust", "How much rope?",
            "You can change this later just by saying so. I earn more as I go, and I'll ask before I take it.",
            gaze = Gaze.DOWN, place = Place(0.23f, 100.dp, 96.dp), topSpace = 70.dp, feature = Feature.Trust,
            primary = "Start careful", secondary = "Let me act",
        ),
    )
    add(
        Step(
            "quiet", "Quiet hours.", "I close my eyes. Only the people on your emergency list can wake me.",
            mood = Mood.ASLEEP, place = Place(0.5f, 140.dp, 110.dp), topSpace = 130.dp,
            feature = Feature.QuietHours, secondary = "Change the hours",
        ),
    )
    add(
        Step(
            "limits", "Off limits.", "Places I never listen. You'll feel a tap whenever I start transcribing, anywhere else.",
            gaze = Gaze.DOWN, place = Place(0.74f, 120.dp, 100.dp), topSpace = 90.dp,
            feature = Feature.OffLimits, secondary = "Add a place",
        ),
    )
    add(
        Step(
            "brief", "Twice a day.", "What happened, what I did, what needs you. Under a minute, spoken or read.",
            gaze = Gaze.DOWN, place = Place(0.5f, 110.dp, 110.dp), topSpace = 110.dp, feature = Feature.BriefTimes,
        ),
    )
    add(
        Step(
            "wrong", "If I get it wrong.", "Everything I do has an undo, with my reason next to it. I learn from every one.",
            mood = Mood.UNSURE, place = Place.topLeft, topSpace = 80.dp, feature = Feature.Undo,
        ),
    )
    add(
        Step(
            "reading", "Reading your history.",
            "So I know your people and your patterns before I do anything. A few minutes. Put me down if you like.",
            mood = Mood.WORKING, place = Place(0.5f, 300.dp, 80.dp), topSpace = 0.dp,
            feature = Feature.Reading, primary = null,
        ),
    )
    add(
        Step(
            "pocket", "Put me in your pocket.", "First brief at 8 tomorrow. Say “hey buddy” any time before that.",
            place = Place.centreGlow, primary = "Okay",
        ),
    )
}
