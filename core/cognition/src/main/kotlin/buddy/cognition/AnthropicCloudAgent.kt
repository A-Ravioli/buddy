package buddy.cognition

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam

/**
 * [CloudAgent] over the Claude API with a manual tool loop. Tools are strict: the
 * model's arguments always validate against the schema, so a connector never sees a
 * malformed payload. The tool list is rendered in a fixed order so the cached prefix
 * holds across calls.
 */
class AnthropicCloudAgent(
    private val client: AnthropicClient = AnthropicOkHttpClient.fromEnv(),
    private val model: String = AnthropicCloudModel.DEFAULT_MODEL,
    private val effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
    private val maxTokens: Long = 16_000,
) : CloudAgent {

    override fun run(
        system: List<String>,
        user: String,
        operator: String?,
        tools: List<ToolDef>,
        maxTurns: Int,
        onTool: (String, Map<String, Any?>) -> String,
    ): AgentResult {
        val messages = ArrayList<MessageParam>()
        messages.add(MessageParam.builder().role(MessageParam.Role.USER).content(user).build())
        if (operator != null) messages.add(MessageParam.builder().role(MessageParam.Role.SYSTEM).content(operator).build())

        var turns = 0
        var calls = 0
        var inTok = 0L; var outTok = 0L; var cacheTok = 0L
        val sdkTools = tools.sortedBy { it.name }.map(::toSdkTool)

        while (turns < maxTurns) {
            turns++
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .systemOfTextBlockParams(
                    system.mapIndexed { i, text ->
                        val b = TextBlockParam.builder().text(text)
                        if (i == system.lastIndex) b.cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                        b.build()
                    },
                )
                .apply { sdkTools.forEach { addTool(it) } }
                .messages(messages)
                .build()

            val response = try {
                client.messages().create(params)
            } catch (e: AnthropicServiceException) {
                return AgentResult("error", detail = "${e.statusCode()}: ${e.message}", turns = turns, toolCalls = calls, inputTokens = inTok, outputTokens = outTok, cacheReadTokens = cacheTok)
            } catch (e: Exception) {
                return AgentResult("error", detail = e.toString(), turns = turns, toolCalls = calls)
            }
            val usage = response.usage()
            inTok += usage.inputTokens(); outTok += usage.outputTokens(); cacheTok += usage.cacheReadInputTokens().orElse(0L)

            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                return AgentResult("refusal", detail = response.stopDetails().map { it.explanation().orElse("") }.orElse(""), turns = turns, toolCalls = calls, inputTokens = inTok, outputTokens = outTok, cacheReadTokens = cacheTok)
            }

            // Echo the assistant turn back verbatim (thinking blocks included) before the results.
            messages.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(response.content().map { it.toParam() }).build())

            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (toolUses.isEmpty() || response.stopReason().orElse(null) != StopReason.TOOL_USE) {
                val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n")
                return AgentResult("ok", finalText = text, turns = turns, toolCalls = calls, inputTokens = inTok, outputTokens = outTok, cacheReadTokens = cacheTok)
            }

            // Execute every call, return every result in one user message.
            val results = toolUses.map { tu ->
                calls++
                val input = try { toKotlin(tu._input()) as? Map<String, Any?> ?: emptyMap() } catch (t: Throwable) { emptyMap() }
                val out = try { onTool(tu.name(), input) } catch (t: Throwable) { "error: ${t.message}" }
                ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).build())
            }
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build())
        }
        return AgentResult("max_turns", turns = turns, toolCalls = calls, inputTokens = inTok, outputTokens = outTok, cacheReadTokens = cacheTok)
    }

    private fun toSdkTool(t: ToolDef): Tool {
        val props = Tool.InputSchema.Properties.builder()
        for ((k, v) in t.properties) props.putAdditionalProperty(k, JsonValue.from(v))
        return Tool.builder()
            .name(t.name)
            .description(t.description)
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(t.required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    companion object {
        /** JsonValue to plain Kotlin: strings, numbers, booleans, lists, maps, null. */
        fun toKotlin(v: JsonValue): Any? = v.accept(object : JsonValue.Visitor<Any?> {
            override fun visitNull(): Any? = null
            override fun visitMissing(): Any? = null
            override fun visitBoolean(value: Boolean): Any = value
            override fun visitNumber(value: Number): Any = value
            override fun visitString(value: String): Any = value
            override fun visitArray(values: List<JsonValue>): Any = values.map(::toKotlin)
            override fun visitObject(values: Map<String, JsonValue>): Any = values.mapValues { toKotlin(it.value) }
        })
    }
}
