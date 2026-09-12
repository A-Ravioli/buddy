package buddy.android.surface

import android.content.Context
import android.util.Log
import buddy.android.BuddyApp
import buddy.audio.CueEmitter
import buddy.cognition.Brief

/**
 * The phone side of the watch contract (wear/README.md). Sends the escalation queue,
 * the brief, and the transcription cue; receives resolutions.
 *
 * The Wearable Data Layer client is loaded reflectively so the app builds without the
 * Play Services wearable library present; with it absent the bridge is a no-op and
 * the phone's own screen and earbuds remain the surface.
 */
class WearBridge(private val context: Context) : CueEmitter {
    private val available: Boolean = runCatching { Class.forName("com.google.android.gms.wearable.Wearable") }.isSuccess

    fun sendEscalations(items: List<Escalation>) {
        val json = items.joinToString(",", "[", "]") { e ->
            """{"id":${q(e.id)},"text":${q(e.text)},"urgent":${e.urgent},"options":[${e.options.joinToString(",") { q(it) }}]}"""
        }
        put("/buddy/escalations", mapOf("json" to json))
    }

    fun sendBrief(b: Brief) = put("/buddy/brief", mapOf("spoken" to b.spoken))

    override fun transcriptionStarted(reason: String) = put("/buddy/cue", mapOf("kind" to "transcription_started", "reason" to reason))
    override fun transcriptionStopped(reason: String) = put("/buddy/cue", mapOf("kind" to "transcription_stopped", "reason" to reason))

    private fun put(path: String, values: Map<String, String>) {
        if (!available) return
        try {
            val wearable = Class.forName("com.google.android.gms.wearable.Wearable")
            val dataClient = wearable.getMethod("getDataClient", Context::class.java).invoke(null, context)
            val reqClass = Class.forName("com.google.android.gms.wearable.PutDataMapRequest")
            val req = reqClass.getMethod("create", String::class.java).invoke(null, path)
            val dataMap = reqClass.getMethod("getDataMap").invoke(req)
            val putString = dataMap.javaClass.getMethod("putString", String::class.java, String::class.java)
            for ((k, v) in values) putString.invoke(dataMap, k, v)
            dataMap.javaClass.getMethod("putLong", String::class.java, Long::class.javaPrimitiveType).invoke(dataMap, "ts", System.currentTimeMillis())
            val putReq = reqClass.getMethod("asPutDataRequest").invoke(req)
            putReq.javaClass.getMethod("setUrgent").invoke(putReq)
            dataClient.javaClass.getMethod("putDataItem", putReq.javaClass.superclass ?: putReq.javaClass).invoke(dataClient, putReq)
        } catch (t: Throwable) {
            Log.w(BuddyApp.TAG, "wear bridge put failed", t)
        }
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    data class Escalation(val id: String, val text: String, val urgent: Boolean, val options: List<String>)
}
