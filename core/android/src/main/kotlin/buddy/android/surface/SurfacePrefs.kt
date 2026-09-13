package buddy.android.surface

import android.content.Context
import buddy.android.surface.theme.Scheme

/**
 * The few things the surface remembers on its own: whether it has said hello, and how it
 * looks. Everything about the user's life lives in the ledger and the profile files.
 */
class SurfacePrefs(context: Context) {
    // Device-protected storage: the surface is the home, so it draws before the first
    // unlock of every boot, and "have I said hello" has to be readable then. Nothing
    // here is about the user's life; that all lives in the credential-encrypted ledger.
    private val p = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("surface", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    var scheme: Scheme
        get() = runCatching { Scheme.valueOf(p.getString("scheme", null) ?: "") }.getOrDefault(Scheme.COLOUR)
        set(v) = p.edit().putString("scheme", v.name).apply()

    var accentIndex: Int
        get() = p.getInt("accent", 0)
        set(v) = p.edit().putInt("accent", v).apply()
}
