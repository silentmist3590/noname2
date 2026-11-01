package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.APIHolder

object ClipboardLogger {
    private const val TAG = "VidsrcmePlugin"

    fun logAndCopy(title: String, message: String) {
        val logMessage = "=== [$title] ===\n$message\n==================="
        Log.d(TAG, logMessage)

        try {
            val context = APIHolder.context ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            Handler(Looper.getMainLooper()).post {
                val clip = ClipData.newPlainText("Vidsrcme Log", logMessage)
                clipboard.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
