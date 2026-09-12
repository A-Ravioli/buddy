package buddy.cognition

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StructuredOutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import java.util.Base64

/** The model's next move on a screen it is driving. */
class NextAction {
    @JsonPropertyDescription("One of: tap, type, scroll_down, scroll_up, back, done, give_up.")
    var kind: String = "give_up"

    @JsonPropertyDescription("For tap and type: x coordinate in pixels.")
    var x: Int = 0

    @JsonPropertyDescription("For tap and type: y coordinate in pixels.")
    var y: Int = 0

    @JsonPropertyDescription("For type: the text to enter.")
    var text: String = ""

    @JsonPropertyDescription("What you see and why this move, one sentence.")
    var reason: String = ""
}

/**
 * The screen-driving fallback (docs/01-architecture.md, "Actuation", path 4): a
 * screenshot in, the next move out, one step at a time. Always runs under the
 * most conservative autonomy level; the harness owns the loop and stops it.
 */
class VisionFallback(private val client: AnthropicClient, private val model: String = AnthropicCloudModel.DEFAULT_MODEL) {

    fun next(goal: String, screenshotPng: ByteArray, history: List<String>): NextAction? {
        val image = ImageBlockParam.builder()
            .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_PNG).data(Base64.getEncoder().encodeToString(screenshotPng)).build())
            .build()
        val text = TextBlockParam.builder().text(
            "Goal: $goal\nMoves so far:\n" + history.joinToString("\n").ifBlank { "none" } +
                "\n\nDecide the single next move. Prefer done or give_up over guessing. Never enter codes, passwords, or payment details.",
        ).build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2_000)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(Prompts.CONSTITUTION).build()))
            .addUserMessageOfBlockParams(listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(text)))
            .outputConfig(StructuredOutputConfig.builder<NextAction>().format(NextAction::class.java).effort(OutputConfig.Effort.MEDIUM).build())
            .build()
        return try {
            client.messages().create(params).content().firstOrNull { it.isText() }?.asText()?.text()
        } catch (e: Exception) {
            null
        }
    }
}
