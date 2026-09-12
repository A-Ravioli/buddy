package buddy.audio

/**
 * What the pipeline knows about the moment, from tiers 0 to 2 and from the rest of
 * the phone. Built by the Android side; consumed by [AudioGate].
 */
data class Situation(
    val ts: Long,
    /** Tier 1: speech is present in the last window. */
    val speechPresent: Boolean,
    /** Tier 2: the enrolled user is one of the speakers in the last window. */
    val userSpeaking: Boolean,
    /** Tier 1 scene: "conversation", "tv", "car", "silence", "unknown". */
    val scene: String = "unknown",
    /** A phone or VoIP call is in progress (captured through the dialer path, not this gate). */
    val inCall: Boolean = false,
    /** A calendar meeting is in progress right now. */
    val inMeeting: Boolean = false,
    /** Calendar title of the current meeting, for the off-limits keyword check. */
    val meetingTitle: String? = null,
    /** Category of the current place from on-device map data, e.g. "medical", "legal". */
    val placeCategory: String? = null,
    /** The user marked the current place off-limits. */
    val atMarkedPlace: Boolean = false,
    /** Manual pause is active (stop phrase or earbud gesture). */
    val manualPause: Boolean = false,
    /** The hotword fired in the last window: a command may follow. */
    val hotword: Boolean = false,
)

/** Decision 11 in docs/06-open-questions.md: deterministic off-limits rules only. */
class OffLimitsRules(
    private val placeCategories: Set<String> = DEFAULT_PLACE_CATEGORIES,
    private val calendarKeywords: Set<String> = DEFAULT_CALENDAR_KEYWORDS,
) {
    /** Returns the reason the situation is off limits, or null if it is not. */
    fun reason(s: Situation): String? {
        if (s.manualPause) return "manual_pause"
        if (s.atMarkedPlace) return "marked_place"
        s.placeCategory?.lowercase()?.let { if (it in placeCategories) return "place:$it" }
        s.meetingTitle?.lowercase()?.let { title ->
            calendarKeywords.firstOrNull { title.contains(it) }?.let { return "calendar:$it" }
        }
        return null
    }

    companion object {
        val DEFAULT_PLACE_CATEGORIES = setOf("medical", "hospital", "clinic", "pharmacy", "legal", "courthouse", "religious", "childcare", "school", "counselling", "therapy")
        val DEFAULT_CALENDAR_KEYWORDS = setOf("doctor", "gp ", "dentist", "therapy", "therapist", "counsel", "lawyer", "solicitor", "attorney", "hr ", "1:1", "one on one", "interview", "medical", "hospital", "clinic")
    }
}

/**
 * Decision 7: three hours a day, calls and meetings take priority when the budget runs
 * low. Tracks minutes used per local day; the caller supplies the day key so the gate
 * stays pure.
 */
class TranscriptionBudget(
    private val dailyMinutes: Int = 180,
    /** Below this many minutes left, only calls and meetings may transcribe. */
    private val reserveMinutes: Int = 30,
) {
    private var dayKey: String? = null
    private var usedSeconds: Long = 0

    fun usedMinutes(day: String): Int {
        roll(day)
        return (usedSeconds / 60).toInt()
    }

    fun remainingMinutes(day: String): Int = (dailyMinutes - usedMinutes(day)).coerceAtLeast(0)

    /** Whether a [priority] request may transcribe today. Priority requests are calls and meetings. */
    fun allows(day: String, priority: Boolean): Boolean {
        val left = remainingMinutes(day)
        return if (priority) left > 0 else left > reserveMinutes
    }

    fun record(day: String, seconds: Long) {
        roll(day)
        usedSeconds += seconds
    }

    private fun roll(day: String) {
        if (dayKey != day) {
            dayKey = day
            usedSeconds = 0
        }
    }
}

/** Why the gate decided what it decided. Recorded in the ledger as device state. */
data class GateDecision(val transcribe: Boolean, val reason: String, val priority: Boolean = false)

/**
 * Whether tier 3 may run right now. Pure given its inputs. The rules, in order:
 *
 * 1. Off-limits situations never transcribe, whatever else is true.
 * 2. Calls are transcribed through the dialer path, not the ambient gate.
 * 3. Hotword always transcribes (a command may follow), for a short window.
 * 4. Meetings and conversations the user is part of transcribe, within budget.
 * 5. Speech the user is not part of (TV, other people's conversation) does not.
 */
class AudioGate(
    private val offLimits: OffLimitsRules = OffLimitsRules(),
    private val budget: TranscriptionBudget = TranscriptionBudget(),
    /** Set only if the user has turned on transcribing conversations they are not part of. */
    private val transcribeBystanders: Boolean = false,
) {
    fun decide(s: Situation, day: String): GateDecision {
        offLimits.reason(s)?.let { return GateDecision(false, "off_limits:$it") }
        if (s.inCall) return GateDecision(false, "in_call_dialer_path")
        if (s.hotword) return GateDecision(true, "hotword", priority = true)
        if (!s.speechPresent) return GateDecision(false, "no_speech")

        val priority = s.inMeeting
        val participant = s.userSpeaking || s.inMeeting
        if (!participant && !transcribeBystanders) return GateDecision(false, "not_participant")
        if (s.scene == "tv" && !s.userSpeaking) return GateDecision(false, "scene_tv")
        if (!budget.allows(day, priority)) return GateDecision(false, if (priority) "budget_exhausted" else "budget_reserve")
        return GateDecision(true, if (priority) "meeting" else "conversation", priority)
    }
}
