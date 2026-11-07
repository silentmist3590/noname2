package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp

object ClipboardLogger {
    private const val TAG = "Cat3movieLogger"
    private const val MAX_CHARS = 60_000

    /** true = luôn copy log vào clipboard; false = chỉ copy khi tiêu đề chứa "Error"/"Exception". */
    private const val COPY_ALWAYS = true

    private val buffer = StringBuilder()

    /** Xoá log cũ (gọi ở đầu mỗi phiên: load / loadLinks). */
    @Synchronized
    fun reset() {
        buffer.setLength(0)
    }

    /** Ghi nối tiếp vào bộ đệm rồi copy TOÀN BỘ log vào clipboard. */
    @Synchronized
    fun logAndCopy(title: String, message: String) {
        val entry = "=== [$title] ===\n$message\n"
        Log.d(TAG, entry)
        buffer.append(entry).append('\n')
        if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        if (!COPY_ALWAYS && !title.contains("Error", true) && !title.contains("Exception", true)) return
        val text = buffer.toString()
        try {
            val context = CloudStreamApp.context ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            Handler(Looper.getMainLooper()).post {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cat3movie Log", text))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy to clipboard", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
