package buddy.voice

/**
 * What the user meant. The recogniser gives words; this decides which of buddy's
 * handful of verbs they are. Anything else is a question for cognition.
 */
sealed class Command {
    /** "tell Sam I'll be ten minutes late" */
    data class Tell(val person: String, val message: String) : Command()
    /** "what did the plumber say about the boiler", "when is my dentist appointment" */
    data class Recall(val query: String) : Command()
    /** "cancel Thursday", "cancel dinner with Sam" */
    data class Cancel(val what: String) : Command()
    /** "move the dentist to Friday", "reschedule the delivery to Thursday" */
    data class Reschedule(val what: String, val to: String?) : Command()
    /** "reply yes", "reply that works" (to the item in the brief) */
    data class Reply(val text: String) : Command()
    /** "what's the brief", "read the brief", "anything for me" */
    data object Brief : Command()
    /** "stop listening", "buddy stop listening", "pause" */
    data object Pause : Command()
    /** "start listening again", "resume" */
    data object Resume : Command()
    /** "undo that", "undo" */
    data object Undo : Command()
    /** Standing instructions: "don't reply to my mum for me", "always accept invites from Alex" */
    data class Instruction(val text: String) : Command()
    data class Unknown(val text: String) : Command()
}

object CommandParser {
    private val wake = Regex("""^(?:hey |ok |okay )?buddy[,]?\s*""", RegexOption.IGNORE_CASE)
    private val tell = Regex("""^(?:tell|text|message|let)\s+(?<person>[a-z][a-z' -]{0,40}?)\s+(?:know\s+)?(?:that\s+)?(?<msg>.+)$""", RegexOption.IGNORE_CASE)
    private val recall = Regex("""^(?:what did|what's|what is|what was|when is|when's|when was|where is|where's|did|do i have|remind me what|remind me when)\b(?<q>.+)$""", RegexOption.IGNORE_CASE)
    private val cancel = Regex("""^cancel\s+(?<what>.+)$""", RegexOption.IGNORE_CASE)
    private val reschedule = Regex("""^(?:move|reschedule|push|shift)\s+(?<what>.+?)(?:\s+(?:to|until|till)\s+(?<to>.+))?$""", RegexOption.IGNORE_CASE)
    private val reply = Regex("""^(?:reply|say|answer|respond)\s+(?:with\s+)?(?:that\s+)?(?<text>.+)$""", RegexOption.IGNORE_CASE)
    private val brief = Regex("""^(?:(?:what's|what is|read|give me|play)\s+(?:the\s+|my\s+)?brief|anything for me|what do i need to do|catch me up)\b""", RegexOption.IGNORE_CASE)
    private val pause = Regex("""^(?:stop listening|pause(?: listening)?|go quiet|stop recording)$""", RegexOption.IGNORE_CASE)
    private val resume = Regex("""^(?:start listening(?: again)?|resume(?: listening)?|listen again)$""", RegexOption.IGNORE_CASE)
    private val undo = Regex("""^undo(?: that| the last one)?$""", RegexOption.IGNORE_CASE)
    private val instruction = Regex("""^(?:from now on|always|never|don't|do not|stop)\b""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Command {
        val text = raw.trim().replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (text.isEmpty()) return Command.Unknown(raw)
        pause.matchEntire(text)?.let { return Command.Pause }
        resume.matchEntire(text)?.let { return Command.Resume }
        undo.matchEntire(text)?.let { return Command.Undo }
        brief.find(text)?.let { return Command.Brief }
        tell.matchEntire(text)?.let { m ->
            val person = m.groups["person"]!!.value.trim()
            if (person.lowercase() !in setOf("me", "them")) return Command.Tell(person, m.groups["msg"]!!.value.trim())
        }
        instruction.find(text)?.let { return Command.Instruction(text) }
        cancel.matchEntire(text)?.let { return Command.Cancel(it.groups["what"]!!.value.trim()) }
        reschedule.matchEntire(text)?.let { return Command.Reschedule(it.groups["what"]!!.value.trim(), it.groups["to"]?.value?.trim()) }
        reply.matchEntire(text)?.let { return Command.Reply(it.groups["text"]!!.value.trim()) }
        recall.matchEntire(text)?.let { return Command.Recall(text) }
        if (text.endsWith("?") || raw.trim().endsWith("?")) return Command.Recall(text)
        return Command.Unknown(text)
    }
}
