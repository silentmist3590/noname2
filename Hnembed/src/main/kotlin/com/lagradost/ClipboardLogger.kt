package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/**
 * CloudStream 4.5.2-compatible diagnostic logger.
 * Does not reference CloudStreamApp, which is not a plugin API.
 *
 * If CommonActivity is not available at the moment of logging, the message
 * is still written to Logcat under the HnembedDiag tag.
 */
object ClipboardLogger {
    private const val TAG = "HnembedDiag"
    private const val MAX_CHARS = 45000

    private val lock = Any()
    private val buffer = StringBuilder()

    fun logAndCopy(title: String, message: String) {
        val entry = "=== [$title] ===\n${redact(message)}\n===================\n"

        val snapshot = synchronized(lock) {
            buffer.append(entry)
            if (buffer.length > MAX_CHARS) {
                buffer.delete(0, buffer.length - MAX_CHARS)
            }
            buffer.toString()
        }

        Log.d(TAG, entry)

        val activity = try {
            CommonActivity.activity
        } catch (_: Throwable) {
            null
        } ?: return

        activity.runOnUiThread {
            try {
                val clipboard = activity.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Hnembed diagnostic log", snapshot)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard copy failed", e)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
        }
    }

    private fun redact(input: String): String {
        return input
            .replace(
                Regex("""(?i)(api_token|token|authorization|cookie|vs)=([^&\s"'<>]+)"""),
                "$1=<redacted>"
            )
            .replace(
                Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~-]+"""),
                "$1<redacted>"
            )
    }
}
