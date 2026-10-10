package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notifications for routines, which run while the user may be elsewhere: their replies, and approvals they wait on. */
object Notifier {
    private const val REPLIES = "routine-replies"
    private const val APPROVALS = "routine-approvals"
    private const val REPLY_ID_BASE = 1000
    private const val APPROVAL_ID_BASE = 100_000

    private lateinit var context: Context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var replies = 0

    fun init(context: Context) {
        this.context = context.applicationContext
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(REPLIES, "Routine results", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(APPROVALS, "Routine approvals", NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    /** `trigger` says which routine this was, e.g. "Every day at 08:00". */
    fun reply(trigger: String, reply: String) =
        manager.notify(REPLY_ID_BASE + (replies++ % 50), build(REPLIES, "or1", reply, trigger, group = REPLIES))

    /** With Allow, Always and Deny buttons, so the user can answer without opening the phone's chat. */
    fun approvalNeeded(itemId: Long, prompt: String, label: String) {
        // A group of its own: Android otherwise bundles it with the agent's ongoing notification, collapsed, buttons hidden.
        val notification = build(APPROVALS, "Approve: $label", "A routine is waiting: “$prompt”.", group = "$APPROVALS-$itemId", actions = listOf(
            Decision.DENY to "Deny", Decision.ALWAYS to "Always", Decision.ALLOW to "Allow",
        ).map { (decision, title) ->
            val intent = Intent(context, ApprovalReceiver::class.java)
                .putExtra(ApprovalReceiver.EXTRA_ITEM, itemId).putExtra(ApprovalReceiver.EXTRA_DECISION, decision.name)
            // Distinct request codes, or the three buttons would share one PendingIntent.
            val pending = PendingIntent.getBroadcast(context, (itemId * 3 + decision.ordinal).toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            Notification.Action.Builder(null, title, pending).build()
        })
        manager.notify(APPROVAL_ID_BASE + (itemId % 100_000).toInt(), notification)
    }

    fun cancel(itemId: Long) = manager.cancel(APPROVAL_ID_BASE + (itemId % 100_000).toInt())

    /** Tapping opens the Routines screen. */
    private fun build(
        channel: String, title: String, text: String, subText: String? = null, group: String,
        actions: List<Notification.Action> = emptyList(),
    ): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SHOW_ROUTINES, true),
            // UPDATE_CURRENT: extras don't count when matching PendingIntents, so an older one without them would be reused.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setActions(*actions.toTypedArray())
            .setGroup(group)
            .build()
    }
}

/** A button on an approval notification. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = intent.getLongExtra(EXTRA_ITEM, -1)
        val decision = intent.getStringExtra(EXTRA_DECISION)?.let { runCatching { Decision.valueOf(it) }.getOrNull() } ?: return
        AgentHub.decide(item, decision)
        Notifier.cancel(item)
    }

    companion object {
        const val EXTRA_ITEM = "item"
        const val EXTRA_DECISION = "decision"
    }
}
