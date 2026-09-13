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
 * - **Quick settings** keeps only what buddy cannot do for the user: the internet toggle
 *   and the torch. The default list is a SystemUI resource, overlaid in
 *   `platform/overlay`; this writes the same pair to the user's own list, which takes
 *   precedence over the default and so survives a resource name drifting.
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

    fun apply(context: Context) {
        runCatching { Settings.Secure.putString(context.contentResolver, QS_TILES, TILES) }
            .onFailure { Log.w(BuddyApp.TAG, "could not set the quick settings tiles", it) }

        val bar = context.getSystemService(StatusBarManager::class.java)
        if (bar == null) {
            Log.w(BuddyApp.TAG, "no status bar service")
            return
        }
        // Needs the signature-level STATUS_BAR permission, which the platform-signed app
        // holds. A build without it lands in the failure branch and keeps its recents.
        runCatching { bar.disable(StatusBarManager.DISABLE_RECENT) }
            .onSuccess { Log.i(BuddyApp.TAG, "recents disabled") }
            .onFailure { Log.w(BuddyApp.TAG, "could not disable recents", it) }
    }
}
