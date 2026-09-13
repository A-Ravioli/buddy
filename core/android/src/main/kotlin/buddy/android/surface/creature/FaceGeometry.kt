package buddy.android.surface.creature

/**
 * Where the two strokes go, in a 40 x 40 face space.
 *
 * The face is drawn twice in this build: by the Compose creature in the app, and by a
 * plain View on the lock screen, which SystemUI vendors because it cannot reach into
 * buddy's process. Both read their geometry from here, so the face on the lock screen and
 * the face in the chat are the same face rather than two drawings that drift apart.
 *
 * Pure Kotlin, no Android and no Compose: it is the one file both drawings can share, and
 * it is what the unit tests exercise.
 */
object FaceGeometry {
    /** The face is laid out in this square and scaled to whatever it is drawn into. */
    const val UNITS = 40f

    const val BODY_RADIUS = 19f
    const val CENTRE = 20f

    private const val LEFT_EYE_X = 14.5f
    private const val RIGHT_EYE_X = 25.5f
    private const val EYE_Y = 19f

    /** The happy arc, used only by [Mood.DONE]. */
    const val ARC_STROKE = 4.5f
    const val ARC_HALF_WIDTH = 4f
    const val ARC_END_DY = 2.5f
    const val ARC_CONTROL_DY = -4.5f

    fun of(mood: Mood, gaze: Gaze = Gaze.AHEAD, driftX: Float = 0f, driftY: Float = 0f): Face =
        of(eyeSpec(mood, gaze), driftX, driftY)

    fun of(spec: EyeSpec, driftX: Float = 0f, driftY: Float = 0f): Face {
        val dx = spec.dx + driftX
        val dy = spec.dy + driftY
        return Face(
            left = Eye(LEFT_EYE_X + dx, EYE_Y + dy, spec.width, spec.height, spec.tiltLeft, spec.arc),
            right = Eye(RIGHT_EYE_X + dx, EYE_Y + dy, spec.width, spec.height, spec.tiltRight, spec.arc),
        )
    }
}

/** One stroke. [tilt] is degrees clockwise about its own centre. */
data class Eye(
    val centreX: Float,
    val centreY: Float,
    val width: Float,
    val height: Float,
    val tilt: Float,
    /** 0 draws the rounded bar, 1 draws the arc. Between the two while a mood changes. */
    val arc: Float,
) {
    val left: Float get() = centreX - width / 2f
    val top: Float get() = centreY - height / 2f
    val right: Float get() = centreX + width / 2f
    val bottom: Float get() = centreY + height / 2f
    val cornerRadius: Float get() = width / 2f
}

data class Face(val left: Eye, val right: Eye)
