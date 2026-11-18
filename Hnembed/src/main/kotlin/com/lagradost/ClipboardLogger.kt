package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/**
 * Diagnostic logger for Hnembed.
 * Uses the active CloudStream activity rather than the internal CloudStreamApp class.
 * Accumulates the full session for clipboard diagnostics.
 */
object ClipboardLogger {
    private const val TAG = "HnembedLogger"
    private const val MAX_CHARS = 55_000
    private val buffer = StringBuilder()

    @Synchronized
    fun reset() {
        buffer.setLength(0)
    }

    @Synchronized
    fun logAndCopy(title: String, message: String) {
        val safeMessage = redact(message).take(10_000)
        val entry = "=== [$title] ===\n$safeMessage\n===================\n"
        Log.d(TAG, entry)
        buffer.append(entry).append('\n')
        if (buffer.length > MAX_CHARS) {
            buffer.delete(0, buffer.length - MAX_CHARS)
        }
        val snapshot = buffer.toString()

        try {
            val activity = CommonActivity.activity ?: return
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Hnembed diagnostics", snapshot))
                } catch (e: Throwable) {
                    Log.e(TAG, "Clipboard copy failed", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Clipboard unavailable", e)
        }
    }

    private fun redact(value: String): String = value
        .replace(
            Regex("""(?i)([?&](?:vs|token|key|sig|signature|auth|authorization)=)[^&\s]+"""),
            "$1<redacted>"
        )
        .replace(
            Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~-]+"""),
            "$1<redacted>"
        )
}
