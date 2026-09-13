package buddy.android.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import buddy.android.BuddyApp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.concurrent.thread

/**
 * How buddy announces himself when he has something, now that no notification of his
 * reaches anyone: the shade does not open, the status bar carries no icons of his, and
 * the lock screen shows a face rather than a list. The face lifting its eyes is the whole
 * of the message (see [buddy.android.surface.lockscreen.LockFace]); this is what makes
 * someone look at it.
 *
 * The chime is generated rather than shipped. Two notes, a fifth apart, with a soft
 * envelope: short enough not to be a ringtone, pitched to be his and not the platform's.
 * No asset to manage, and it is the same idea as the creature being geometry rather than
 * a drawing.
 */
object Arrival {
    /** What an announcement comes to, once the hour and the user have been considered. */
    enum class Announcement { SOUND_AND_HAPTIC, HAPTIC, NOTHING }

    private const val RATE = 44_100
    private const val LOW_HZ = 587.33 // D5
    private const val HIGH_HZ = 880.0 // A5
    private const val NOTE_MS = 180
    private const val GAP_MS = 40

    /**
     * Quiet hours mean quiet: nothing at all, not even for something urgent, which will
     * still be waiting in the morning. The one exception is the user already looking at
     * the screen, where the creature changing in front of them is the announcement and a
     * sound would be buddy talking over himself.
     */
    fun decide(urgent: Boolean, quiet: Boolean, onScreen: Boolean): Announcement = when {
        onScreen -> Announcement.NOTHING
        quiet -> Announcement.NOTHING
        urgent -> Announcement.SOUND_AND_HAPTIC
        else -> Announcement.HAPTIC
    }

    fun announce(context: Context, announcement: Announcement) {
        when (announcement) {
            Announcement.NOTHING -> return
            Announcement.HAPTIC -> vibrate(context, urgent = false)
            Announcement.SOUND_AND_HAPTIC -> {
                vibrate(context, urgent = true)
                chime()
            }
        }
    }

    /**
     * The waveform, as signed 16-bit mono. Pure arithmetic, so the shape of the sound is
     * checked on the host rather than guessed at on the phone.
     */
    fun waveform(): ShortArray {
        val note = RATE * NOTE_MS / 1000
        val gap = RATE * GAP_MS / 1000
        val samples = ShortArray(note * 2 + gap)
        fun strike(at: Int, hz: Double) {
            for (i in 0 until note) {
                // Quick in, slow out, so it reads as a struck note rather than a beep.
                val t = i / note.toDouble()
                val envelope = if (t < 0.06) t / 0.06 else (1.0 - (t - 0.06) / 0.94) * (1.0 - (t - 0.06) / 0.94)
                val value = sin(2.0 * PI * hz * i / RATE) * envelope * 0.45
                samples[at + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        strike(0, LOW_HZ)
        strike(note + gap, HIGH_HZ)
        return samples
    }

    private fun chime() {
        thread(name = "buddy-arrival") {
            var track: AudioTrack? = null
            try {
                val samples = waveform()
                val bytes = samples.size * 2
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(samples, 0, samples.size)
                t.play()
                Thread.sleep((NOTE_MS * 2 + GAP_MS + 120).toLong())
            } catch (e: Throwable) {
                Log.w(BuddyApp.TAG, "could not sound the arrival", e)
            } finally {
                runCatching { track?.release() }
            }
        }
    }

    private fun vibrate(context: Context, urgent: Boolean) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        // One tap for something waiting, two for something urgent. Nothing longer: a long
        // buzz is a phone demanding, and buddy is not allowed to demand.
        val effect = if (urgent) {
            VibrationEffect.createWaveform(longArrayOf(0, 24, 90, 24), -1)
        } else {
            VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
            .onFailure { Log.w(BuddyApp.TAG, "could not feel the arrival", it) }
    }
}
