package com.or1.agent

import android.service.notification.NotificationListenerService

/** Gives the agent read access to all notifications. Enabled by provision.sh. */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    companion object {
        @Volatile
        var instance: NotifListener? = null
    }
}
