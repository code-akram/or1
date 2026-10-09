package com.or1.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Temporary placeholder while the redesigned chat is built. */
@Composable
fun ChatScreen() {
    Or1Theme {
        Column(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().padding(24.dp)) {
            Text("or1 is being redesigned.", style = Type.Reply)
        }
    }
}
