package com.or1.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notifications for routines, which run while the user may be elsewhere: their replies. */
object Notifier {
    private const val REPLIES = "routine-replies"
    private const val REPLY_ID_BASE = 1000

    private lateinit var context: Context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var replies = 0

    fun init(context: Context) {
        this.context = context.applicationContext
        manager.createNotificationChannel(NotificationChannel(REPLIES, "Routine results", NotificationManager.IMPORTANCE_DEFAULT))
        // Left over from when routines asked before changes.
        manager.deleteNotificationChannel("routine-approvals")
    }

    /** `trigger` says which routine this was, e.g. "Every day at 08:00". */
    fun reply(trigger: String, reply: String) =
        manager.notify(REPLY_ID_BASE + (replies++ % 50), build(REPLIES, "or1", reply, trigger, group = REPLIES))

    /**
     * The sound and buzz for a routine result in the chat stream. With the lockdown's notification alerts off, the
     * system mutes notification sounds, so the chat makes its own.
     */
    fun chime() {
        runCatching {
            android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))?.play()
            context.getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Tapping opens the Routines screen. */
    private fun build(
        channel: String, title: String, text: String, subText: String? = null, group: String,
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
            .setGroup(group)
            .build()
    }
}
