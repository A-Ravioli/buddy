package buddy.android.surface.lockscreen

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import buddy.android.surface.creature.Mood

/**
 * The one-way channel from buddy to his own face on the lock screen.
 *
 * The keyguard runs inside SystemUI, a different process with no binding to buddy's, so
 * the face cannot read [buddy.android.surface.SurfaceStore] the way the surface does.
 * Two `Settings.Global` keys carry the little it needs to know. Global settings are in
 * device-encrypted storage, so they read back before the first unlock, which is exactly
 * when the lock screen is first drawn; they also survive a reboot, so a phone that has
 * been restarted but not yet unlocked still knows when its quiet hours are.
 *
 * Nothing about the user's content goes through here. The face says whether something is
 * waiting and whether it is the middle of the night, and that is the whole protocol.
 *
 * VERIFY on the build host: writing a key of buddy's own to `Settings.Global` needs
 * WRITE_SECURE_SETTINGS, which the platform-signed app holds, but a future release could
 * start rejecting names the framework does not know. The writes log and carry on if so,
 * and the face falls back to resting.
 */
object LockFace {
    /** Set to [WAITING] while something needs the user. */
    const val FACE_KEY = "buddy_lock_face"

    /** Quiet hours as "start-end", local hours, or empty for none. */
    const val QUIET_KEY = "buddy_lock_quiet"

    private const val WAITING = "waiting"
    private const val REST = "rest"
    private const val TAG = "buddy"

    /** What a locked phone can say. Narrower than the app's moods: nobody is looking yet. */
    enum class State { RESTING, NEEDS_YOU, ASLEEP }

    /** Quiet hours, start inclusive to end exclusive, wrapping midnight when start > end. */
    data class Quiet(val startHour: Int, val endHour: Int) {
        fun contains(hour: Int): Boolean = when {
            startHour == endHour -> false
            startHour < endHour -> hour in startHour until endHour
            else -> hour >= startHour || hour < endHour
        }
    }

    fun faceUri(): Uri = Settings.Global.getUriFor(FACE_KEY)

    fun quietUri(): Uri = Settings.Global.getUriFor(QUIET_KEY)

    // ---- buddy's side

    /** Something is waiting for the user, or nothing is. */
    fun publish(context: Context, waiting: Boolean) {
        put(context, FACE_KEY, if (waiting) WAITING else REST)
    }

    /** The user's quiet hours, from the policy profile. Null clears them. */
    fun publishQuiet(context: Context, quiet: Quiet?) {
        put(context, QUIET_KEY, quiet?.let { "${it.startHour}-${it.endHour}" } ?: "")
    }

    // ---- the lock screen's side

    fun read(resolver: ContentResolver, hour: Int): State =
        state(waiting(resolver), quiet(resolver), hour)

    /**
     * Quiet hours win over anything waiting. The point of the hours is that the phone
     * does not summon anyone, and a glow across a dark room is a summons; whatever is
     * waiting is still waiting in the morning.
     */
    fun state(waiting: Boolean, quiet: Quiet?, hour: Int): State = when {
        quiet != null && quiet.contains(hour) -> State.ASLEEP
        waiting -> State.NEEDS_YOU
        else -> State.RESTING
    }

    fun moodOf(state: State): Mood = when (state) {
        State.ASLEEP -> Mood.ASLEEP
        State.NEEDS_YOU -> Mood.NEEDS_YOU
        State.RESTING -> Mood.RESTING
    }

    fun waiting(resolver: ContentResolver): Boolean =
        runCatching { Settings.Global.getString(resolver, FACE_KEY) }.getOrNull() == WAITING

    fun quiet(resolver: ContentResolver): Quiet? =
        parseQuiet(runCatching { Settings.Global.getString(resolver, QUIET_KEY) }.getOrNull())

    /** "22-8" to a window. Anything else, including the empty string, is no quiet hours. */
    fun parseQuiet(raw: String?): Quiet? {
        val parts = raw?.split('-') ?: return null
        if (parts.size != 2) return null
        val start = parts[0].trim().toIntOrNull() ?: return null
        val end = parts[1].trim().toIntOrNull() ?: return null
        if (start !in 0..23 || end !in 0..23) return null
        return Quiet(start, end)
    }

    private fun put(context: Context, key: String, value: String) {
        runCatching { Settings.Global.putString(context.contentResolver, key, value) }
            .onFailure { Log.w(TAG, "could not publish $key to the lock screen", it) }
    }
}
