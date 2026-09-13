package buddy.android.surface.creature

import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The face is the product's whole notification channel, so the rules it reads by are
 * worth pinning: the numbers the drawings share, and the grammar from the creature sheet
 * (tilt is gaze, splayed at the top is attention, flat is asleep).
 *
 * These run on the JVM because [FaceGeometry] is deliberately free of Android and Compose.
 */
class FaceGeometryTest {

    /** A glance slides the pair; anything more would be a face pointing the wrong way. */
    private val MAX_GAZE_SHIFT = 2f

    @Test
    fun `resting face sits where both drawings expect it`() {
        val face = FaceGeometry.of(Mood.RESTING)
        assertEquals(14.5f, face.left.centreX)
        assertEquals(25.5f, face.right.centreX)
        assertEquals(19f, face.left.centreY)
        assertEquals(19f, face.right.centreY)
        assertEquals(5f, face.left.width)
        assertEquals(12f, face.left.height)
        assertEquals(0f, face.left.tilt)
        assertEquals(0f, face.left.arc)
        assertEquals(2.5f, face.left.cornerRadius, "a stroke is a capsule, so the radius is half its width")
    }

    @Test
    fun `every mood keeps the eyes inside the body`() {
        for (mood in Mood.entries) {
            val face = FaceGeometry.of(mood)
            for (eye in listOf(face.left, face.right)) {
                for (x in listOf(eye.left, eye.right)) {
                    for (y in listOf(eye.top, eye.bottom)) {
                        val r = hypot(x - FaceGeometry.CENTRE, y - FaceGeometry.CENTRE)
                        assertTrue(r < FaceGeometry.BODY_RADIUS, "$mood puts an eye corner outside the face: $r")
                    }
                }
            }
        }
    }

    @Test
    fun `every mood moves the eyes as one pair`() {
        // A mood may look somewhere, which slides both strokes together, but it may never
        // pull them apart or set them at different heights: that would read as a squint.
        val restingGap = FaceGeometry.of(Mood.RESTING).let { it.right.centreX - it.left.centreX }
        for (mood in Mood.entries) {
            val face = FaceGeometry.of(mood)
            assertEquals(face.left.centreY, face.right.centreY, "$mood set the eyes at different heights")
            assertEquals(face.left.width, face.right.width, "$mood gave the eyes different widths")
            assertEquals(face.left.height, face.right.height, "$mood gave the eyes different heights")
            assertEquals(restingGap, face.right.centreX - face.left.centreX, "$mood changed the gap between the eyes")
            val midpoint = (face.left.centreX + face.right.centreX) / 2f
            assertTrue(
                kotlin.math.abs(midpoint - FaceGeometry.CENTRE) <= MAX_GAZE_SHIFT,
                "$mood looks $midpoint, further off centre than a glance",
            )
        }
    }

    @Test
    fun `attention splays the strokes apart at the top`() {
        // From the creature sheet: splayed apart at the top is attention on you. A positive
        // left tilt and a negative right tilt lean the tops away from each other.
        for (mood in listOf(Mood.LISTENING, Mood.NEEDS_YOU)) {
            val face = FaceGeometry.of(mood)
            assertTrue(face.left.tilt > 0f, "$mood should lean in")
            assertTrue(face.right.tilt < 0f, "$mood should lean in")
            assertEquals(face.left.tilt, -face.right.tilt, "$mood should be symmetric")
        }
    }

    @Test
    fun `doubt splays them apart at the bottom`() {
        val face = FaceGeometry.of(Mood.UNSURE)
        assertTrue(face.left.tilt < 0f && face.right.tilt > 0f, "unsure is the other way up from attention")
    }

    @Test
    fun `gaze tilts both strokes the same way`() {
        // Parallel tilt is gaze; the pair points somewhere rather than expressing something.
        val working = FaceGeometry.of(Mood.WORKING)
        assertEquals(working.left.tilt, working.right.tilt)
        val right = FaceGeometry.of(Mood.RESTING, Gaze.RIGHT)
        assertEquals(right.left.tilt, right.right.tilt)
        assertTrue(right.left.centreX > FaceGeometry.of(Mood.RESTING).left.centreX, "looking right moves the eyes right")
    }

    @Test
    fun `asleep is flat and done is an arc`() {
        val asleep = FaceGeometry.of(Mood.ASLEEP)
        assertTrue(asleep.left.height < asleep.left.width, "shut eyes are wider than they are tall")
        assertEquals(0f, asleep.left.arc, "sleeping is not smiling")
        assertEquals(1f, FaceGeometry.of(Mood.DONE).left.arc)
    }

    @Test
    fun `drift moves the pair together`() {
        val still = FaceGeometry.of(Mood.RESTING)
        val drifted = FaceGeometry.of(Mood.RESTING, driftX = 1.5f, driftY = -2f)
        assertEquals(still.left.centreX + 1.5f, drifted.left.centreX)
        assertEquals(still.right.centreX + 1.5f, drifted.right.centreX)
        assertEquals(still.left.centreY - 2f, drifted.left.centreY)
        assertEquals(still.right.centreY - 2f, drifted.right.centreY)
    }
}
