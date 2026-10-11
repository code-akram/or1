package com.or1.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.or1.agent.AgentHub

/**
 * What or1 knows: the memory notes it keeps across chats. Tap one (or ⋯ Edit) to edit it in place; a blank note is
 * deleted. Deleting takes effect at once (the agent may read memory any time) and the Undo pill puts the note back.
 */
@Composable
fun MemoryView(onClose: () -> Unit) {
    val notes by AgentHub.memoryNotes.collectAsState()
    // By text, not index: the agent can add or remove notes while this is open.
    var editing by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    var deleted by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun delete(note: String) {
        val index = AgentHub.memoryNotes.value.indexOf(note).takeIf { it >= 0 } ?: return
        val text = AgentHub.deleteNote(index) ?: return
        deleted = index to text
        pending = PendingDelete(text, "Note deleted") {}
    }
    Column(Modifier.fillMaxSize().background(Palette.Ink0).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Back, "Back", onClose, tint = Palette.Text, background = Palette.Ink3)
            Spacer(Modifier.width(12.dp))
            Text("What or1 knows", style = Type.Label)
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (notes.isEmpty()) {
                    item(key = "empty") {
                        Text("Nothing yet. Tell or1 something to remember, and it shows up here.", style = Type.Caption)
                    }
                }
                // Keyed by text so rows keep their place as others go; an exact duplicate (an edit can make one) by index.
                itemsIndexed(notes, key = { i, note -> if (notes.indexOf(note) == i) note else "$i:$note" }) { _, note ->
                    if (editing == note) {
                        InlineRename(
                            initial = note, placeholder = "Blank deletes the note", style = Type.Body, multiLine = true,
                            onDone = { text ->
                                editing = null
                                val at = AgentHub.memoryNotes.value.indexOf(note)
                                when {
                                    at < 0 || text == note -> {}
                                    text.isEmpty() -> delete(note)
                                    else -> AgentHub.editNote(at, text)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().itemMotion(this),
                        )
                    } else {
                        NoteRow(note, onEdit = { editing = note }, onDelete = { delete(note) }, modifier = Modifier.itemMotion(this))
                    }
                }
            }
            UndoBar(
                pending,
                onUndo = { deleted?.let { (i, text) -> AgentHub.restoreNote(i, text) }; pending = null; deleted = null },
                onExpired = { pending = null; deleted = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun NoteRow(note: String, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Ink2)
            .border(1.dp, Palette.Line, RoundedCornerShape(16.dp)).clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(note, style = Type.Body, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        RowMenu(
            listOf(
                MenuAction("Edit", Icons.Rename, onClick = onEdit),
                MenuAction("Delete", Icons.Delete, destructive = true, onClick = onDelete),
            ),
            label = "Note options",
        )
    }
}
