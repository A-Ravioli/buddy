package buddy.cognition

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * The brief, as the model produces it and as the surface renders it. Plain Java-style
 * classes with no-arg constructors and Jackson descriptions so the SDK can derive the
 * JSON schema for structured output.
 */
class Brief {
    @JsonPropertyDescription("Items that should interrupt the person now. Usually empty.")
    var urgent: List<BriefItem> = emptyList()

    @JsonPropertyDescription("Decisions only the person can make, each with options and a recommendation.")
    var decisions: List<Decision> = emptyList()

    @JsonPropertyDescription("What buddy handled without the person, as short lines with counts.")
    var done: List<String> = emptyList()

    @JsonPropertyDescription("Next commitments, deliveries, travel, in time order.")
    var upcoming: List<BriefItem> = emptyList()

    @JsonPropertyDescription("One line about buddy itself, or empty.")
    var health: String = ""

    @JsonPropertyDescription("The whole brief as spoken text, under one minute.")
    var spoken: String = ""

    override fun toString(): String = "Brief(urgent=${urgent.size}, decisions=${decisions.size}, done=${done.size}, upcoming=${upcoming.size})"
}

class BriefItem {
    @JsonPropertyDescription("One sentence.")
    var summary: String = ""

    @JsonPropertyDescription("Ids of the events this item is about.")
    var eventIds: List<String> = emptyList()
}

class Decision {
    @JsonPropertyDescription("The question, in one sentence, naming who it involves.")
    var question: String = ""

    @JsonPropertyDescription("Two to four options, short.")
    var options: List<String> = emptyList()

    @JsonPropertyDescription("Which option buddy recommends and why, in one sentence.")
    var recommendation: String = ""

    @JsonPropertyDescription("Ids of the events this decision is about.")
    var eventIds: List<String> = emptyList()
}
