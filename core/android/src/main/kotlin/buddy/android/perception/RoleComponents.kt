package buddy.android.perception

import android.app.IntentService
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.util.Log
import buddy.android.BuddyApp
import buddy.android.surface.call.CallActivity
import buddy.android.surface.call.CallStore
import buddy.ledger.Event
import buddy.ledger.EventId
import buddy.ledger.EventKind
import buddy.ledger.Trust
import buddy.perception.SmsNormalizer
import buddy.perception.SmsRow

/**
 * Components the SMS, dialer, and call screening roles require. In Phase 0 they
 * observe and record; nothing here replies, dials, or blocks.
 */

/**
 * As the default SMS app, buddy receives SMS_DELIVER and must write the message to the
 * provider itself, or it is lost. Then it is recorded like any other message.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val address = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: return
        val date = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        val id = uri?.lastPathSegment?.toLongOrNull()
        if (id == null) {
            Log.e(BuddyApp.TAG, "failed to persist incoming SMS")
            return
        }
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        Ingest.submit(SmsNormalizer.toEvent(SmsRow(id, threadId, address, date, body, SmsRow.TYPE_INBOX)))
    }
}

/** MMS delivery. Phase 0 records that one arrived; parsing the PDU comes with the messaging playbook. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ts = System.currentTimeMillis()
        Ingest.submit(
            Event(
                id = EventId.of(ts, SmsNormalizer.SOURCE, "mms", intent.dataString),
                ts = ts,
                sourceApp = SmsNormalizer.SOURCE,
                channel = "mms",
                kind = EventKind.MESSAGE,
                text = null,
                structured = mapOf("unparsed" to "true"),
                trust = Trust.UNTRUSTED,
            ),
        )
    }
}

/** Required by the SMS role for "respond via message" from the in-call screen. Phase 0: no-op. */
@Suppress("DEPRECATION")
class HeadlessSmsSendService : IntentService("buddy-sms-send") {
    override fun onHandleIntent(intent: Intent?) {
        Log.i(BuddyApp.TAG, "respond-via-message requested; not acting in this phase")
    }
}

/**
 * Dialer role. Records the call lifecycle into the ledger and puts buddy's call screen on
 * the display, since nothing else on this build can: the phone app is not installed and
 * buddy's is the only `IN_CALL_SERVICE_UI`.
 */
class BuddyInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            record(call, state)
            CallStore.publish(call)
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallStore.onAudioState(audioState)
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        record(call, call.details.state)
        CallStore.attach(this)
        CallStore.onCall(call)
        // The in-call UI may start an activity from the background; that is what the role
        // is for. VERIFY at the pinned tag that this still holds for a call that arrives
        // while the phone is locked.
        runCatching {
            startActivity(
                Intent(this, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { Log.w(BuddyApp.TAG, "could not show the call screen", it) }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        record(call, Call.STATE_DISCONNECTED)
        CallStore.onCallGone(call)
        CallStore.detach()
    }

    private fun record(call: Call, state: Int) {
        val ts = System.currentTimeMillis()
        val number = call.details.handle?.schemeSpecificPart
        val outgoing = call.details.callDirection == Call.Details.DIRECTION_OUTGOING
        val stateName = when (state) {
            Call.STATE_RINGING -> "ringing"
            Call.STATE_DIALING -> "dialing"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTED -> "disconnected"
            else -> "state_$state"
        }
        Ingest.submit(
            Event(
                id = EventId.of(ts, "android.telecom", "call", number, stateName),
                ts = ts,
                sourceApp = "android.telecom",
                channel = "call",
                kind = EventKind.CALL,
                actor = if (outgoing) "me" else number,
                threadId = number?.let { "call:${SmsNormalizer.normalizeAddress(it)}" },
                structured = buildMap {
                    put("state", stateName)
                    put("direction", if (outgoing) "out" else "in")
                    number?.let { put("counterparty", SmsNormalizer.normalizeAddress(it)) }
                },
                trust = if (outgoing) Trust.USER else Trust.UNTRUSTED,
            ),
        )
    }
}

/** Call screening role. Phase 0: allow every call, record the screening event. */
class BuddyCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val ts = System.currentTimeMillis()
        val number = callDetails.handle?.schemeSpecificPart
        Ingest.submit(
            Event(
                id = EventId.of(ts, "android.telecom", "screening", number),
                ts = ts,
                sourceApp = "android.telecom",
                channel = "screening",
                kind = EventKind.CALL,
                actor = number,
                structured = mapOf("decision" to "allow", "phase" to "0"),
                trust = Trust.UNTRUSTED,
            ),
        )
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
