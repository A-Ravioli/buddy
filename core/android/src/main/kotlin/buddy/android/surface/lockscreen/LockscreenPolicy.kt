package buddy.android.surface.lockscreen

import android.content.Context
import android.provider.Settings
import android.util.Log
import buddy.android.BuddyApp

/**
 * What the locked phone shows. Everything here is a secure setting, so none of it needs a
 * framework patch; buddy writes it once, at the end of the walk-through.
 *
 * The reasoning is the plan's, not taste: notifications feed the agent and the user gets a
 * brief (docs/01-architecture.md), so a lock screen listing them is the old phone leaking
 * through. The shortcuts beside the clock are the same. What is left is buddy's face,
 * which the keyguard patch puts there, and the eyes lifting is the whole notification.
 *
 * Always-on is turned on deliberately: the creature sheet asks for the chin to stay lit at
 * whisper brightness and blink, and that is the always-on display.
 *
 * Keys are written as literals because several are hidden from the SDK, which keeps this
 * the same on the host compile check and in the tree. VERIFY the list at the pinned tag;
 * an unknown key here is a silent no-op rather than a build failure.
 */
object LockscreenPolicy {
    private val off = listOf(
        "lock_screen_show_notifications",
        "lock_screen_allow_private_notifications",
        "lock_screen_show_silent_notifications",
        "lockscreen_show_wallet",
        "lockscreen_show_controls",
        "lock_screen_show_qr_code_scanner",
    )

    fun apply(context: Context) {
        val cr = context.contentResolver
        for (key in off) put(cr, key, 0)
        // The chin stays lit: always-on, so the face is there when the phone is face up.
        put(cr, "doze_always_on", 1)
        Log.i(BuddyApp.TAG, "lockscreen policy applied")
    }

    private fun put(cr: android.content.ContentResolver, key: String, value: Int) {
        runCatching { Settings.Secure.putInt(cr, key, value) }
            .onFailure { Log.w(BuddyApp.TAG, "could not set $key", it) }
    }
}
