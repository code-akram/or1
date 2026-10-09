package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.or1.agent.tools.HiddenDisplay
import kotlinx.coroutines.launch

/** Keeps the agent's process alive while a request runs in the background (another app in front). */
class AgentService : Service() {
    override fun onCreate() {
        super.onCreate()
        AgentHub.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("or1 agent")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "agent"
    }
}

/**
 * Dev-only remote driver for evals: `am broadcast -n com.or1.agent/.DebugReceiver --es text "..." --es id N
 * [--ez auto_approve true] [--ez fresh true]`. Guarded by android.permission.DUMP, which only shell and system hold.
 * The reply is appended to files/replies.jsonl under the given id.
 *
 * Hidden display checks: `--es display info|create|launch|tap|back|home|park|snap|front|move|tool [--es name N --es args JSON] [--es pkg P] [--ei x X --ei y Y]
 * [--ei task T]`; results go to logcat (tag or1-display) and files/display.txt.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentHub.init(context.applicationContext)
        intent.getStringExtra("display")?.let { displayCommand(context, it, intent); return }
        val text = intent.getStringExtra("text") ?: return
        AgentHub.submit(
            text,
            requestId = intent.getStringExtra("id"),
            autoApprove = intent.getBooleanExtra("auto_approve", false),
            fresh = intent.getBooleanExtra("fresh", false),
        )
    }
}

private fun displayCommand(context: Context, command: String, intent: Intent) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        val result = try {
            when (command) {
                "create" -> "id=${HiddenDisplay.ensure()} a11y=${HiddenDisplay.accessibilitySees()}"
                "launch" -> {
                    val pkg = intent.getStringExtra("pkg")!!
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)!!
                    "landed on display ${HiddenDisplay.launch(launch, pkg)}"
                }
                "tap" -> {
                    val id = HiddenDisplay.ensure()!!
                    intent.getIntExtra("x", 0).toFloat().let { x -> intent.getIntExtra("y", 0).toFloat().let { y ->
                        "injected=${HiddenDisplay.injectStroke(x, y, x, y, 50, id)}" } }
                }
                "back" -> "focus=${HiddenDisplay.focus()} injected=${HiddenDisplay.injectKey(android.view.KeyEvent.KEYCODE_BACK, HiddenDisplay.ensure()!!)}"
                "home" -> { HiddenDisplay.goHome(); "home" }
                "park" -> { HiddenDisplay.park(); "parked" }
                "tool" -> debugTools(context).run(intent.getStringExtra("name")!!, org.json.JSONObject(intent.getStringExtra("args") ?: "{}")).text
                "snap" -> {
                    java.io.File(context.filesDir, "preview.png").outputStream().use {
                        HiddenDisplay.preview.value!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    "snap saved"
                }
                "front" -> "moved=${HiddenDisplay.bringToFront()}"
                "move" -> "moved=${HiddenDisplay.moveTaskToDisplay(intent.getIntExtra("task", -1), HiddenDisplay.ensure()!!)}"
                else -> ""
            } + "\n" + info()
        } catch (e: Exception) {
            "error: $e"
        }
        android.util.Log.i("or1-display", result)
        java.io.File(context.filesDir, "display.txt").writeText(result + "\n")
    }
}

private var tools: com.or1.agent.tools.Tools? = null
private fun debugTools(context: Context) = tools ?: com.or1.agent.tools.Tools(context.applicationContext).also { tools = it }

private fun info(): String {
    val id = HiddenDisplay.id ?: return "no hidden display (unavailable=${HiddenDisplay.unavailable})"
    return "display=$id a11y=${HiddenDisplay.accessibilitySees()} top0=${HiddenDisplay.topAppPackage(0)} topN=${HiddenDisplay.topAppPackage(id)} " +
        "tasksN=${HiddenDisplay.tasksOn(id)} preview=${HiddenDisplay.preview.value?.let { "${it.width}x${it.height}" }}"
}
