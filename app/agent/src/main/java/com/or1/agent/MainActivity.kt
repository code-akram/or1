package com.or1.agent

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.or1.agent.ui.ChatScreen

/** The phone's home screen: the chat composer. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        AgentHub.init(applicationContext)
        startForegroundService(Intent(this, AgentService::class.java))
        setContent { ChatScreen() }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A routine notification was tapped: show the Routines screen. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_ROUTINES, false) == true) AgentHub.showRoutines()
    }

    companion object {
        const val EXTRA_SHOW_ROUTINES = "show_routines"
    }
}
