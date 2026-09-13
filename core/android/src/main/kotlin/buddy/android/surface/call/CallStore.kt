package buddy.android.surface.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
import buddy.perception.SmsNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a call has got to, in the four states a person cares about. */
enum class Phase { RINGING, DIALING, ACTIVE, HOLDING }

/**
 * One call, as the screen needs it. The number is kept apart from the name so buddy can
 * say who it is without pretending to be sure.
 */
data class CallUi(
    val name: String,
    val number: String?,
    val phase: Phase,
    val connectedAt: Long,
    val muted: Boolean,
    val speaker: Boolean,
)

/**
 * The call, for the screen that draws it. buddy holds the dialer role, so this is the
 * only in-call UI on the phone: nothing else on the build can answer a call.
 *
 * It is deliberately thin. Telecom owns the call; this mirrors it and passes taps back.
 */
object CallStore {
    private val _call = MutableStateFlow<CallUi?>(null)
    val call: StateFlow<CallUi?> = _call

    @Volatile
    private var current: Call? = null

    @Volatile
    private var service: InCallService? = null

    private var muted = false
    private var speaker = false

    /** Telecom's states, reduced to the four a person can act on. Null means it is over. */
    fun phaseOf(state: Int): Phase? = when (state) {
        Call.STATE_RINGING -> Phase.RINGING
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> Phase.DIALING
        Call.STATE_ACTIVE -> Phase.ACTIVE
        Call.STATE_HOLDING -> Phase.HOLDING
        else -> null
    }

    /**
     * Who is calling. The entity graph already knows the people who matter, so a call
     * from one of them arrives with a name rather than a number, and a call from anyone
     * else arrives honestly as a number.
     */
    fun nameFor(number: String?): String {
        if (number.isNullOrBlank()) return "Unknown number"
        val normalized = SmsNormalizer.normalizeAddress(number)
        val known = runCatching {
            Brain.entities?.people(500)?.firstOrNull { person ->
                person.identities.any { it.startsWith("tel:") && SmsNormalizer.normalizeAddress(it.removePrefix("tel:")) == normalized }
            }?.displayName
        }.getOrNull()
        return known ?: number
    }

    fun attach(service: InCallService) {
        this.service = service
    }

    fun detach() {
        service = null
    }

    fun onCall(call: Call) {
        current = call
        publish(call)
    }

    fun onCallGone(call: Call) {
        if (current === call) {
            current = null
            muted = false
            speaker = false
            _call.value = null
        }
    }

    fun publish(call: Call) {
        val phase = phaseOf(call.details.state)
        if (phase == null) {
            onCallGone(call)
            return
        }
        val number = call.details.handle?.schemeSpecificPart
        _call.value = CallUi(
            name = call.details.callerDisplayName?.takeIf { it.isNotBlank() } ?: nameFor(number),
            number = number,
            phase = phase,
            connectedAt = call.details.connectTimeMillis,
            muted = muted,
            speaker = speaker,
        )
    }

    // ---- what the screen does

    fun answer() {
        runCatching { current?.answer(0) }.onFailure { Log.w(BuddyApp.TAG, "could not answer", it) }
    }

    /** Decline a ringing call, or hang up one in progress. Telecom wants different calls. */
    fun end() {
        val call = current ?: return
        runCatching {
            if (call.details.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
        }.onFailure { Log.w(BuddyApp.TAG, "could not end the call", it) }
    }

    fun toggleMute() {
        val s = service ?: return
        muted = !muted
        runCatching { s.setMuted(muted) }.onFailure { Log.w(BuddyApp.TAG, "could not mute", it) }
        current?.let { publish(it) }
    }

    fun toggleSpeaker() {
        val s = service ?: return
        speaker = !speaker
        val route = if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        runCatching { s.setAudioRoute(route) }.onFailure { Log.w(BuddyApp.TAG, "could not change the route", it) }
        current?.let { publish(it) }
    }

    /** The audio state changed under us, from a headset or the framework. */
    fun onAudioState(state: CallAudioState) {
        muted = state.isMuted
        speaker = state.route == CallAudioState.ROUTE_SPEAKER
        current?.let { publish(it) }
    }

    /** mm:ss since the call connected, or empty while it has not. */
    fun elapsed(connectedAt: Long, now: Long): String {
        if (connectedAt <= 0L || now < connectedAt) return ""
        val seconds = (now - connectedAt) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
