package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private const val TAG = "PluginLogger"

    private fun getContext(): Context? {
        // Chỉ sử dụng CommonActivity, bỏ hoàn toàn app.context để tránh lỗi
        return try {
            CommonActivity.activity
        } catch (e: Throwable) {
            null
        }
    }

    fun logAndCopy(title: String, message: String) {
        val logMessage = "=== [$title] ===\n$message\n==================="
        Log.d(TAG, logMessage)
        try {
            val ctx = getContext() ?: return // Nếu không có context thì bỏ qua
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Plugin Log", logMessage)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    fun reset() {
        try {
            val ctx = getContext() ?: return // Nếu không có context thì bỏ qua
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset clipboard: ${e.message}")
        }
    }
}
