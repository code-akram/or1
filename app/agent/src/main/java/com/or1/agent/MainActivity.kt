package com.or1.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.or1.agent.ui.ChatScreen

/** The phone's home screen: the chat composer. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentHub.init(applicationContext)
        startForegroundService(Intent(this, AgentService::class.java))
        setContent { ChatScreen() }
    }
}
