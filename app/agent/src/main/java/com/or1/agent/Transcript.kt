package com.or1.agent

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** Append-only JSONL log of one conversation: user messages, API responses with usage and cost, tool calls. */
class Transcript(dir: File) {
    val file: File = File(dir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl")

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun log(record: JSONObject) {
        file.appendText(record.put("t", System.currentTimeMillis() / 1000.0).toString() + "\n")
    }
}
