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
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.ToolUseBlockParam
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
/** Tools whose result is the screen they leave (a `screen` straight after one of them is answered from it). */
private val SCREEN_RESULTS = setOf("open", "act", "screen", "email")
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

/**
 * The request was replaced before it did anything: voice mode sent the recogniser's last partial words at the end of
 * speech, and its final words differed. The exchange leaves no trace in the conversation.
 */
class Superseded : CancellationException("Superseded by the final words.")

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

    /**
     * [spoken]: the request was spoken and the reply will be read aloud; marked [Voice] for the prompt's short-reply rule.
     * [gate]: the request was sent before its words were final (see [Superseded]); it's awaited before the first tool
     * runs or the reply is returned, and false drops the exchange.
     */
    suspend fun send(text: String, spoken: Boolean = false, gate: (suspend () -> Boolean)? = null): String {
        val start = messages.size
        // The local time rides on each message (not the system prompt, which must stay byte-stable for the cache), so
        // date-relative requests don't spend a call on `device get`.
        val now = nowStamp()
        val mark = if (spoken) " [Voice]" else ""
        messages += MessageParam.builder().role(MessageParam.Role.USER).content("[Now: $now]$mark $text").build()
        transcript.log(JSONObject().put("user", text).put("now", now).apply { if (spoken) put("voice", true) })
        this.gate = gate
        try {
            return loop(start, spoken)
        } catch (e: Superseded) {
            while (messages.size > start) messages.removeAt(messages.lastIndex)
            openCalls = emptyList()
            transcript.log(JSONObject().put("superseded", true))
            throw e
        } catch (e: CancellationException) {
            closeOpenToolCalls("Interrupted by the user before this ran.")
            throw e
        } finally {
            this.gate = null
        }
    }

    private fun nowStamp() = java.text.SimpleDateFormat("EEE dd MMM yyyy HH:mm zzz", java.util.Locale.US).format(java.util.Date())

    /**
     * A spoken request done on the phone without the model ([LocalCommands]): it joins the conversation as if the model
     * had made the [tool] call and replied [reply], so a follow-up ("make it ten minutes") has it.
     */
    fun addLocal(text: String, tool: String, args: JSONObject?, result: String?, reply: String) {
        val now = nowStamp()
        messages += MessageParam.builder().role(MessageParam.Role.USER).content("[Now: $now] [Voice] $text").build()
        transcript.log(JSONObject().put("user", text).put("now", now).put("voice", true).put("local", true))
        if (args != null && result != null) {
            val id = "toolu_local_${System.currentTimeMillis()}"
            val use = ToolUseBlockParam.builder().id(id).name(tool)
                .input(JsonValue.from(toJava(args)).convert(ToolUseBlockParam.Input::class.java)!!).build()
            messages += MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(listOf(ContentBlockParam.ofToolUse(use))).build()
            val done = ToolResultBlockParam.builder().toolUseId(id).content(result).build()
            messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(listOf(ContentBlockParam.ofToolResult(done))).build()
            transcript.log(JSONObject().put("tool", tool).put("args", args).put("result", result).put("local", true))
        }
        messages += MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(reply).build()
        transcript.log(JSONObject().put("context_reply", reply).put("local", true))
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

    @Volatile
    private var gate: (suspend () -> Boolean)? = null

    /** Waits for [gate] once: throws [Superseded] if the words changed. */
    private suspend fun passGate() {
        val g = gate ?: return
        gate = null
        if (!g()) throw Superseded()
    }

    /**
     * [spoken]: [VOICE_EFFORT] and no thinking, so the first step starts sooner (the user is waiting to hear the
     * answer). Measured on Haiku for "Open Gmail and show me the last email": 1.7 s and 85 output tokens without
     * thinking, 2.4 s and 335 with adaptive thinking (which also added a needless screen read).
     */
    private suspend fun loop(start: Int, spoken: Boolean): String {
        val effort = if (spoken) VOICE_EFFORT else config.effort
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
                .apply {
                    if (spoken) thinking(ThinkingConfigDisabled.builder().build())
                    else thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                }
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
                    .put("headers_ms", headersMs)
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

            // Nothing has run yet: a request sent before its words were final stops here if they changed.
            passGate()
            if (stop == StopReason.PAUSE_TURN) return@repeat
            if (stop == StopReason.MAX_TOKENS) return reply.joinToString("\n") + "\n[Stopped: hit the output token limit.]"

            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (calls.isEmpty()) return reply.joinToString("\n")
            openCalls = calls
            var previous: String? = null
            val results = calls.map { call ->
                val result = runTool(call, previous)
                previous = call.name().takeIf { it in SCREEN_RESULTS && result.isError().orElse(false) != true }
                ContentBlockParam.ofToolResult(result)
            }
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
        client.messages().createStreaming(params).also { headersMs = SystemClock.elapsedRealtime() - started }.use { response ->
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
    /** Milliseconds until the response's headers came (connection, upload and the server's queue, before any token). */
    @Volatile
    private var headersMs = -1L

    /**
     * [previous]: the call just before this one in the same response, if it returned the screen (open, act, screen,
     * email). A screen read right after it would return the same screen, so it's answered from that one.
     */
    private suspend fun runTool(call: ToolUseBlock, previous: String? = null): ToolResultBlockParam {
        val args = toJsonObject(call._input())
        val name = call.name()
        val started = SystemClock.elapsedRealtime()
        if (name == "screen" && previous != null && !args.optBoolean("screenshot")) {
            val same = "Same screen as the $previous result just above (nothing ran in between): use its element numbers. " +
                "Read the screen again only after something changes."
            transcript.log(JSONObject().put("tool", name).put("args", args).put("result", same).put("skipped", true).put("ms", 0))
            return ToolResultBlockParam.builder().toolUseId(call.id()).content(same).build()
        }
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

/**
 * Does once, without the network, what a process's first request would: the SDK's JSON models are introspected and
 * linked on first use, which took ~3.5 s on the RAVOZ (a cold first call 3.9 s, the next 0.37 s, with DNS at 13 ms and
 * a TLS handshake at 144 ms). Serializes a request like [Agent]'s and folds a stream of sample events into a message.
 */
internal fun warmUpSdk(config: AgentConfig) {
    val oneHour = CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()
    val params = MessageCreateParams.builder()
        .model(config.model)
        .maxTokens(16000L)
        .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(config.system).cacheControl(oneHour).build()))
        .cacheControl(oneHour)
        .tools(config.tools.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
        .messages(listOf(MessageParam.builder().role(MessageParam.Role.USER).content("warm-up").build()))
        .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        .build()
    val mapper = com.anthropic.core.jsonMapper()
    mapper.writeValueAsString(params._body())
    val accumulator = MessageAccumulator.create()
    SAMPLE_EVENTS.forEach { accumulator.accumulate(mapper.readValue(it, com.anthropic.models.messages.RawMessageStreamEvent::class.java)) }
    val message = accumulator.message()
    message.toParam()
    toJsonObject(message)
}

private val SAMPLE_EVENTS = listOf(
    """{"type":"message_start","message":{"id":"msg_0","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hm."}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"c2ln"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hi."}}""",
    """{"type":"content_block_stop","index":1}""",
    """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_0","name":"screen","input":{}}}""",
    """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
    """{"type":"content_block_stop","index":2}""",
    """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":5}}""",
    """{"type":"message_stop"}""",
)

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
