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
 * [--ez fresh true]`. Guarded by android.permission.DUMP, which only shell and system hold.
 * The reply is appended to files/replies.jsonl under the given id. `--es routine ID` runs that routine now;
 * `--el backdate_chat_ms N` makes the chat look idle N ms longer (tests the auto new chat after an hour);
 * `--es lockdown off|on` lifts or restores the system UI lockdown ([Lockdown]) for development;
 * `--ez stop true` stops the request or routine run in progress;
 * `--es dismiss pkg1,pkg2` clears those apps' notifications (eval cleanup: the shell can't clear other apps');
 * `--es audio test` runs the speaker/mic check, `--es audio listen` just measures what the mic hears ([AudioCheck];
 * result in logcat tag or1-audio and files/audio.txt);
 * `--es wallpaper black` sets a plain black wallpaper on home and lock (provisioning: nothing but or1 from boot on).
 *
 * Effects ([ActivityLog]): `--es effects list|today` writes the recent (or today's) effects, newest first and eval ones
 * included, one per line (`id tier state source label | call | detail`) to files/effects.txt and logcat tag or1-activity;
 * `--es undo ID|last` undoes one (`last`: the newest that can be), then writes the list again once it settles.
 *
 * Hidden display checks: `--es display info|create|launch|tap|back|home|park|restore|focus|snap|front|move|tool [--es name N --es args JSON] [--es pkg P] [--ei x X --ei y Y]
 * [--ei task T]`; results go to logcat (tag or1-display) and files/display.txt.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentHub.init(context.applicationContext)
        intent.getStringExtra("display")?.let { displayCommand(context, it, intent); return }
        intent.getStringExtra("routine")?.let { Routines.fire(it); return }
        if (intent.getBooleanExtra("stop", false)) { AgentHub.stop(); return }
        intent.getStringExtra("lockdown")?.let { Lockdown.setEnabled(it != "off"); return }
        intent.getLongExtra("backdate_chat_ms", 0).takeIf { it > 0 }?.let { AgentHub.backdateChat(it); return }
        intent.getStringExtra("dismiss")?.let { NotifListener.dismiss(it.split(',').toSet()); return }
        intent.getStringExtra("effects")?.let { writeEffects(context, today = it == "today"); return }
        intent.getStringExtra("undo")?.let { id ->
            val target = if (id == "last") ActivityLog.effects.value.firstOrNull { it.canUndo() }?.id else id
            val started = target != null && AgentHub.undo(target)
            android.util.Log.i("or1-activity", "undo $id -> ${target ?: "nothing to undo"}: ${if (started) "started" else "not undoable"}")
            kotlin.concurrent.thread {
                // Tier 1 settles in a few seconds; a tier-2 undo is a whole agent request.
                val deadline = System.currentTimeMillis() + 120_000
                while (target != null && ActivityLog.get(target)?.state == UndoState.PENDING && System.currentTimeMillis() < deadline) Thread.sleep(500)
                writeEffects(context, today = false)
            }
            return
        }
        if (intent.getStringExtra("wallpaper") == "black") {
            val black = android.graphics.Bitmap.createBitmap(72, 160, android.graphics.Bitmap.Config.ARGB_8888)
            black.eraseColor(android.graphics.Color.BLACK)
            val flags = android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
            android.app.WallpaperManager.getInstance(context).setBitmap(black, null, true, flags)
            return
        }
        intent.getStringExtra("audio")?.let { mode ->
            kotlin.concurrent.thread {
                val check = AudioCheck(context.applicationContext)
                val result = try { if (mode == "listen") check.listen() else check.run() } catch (e: Exception) { "error: $e" }
                android.util.Log.i("or1-audio", result)
                java.io.File(context.filesDir, "audio.txt").writeText(result + "\n")
            }
            return
        }
        val text = intent.getStringExtra("text") ?: return
        AgentHub.submit(
            text,
            requestId = intent.getStringExtra("id"),
            fresh = intent.getBooleanExtra("fresh", false),
        )
    }
}

private fun writeEffects(context: Context, today: Boolean) {
    val all = ActivityLog.effects.value
    val text = (if (today) all.today() else all.take(50)).joinToString("\n") { e ->
        "${e.id} tier${e.tier.level} ${e.stateAt().name.lowercase()} ${e.source}${e.sourceId?.let { ":$it" }.orEmpty()} ${e.label} | ${e.call}" +
            (e.detail?.let { " | $it" }.orEmpty())
    }.ifEmpty { "no effects" }
    android.util.Log.i("or1-activity", text)
    java.io.File(context.filesDir, "effects.txt").writeText(text + "\n")
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
                "back" -> "injected=${HiddenDisplay.injectKey(android.view.KeyEvent.KEYCODE_BACK, HiddenDisplay.ensure()!!)}"
                "home" -> { HiddenDisplay.goHome(); "home" }
                "park" -> { HiddenDisplay.park(); "parked" }
                "restore" -> { HiddenDisplay.restoreFocus(); "restored" }
                "focus" -> "focused=${HiddenDisplay.focus()}"
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
        "tasksN=${HiddenDisplay.tasksOn(id)} tasks0=${HiddenDisplay.tasksOn(0)} preview=${HiddenDisplay.preview.value?.let { "${it.width}x${it.height}" }}"
}
