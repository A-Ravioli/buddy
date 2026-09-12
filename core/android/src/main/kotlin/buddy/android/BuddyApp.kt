package buddy.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import buddy.android.ledger.LedgerHolder
import buddy.android.perception.Ingest
import buddy.android.perception.PerceptionService

class BuddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedgerHolder.init(this)
        Ingest.init(this)
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            LedgerHolder.onUserUnlocked()
        }
        PerceptionService.start(this)
    }

    companion object {
        const val TAG = "buddy"
    }
}

/**
 * Boot and unlock. The ledger lives in credential-encrypted storage, so it opens only
 * once the user has unlocked the device; anything perceived before that waits in
 * memory (see [Ingest]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(BuddyApp.TAG, "boot receiver: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_USER_UNLOCKED -> LedgerHolder.onUserUnlocked()
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> PerceptionService.start(context)
        }
    }
}
