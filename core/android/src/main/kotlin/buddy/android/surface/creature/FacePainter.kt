package buddy.android.surface.creature

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * buddy drawn with plain framework calls, for the places Compose cannot go: the lock
 * screen and the always-on display, which SystemUI draws in its own process.
 *
 * The geometry comes from [FaceGeometry], the same source the Compose creature reads, so
 * this is the same face rather than a second drawing of it. Allocation-free once
 * constructed, because the always-on display redraws this for hours.
 */
class FacePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /**
     * @param bodyColor the circle behind the eyes, or [Color.TRANSPARENT] for eyes alone.
     * @param blink 1 is open, 0 is shut. The eyes squash about their own centre.
     * @param breathe a scale about the centre, 1 for none.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        mood: Mood,
        gaze: Gaze = Gaze.AHEAD,
        bodyColor: Int,
        eyeColor: Int,
        blink: Float = 1f,
        breathe: Float = 1f,
        driftX: Float = 0f,
        driftY: Float = 0f,
    ) {
        val u = minOf(bounds.width(), bounds.height()) / FaceGeometry.UNITS
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val originX = cx - FaceGeometry.CENTRE * u
        val originY = cy - FaceGeometry.CENTRE * u

        val saved = canvas.save()
        if (breathe != 1f) canvas.scale(breathe, breathe, cx, cy)

        if (bodyColor != Color.TRANSPARENT) {
            paint.color = bodyColor
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, FaceGeometry.BODY_RADIUS * u, paint)
        }

        val face = FaceGeometry.of(mood, gaze, driftX, driftY)
        drawEye(canvas, face.left, originX, originY, u, eyeColor, blink)
        drawEye(canvas, face.right, originX, originY, u, eyeColor, blink)
        canvas.restoreToCount(saved)
    }

    private fun drawEye(canvas: Canvas, eye: Eye, ox: Float, oy: Float, u: Float, color: Int, blink: Float) {
        val cx = ox + eye.centreX * u
        val cy = oy + eye.centreY * u
        if (eye.arc < 1f) {
            val saved = canvas.save()
            canvas.rotate(eye.tilt, cx, cy)
            if (blink != 1f) canvas.scale(1f, blink, cx, cy)
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.alpha = ((1f - eye.arc) * 255).toInt().coerceIn(0, 255)
            rect.set(ox + eye.left * u, oy + eye.top * u, ox + eye.right * u, oy + eye.bottom * u)
            val r = eye.cornerRadius * u
            canvas.drawRoundRect(rect, r, r, paint)
            canvas.restoreToCount(saved)
        }
        if (eye.arc > 0f) {
            path.reset()
            path.moveTo(cx - FaceGeometry.ARC_HALF_WIDTH * u, cy + FaceGeometry.ARC_END_DY * u)
            path.quadTo(cx, cy + FaceGeometry.ARC_CONTROL_DY * u, cx + FaceGeometry.ARC_HALF_WIDTH * u, cy + FaceGeometry.ARC_END_DY * u)
            paint.color = color
            paint.alpha = (eye.arc * 255).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = FaceGeometry.ARC_STROKE * u
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
        paint.alpha = 255
    }
}
