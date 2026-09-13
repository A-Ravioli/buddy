package buddy.boot

import buddy.android.surface.creature.Eye
import buddy.android.surface.creature.EyeSpec
import buddy.android.surface.creature.FaceGeometry
import buddy.android.surface.creature.Gaze
import buddy.android.surface.creature.Mood
import buddy.android.surface.creature.eyeSpec
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * The first thing the phone shows. Not a logo: buddy opening his eyes, from the same
 * numbers the app and the lock screen draw him with ([FaceGeometry]), so the creature on
 * the boot screen is the creature — not a picture of him that drifts a release later.
 *
 * Two parts, the format surfaceflinger reads:
 *
 * - **part0** plays once: the strokes appear on black, then the body grows out around
 *   them until he is the blob the lock screen shows.
 * - **part1** loops while the rest of the system starts: breathing, with one blink.
 *
 * It ends where the lock screen begins, so a cold boot is one continuous movement rather
 * than an animation that stops and a screen that starts.
 */
private const val WIDTH = 720
private const val HEIGHT = 1600
private const val FPS = 24
private const val WAKE_FRAMES = 28
private const val LOOP_FRAMES = 48

/** The face's size on screen, in pixels across, and where its centre sits. */
private const val FACE = 300f
private val CENTRE_Y = HEIGHT * 0.42f

private val BLACK = Color(0, 0, 0)
private val WHITE = Color(0xF2, 0xEF, 0xE9)

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "bootanimation.zip")
    out.parentFile?.mkdirs()

    val wake = (0 until WAKE_FRAMES).map { frame -> png(wakeFrame(it = frame / (WAKE_FRAMES - 1f))) }
    val loop = (0 until LOOP_FRAMES).map { frame -> png(loopFrame(frame)) }

    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        // bootanimation refuses a deflated zip: every entry is stored.
        zip.setMethod(ZipOutputStream.STORED)
        val desc = """
            |$WIDTH $HEIGHT $FPS
            |p 1 0 part0
            |p 0 0 part1
            |
        """.trimMargin().toByteArray()
        store(zip, "desc.txt", desc)
        wake.forEachIndexed { i, bytes -> store(zip, "part0/%03d.png".format(i), bytes) }
        loop.forEachIndexed { i, bytes -> store(zip, "part1/%03d.png".format(i), bytes) }
    }
    println("wrote ${out.absolutePath} (${out.length() / 1024} KiB, ${wake.size + loop.size} frames)")
}

/**
 * The wake, as one number from 0 to 1: the strokes fade up on black, then the body grows
 * out from behind them. The eyes start shut, which is what a thing that has been off looks
 * like, and open as the body arrives.
 */
private fun wakeFrame(it: Float): BufferedImage = frame { g ->
    val appear = (it / 0.3f).coerceIn(0f, 1f)
    val grow = ((it - 0.25f) / 0.45f).coerceIn(0f, 1f)
    val opening = ((it - 0.6f) / 0.4f).coerceIn(0f, 1f)

    // Shut to open, one frame at a time rather than a switch: the asleep bars lengthen
    // into resting ones, which is the whole of the animation and the one thing that must
    // not pop.
    val face = FaceGeometry.of(between(eyeSpec(Mood.ASLEEP), eyeSpec(Mood.RESTING), ease(opening)))
    val scale = FACE / FaceGeometry.UNITS
    val bodyRadius = FaceGeometry.BODY_RADIUS * scale * ease(grow)

    g.color = Color(WHITE.red, WHITE.green, WHITE.blue, (255 * appear).toInt().coerceIn(0, 255))
    drawEyes(g, face, scale, filled = null)

    g.color = WHITE
    g.fill(Ellipse2D.Float(WIDTH / 2f - bodyRadius, CENTRE_Y - bodyRadius, bodyRadius * 2, bodyRadius * 2))

    // Inside the body the strokes are the eye colour; outside they are still the body's,
    // so the growing circle reads as arriving behind them rather than covering them.
    g.clip = Ellipse2D.Float(WIDTH / 2f - bodyRadius, CENTRE_Y - bodyRadius, bodyRadius * 2, bodyRadius * 2)
    g.color = BLACK
    drawEyes(g, face, scale, filled = null)
    g.clip = null
}

