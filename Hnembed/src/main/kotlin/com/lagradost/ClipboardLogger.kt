package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp

object ClipboardLogger {
    private const val TAG = "PluginLogger"

    fun logAndCopy(title: String, message: String) {
        val logMessage = "=== [$title] ===\n$message\n==================="
        Log.d(TAG, logMessage)

        try {
            // Sử dụng CloudStreamApp.context thay vì APIHolder.context
            val context = CloudStreamApp.context ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            Handler(Looper.getMainLooper()).post {
                val clip = ClipData.newPlainText("Plugin Log", logMessage)
                clipboard.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
