package buddy.cognition

/**
 * The one thing cognition needs from the cloud: a structured answer to a prompt made of
 * a stable system prefix, a per-request user turn, and an optional operator
 * instruction. Implemented by [AnthropicCloudModel]; tests use a fake.
 */
interface CloudModel {
    /**
     * @param system stable blocks, cached across calls; must be byte-identical between requests
     * @param user the per-request content (the context slice)
     * @param operator a per-request operator instruction, sent on the operator channel where
     *   the model supports it, never as user text
     * @param schema the class to fill from the model's structured output
     */
    fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T>
}

data class CloudResult<T>(
    val value: T?,
    /** "ok", "refusal", "error". */
    val status: String,
    val detail: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)
