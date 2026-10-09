package com.or1.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.or1.agent.tools.HiddenDisplay
import com.or1.agent.tools.Tools
import com.or1.agent.ui.StepLabels
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** What the chat shows. */
sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem
    data class Reply(override val id: Long, val text: String, val cost: Double, val seconds: Double) : ChatItem
    data class Thinking(override val id: Long, val text: String) : ChatItem
    /** `label` is plain language ("Set media volume to 3"); `detail` is the raw call. */
    data class ToolCall(override val id: Long, val label: String, val detail: String, val result: String? = null, val isError: Boolean = false) : ChatItem
    data class Approval(
        override val id: Long, val label: String, val detail: String,
        val decision: CompletableDeferred<Decision>, val state: Decision? = null,
    ) : ChatItem
    data class Info(override val id: Long, val text: String) : ChatItem
}

enum class Decision { ALLOW, DENY, ALWAYS }

/** Process-wide owner of the agent: runs one request at a time and publishes chat items for the UI. */
object AgentHub {
    private const val TAG = "or1"
    private val ids = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val alwaysAllowed = mutableSetOf<String>()

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _totalCost = MutableStateFlow(0.0)
    val totalCost: StateFlow<Double> = _totalCost
    private val _effort = MutableStateFlow("medium")
    /** Reasoning effort for the next requests: low, medium or high. */
    val effort: StateFlow<String> = _effort
    lateinit var model: String
        private set

    private lateinit var appContext: Context
    private lateinit var tools: Tools
    private var agent: Agent? = null
    private var agentConfig: AgentConfig? = null
    private var current: Job? = null
    private var autoApprove = false
    /** Set when the user takes over the hidden app, so the chat doesn't jump back in front of it. */
    @Volatile
    private var suppressReturn = false

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        HiddenDisplay.init(appContext)
        HiddenDisplay.onLeakMoved = { returnToComposer() }
        tools = Tools(appContext)
        val config = AgentConfig.load(appContext)
        model = config.model
        _effort.value = config.effort
    }

    private fun agent(): Agent {
        agent?.let { return it }
        val key = File(appContext.filesDir, "anthropic-key").takeIf { it.exists() }?.readText()?.trim()
            ?: throw IllegalStateException("No API key on the phone: run provision.sh.")
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        val transcript = Transcript(File(appContext.filesDir, "transcripts"))
        val config = AgentConfig.load(appContext).also { it.effort = _effort.value }
        return Agent(client, config, tools, hooks, transcript).also { agent = it; agentConfig = config }
    }

    /** Queue a user message. `requestId` makes the reply readable from replies.jsonl (debug driver). */
    fun submit(text: String, requestId: String? = null, autoApprove: Boolean = false, fresh: Boolean = false) {
        current = scope.launch {
            lock.withLock {
                this@AgentHub.autoApprove = autoApprove
                suppressReturn = false
                if (fresh) newConversation()
                add(ChatItem.User(ids.incrementAndGet(), text))
                _busy.value = true
                val started = System.currentTimeMillis()
                var cost = 0.0
                var requests = 0
                val reply = try {
                    val a = agent()
                    val before = a.usage.cost
                    val beforeRequests = a.usage.requests
                    try {
                        a.send(text)
                    } finally {
                        cost = a.usage.cost - before
                        requests = a.usage.requests - beforeRequests
                        _totalCost.value = a.usage.cost
                    }
                } catch (e: CancellationException) {
                    "[Stopped.]"
                } catch (e: AnthropicServiceException) {
                    Log.e(TAG, "API error", e)
                    "[API error ${e.statusCode()}: ${e.message}]"
                } catch (e: Exception) {
                    Log.e(TAG, "agent failed", e)
                    "[Error: ${e.message ?: e.javaClass.simpleName}]"
                }
                add(ChatItem.Reply(ids.incrementAndGet(), reply, cost, (System.currentTimeMillis() - started) / 1000.0))
                _busy.value = false
                requestId?.let { recordReply(it, reply, cost, requests, (System.currentTimeMillis() - started) / 1000.0) }
                if (!suppressReturn) {
                    returnToComposer()
                    HiddenDisplay.park()
                }
            }
        }
    }

    fun stop() {
        current?.cancel()
    }

    fun setEffort(level: String) {
        _effort.value = level
        agentConfig?.effort = level
    }

    /** The user is taking over the hidden app (touching it in the viewer, or bringing it to the front): stop the agent. */
    fun takeOver() {
        suppressReturn = true
        current?.cancel()
    }

    fun newConversation() {
        agent?.reset()
        _items.value = emptyList()
        alwaysAllowed.clear()
    }

    fun decide(item: ChatItem.Approval, decision: Decision) {
        item.decision.complete(decision)
    }

    private val hooks = object : AgentHooks {
        override suspend fun approve(tool: String, args: JSONObject): Boolean {
            val key = "$tool:${args.optString("action")}"
            if (autoApprove || key in alwaysAllowed) return true
            val item = ChatItem.Approval(ids.incrementAndGet(), label(tool, args), tools.describe(tool, args), CompletableDeferred())
            add(item)
            val decision = item.decision.await()
            if (decision == Decision.ALWAYS) alwaysAllowed += key
            replace(item.id) { (it as ChatItem.Approval).copy(state = decision) }
            return decision != Decision.DENY
        }

        override fun thinking(text: String) = add(ChatItem.Thinking(ids.incrementAndGet(), text.trim()))

        override fun toolCall(tool: String, args: JSONObject) =
            add(ChatItem.ToolCall(ids.incrementAndGet(), label(tool, args), tools.describe(tool, args)))

        override fun toolResult(tool: String, summary: String, isError: Boolean) {
            val pending = _items.value.lastOrNull { it is ChatItem.ToolCall && it.result == null } ?: return
            replace(pending.id) { (it as ChatItem.ToolCall).copy(result = summary, isError = isError) }
        }
    }

    private fun label(tool: String, args: JSONObject) = StepLabels.label(tool, args, tools::elementLabel)

    private fun add(item: ChatItem) = _items.update { it + item }

    private fun replace(id: Long, transform: (ChatItem) -> ChatItem) =
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun recordReply(id: String, reply: String, cost: Double, requests: Int, seconds: Double) {
        val record = JSONObject().put("id", id).put("reply", reply).put("cost", cost).put("requests", requests).put("seconds", seconds)
        File(appContext.filesDir, "replies.jsonl").appendText(record.toString() + "\n")
    }

    /**
     * The composer is the phone's home: come back to it once a request is done. Apps run on the hidden display, so
     * this mostly gives the chat back input focus (the hidden display takes it whenever the agent acts there).
     */
    private fun returnToComposer() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}
