package buddy.android.surface.onboarding

import androidx.compose.runtime.Composable
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
)

/** The featured element on a step. Rendered by [FeatureView]. */
sealed interface Feature {
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
 * The walk-through, in order. Values are defaults the user can change by asking later;
 * the flow never asks for a setting it could infer.
 */
val steps: List<Step> = listOf(
    Step("hi", "Hi.", place = Place.centreGlow),
    Step("name", "I'm buddy.", "This phone is me."),
    Step(
        "noise", "I'll handle the noise.",
        "You'll hear from me when something actually needs you. Most days, that's a couple of times.",
        gaze = Gaze.RIGHT, place = Place(0.25f, 150.dp, 100.dp), topSpace = 120.dp,
    ),
    Step(
        "old", "Let's bring your life over.", "Hold your old phone next to me.",
        gaze = Gaze.RIGHT, place = Place(0.3f, 300.dp, 110.dp), topSpace = 0.dp,
        feature = Feature.OldPhone, secondary = "Start fresh instead",
    ),
    Step(
        "people", "Your people.", "I'll learn who matters from how you talk to them, not from a list.",
        gaze = Gaze.DOWN, place = Place(0.5f, 130.dp, 120.dp), topSpace = 130.dp, feature = Feature.People,
    ),
    Step(
        "accounts", "Your accounts.", "Sign in once. I read from here, and only here.",
        gaze = Gaze.DOWN, place = Place.topLeft, topSpace = 80.dp, feature = Feature.Accounts,
        primary = "Sign in with Google", secondary = "Not now",
    ),
    Step(
        "messages", "Your messages.", "I read them so you don't have to. I never answer a close friend without you.",
        gaze = Gaze.LEFT, place = Place.topRight, topSpace = 80.dp, feature = Feature.Messages,
    ),
    Step(
        "money", "Your money.",
        "I watch bills and alerts, and I never spend past your cap without asking. Change the number any time.",
        gaze = Gaze.DOWN, place = Place(0.5f, 120.dp, 110.dp), topSpace = 120.dp, feature = Feature.MoneyCap,
    ),
    Step(
        "apps", "Your apps stay.", "They're still here, behind me. You just won't need to open them much.",
        place = Place(0.5f, 302.dp, 100.dp), topSpace = 0.dp, feature = Feature.Apps,
    ),
    Step(
        "voice", "Say hi to me.", "Three times, however you'd normally say it.",
        mood = Mood.LISTENING, place = Place(0.5f, 130.dp, 120.dp), topSpace = 130.dp,
        feature = Feature.Voice, primary = null,
    ),
    Step("only", "Only you.", "Only your voice can tell me what to do. Everyone else I hear is just information, never a command."),
    Step(
        "trust", "How much rope?",
        "You can change this later just by saying so. I earn more as I go, and I'll ask before I take it.",
        gaze = Gaze.DOWN, place = Place(0.23f, 100.dp, 96.dp), topSpace = 70.dp, feature = Feature.Trust,
        primary = "Start careful", secondary = "Let me act",
    ),
    Step(
        "quiet", "Quiet hours.", "I close my eyes. Only the people on your emergency list can wake me.",
        mood = Mood.ASLEEP, place = Place(0.5f, 140.dp, 110.dp), topSpace = 130.dp,
        feature = Feature.QuietHours, secondary = "Change the hours",
    ),
    Step(
        "limits", "Off limits.", "Places I never listen. You'll feel a tap whenever I start transcribing, anywhere else.",
        gaze = Gaze.DOWN, place = Place(0.74f, 120.dp, 100.dp), topSpace = 90.dp,
        feature = Feature.OffLimits, secondary = "Add a place",
    ),
    Step(
        "brief", "Twice a day.", "What happened, what I did, what needs you. Under a minute, spoken or read.",
        gaze = Gaze.DOWN, place = Place(0.5f, 110.dp, 110.dp), topSpace = 110.dp, feature = Feature.BriefTimes,
    ),
    Step(
        "wrong", "If I get it wrong.", "Everything I do has an undo, with my reason next to it. I learn from every one.",
        mood = Mood.UNSURE, place = Place.topLeft, topSpace = 80.dp, feature = Feature.Undo,
    ),
    Step(
        "reading", "Reading your history.",
        "So I know your people and your patterns before I do anything. A few minutes. Put me down if you like.",
        mood = Mood.WORKING, place = Place(0.5f, 300.dp, 80.dp), topSpace = 0.dp,
        feature = Feature.Reading, primary = null,
    ),
    Step(
        "pocket", "Put me in your pocket.", "First brief at 8 tomorrow. Say “hey buddy” any time before that.",
        place = Place.centreGlow, primary = "Okay",
    ),
)
