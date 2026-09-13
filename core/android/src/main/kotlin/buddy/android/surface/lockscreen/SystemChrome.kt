package buddy.android.surface.lockscreen

import android.app.StatusBarManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import buddy.android.BuddyApp

/**
 * The chrome that assumes someone is looking (patch 0013 in the patch set).
 *
 * Writing it turned out not to need a patch:
 *
 * - **Recents** is provided by the launcher's overview, and the launcher is already
 *   dropped from the build, so there is nothing behind the gesture. [StatusBarManager]
 *   makes that explicit rather than leaving a gesture that half-animates into nothing.
 * - **The notification shade** does not pull down at all. Nothing posts to it: buddy is
 *   the notification assistant and holds what arrives, so the panel would be an empty
 *   drawer with a "no notifications" label, which is a worse answer than no drawer. The
 *   same flags stop anything that does slip through from ringing or peeking, because what
 *   interrupts the user is buddy's decision and not each app's.
 * - **Quick settings** goes with the shade, so the two switches it was still carrying
 *   move to buddy: he throws the torch himself ([buddy.android.device.Torch]) and he has
 *   a network picker of his own, the one he used to get the phone online during the
 *   walk-through. Ask him for either. The trimmed tile list is still written, because it
 *   is what a build where the disable call fails would fall back to.
 *
 * The status bar stays. A clock and a battery figure are not a thing to manage, and a
 * phone with no way to see either is a worse phone, not a calmer one.
 *
 * Disable flags are held against the caller's token and last as long as the process, so
 * this is re-applied at every boot. buddy is a persistent app, so that is the whole story.
 */
object SystemChrome {
    private const val QS_TILES = "sysui_qs_tiles"
    private const val TILES = "internet,flashlight"

    private const val DISABLE =
        StatusBarManager.DISABLE_RECENT or
            StatusBarManager.DISABLE_NOTIFICATION_ICONS or
            StatusBarManager.DISABLE_NOTIFICATION_ALERTS

    // The shade flag is what closes the drawer; quick settings is named as well so that a
    // release where the two are separable still loses the panel buddy has replaced.
    private const val DISABLE2 =
        StatusBarManager.DISABLE2_NOTIFICATION_SHADE or
            StatusBarManager.DISABLE2_QUICK_SETTINGS

    fun apply(context: Context) {
        runCatching { Settings.Secure.putString(context.contentResolver, QS_TILES, TILES) }
            .onFailure { Log.w(BuddyApp.TAG, "could not set the quick settings tiles", it) }

        val bar = context.getSystemService(StatusBarManager::class.java)
        if (bar == null) {
            Log.w(BuddyApp.TAG, "no status bar service")
            return
        }
        // Needs the signature-level STATUS_BAR permission, which the platform-signed app
        // holds. A build without it lands in the failure branch and keeps its chrome.
        runCatching {
            bar.disable(DISABLE)
            bar.disable2(DISABLE2)
        }
            .onSuccess { Log.i(BuddyApp.TAG, "recents and the shade disabled") }
            .onFailure { Log.w(BuddyApp.TAG, "could not disable the viewer chrome", it) }
    }
}
