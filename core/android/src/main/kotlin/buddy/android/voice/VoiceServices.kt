package buddy.android.voice

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import buddy.actuation.Actions
import buddy.android.BuddyApp
import buddy.android.cognition.Brain
import buddy.android.ledger.LedgerHolder
import buddy.ledger.EventKind
import buddy.policy.PolicyContext
import buddy.policy.PolicyEngine
import buddy.policy.Proposal
import buddy.voice.Command
import buddy.voice.CommandParser
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Voice interaction role holders. Phase 3: the assist gesture (long-press, earbud
 * button) opens a session that listens once with the on-device recogniser, parses the
 * words with the command grammar, and acts through the same policy engine as
 * everything else. Hotword on the DSP needs an enrolled keyphrase and is wired on the
 * build host (see docs/phase-3.md).
 */
class BuddyVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(BuddyApp.TAG, "voice interaction service ready")
    }
}

class BuddyVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CommandSession(this)
}

/** One listen, one command, one spoken reply. */
class CommandSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        tts = TextToSpeech(service) { }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(service)) {
            say("On-device speech is not available.")
            hide()
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.i(BuddyApp.TAG, "heard: $text")
                say(handle(CommandParser.parse(text)))
                hide()
            }
            override fun onError(error: Int) { say("Sorry, I didn't catch that."); hide() }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()),
        )
    }

    override fun onHide() {
        recognizer?.destroy(); recognizer = null
        super.onHide()
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "buddy")
    }

    /** Commands go through policy like any other action; nothing here bypasses it. */
    private fun handle(c: Command): String {
        val ledger = LedgerHolder.getOrNull() ?: return "The ledger is locked until you unlock the phone."
        return when (c) {
            is Command.Brief -> ledger.recent(50).firstOrNull { it.kind == EventKind.BRIEF }?.text ?: "No brief yet."
            is Command.Recall -> ledger.search(c.query, 3).joinToString(" ") { "${it.actor ?: it.sourceApp}: ${it.text?.take(160)}" }.ifBlank { "Nothing in the ledger about that." }
            is Command.Pause -> { VoiceState.manualPauseUntil = System.currentTimeMillis() + 3_600_000L; "Paused for an hour." }
            is Command.Resume -> { VoiceState.manualPauseUntil = 0; "Listening again." }
            is Command.Undo -> {
                val exec = Brain.executor ?: return "Nothing to undo."
                val last = ledger.recent(200).firstOrNull { it.kind == EventKind.ACTION && it.structured["state"] == "done" } ?: return "Nothing to undo."
                "Undone: ${exec.undo(last).structured["state"]}."
            }
            is Command.Tell -> {
                val exec = Brain.executor ?: return "Not ready."
                val entities = Brain.entities ?: return "Not ready."
                val person = entities.people(500).firstOrNull { it.displayName?.equals(c.person, ignoreCase = true) == true }
                    ?: return "I don't know who ${c.person} is."
                val phone = person.identities.firstOrNull { it.startsWith("tel:") }?.removePrefix("tel:") ?: return "I have no number for ${c.person}."
                val p = Proposal("voice-" + System.currentTimeMillis(), Actions.SEND_MESSAGE, phone, mapOf("thread_id" to "sms:$phone", "text" to c.message), reason = "spoken command")
                val v = PolicyEngine(Brain.policyProfile).decide(p, PolicyContext(System.currentTimeMillis(), ZonedDateTime.now().hour, firstContact = person.userMessages == 0L))
                val rec = exec.apply(p, v)
                "${rec.structured["state"]}: ${v.reasons.joinToString()}"
            }
            is Command.Reply, is Command.Cancel, is Command.Reschedule -> "That comes with the next phase."
            is Command.Instruction -> {
                ledger.append(buddy.ledger.Event(
                    buddy.ledger.EventId.of(System.currentTimeMillis(), "buddy", "instruction", c.text), System.currentTimeMillis(), "buddy", "instruction",
                    EventKind.MEMORY_NOTE, "me", null, c.text, mapOf("kind" to "standing_instruction", "confidence" to "1.00"), buddy.ledger.Trust.USER,
                ))
                "Noted."
            }
            is Command.Unknown -> "I didn't understand that."
        }
    }
}

/** Shared state between the voice surface and the audio gate. */
object VoiceState {
    @Volatile
    var manualPauseUntil: Long = 0
}
