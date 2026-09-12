package buddy.triage

import buddy.ledger.Event
import buddy.ledger.EventKind
import buddy.ledger.Trust

/**
 * The rule-based triage baseline. Coarse and conservative: when unsure it escalates
 * rather than drops, because a missed item is the expensive error (docs/00-vision.md,
 * principle 6). Every rule adds a reason so the timeline can explain the decision and
 * the replay harness can attribute errors.
 *
 * Ordering matters: the earlier rules are the ones with the highest cost of being
 * wrong (emergency, close contacts, muted), then the cheap certain wins (noise, codes),
 * then content heuristics.
 */
class RuleTriage : Triage {

    override fun triage(event: Event, profile: TriageProfile): TriageDecision {
        val reasons = ArrayList<String>()
        val text = event.text ?: ""
        val lower = text.lowercase()
        val actor = event.actor?.lowercase()
        val extracted = LinkedHashMap(event.structured).apply { putAll(Extractors.extract(text)) }

        fun decide(k: TriageClass, urgent: Boolean = false, confidence: Double = 0.7) =
            TriageDecision(event.id, k, urgent, reasons, extracted, confidence)

        // Buddy's own records and device facts are never triaged for the user.
        if (event.trust == Trust.SYSTEM || event.kind in setOf(EventKind.ACTION, EventKind.MEMORY_NOTE, EventKind.CORRECTION)) {
            reasons += "own_record"
            return decide(TriageClass.FILE, confidence = 0.99)
        }
        if (event.kind in setOf(EventKind.LOCATION, EventKind.DEVICE_STATE, EventKind.SCREEN_STATE)) {
            reasons += "sensor"
            return decide(TriageClass.FILE, confidence = 0.99)
        }

        // The user's own words are context, not tasks.
        if (event.trust == Trust.USER) {
            reasons += "from_user"
            return decide(TriageClass.FILE, confidence = 0.95)
        }

        // 1. Emergency actors and urgent keywords interrupt.
        if (actor != null && actor in profile.emergencyActors.lower()) {
            reasons += "emergency_actor"
            return decide(TriageClass.ESCALATE, urgent = true, confidence = 0.95)
        }
        if (URGENT_RE.containsMatchIn(lower) || profile.urgentKeywords.any { wordRe(it).containsMatchIn(lower) }) {
            reasons += "urgent_keyword"
            return decide(TriageClass.ESCALATE, urgent = true, confidence = 0.6)
        }

        // 2. Close contacts are never handled for the user.
        if (actor != null && actor in profile.closeActors.lower()) {
            reasons += "close_actor"
            return decide(TriageClass.ESCALATE, confidence = 0.9)
        }

        // 3. Muted actors and packages, and known-noise packages, are dropped.
        if ((actor != null && actor in profile.mutedActors.lower()) || event.sourceApp in profile.mutedPackages) {
            reasons += "muted"
            return decide(TriageClass.DROP, confidence = 0.95)
        }
        if (event.sourceApp in profile.noisePackages) {
            reasons += "noise_package"
            return decide(TriageClass.DROP, confidence = 0.9)
        }

        // 4. One-time codes: file with the code extracted. Never forwarded, never escalated.
        if (extracted.containsKey("otp")) {
            reasons += "otp"
            return decide(TriageClass.FILE, confidence = 0.9)
        }

        // 5. Calls are handled by the calls playbook; missed calls from known people escalate.
        if (event.kind == EventKind.CALL) {
            val outcome = event.structured["outcome"]
            return if (outcome == "missed" || outcome == "voicemail") {
                reasons += "missed_call"
                decide(TriageClass.ESCALATE, confidence = 0.7)
            } else {
                reasons += "call_record"
                decide(TriageClass.FILE, confidence = 0.9)
            }
        }

        // 6. Calendar changes: cancellations and invitations need a decision; the rest is filed.
        if (event.kind == EventKind.CALENDAR_CHANGE) {
            return when {
                event.structured["status"] == "canceled" -> { reasons += "calendar_cancelled"; decide(TriageClass.ESCALATE, confidence = 0.8) }
                event.structured["my_response"] == "invited" -> { reasons += "calendar_invite"; decide(TriageClass.ACT_LATER, confidence = 0.8) }
                else -> { reasons += "calendar_update"; decide(TriageClass.FILE, confidence = 0.85) }
            }
        }

        // 7. Marketing and automated noise: unsubscribe language, promo phrasing, no-reply senders.
        if (MARKETING_MARKERS.any { lower.contains(it) } || (actor != null && NOREPLY.any { actor.contains(it) })) {
            reasons += "marketing"
            // Receipts and deliveries hide in automated mail; keep them as FILE, not DROP.
            return if (extracted.containsKey("amount") || extracted.containsKey("tracking") || RECEIPT_MARKERS.any { lower.contains(it) }) {
                reasons += "receipt_or_delivery"
                decide(TriageClass.FILE, confidence = 0.8)
            } else {
                decide(TriageClass.DROP, confidence = 0.75)
            }
        }

        // 8. Transactions, deliveries, and bookings are informational unless they ask something.
        if (extracted.containsKey("tracking") || DELIVERY_MARKERS.any { lower.contains(it) }) {
            reasons += "delivery"
            return if (ASKS.any { lower.contains(it) }) decide(TriageClass.ACT_LATER, confidence = 0.7) else decide(TriageClass.FILE, confidence = 0.8)
        }
        if (extracted.containsKey("amount") && event.kind == EventKind.NOTIFICATION) {
            reasons += "transaction"
            return decide(TriageClass.FILE, confidence = 0.8)
        }

        // 9. Direct questions and requests in messages need an answer.
        if (event.kind == EventKind.MESSAGE) {
            val asks = text.contains('?') || ASKS.any { lower.contains(it) }
            val timeSensitive = TIME_MARKERS.any { lower.contains(it) } || extracted["date_hint"] in setOf("today", "tonight", "now")
            return when {
                asks && timeSensitive -> { reasons += "question_time_sensitive"; decide(TriageClass.ACT_NOW, confidence = 0.65) }
                asks -> { reasons += "question"; decide(TriageClass.ACT_LATER, confidence = 0.65) }
                event.structured["group"] == "true" -> { reasons += "group_chatter"; decide(TriageClass.FILE, confidence = 0.6) }
                else -> { reasons += "message_no_ask"; decide(TriageClass.FILE, confidence = 0.55) }
            }
        }

        // 10. Anything else that reached here is a notification we cannot place. File it;
        // the planning cycle sees filed items in aggregate and can promote them.
        reasons += "unclassified_notification"
        return decide(TriageClass.FILE, confidence = 0.5)
    }