/** Breathing, with one blink, seamless end to start. */
private fun loopFrame(frame: Int): BufferedImage = frame { g ->
    val t = frame / LOOP_FRAMES.toFloat()
    val breathe = 1f + 0.03f * sin(2.0 * PI * t).toFloat()
    val blink = blinkAt(frame)
    val face = FaceGeometry.of(Mood.RESTING, Gaze.AHEAD)
    val scale = FACE / FaceGeometry.UNITS * breathe
    val radius = FaceGeometry.BODY_RADIUS * scale

    g.color = WHITE
    g.fill(Ellipse2D.Float(WIDTH / 2f - radius, CENTRE_Y - radius, radius * 2, radius * 2))
    g.color = BLACK
    drawEyes(g, face, scale, filled = blink)
}

/** 1 open, 0 shut. One blink per loop, four frames long, away from the seam. */
private fun blinkAt(frame: Int): Float {
    val start = LOOP_FRAMES / 2
    val length = 4
    if (frame < start || frame >= start + length) return 1f
    val t = (frame - start) / length.toFloat()
    return if (t < 0.5f) 1f - t * 2f * 0.92f else 0.08f + (t - 0.5f) * 2f * 0.92f
}

private fun drawEyes(g: Graphics2D, face: buddy.android.surface.creature.Face, scale: Float, filled: Float?) {
    eye(g, face.left, scale, filled ?: 1f)
    eye(g, face.right, scale, filled ?: 1f)
}

/**
 * One stroke, in the app's own terms: a rounded bar, tilted about its own centre, or the
 * arc when the geometry asks for one. The same shape [buddy.android.surface.creature.FacePainter]
 * draws on the phone.
 */
private fun eye(g: Graphics2D, eye: Eye, scale: Float, blink: Float) {
    val cx = WIDTH / 2f + (eye.centreX - FaceGeometry.CENTRE) * scale
    val cy = CENTRE_Y + (eye.centreY - FaceGeometry.CENTRE) * scale
    val w = eye.width * scale
    val h = eye.height * scale * blink.coerceIn(0.06f, 1f)

    val old = g.transform
    g.transform(AffineTransform.getRotateInstance(Math.toRadians(eye.tilt.toDouble()), cx.toDouble(), cy.toDouble()))
    if (eye.arc > 0.5f) {
        val half = FaceGeometry.ARC_HALF_WIDTH * scale
        val path = Path2D.Float()
        path.moveTo((cx - half).toDouble(), (cy + FaceGeometry.ARC_END_DY * scale).toDouble())
        path.quadTo(cx.toDouble(), (cy + FaceGeometry.ARC_CONTROL_DY * scale).toDouble(), (cx + half).toDouble(), (cy + FaceGeometry.ARC_END_DY * scale).toDouble())
        g.stroke = BasicStroke(FaceGeometry.ARC_STROKE * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path)
    } else {
        g.fill(RoundRectangle2D.Float(cx - w / 2f, cy - h / 2f, w, h, w, w))
    }
    g.transform = old
}

private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

/** One eye shape on the way to another. The same reading the app animates between. */
private fun between(from: EyeSpec, to: EyeSpec, t: Float) = EyeSpec(
    tiltLeft = lerp(from.tiltLeft, to.tiltLeft, t),
    tiltRight = lerp(from.tiltRight, to.tiltRight, t),
    width = lerp(from.width, to.width, t),
    height = lerp(from.height, to.height, t),
    dx = lerp(from.dx, to.dx, t),
    dy = lerp(from.dy, to.dy, t),
    arc = lerp(from.arc, to.arc, t),
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun frame(draw: (Graphics2D) -> Unit): BufferedImage {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = BLACK
    g.fill(Rectangle2D.Float(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))
    draw(g)
    g.dispose()
    return image
}

private fun png(image: BufferedImage): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

private fun store(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    val entry = ZipEntry(name)
    entry.method = ZipEntry.STORED
    entry.size = bytes.size.toLong()
    entry.compressedSize = bytes.size.toLong()
    entry.crc = CRC32().apply { update(bytes) }.value
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}
