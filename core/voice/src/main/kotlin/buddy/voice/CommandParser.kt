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
    /** "torch on", "turn the flashlight off", "torch". Null is a toggle. */
    data class Torch(val on: Boolean?) : Command()
    /** "wifi", "connect to the wifi", "get me online". Quick settings is gone; buddy is it. */
    data object Network : Command()
    /** "open Monzo", "show me the camera". The launcher is gone; buddy is the way in. */
    data class Open(val app: String) : Command()
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
    private val torchTrailing = Regex("""^(?:turn|switch|put)?\s*(?:the\s+)?(?:torch|flashlight)(?:\s+(?<state>on|off))?$""", RegexOption.IGNORE_CASE)
    private val torchLeading = Regex("""^(?:turn|switch|put)\s+(?<state>on|off)\s+(?:the\s+)?(?:torch|flashlight)$""", RegexOption.IGNORE_CASE)
    private val network = Regex("""^(?:(?:turn on|switch on|connect(?:\s+me)?(?:\s+to)?|join|show me)\s+)?(?:the\s+|a\s+|my\s+)?(?:wi-?fi|network|internet)(?:\s+list|\s+picker)?$""", RegexOption.IGNORE_CASE)
    private val online = Regex("""^(?:(?:get|put)\s+me\s+|go\s+)?(?:back\s+)?online$""", RegexOption.IGNORE_CASE)
    private val open = Regex("""^(?:open|launch|start|show me)\s+(?<app>.{2,40})$""", RegexOption.IGNORE_CASE)
    private val instruction = Regex("""^(?:from now on|always|never|don't|do not|stop)\b""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Command {
        val text = raw.trim().replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (text.isEmpty()) return Command.Unknown(raw)
        pause.matchEntire(text)?.let { return Command.Pause }
        resume.matchEntire(text)?.let { return Command.Resume }
        undo.matchEntire(text)?.let { return Command.Undo }
        // Before the standing-instruction catch-all, which owns anything starting "stop".
        torchTrailing.matchEntire(text)?.let { return Command.Torch(state(it.groups["state"]?.value)) }
        torchLeading.matchEntire(text)?.let { return Command.Torch(state(it.groups["state"]?.value)) }
        network.matchEntire(text)?.let { return Command.Network }
        online.matchEntire(text)?.let { return Command.Network }
        // After the network, so "show me the wifi" is buddy's picker and not a hunt for an
        // app called wifi, and before the standing instructions, which own "stop".
        open.matchEntire(text)?.let { return Command.Open(it.groups["app"]!!.value.trim()) }
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

    /** "on", "off", or nothing said, which means the other one. */
    private fun state(word: String?): Boolean? = when (word?.lowercase()) {
        "on" -> true
        "off" -> false
        else -> null
    }
}
