package buddy.wear

import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the escalation queue, the brief, and the cue from the phone. Escalations
 * are shown by [EscalationActivity]; cues are a haptic pattern that differs for start
 * and stop so the wearer knows which without looking.
 */
class EscalationListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val item = DataMapItem.fromDataItem(e.dataItem)
            when (e.dataItem.uri.path) {
                "/buddy/escalations" -> {
                    State.escalations = item.dataMap.getString("json") ?: "[]"
                    startActivity(Intent(this, EscalationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "/buddy/brief" -> State.brief = item.dataMap.getString("spoken") ?: ""
                "/buddy/cue" -> cue(item.dataMap.getString("kind") == "transcription_started")
            }
        }
    }

    private fun cue(started: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val pattern = if (started) longArrayOf(0, 40, 60, 40) else longArrayOf(0, 120)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

/** The watch keeps only what is on screen; the phone is the source of truth. */
object State {
    @Volatile var escalations: String = "[]"
    @Volatile var brief: String = ""
}
