package com.lagradost // Đảm bảo đúng package của bạn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.app

object ClipboardLogger {
    private const val TAG = "VidLinkPlugin"

    fun logAndCopy(title: String, message: String) {
        val logMessage = "=== [$title] ===\n$message\n==================="
        Log.d(TAG, logMessage)

        try {
            // Sử dụng app context trực tiếp để lấy ClipboardManager
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
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
