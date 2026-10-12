package com.or1.agent

import android.content.Context
import android.os.SystemClock
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.or1.agent.tools.ToolOutput
import com.or1.agent.tools.Tools
import com.or1.agent.ui.ResultCards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_STEPS = 30
/** Model effort for spoken requests (typed ones use agent.json's). */
private const val VOICE_EFFORT = "low"

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

/** Callbacks the UI layer implements: progress display. */
interface AgentHooks {
    fun thinking(text: String) {}
    /** The text of the response being streamed so far (replaced, not appended, on each call). */
    fun partial(text: String) {}
    fun text(text: String) {}
    fun toolCall(tool: String, args: JSONObject) {}
    fun toolResult(tool: String, summary: String, isError: Boolean) {}
    /** After a call succeeded (right after [toolResult]), with the full output: its undo data makes the effect. */
    fun toolDone(tool: String, args: JSONObject, output: ToolOutput) {}
}

/** The agent loop: one user message in, tool calls against the phone, one reply out. Port of proto/src/or1/agent.py. */
class Agent(
    private val client: AnthropicClient,
    private val config: AgentConfig,
    private val tools: Tools,
    private val hooks: AgentHooks,
    private val transcript: Transcript,
    /** Tell the model when a result shows as a card ([ResultCards.cardHint]); off for routines, whose reply is a notification. */
    private val cardHints: Boolean = true,
) {
    private val messages = mutableListOf<MessageParam>()
    private var openCalls: List<ToolUseBlock> = emptyList()
    val usage = UsageTally()
    /** This conversation's transcript (past chats list it by file). */
    val transcriptFile: java.io.File get() = transcript.file

    /** [spoken]: the request was spoken and the reply will be read aloud; marked [Voice] for the prompt's short-reply rule. */
    suspend fun send(text: String, spoken: Boolean = false): String {
        val start = messages.size
        // The local time rides on each message (not the system prompt, which must stay byte-stable for the cache), so
        // date-relative requests don't spend a call on `device get`.
        val now = java.text.SimpleDateFormat("EEE dd MMM yyyy HH:mm zzz", java.util.Locale.US).format(java.util.Date())
        val mark = if (spoken) " [Voice]" else ""
        messages += MessageParam.builder().role(MessageParam.Role.USER).content("[Now: $now]$mark $text").build()
        transcript.log(JSONObject().put("user", text).put("now", now).apply { if (spoken) put("voice", true) })
        try {
            return loop(start, if (spoken) VOICE_EFFORT else config.effort)
        } catch (e: CancellationException) {
            closeOpenToolCalls("Interrupted by the user before this ran.")
            throw e
        }
    }

    fun reset() {
        messages.clear()
        openCalls = emptyList()
    }

    /** Adds an exchange that happened elsewhere (a routine run) as context, so the next message can follow up on it. */
    fun addContext(user: String, assistant: String) {
        messages += MessageParam.builder().role(MessageParam.Role.USER).content(user).build()
        messages += MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(assistant).build()
        transcript.log(JSONObject().put("user", user).put("context_reply", assistant))
    }

    /**
     * [effort]: spoken requests use [VOICE_EFFORT], so less reasoning before each step (the user is waiting to hear the
     * answer).
     */
    private suspend fun loop(start: Int, effort: String): String {
        // Prompt caching, 1-hour TTL: an explicit breakpoint after the static tools + system prompt, plus top-level
        // automatic caching for the growing conversation. Both 1h: a 1-hour entry can't follow a 5-minute one.
        // Calls within one request are seconds apart, but the next chat message or routine run often comes 5-60 min later.
        val oneHour = CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()
        repeat(MAX_STEPS) {
            val params = MessageCreateParams.builder()
                .model(config.model)
                .maxTokens(16000L)
                .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(config.system).cacheControl(oneHour).build()))
                .cacheControl(oneHour)
                .tools(config.tools.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
                .messages(messages.toList())
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(effort(effort)).build())
                .build()
            val started = SystemClock.elapsedRealtime()
            val response = stream(params)
            val cost = usage.add(config.model, response.usage())
            transcript.log(
                JSONObject()
                    .put("stop", response.stopReason().map { it.toString() }.orElse(""))
                    .put("cost", cost)
                    // Where a request's time goes: the whole call, and until its first streamed event.
                    .put("ms", SystemClock.elapsedRealtime() - started)
                    .put("first_ms", firstEventMs)
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

    /**
     * One request, streamed: text is passed to [AgentHooks.partial] as it arrives, and the events are folded into the
     * same [Message] a non-streaming call returns. Stop is checked on every event (a blocking call couldn't be stopped).
     */
    private suspend fun stream(params: MessageCreateParams): Message = withContext(Dispatchers.IO) {
        val accumulator = MessageAccumulator.create()
        val text = StringBuilder()
        val started = SystemClock.elapsedRealtime()
        firstEventMs = -1
        client.messages().createStreaming(params).use { response ->
            for (event in response.stream().iterator()) {
                coroutineContext.ensureActive()
                if (firstEventMs < 0) firstEventMs = SystemClock.elapsedRealtime() - started
                accumulator.accumulate(event)
                event.contentBlockStart().ifPresent { start ->
                    if (start.contentBlock().isText() && text.isNotEmpty()) text.append('\n')
                }
                event.contentBlockDelta().ifPresent { delta ->
                    delta.delta().text().ifPresent { hooks.partial(text.append(it.text()).toString()) }
                }
            }
        }
        accumulator.message()
    }

    /** Milliseconds from sending the last model call to its first streamed event (time to first token, roughly). */
    @Volatile
    private var firstEventMs = -1L

    private suspend fun runTool(call: ToolUseBlock): ToolResultBlockParam {
        val args = toJsonObject(call._input())
        val name = call.name()
        val started = SystemClock.elapsedRealtime()
        hooks.toolCall(name, args)
        val result = ToolResultBlockParam.builder().toolUseId(call.id())
        val output = try {
            tools.run(name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tool failures go back to the model, not up the stack.
            val error = "Error: ${e.message ?: e.javaClass.simpleName}"
            hooks.toolResult(name, error, true)
            transcript.log(JSONObject().put("tool", name).put("args", args).put("result", error).put("is_error", true).put("ms", SystemClock.elapsedRealtime() - started))
            return result.content(error).isError(true).build()
        }
        hooks.toolResult(name, output.text, false)
        hooks.toolDone(name, args, output)
        transcript.log(
            JSONObject().put("tool", name).put("args", args).put("result", output.text).put("image", output.jpegBase64 != null)
                .put("ms", SystemClock.elapsedRealtime() - started),
        )
        val hint = if (!cardHints) null else runCatching {
            ResultCards.cardHint(name, args.keys().asSequence().associateWith { args.optString(it) }, output.text)
        }.getOrNull()
        val text = output.text + hint.orEmpty()
        if (output.jpegBase64 == null) return result.content(text).build()
        return result.contentOfBlocks(
            listOf(
                ToolResultBlockParam.Content.Block.ofText(text),
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
