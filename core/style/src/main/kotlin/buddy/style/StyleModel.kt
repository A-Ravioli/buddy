package buddy.style

import kotlin.math.abs
import kotlin.math.exp

/**
 * How the user writes to one relationship class, as measurable features. Learned from
 * sent messages; used to score drafts. Deliberately simple: this is a guardrail
 * against drafts that sound wrong, not a generator.
 */
data class StyleFeatures(
    val samples: Int,
    val medianLength: Double,
    val emojiRate: Double,
    val exclamationRate: Double,
    val questionRate: Double,
    val lowercaseStartRate: Double,
    val contractionRate: Double,
    val politenessRate: Double,
    val commonSignOff: String?,
    val commonGreeting: String?,
    val signOffs: Map<String, Int> = emptyMap(),
)

object StyleModel {
    private val emoji = Regex("[\\p{So}\\p{Cn}]|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]")
    private val contractions = Regex("(?i)\\b\\w+'(s|t|re|ve|ll|d|m)\\b")
    private val politeness = Regex("(?i)\\b(please|thanks|thank you|cheers|sorry|appreciate)\\b")
    // A sign-off is the last word (or a trailing "x" or "xx") on one of the last two lines,
    // so both "Thanks,\nSam" and "on my way x" are recognised.
    private val signOffRe = Regex("(?i)(?:^|\\s)(thanks|thank you|cheers|best|regards|kind regards|best regards|love|talk soon|ta|x+)\\s*[,!.]?\\s*$")
    private val greetingRe = Regex("(?i)^\\s*(hi|hey|hello|dear|yo|morning|afternoon|evening)\\b")

    fun learn(sent: List<String>): StyleFeatures {
        val msgs = sent.map { it.trim() }.filter { it.isNotEmpty() }
        if (msgs.isEmpty()) return StyleFeatures(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null)
        val lengths = msgs.map { it.length }.sorted()
        val median = if (lengths.size % 2 == 1) lengths[lengths.size / 2].toDouble() else (lengths[lengths.size / 2 - 1] + lengths[lengths.size / 2]) / 2.0
        val signOffs = msgs.mapNotNull(::signOff).groupingBy { it }.eachCount()
        val greetings = msgs.mapNotNull { greetingRe.find(it)?.groupValues?.get(1)?.lowercase() }.groupingBy { it }.eachCount()
        return StyleFeatures(
            samples = msgs.size,
            medianLength = median,
            emojiRate = msgs.count { emoji.containsMatchIn(it) }.toDouble() / msgs.size,
            exclamationRate = msgs.count { it.contains('!') }.toDouble() / msgs.size,
            questionRate = msgs.count { it.contains('?') }.toDouble() / msgs.size,
            lowercaseStartRate = msgs.count { it.first().isLowerCase() }.toDouble() / msgs.size,
            contractionRate = msgs.count { contractions.containsMatchIn(it) }.toDouble() / msgs.size,
            politenessRate = msgs.count { politeness.containsMatchIn(it) }.toDouble() / msgs.size,
            commonSignOff = signOffs.maxByOrNull { it.value }?.takeIf { it.value * 2 >= msgs.size }?.key,
            commonGreeting = greetings.maxByOrNull { it.value }?.takeIf { it.value * 2 >= msgs.size }?.key,
            signOffs = signOffs,
        )
    }

    /**
     * How much a draft looks like the learned style, 0 to 1. Each feature contributes
     * a penalty for distance from the learned rate; length is compared on a log scale.
     * With no samples the score is 0.5: no evidence either way.
     */
    fun score(draft: String, f: StyleFeatures): Double {
        if (f.samples == 0) return 0.5
        val d = draft.trim()
        if (d.isEmpty()) return 0.0
        var penalty = 0.0
        penalty += rateDistance(if (emoji.containsMatchIn(d)) 1.0 else 0.0, f.emojiRate) * 1.5
        penalty += rateDistance(if (d.contains('!')) 1.0 else 0.0, f.exclamationRate)
        penalty += rateDistance(if (d.first().isLowerCase()) 1.0 else 0.0, f.lowercaseStartRate)
        penalty += rateDistance(if (contractions.containsMatchIn(d)) 1.0 else 0.0, f.contractionRate)
        penalty += rateDistance(if (politeness.containsMatchIn(d)) 1.0 else 0.0, f.politenessRate) * 0.5
        val lengthRatio = abs(Math.log((d.length + 1.0) / (f.medianLength + 1.0)))
        penalty += (lengthRatio / 1.5).coerceAtMost(2.0)
        f.commonSignOff?.let { so -> if (signOff(d) != so) penalty += 0.75 }
        return exp(-penalty)
    }

    /** The sign-off of a message, looking at its last two non-empty lines. */
    fun signOff(message: String): String? =
        message.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(2).reversed()
            .firstNotNullOfOrNull { line -> signOffRe.find(line)?.groupValues?.get(1)?.lowercase() }

    /** A draft is acceptable when it scores at least this against the class it targets. */
    const val ACCEPT_THRESHOLD = 0.35

    /** Only penalise a draft for a feature the user clearly does or clearly does not use. */
    private fun rateDistance(draftHas: Double, learnedRate: Double): Double = when {
        learnedRate >= 0.8 && draftHas == 0.0 -> 1.0
        learnedRate <= 0.2 && draftHas == 1.0 -> 1.0
        else -> 0.0
    }
}

/** Style per relationship class ("close", "friend", "colleague", "service", "unknown"). */
class StyleBook(private val byClass: Map<String, StyleFeatures>) {
    fun features(relationship: String): StyleFeatures =
        byClass[relationship] ?: byClass["unknown"] ?: StyleModel.learn(emptyList())

    fun score(draft: String, relationship: String): Double = StyleModel.score(draft, features(relationship))

    fun accepts(draft: String, relationship: String): Boolean = score(draft, relationship) >= StyleModel.ACCEPT_THRESHOLD

    companion object {
        fun learn(sentByClass: Map<String, List<String>>): StyleBook = StyleBook(sentByClass.mapValues { StyleModel.learn(it.value) })
    }
}
