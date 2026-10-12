package com.or1.agent.tools

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent

/**
 * The hidden display's home screen (it has no launcher): a grid of the phone's apps, so the user can start one when
 * they use the work screen themselves (see WorkViewer). Apps open on the same display.
 */
class WorkHomeActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        setContent { com.or1.agent.ui.AppGrid(onOpen = ::open) }
    }

    private fun open(intent: Intent) {
        val displayId = window.decorView.display?.displayId ?: return
        startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
    }
}
