package com.or1.agent.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub
import com.or1.agent.ChatItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject

/** A past conversation, from files/transcripts/<yyyyMMdd-HHmmss>.jsonl. `renamed`: the title is the user's. */
data class PastChat(val file: File, val title: String, val time: String, val renamed: Boolean = false)

object History {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val shown = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)

    /** Titles the user gave chats, by transcript file name (otherwise a chat is titled by its first message). */
    private fun titlesFile(context: Context) = File(context.filesDir, "chat-titles.json")

    private fun titles(context: Context): JSONObject =
        runCatching { JSONObject(titlesFile(context).readText()) }.getOrDefault(JSONObject())

    fun list(context: Context, limit: Int = 30): List<PastChat> {
        val titles = titles(context)
        return File(context.filesDir, "transcripts").listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.name }
            .take(limit)
            .mapNotNull { file ->
                val first = file.useLines { lines ->
                    lines.firstNotNullOfOrNull { runCatching { JSONObject(it).optString("user").ifEmpty { null } }.getOrNull() }
                } ?: return@mapNotNull null
                val time = runCatching { shown.format(stamp.parse(file.nameWithoutExtension)!!) }.getOrDefault("")
                val given = titles.optString(file.name).ifBlank { null }
                PastChat(file, given ?: first, time, renamed = given != null)
            }
    }

    /** A blank title goes back to the first message. */
    @Synchronized
    fun rename(context: Context, chat: PastChat, title: String) {
        val titles = titles(context)
        if (title.isBlank()) titles.remove(chat.file.name) else titles.put(chat.file.name, title.trim())
        titlesFile(context).writeText(titles.toString())
    }

    /** Deletes the transcript; the chat in progress is closed first, so it doesn't write the file back. */
    @Synchronized
    fun delete(context: Context, chat: PastChat) {
        if (AgentHub.currentChatFile() == chat.file) AgentHub.newConversation()
        chat.file.delete()
        val titles = titles(context)
        if (titles.remove(chat.file.name) != null) titlesFile(context).writeText(titles.toString())
    }

    /** The user messages and the agent's text replies, for read-only viewing. */
    fun load(chat: PastChat): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        var id = 0L
        chat.file.forEachLine { line ->
            val record = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            record.optString("user").takeIf { it.isNotEmpty() }?.let { items += ChatItem.User(id++, it) }
            // A routine run carried into the chat (AgentHub.continueInChat).
            record.optString("context_reply").takeIf { it.isNotEmpty() }?.let { items += ChatItem.Reply(id++, it, 0.0, 0.0) }
            val content = record.optJSONObject("response")?.optJSONArray("content") ?: return@forEachLine
            val text = (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }.trim()
            if (text.isNotEmpty()) items += ChatItem.Reply(id++, text, record.optDouble("cost", 0.0), 0.0)
        }
        return items
    }
}

@Composable
fun HistoryView(chat: PastChat, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val items = remember(chat) { History.load(chat) }
    Column(Modifier.fillMaxSize().background(Palette.Background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Back, "Back", onClose, tint = Palette.Text, background = Palette.Surface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.title, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chat.time, style = Type.Caption)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item)
                    is ChatItem.Reply -> ReplyBlock(item.copy(cost = 0.0))
                    else -> {}
                }
            }
        }
    }
}
