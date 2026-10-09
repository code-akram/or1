package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

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
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        AgentHub.init(context.applicationContext)
        AgentHub.submit(
            text,
            requestId = intent.getStringExtra("id"),
            autoApprove = intent.getBooleanExtra("auto_approve", false),
            fresh = intent.getBooleanExtra("fresh", false),
        )
    }
}
