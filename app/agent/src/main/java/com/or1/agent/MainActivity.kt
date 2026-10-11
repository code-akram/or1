package com.or1.agent

import android.content.BroadcastReceiver
import android.content.Context
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

    override fun onResume() {
        super.onResume()
        // Cheap; covers a SystemUI restart, which drops the flags.
        Lockdown.apply()
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

/**
 * After an update the system kills the app and, with its home task gone, shows launcher3's Recents (TrebleDroid keeps
 * launcher3 for Recents, so it can't just be disabled). Put the chat back in front.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle(),
        )
    }
}

