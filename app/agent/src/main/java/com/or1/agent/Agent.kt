package com.or1.agent

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.or1.agent.tools.Tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_STEPS = 30

/** System prompt, tool schemas and model settings, exported from the host prototype (proto/scripts/export_agent_json.py). */
class AgentConfig(val model: String, val effort: String, val system: String, val tools: List<Tool>) {
    companion object {
        fun load(context: Context): AgentConfig {
            val root = JSONObject(context.assets.open("agent.json").bufferedReader().readText())
            val schemas = root.getJSONArray("tools")
            val tools = (0 until schemas.length()).map { JsonValue.from(toJava(schemas.get(it))).convert(Tool::class.java)!! }
            return AgentConfig(root.getString("model"), root.getString("effort"), root.getString("system"), tools)
        }
    }
}

/** USD per million tokens: input, output, cache read, 1-hour cache write (this account caches automatically for 1h). */
private val PRICES = mapOf(
    "claude-haiku-5-5" to doubleArrayOf(0.10, 0.50, 0.01, 0.20),
    "claude-sonnet-5-5" to doubleArrayOf(2.00, 10.00, 0.20, 4.00),
    "claude-opus-5-5" to doubleArrayOf(4.00, 20.00, 0.20, 8.00),
)
private const val HAIKU_LONG_PROMPT = 100_000

class UsageTally {
    var requests = 0; private set
    var cost = 0.0; private set

    fun add(model: String, usage: com.anthropic.models.messages.Usage): Double {
        val read = usage.cacheReadInputTokens().orElse(0L)
        val write = usage.cacheCreationInputTokens().orElse(0L)
        var p = PRICES[model] ?: PRICES.getValue("claude-opus-5-5")
        if (model == "claude-haiku-5-5" && usage.inputTokens() + read + write > HAIKU_LONG_PROMPT) {
            p = DoubleArray(4) { p[it] * 5 }
        }
        val c = (usage.inputTokens() * p[0] + usage.outputTokens() * p[1] + read * p[2] + write * p[3]) / 1e6
        requests++
        cost += c
        return c
    }
}

/** Callbacks the UI layer implements: progress display and the approval gate. */
interface AgentHooks {
    suspend fun approve(tool: String, args: JSONObject): Boolean
    fun thinking(text: String) {}
    fun text(text: String) {}
    fun toolCall(tool: String, args: JSONObject) {}
    fun toolResult(tool: String, summary: String, isError: Boolean) {}
}

