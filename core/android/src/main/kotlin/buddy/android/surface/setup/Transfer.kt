package buddy.android.surface.setup

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.device.Apps

/**
 * Moving in from the old phone.
 *
 * Most of this buddy does himself, by reading what is already on the phone: who matters
 * comes from the message history, quiet hours from when the house goes quiet, style from
 * what has been sent. What is left are the two things no app on the device can do for
 * anyone — carrying the number over and signing in to an account — and for those he opens
 * the framework's own flow and waits, rather than pretending to have done it.
 *
 * What the setup wizard used to do and nothing here replaces — restoring apps and their
 * data from a backup — is a decision, not an omission: see `docs/moving-in.md`. A phone
 * that restores the old phone's habits is the old phone.
 */
object Transfer {
    /** Accounts the phone already has, as the user would name them. */
    fun accounts(context: Context): List<String> = runCatching {
        AccountManager.get(context).accounts.map { it.name }.distinct()
    }.getOrElse {
        Log.w(BuddyApp.TAG, "could not read the accounts", it)
        emptyList()
    }

    /** The framework's add-account flow. buddy has no business handling a password. */
    fun addAccount(context: Context) = start(
        context,
        Intent(Settings.ACTION_ADD_ACCOUNT),
        Intent(Settings.ACTION_SYNC_SETTINGS),
    )

    /** Carrying the number over: eSIM transfer, which is the carrier's flow and the SIM's. */
    fun moveSim(context: Context) = start(
        context,
        Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"),
        Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /** The messaging apps that are actually installed, in the order people name them. */
    fun messaging(context: Context): List<String> {
        val known = mapOf(
            "com.whatsapp" to 0,
            "org.thoughtcrime.securesms" to 1,
            "org.telegram.messenger" to 2,
            "com.facebook.orca" to 3,
            "com.google.android.apps.messaging" to 4,
        )
        return Apps.launchable(context)
            .filter { it.packageName in known }
            .sortedBy { known[it.packageName] }
            .map { it.label }
    }

    /** How many apps came across, for the step that says they are still here. */
    fun appCount(context: Context): Int = Apps.launchable(context).size

    /** The first of these the phone has. A build without any leaves the step's words true. */
    private fun start(context: Context, vararg options: Intent) {
        for (intent in options) {
            val resolved = runCatching {
                context.packageManager.resolveActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0)
            }.getOrNull()
            if (resolved != null) {
                runCatching { context.startActivity(intent) }
                    .onSuccess { return }
                    .onFailure { Log.w(BuddyApp.TAG, "could not open ${intent.action}", it) }
            }
        }
        Log.w(BuddyApp.TAG, "no screen on this build for ${options.firstOrNull()?.action}")
    }
}
