package com.or1.agent.tools

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val OUTPUT_LIMIT = 4000
private const val TIMEOUT_SECONDS = 60L

/**
 * Runs a shell command as root when su grants it, otherwise as the app's own uid. TrebleDroid's phh-su only grants
 * root through phh's Superuser app, which is not installed, so for now commands run as the (platform-signed) app.
 */
class ShellTool {
    private val rootAvailable by lazy { exec(listOf("su", "-c", "id -u"))?.trim() == "0" }

    suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        val who = if (rootAvailable) "root" else "the agent app's uid (no root available)"
        val argv = if (rootAvailable) listOf("su", "-c", command) else listOf("sh", "-c", command)
        val result = exec(argv) ?: "Could not start a shell."
        val text = if (result.length > OUTPUT_LIMIT) result.take(OUTPUT_LIMIT) + "\n... [truncated, ${result.length} chars total]" else result
        "[ran as $who]\n" + text.ifEmpty { "(no output)" }
    }

    /** Combined stdout/stderr plus the exit code, or null if the binary could not be started. */
    private fun exec(argv: List<String>): String? {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return null
        }
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        reader.start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            return output.toString() + "[timed out after ${TIMEOUT_SECONDS}s]"
        }
        reader.join(1000)
        val code = process.exitValue()
        return output.toString().trimEnd() + if (code != 0) "\n[exit code $code]" else ""
    }
}
