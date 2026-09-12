package buddy.audio

/**
 * Bystander controls (docs/03-autonomy-and-trust.md, "Regulatory and social", and
 * decision 6 in docs/06-open-questions.md). Three mechanisms, all deterministic:
 *
 * - the stop phrase, honoured from any voice, pauses capture for an hour;
 * - the earbud gesture does the same;
 * - a cue fires whenever tier 3 starts, so the user always knows.
 */
class StopPhraseDetector(
    private val phrases: List<String> = DEFAULT_PHRASES,
) {
    /** True if the utterance is a stop request, whoever said it. */
    fun isStop(utterance: String): Boolean {
        val t = utterance.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return phrases.any { t == it || t.endsWith(" $it") || t.startsWith("$it ") || t.contains(" $it ") }
    }

    companion object {
        val DEFAULT_PHRASES = listOf(
            "buddy stop listening", "stop listening", "buddy stop recording", "stop recording",
            "buddy pause", "stop listening please", "not now buddy",
        )
    }
}

/** What the phone does to tell the user transcription started. */
interface CueEmitter {
    fun transcriptionStarted(reason: String)
    fun transcriptionStopped(reason: String)
}

/**
 * Wraps the gate with the bystander controls and the cue. Tracks whether tier 3 is
 * currently running so the cue fires on transitions only.
 */
class BystanderControls(
    private val gate: AudioGate,
    private val cue: CueEmitter,
    private val stop: StopPhraseDetector = StopPhraseDetector(),
    private val pauseMillis: Long = 60 * 60 * 1000,
) {
    @Volatile
    var pausedUntil: Long = 0
        private set

    @Volatile
    var transcribing: Boolean = false
        private set

    /** Called for every utterance the pipeline hears, from anyone. */
    fun onUtterance(text: String, nowTs: Long): Boolean {
        if (!stop.isStop(text)) return false
        pause(nowTs, "stop_phrase")
        return true
    }

    /** The earbud gesture or the voice command. */
    fun pause(nowTs: Long, reason: String) {
        pausedUntil = nowTs + pauseMillis
        if (transcribing) {
            transcribing = false
            cue.transcriptionStopped(reason)
        }
    }

    fun resume() {
        pausedUntil = 0
    }

    /** The gate decision with the pause applied, and the cue on transitions. */
    fun decide(s: Situation, day: String): GateDecision {
        val paused = s.ts < pausedUntil
        val d = gate.decide(s.copy(manualPause = s.manualPause || paused), day)
        if (d.transcribe && !transcribing) {
            transcribing = true
            cue.transcriptionStarted(d.reason)
        } else if (!d.transcribe && transcribing) {
            transcribing = false
            cue.transcriptionStopped(d.reason)
        }
        return d
    }
}
