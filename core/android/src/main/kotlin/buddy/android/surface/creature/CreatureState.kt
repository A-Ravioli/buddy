package buddy.android.surface.creature

/**
 * What buddy is doing. The eyes are the only thing that changes; see [eyeSpec].
 * Each mood maps to something the product actually does, never to a decorative emotion.
 */
enum class Mood {
    /** Nothing needs you. Breathes and blinks. */
    RESTING,
    /** Hotword or held. Leans in, eyes open wider, a ring pulses. */
    LISTENING,
    /** Reading an app, planning, acting. Glances aside and drifts. */
    WORKING,
    /** A decision is waiting. Eyes lift and it bobs. */
    NEEDS_YOU,
    /** Right after an action you asked for. Half a second, then rest. */
    DONE,
    /** A hold window is running. Eyes lowered, watching the card. */
    HOLDING,
    /** It stopped short and is asking. Failing toward silence, visibly. */
    UNSURE,
    /** Quiet hours. Eyes closed. */
    ASLEEP,
}

/** Where buddy is looking. Only applied on moods that leave the eyes free. */
enum class Gaze { AHEAD, UP, DOWN, LEFT, RIGHT }

/**
 * Eye geometry in a 40 x 40 face space. Bars are centred at x = 14.5 and 25.5, y = 19.
 * [tiltLeft] and [tiltRight] are degrees, clockwise positive, so `/ \` (leaning in) is
 * a positive left tilt and a negative right tilt.
 */
data class EyeSpec(
    val tiltLeft: Float,
    val tiltRight: Float,
    val width: Float,
    val height: Float,
    val dx: Float,
    val dy: Float,
    /** 0 is a bar, 1 is the happy arc. */
    val arc: Float,
) {
    companion object {
        val rest = EyeSpec(0f, 0f, 5f, 12f, 0f, 0f, 0f)
    }
}

fun eyeSpec(mood: Mood, gaze: Gaze = Gaze.AHEAD): EyeSpec = when (mood) {
    Mood.LISTENING -> EyeSpec(18f, -18f, 5f, 15f, 0f, 0f, 0f)
    Mood.WORKING -> EyeSpec(-16f, -16f, 5f, 12f, 1.5f, 0f, 0f)
    Mood.NEEDS_YOU -> EyeSpec(24f, -24f, 5f, 14f, 0f, 0f, 0f)
    Mood.DONE -> EyeSpec(0f, 0f, 5f, 12f, 0f, 0f, 1f)
    Mood.HOLDING -> EyeSpec(0f, 0f, 5f, 10f, 0f, 4f, 0f)
    Mood.UNSURE -> EyeSpec(-14f, 14f, 5f, 12f, 0f, 0f, 0f)
    Mood.ASLEEP -> EyeSpec(0f, 0f, 9f, 4f, 0f, 1f, 0f)
    Mood.RESTING -> when (gaze) {
        Gaze.AHEAD -> EyeSpec.rest
        Gaze.UP -> EyeSpec(10f, -10f, 5f, 13f, 0f, -1.5f, 0f)
        Gaze.DOWN -> EyeSpec(0f, 0f, 5f, 11f, 0f, 3f, 0f)
        Gaze.LEFT -> EyeSpec(16f, 16f, 5f, 12f, -1.5f, 0f, 0f)
        Gaze.RIGHT -> EyeSpec(-16f, -16f, 5f, 12f, 1.5f, 0f, 0f)
    }
}

/** The mood's ambient motion, on top of the geometry. */
enum class Motion { NONE, BREATHE, DRIFT, BOB }

val Mood.motion: Motion
    get() = when (this) {
        Mood.RESTING -> Motion.BREATHE
        Mood.WORKING -> Motion.DRIFT
        Mood.NEEDS_YOU -> Motion.BOB
        else -> Motion.NONE
    }

/** How much the glow behind the face should show, 0 to 1. The only mood channel. */
val Mood.glow: Float
    get() = when (this) {
        Mood.RESTING -> 0.18f
        Mood.LISTENING -> 0.5f
        Mood.WORKING -> 0.22f
        Mood.NEEDS_YOU -> 0.6f
        Mood.DONE -> 0.4f
        Mood.HOLDING -> 0.18f
        Mood.UNSURE -> 0.14f
        Mood.ASLEEP -> 0f
    }
