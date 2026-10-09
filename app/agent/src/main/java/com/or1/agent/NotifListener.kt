package com.or1.agent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Gives the agent read access to all notifications. Enabled by provision.sh. */
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
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        _changes.value++
    }

    companion object {
        @Volatile
        var instance: NotifListener? = null

        private val _changes = MutableStateFlow(0)
        /** Bumped whenever notifications change, so the chat can refresh its suggestions. */
        val changes: StateFlow<Int> = _changes
    }
}
