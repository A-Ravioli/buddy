package buddy.cognition

/** A tool the model may call. [schema] is a JSON-schema properties map; [required] its required keys. */
data class ToolDef(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>>,
    val required: List<String>,
)

data class AgentResult(
    /** "ok", "refusal", "error", "max_turns". */
    val status: String,
    val finalText: String = "",
    val turns: Int = 0,
    val toolCalls: Int = 0,
    val detail: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
)

/**
 * A tool-use loop. The harness owns the loop: every tool call comes back through
 * [onTool], whose string result is what the model sees next. Nothing runs unless the
 * harness runs it, which is what lets the policy engine sit between the model and
 * the world.
 */
interface CloudAgent {
    fun run(
        system: List<String>,
        user: String,
        operator: String?,
        tools: List<ToolDef>,
        maxTurns: Int,
        onTool: (name: String, input: Map<String, Any?>) -> String,
    ): AgentResult
}