/** The agent loop: one user message in, tool calls against the phone, one reply out. Port of proto/src/or1/agent.py. */
class Agent(
    private val client: AnthropicClient,
    private val config: AgentConfig,
    private val tools: Tools,
    private val hooks: AgentHooks,
    private val transcript: Transcript,
) {
    private val messages = mutableListOf<MessageParam>()
    private var openCalls: List<ToolUseBlock> = emptyList()
    val usage = UsageTally()

    suspend fun send(text: String): String {
        val start = messages.size
        messages += MessageParam.builder().role(MessageParam.Role.USER).content(text).build()
        transcript.log(JSONObject().put("user", text))
        try {
            return loop(start)
        } catch (e: CancellationException) {
            closeOpenToolCalls("Interrupted by the user before this ran.")
            throw e
        }
    }

    fun reset() {
        messages.clear()
        openCalls = emptyList()
    }

    private suspend fun loop(start: Int): String {
        // No cache_control: this account applies automatic 1-hour caching server-side, and adding our own
        // breakpoints conflicts with it (TTL mismatch, then the 4-breakpoint limit).
        repeat(MAX_STEPS) {
            val params = MessageCreateParams.builder()
                .model(config.model)
                .maxTokens(16000L)
                .system(config.system)
                .tools(config.tools.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                .messages(messages.toList())
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(effort(config.effort)).build())
                .build()
            val response = withContext(Dispatchers.IO) { client.messages().create(params) }
            val cost = usage.add(config.model, response.usage())
            transcript.log(
                JSONObject()
                    .put("stop", response.stopReason().map { it.toString() }.orElse(""))
                    .put("cost", cost)
                    .put("response", toJsonObject(response))
            )

            val stop = response.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                // Drop this whole exchange so the history stays valid for the next message.
                while (messages.size > start) messages.removeAt(messages.lastIndex)
                val category = response.stopDetails().flatMap { it.category() }.map { " ($it)" }.orElse("")
                return "[The model declined this request$category.]"
            }

            messages += response.toParam()
            val reply = mutableListOf<String>()
            for (block in response.content()) {
                block.thinking().ifPresent { if (it.thinking().isNotBlank()) hooks.thinking(it.thinking()) }
                block.text().ifPresent { hooks.text(it.text()); reply += it.text() }
            }

            if (stop == StopReason.PAUSE_TURN) return@repeat
            if (stop == StopReason.MAX_TOKENS) return reply.joinToString("\n") + "\n[Stopped: hit the output token limit.]"

            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (calls.isEmpty()) return reply.joinToString("\n")
            openCalls = calls
            val results = calls.map { ContentBlockParam.ofToolResult(runTool(it)) }
            messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
            openCalls = emptyList()
        }
        return "[Stopped after $MAX_STEPS steps without finishing.]"
    }

    private suspend fun runTool(call: ToolUseBlock): ToolResultBlockParam {
        val args = toJsonObject(call._input())
        val name = call.name()
        hooks.toolCall(name, args)
        val result = ToolResultBlockParam.builder().toolUseId(call.id())
        if (tools.needsApproval(name, args) && !hooks.approve(name, args)) {
            val declined = "The user declined this action."
            hooks.toolResult(name, declined, false)
            transcript.log(JSONObject().put("tool", name).put("args", args).put("declined", true))
            return result.content(declined).build()
        }
        val output = try {
            tools.run(name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tool failures go back to the model, not up the stack.
            val error = "Error: ${e.message ?: e.javaClass.simpleName}"
            hooks.toolResult(name, error, true)
            transcript.log(JSONObject().put("tool", name).put("args", args).put("result", error).put("is_error", true))
            return result.content(error).isError(true).build()
        }
        hooks.toolResult(name, output.text, false)
        transcript.log(JSONObject().put("tool", name).put("args", args).put("result", output.text).put("image", output.jpegBase64 != null))
        if (output.jpegBase64 == null) return result.content(output.text).build()
        return result.contentOfBlocks(
            listOf(
                ToolResultBlockParam.Content.Block.ofText(output.text),
                ToolResultBlockParam.Content.Block.ofImage(
                    Base64ImageSource.builder().data(output.jpegBase64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                ),
            )
        ).build()
    }

    /** After an interrupt, answer any unanswered tool calls so the next request is valid. */
    private fun closeOpenToolCalls(note: String) {
        if (openCalls.isEmpty()) return
        val results = openCalls.map {
            ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(it.id()).content(note).build())
        }
        messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
        openCalls = emptyList()
    }

    private fun effort(level: String) = when (level) {
        "low" -> OutputConfig.Effort.LOW
        "high" -> OutputConfig.Effort.HIGH
        else -> OutputConfig.Effort.MEDIUM
    }
}

/** org.json values -> plain Java maps/lists, so JsonValue.from can read them. */
private fun toJava(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { toJava(value.get(it)) }
    is JSONArray -> (0 until value.length()).map { toJava(value.get(it)) }
    JSONObject.NULL -> null
    else -> value
}

/** Any SDK object or JsonValue -> JSONObject, through the SDK's own Jackson mapper. */
internal fun toJsonObject(value: Any): JSONObject {
    val json = if (value is JsonValue) value else JsonValue.from(value)
    return JSONObject(json.convert(Map::class.java) as Map<*, *>)
}