    private fun Set<String>.lower(): Set<String> = mapTo(HashSet()) { it.lowercase() }

    companion object {
        val URGENT_KEYWORDS = setOf("emergency", "urgent", "asap", "call me now", "hospital", "accident", "911", "999", "112")

        /** Whole-word match, so "999" inside a tracking number is not an emergency. */
        fun wordRe(word: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word.lowercase()) + "(?![\\p{L}\\p{N}])")
        private val URGENT_RE = Regex(URGENT_KEYWORDS.joinToString("|") { wordRe(it).pattern })
        val MARKETING_MARKERS = setOf(
            "unsubscribe", "% off", "percent off", "limited time", "flash sale", "deal of the day", "don't miss",
            "exclusive offer", "special offer", "promo code", "view in browser", "no longer wish to receive",
        )
        val NOREPLY = setOf("noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "newsletter", "marketing@")
        val RECEIPT_MARKERS = setOf("receipt", "invoice", "your order", "order confirmation", "payment received", "statement")
        val DELIVERY_MARKERS = setOf("out for delivery", "has been delivered", "will be delivered", "delivery attempt", "we missed you", "your parcel", "your package", "shipped")
        val ASKS = setOf("can you", "could you", "would you", "please", "let me know", "confirm", "rsvp", "are you", "do you", "reschedule", "reply")
        val TIME_MARKERS = setOf("today", "tonight", "now", "right now", "in an hour", "this morning", "this afternoon", "this evening", "before ", "by ")
    }
}
