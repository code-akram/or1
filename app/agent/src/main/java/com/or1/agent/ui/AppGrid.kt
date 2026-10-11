package com.or1.agent.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LauncherApp(val label: String, val icon: ImageBitmap, val intent: Intent)

/** The work screen's home: every launchable app, alphabetically. */
@Composable
fun AppGrid(onOpen: (Intent) -> Unit) {
    val context = LocalContext.current
    val apps by produceState(emptyList<LauncherApp>()) {
        value = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { info ->
                    LauncherApp(
                        info.loadLabel(pm).toString(),
                        info.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(info.activityInfo.packageName, info.activityInfo.name),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Background).padding(top = 48.dp)) {
        Wordmark(block = 6.dp, modifier = Modifier.padding(start = 28.dp, bottom = 28.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(apps, key = { it.intent.component!!.flattenToString() }) { app ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onOpen(app.intent) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(app.icon, null, Modifier.size(56.dp))
                    Text(
                        app.label, style = Type.Caption.copy(color = Palette.Text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}
