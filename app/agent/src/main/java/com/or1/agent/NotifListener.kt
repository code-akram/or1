package com.or1.agent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Gives the agent read access to all notifications, and triggers notification routines. Enabled by provision.sh. */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        _changes.value++
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        _changes.value++
        sbn?.let { Routines.onNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        _changes.value++
    }

    companion object {
        @Volatile
        var instance: NotifListener? = null

        /** Clears every clearable notification from [packages]. */
        fun dismiss(packages: Set<String>) {
            val listener = instance ?: return
            listener.activeNotifications.filter { it.packageName in packages }.forEach { listener.cancelNotification(it.key) }
        }

        private val _changes = MutableStateFlow(0)
        /** Bumped whenever notifications change, so the chat can refresh its suggestions. */
        val changes: StateFlow<Int> = _changes
    }
}
