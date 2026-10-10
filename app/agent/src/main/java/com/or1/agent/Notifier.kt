package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        manager.notify(REPLY_ID_BASE + (replies++ % 50), build(REPLIES, "or1", reply, trigger))

    fun approvalNeeded(itemId: Long, prompt: String, label: String) =
        manager.notify(APPROVAL_ID_BASE + (itemId % 100_000).toInt(), build(APPROVALS, "Approve: $label", "A routine is waiting: “$prompt”. Open the chat to allow or deny."))

    fun cancel(itemId: Long) = manager.cancel(APPROVAL_ID_BASE + (itemId % 100_000).toInt())

    /** Tapping opens the chat. */
    private fun build(channel: String, title: String, text: String, subText: String? = null): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}
