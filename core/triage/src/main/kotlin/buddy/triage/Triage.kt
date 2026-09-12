package buddy.triage

import buddy.ledger.Event

/**
 * The five answers triage can give about an event. Defined in docs/01-architecture.md.
 *
 * DROP never reaches the user or the cloud. FILE is recorded and extracted but needs
 * nothing. ACT_NOW and ACT_LATER go to cognition, now or at the next planning cycle.
 * ESCALATE goes to the user in the next brief, or immediately if [TriageDecision.urgent].
 */
enum class TriageClass { DROP, FILE, ACT_NOW, ACT_LATER, ESCALATE }

data class TriageDecision(
    val eventId: String,
    val klass: TriageClass,
    /** Interrupt the user now rather than wait for the brief. Only meaningful with ESCALATE. */
    val urgent: Boolean = false,
    /** Short machine-readable reasons, for the timeline and the eval harness. */
    val reasons: List<String> = emptyList(),
    /** Fields extracted from the content: amount, otp, tracking, due, and so on. */
    val extracted: Map<String, String> = emptyMap(),
    /** Confidence in [0, 1]. The rule baseline is coarse; a model fills this in properly. */
    val confidence: Double = 0.5,
)

/**
 * A triage implementation. Must be pure: same event and profile in, same decision out,
 * so that the replay harness can score it and the eval is meaningful.
 */
interface Triage {
    fun triage(event: Event, profile: TriageProfile): TriageDecision
}

/**
 * The slice of the user's profile that triage needs. Kept small on purpose: triage
 * runs on every event and must not need the whole profile loaded.
 */
data class TriageProfile(
    /** Actors (phone numbers, emails, names) whose messages always escalate urgently. */
    val emergencyActors: Set<String> = emptySet(),
    /** Actors the user considers close: never dropped, never auto-handled, always escalated. */
    val closeActors: Set<String> = emptySet(),
    /** Actors and packages the user has muted: always dropped. */
    val mutedActors: Set<String> = emptySet(),
    val mutedPackages: Set<String> = emptySet(),
    /** Packages known to be pure noise (system, media, games). */
    val noisePackages: Set<String> = DEFAULT_NOISE_PACKAGES,
    /** Extra keywords the user wants treated as urgent. */
    val urgentKeywords: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT_NOISE_PACKAGES: Set<String> = setOf(
            "com.android.systemui",
            "com.android.vending",
            "com.google.android.gms",
            "android",
        )
    }
}
