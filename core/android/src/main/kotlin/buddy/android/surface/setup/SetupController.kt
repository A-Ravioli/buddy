package buddy.android.surface.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The walk-through's side of first-run setup: what the phone still needs before it is a
 * working phone, and the calls that provide it. One object so the steps stay declarative.
 *
 * buddy is the setup wizard on this build, so everything a wizard would do lives here:
 * get online, set the lock, and tell the framework the phone is provisioned.
 */
class SetupController(context: Context) {
    private val app = context.applicationContext

    val wifi = WifiJoiner(app)

    /** True on a phone that has never been set up; the setup steps only appear then. */
    val needed: Boolean = Provisioning.needsSetup(app)

    private val _lock = MutableStateFlow<LockResult?>(null)

    /** Null until the PIN step runs. */
    val lock: StateFlow<LockResult?> = _lock

    fun setPin(pin: String): LockResult {
        val result = LockCredential.set(app, pin)
        _lock.value = result
        return result
    }

    /** Called once, at the end of the walk-through. */
    fun finish() {
        wifi.stop()
        if (needed) Provisioning.markProvisioned(app)
    }
}
