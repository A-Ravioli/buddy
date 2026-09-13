package buddy.android.device

import android.content.Context
import android.content.Intent
import android.util.Log
import buddy.android.BuddyApp

/**
 * The apps on the phone, which nothing else can reach now that the launcher is gone.
 *
 * Two ways in, and they are the same mechanism. The user asks — "open Monzo", "show me
 * the camera" — and buddy opens it. Or buddy gets as far as an app and hits something
 * only a person can do, a bank wanting a face or a login that expired, and hands the
 * screen over with a card saying why (see the hand-over in `surface/home`).
 *
 * There is deliberately no grid. An app is somewhere buddy takes you for one thing, and
 * you come back to him when it is done.
 */
object Apps {
    data class App(val packageName: String, val label: String)

    /** Everything with a launcher entry, as the launcher would have listed it. */
    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { resolved ->
                    val pkg = resolved.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    App(pkg, resolved.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
        }.getOrElse {
            Log.w(BuddyApp.TAG, "could not list the apps", it)
            emptyList()
        }
    }

    fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * What the user meant, out of what is installed. Spoken names are loose — "the bank
     * app", "my camera" — so the filler words go and the best of exact, then starts-with,
     * then contains wins. Nothing matches on one or two letters, which would open
     * something at random.
     */
    fun match(query: String, apps: List<App>): App? {
        val wanted = query.lowercase()
            .replace(Regex("""\b(the|my|a|an|app|please|open|show me|launch)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (wanted.length < 3) return null
        apps.firstOrNull { it.label.equals(wanted, ignoreCase = true) }?.let { return it }
        apps.filter { it.label.lowercase().startsWith(wanted) }.minByOrNull { it.label.length }?.let { return it }
        apps.filter { it.label.lowercase().contains(wanted) }.minByOrNull { it.label.length }?.let { return it }
        return null
    }

    /** Hands the screen to an app. False when the phone has no way in to it. */
    fun open(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse {
            Log.w(BuddyApp.TAG, "could not open $packageName", it)
            false
        }
    }
}
