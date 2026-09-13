package buddy.android.surface.setup

import android.content.Context
import android.provider.Settings
import android.util.Log
import buddy.android.BuddyApp

/**
 * The two flags that decide whether Android thinks the phone still needs setting up.
 *
 * On a stock build a setup wizard owns these: it runs before anything else and sets them
 * when it is done. buddy replaces that wizard (the wizard package is dropped from the
 * build in `Android.bp`), so buddy sets them itself at the end of the walk-through.
 * Until they are set the framework keeps the lock screen off and hides the status bar
 * and quick settings, which is exactly the blank canvas the wake-up wants.
 *
 * Both names are read as string literals: `USER_SETUP_COMPLETE` is hidden from the SDK,
 * and using literals keeps this the same on the host compile check and in the tree.
 */
object Provisioning {
    private const val DEVICE_PROVISIONED = "device_provisioned"
    private const val USER_SETUP_COMPLETE = "user_setup_complete"

    /** True on a phone that has never been through setup. The setup steps only run then. */
    fun needsSetup(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, DEVICE_PROVISIONED, 0) == 0

    /**
     * Hands the phone over to the user: the lock screen comes up from here on, the status
     * bar appears, and a reboot goes straight to buddy's home instead of the walk-through.
     * Needs WRITE_SECURE_SETTINGS, which buddy holds as a platform-signed privileged app.
     */
    fun markProvisioned(context: Context): Boolean = try {
        Settings.Global.putInt(context.contentResolver, DEVICE_PROVISIONED, 1)
        Settings.Secure.putInt(context.contentResolver, USER_SETUP_COMPLETE, 1)
        Log.i(BuddyApp.TAG, "device marked provisioned")
        true
    } catch (t: Throwable) {
        // A dev build without the privileged grant lands here. The walk-through still
        // finishes; the phone just keeps thinking it is unprovisioned.
        Log.e(BuddyApp.TAG, "could not mark the device provisioned", t)
        false
    }
}
