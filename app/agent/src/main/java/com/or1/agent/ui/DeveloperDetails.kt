package com.or1.agent.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The drawer's "Developer details" switch: cost, seconds and raw tool calls show only while it's on. The data keeps
 * flowing either way (the eval doesn't depend on it being visible).
 */
object DeveloperDetails {
    private const val PREFS = "ui"
    private const val KEY = "developer_details"

    var on by mutableStateOf(false)
        private set

    fun load(context: Context) {
        on = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
