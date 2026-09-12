package buddy.money

import buddy.entities.Recurring
import buddy.ledger.Event
import buddy.policy.ActionSpec
import buddy.policy.BlastRadius
import buddy.policy.Domain
import buddy.policy.Proposal
import buddy.policy.Reversibility
import kotlin.math.abs
import kotlin.math.sqrt

/** The money domain's own action. Irreversible and external: the policy ceiling is ACT. */
object MoneyActions {
    val PAY_BILL = ActionSpec(
        "pay_bill", Domain.MONEY, Reversibility.IRREVERSIBLE, BlastRadius.EXTERNAL,
        "Pay a recurring bill to a known payee. Payload: payee, reference.",
    )
}

/** A transaction as the categoriser sees it. */
data class Transaction(
    val eventId: String,
    val ts: Long,
    val counterparty: String,
    val amount: Double,
    val currency: String,
    val text: String = "",
)

/** Merchant to category, by keyword rules plus the user's own overrides. */
class Categoriser(private val overrides: Map<String, String> = emptyMap()) {
    fun categorise(t: Transaction): String {
        overrides[t.counterparty.lowercase()]?.let { return it }
        val hay = (t.counterparty + " " + t.text).lowercase()
        for ((category, words) in RULES) if (words.any { hay.contains(it) }) return category
        return "other"
    }

    companion object {
        val RULES: List<Pair<String, List<String>>> = listOf(
            "groceries" to listOf("tesco", "sainsbury", "waitrose", "aldi", "lidl", "whole foods", "trader joe", "grocer", "supermarket", "co-op"),
            "eating_out" to listOf("cafe", "coffee", "restaurant", "pizza", "burger", "deliveroo", "uber eats", "just eat", "doordash", "pret", "starbucks"),
            "transport" to listOf("uber", "lyft", "tfl", "rail", "train", "bus", "petrol", "fuel", "shell", "bp ", "parking"),
            "housing" to listOf("rent", "mortgage", "landlord", "council tax", "letting"),
            "utilities" to listOf("electric", "energy", "gas", "water", "broadband", "internet", "vodafone", "ee ", "o2", "three", "verizon", "at&t"),
            "subscriptions" to listOf("netflix", "spotify", "apple.com", "google", "amazon prime", "subscription", "membership", "gym"),
            "health" to listOf("pharmacy", "boots", "chemist", "dentist", "doctor", "clinic", "hospital"),
            "shopping" to listOf("amazon", "ebay", "argos", "john lewis", "ikea", "zara", "uniqlo", "asos"),
            "travel" to listOf("airline", "airways", "ryanair", "easyjet", "hotel", "airbnb", "booking.com", "expedia"),
            "income" to listOf("salary", "payroll", "wages", "refund"),
        )
    }
}

/** Why a transaction stands out. Empty means nothing to say. */
data class Anomaly(val eventId: String, val flags: List<String>)

/**
 * Anomaly detection per counterparty (docs/04-domain-playbooks.md, "Money"). Pure over
 * the history it is given. Flags:
 * unknown_merchant, duplicate (same amount within a day), out_of_pattern (far above the
 * counterparty's history), foreign (not the home currency while not travelling), large.
 */
class AnomalyDetector(
    private val homeCurrency: String = "GBP",
    private val largeThreshold: Map<String, Double> = mapOf("GBP" to 200.0, "USD" to 250.0, "EUR" to 230.0),
) {
    fun check(t: Transaction, history: List<Transaction>, travelling: Boolean = false): Anomaly {
        val flags = ArrayList<String>()
        val same = history.filter { it.counterparty.equals(t.counterparty, ignoreCase = true) && it.eventId != t.eventId }
        if (same.isEmpty()) flags += "unknown_merchant"
        if (same.any { abs(it.amount - t.amount) < 0.005 && abs(it.ts - t.ts) < 86_400_000L }) flags += "duplicate"
        if (same.size >= 3) {
            val amounts = same.map { it.amount }
            val mean = amounts.average()
            val sd = sqrt(amounts.sumOf { (it - mean) * (it - mean) } / amounts.size)
            val median = amounts.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            if (t.amount > mean + 3 * sd + 0.01 && t.amount > 3 * median) flags += "out_of_pattern"
        }
        if (t.currency != homeCurrency && !travelling) flags += "foreign"
        largeThreshold[t.currency]?.let { if (t.amount > it) flags += "large" }
        return Anomaly(t.eventId, flags)
    }
}

/**
 * Turns recurring charges that are due into pay proposals. The policy engine does the
 * rest: unknown payee escalates, caps escalate, the level decides. This never sees a
 * card number; the payee is an identifier the connector resolves.
 */
object BillProposer {
    fun due(recurring: List<Recurring>, nowTs: Long, windowDays: Int = 3, sourceEvents: Map<String, String> = emptyMap()): List<Proposal> =
        recurring
            .filter { it.nextExpectedTs in nowTs..(nowTs + windowDays * 86_400_000L) }
            .map { r ->
                Proposal(
                    id = "bill-${r.counterparty.lowercase()}-${r.nextExpectedTs / 86_400_000L}",
                    spec = MoneyActions.PAY_BILL,
                    target = r.counterparty,
                    payload = mapOf("payee" to r.counterparty, "reference" to "recurring"),
                    amount = r.amount.toDoubleOrNull() ?: 0.0,
                    currency = r.currency.ifBlank { "GBP" },
                    reason = "${r.counterparty} is due (every ${"%.0f".format(r.medianIntervalDays)} days, ${r.occurrences} times so far)",
                    sourceEventIds = listOfNotNull(sourceEvents[r.counterparty]),
                )
            }

    /** Transactions from ledger events that carry an amount. */
    fun transactions(events: List<Event>): List<Transaction> = events.mapNotNull { e ->
        val amount = e.structured["amount"]?.toDoubleOrNull() ?: return@mapNotNull null
        val cp = e.structured["counterparty"] ?: e.actor ?: return@mapNotNull null
        Transaction(e.id, e.ts, cp, amount, e.structured["currency"] ?: "GBP", e.text ?: "")
    }
}
