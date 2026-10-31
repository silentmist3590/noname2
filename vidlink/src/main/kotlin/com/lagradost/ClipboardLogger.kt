package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.appContext

object ClipboardLogger {
    private const val TAG = "VidLinkPlugin"

    fun logAndCopy(title: String, message: String) {
        val logMessage = "=== [$title] ===\n$message\n==================="
        Log.d(TAG, logMessage)

        try {
            // Sử dụng appContext thay vì app (app là HTTP Client)
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            // Chạy trên Main Thread để đảm bảo an toàn
            Handler(Looper.getMainLooper()).post {
                val clip = ClipData.newPlainText("VidLink Log", logMessage)
                clipboard.setPrimaryClip(clip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
