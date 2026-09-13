package buddy.android.surface.setup

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import buddy.android.BuddyApp

/** What happened when buddy tried to set the PIN. */
enum class LockResult {
    /** The credential is set. The ledger's storage key is now bound to it. */
    SET,

    /** The framework would not take it from here; the caller should send the user to Settings. */
    UNAVAILABLE,

    /** It tried and the framework refused (too short, policy). */
    REFUSED,
}

/**
 * Setting the phone's first lock credential from inside the walk-through.
 *
 * This matters beyond keeping the user on one screen: the ledger lives in
 * credential-encrypted storage (see `LedgerHolder`), so the PIN chosen here is what the
 * hardware keystore binds the ledger's key to. Until it is set, nothing buddy knows is
 * encrypted at rest.
 *
 * There is no public API for it. The setup wizard's route is
 * `LockPatternUtils.setLockCredential`, reached over reflection so this file compiles
 * both in the tree (where the class is on the boot classpath) and against the host's
 * framework jar in the compile check. buddy holds the signature-level permissions that
 * back it; a build that lacks them gets [LockResult.UNAVAILABLE] and falls back to
 * [settingsIntent] rather than failing quietly. VERIFY the signature at the pinned tag.
 */
object LockCredential {
    fun set(context: Context, pin: String): LockResult {
        if (pin.length < 4) return LockResult.REFUSED
        return try {
            val credentialClass = Class.forName("com.android.internal.widget.LockscreenCredential")
            val createPin = credentialClass.getMethod("createPin", CharSequence::class.java)
            val createNone = credentialClass.getMethod("createNone")
            val utilsClass = Class.forName("com.android.internal.widget.LockPatternUtils")
            val utils = utilsClass.getConstructor(Context::class.java).newInstance(context)

            val credential = createPin.invoke(null, pin)
            val none = createNone.invoke(null)
            val setLockCredential = utilsClass.getMethod(
                "setLockCredential",
                credentialClass,
                credentialClass,
                Int::class.javaPrimitiveType,
            )
            val ok = setLockCredential.invoke(utils, credential, none, UserHandle.myUserId()) as? Boolean ?: true
            if (ok) {
                Log.i(BuddyApp.TAG, "lock credential set")
                LockResult.SET
            } else {
                LockResult.REFUSED
            }
        } catch (e: ClassNotFoundException) {
            LockResult.UNAVAILABLE
        } catch (e: NoSuchMethodException) {
            Log.w(BuddyApp.TAG, "lock credential API has drifted", e)
            LockResult.UNAVAILABLE
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "could not set the lock credential", t)
            LockResult.UNAVAILABLE
        }
    }

    /** The fallback: the platform's own choose-a-lock flow. Leaves buddy's screen briefly. */
    fun settingsIntent(): Intent =
        Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
