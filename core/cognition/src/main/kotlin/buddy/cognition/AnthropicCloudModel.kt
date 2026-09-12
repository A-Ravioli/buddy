package buddy.cognition

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredOutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive

/**
 * [CloudModel] over the Claude API.
 *
 * Request shape, from docs/01-architecture.md: the stable system blocks carry a cache
 * breakpoint with a one-hour TTL; the slice is the user turn; the operator instruction
 * is a mid-conversation system message so it carries operator authority and does not
 * disturb the cached prefix. Adaptive thinking, effort tuned per task class, and the
 * answer as structured output derived from the schema class.
 */
class AnthropicCloudModel(
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv(),
    private val model: String = DEFAULT_MODEL,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
    private val maxTokens: Long = 16_000,
) : CloudModel {

    override fun <T : Any> structured(system: List<String>, user: String, operator: String?, schema: Class<T>): CloudResult<T> {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .systemOfTextBlockParams(
                system.mapIndexed { i, text ->
                    val b = TextBlockParam.builder().text(text)
                    // One breakpoint at the end of the stable prefix.
                    if (i == system.lastIndex) {
                        b.cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                    }
                    b.build()
                },
            )
            .addUserMessage(user)
        if (operator != null) {
            // Operator channel. On models without mid-conversation system messages the API
            // returns a 400; the caller then retries with the instruction folded into the
            // user turn, marked as operator text.
            builder.addMessage(MessageParam.builder().role(MessageParam.Role.SYSTEM).content(operator).build())
        }
        val params = builder
            .outputConfig(StructuredOutputConfig.builder<T>().format(schema).effort(effort).build())
            .build()

        return try {
            val response = client.messages().create(params)
            val usage = response.usage()
            val base = CloudResult<T>(
                value = null,
                status = "ok",
                inputTokens = usage.inputTokens(),
                outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
                cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
            )
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                val detail = response.stopDetails()
                    .map { "${it.category().orElse(null)}: ${it.explanation().orElse("")}" }
                    .orElse("refusal")
                return base.copy(status = "refusal", detail = detail)
            }
            val value: T? = response.content().firstOrNull { it.isText() }?.asText()?.text()
            if (value == null) base.copy(status = "error", detail = "no structured content") else base.copy(value = value)
        } catch (e: AnthropicServiceException) {
            if (operator != null && e.statusCode() == 400 && (e.message ?: "").contains("role", ignoreCase = true)) {
                // Fall back: operator instruction as clearly marked text in the user turn.
                return structured(system, "$user\n\n<operator-instruction>\n$operator\n</operator-instruction>", null, schema)
            }
            CloudResult(null, "error", "${e.statusCode()} ${e.errorType().map { it.toString() }.orElse("")}: ${e.message}")
        } catch (e: Exception) {
            CloudResult(null, "error", e.toString())
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
