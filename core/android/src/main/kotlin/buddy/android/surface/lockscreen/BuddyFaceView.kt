package buddy.android.surface.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import buddy.android.surface.creature.FacePainter
import buddy.android.surface.creature.Mood
import kotlin.math.sin
import kotlin.random.Random

/**
 * buddy on the lock screen and the always-on display, as a plain View so SystemUI can
 * host it. This is the file the keyguard patch vendors (see patch 0015); it lives here
 * because this is where it is compiled and rendered, and a copy that drifts from the app
 * is worse than no copy at all.
 *
 * The whole lock screen is this: no clock, no notifications, no shortcuts. What the user
 * learns from a locked phone is whether anything needs them, and that is the eyes. From
 * the creature sheet: at rest it breathes and blinks; when something is waiting the eyes
 * lift and the glow warms; during quiet hours they are shut.
 *
 * [lowPower] is the always-on case: no breathing and a rare blink, because this draws for
 * hours on a dimmed panel.
 */
class BuddyFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val painter = FacePainter()
    private val bounds = RectF()

    var mood: Mood = Mood.RESTING
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var faceColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    var eyeColor: Int = Color.BLACK
        set(value) { field = value; invalidate() }

    var lowPower: Boolean = false
        set(value) { field = value; invalidate() }

    private var blinkStartedAt = 0L
    private var nextBlinkAt = 0L
    private val random = Random(System.nanoTime())

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (nextBlinkAt == 0L) nextBlinkAt = now + blinkGap()

        val size = minOf(width, height).toFloat()
        bounds.set(
            (width - size) / 2f,
            (height - size) / 2f,
            (width + size) / 2f,
            (height + size) / 2f,
        )

        painter.draw(
            canvas = canvas,
            bounds = bounds,
            mood = mood,
            bodyColor = faceColor,
            eyeColor = eyeColor,
            blink = blink(now),
            breathe = breathe(now),
        )

        // Only ask for another frame while something is actually moving.
        if (moving(now)) postInvalidateOnAnimation()
    }

    private fun moving(now: Long): Boolean = when {
        mood == Mood.ASLEEP -> false
        blinkStartedAt != 0L -> true
        !lowPower -> true
        else -> now >= nextBlinkAt
    }

    /** 1 open, 0 shut. A blink is 160 ms, at a random gap. */
    private fun blink(now: Long): Float {
        if (mood == Mood.ASLEEP || mood == Mood.DONE) return 1f
        if (blinkStartedAt == 0L) {
            if (now < nextBlinkAt) return 1f
            blinkStartedAt = now
        }
        val t = (now - blinkStartedAt) / BLINK_MS.toFloat()
        if (t >= 1f) {
            blinkStartedAt = 0L
            nextBlinkAt = now + blinkGap()
            return 1f
        }
        // Down and back up.
        return if (t < 0.5f) 1f - t * 2f * 0.92f else 0.08f + (t - 0.5f) * 2f * 0.92f
    }

    private fun breathe(now: Long): Float {
        if (lowPower || mood != Mood.RESTING) return 1f
        return 1f + 0.035f * sin(now / BREATHE_MS.toDouble() * Math.PI).toFloat()
    }

    private fun blinkGap(): Long =
        if (lowPower) random.nextLong(7_000L, 12_000L) else random.nextLong(3_000L, 6_000L)

    private companion object {
        const val BLINK_MS = 160
        const val BREATHE_MS = 3_200
    }
}
