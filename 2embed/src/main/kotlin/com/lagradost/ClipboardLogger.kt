package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.AcraApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClipboardLogger {
    private const val TAG = "PluginLogger"
    private var appContextRef: Context? = null
    private val logs = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        appContextRef = context.applicationContext ?: context
    }

    private fun getContext(): Context? {
        return appContextRef 
            ?: CommonActivity.activity?.applicationContext 
            ?: CommonActivity.activity 
            ?: try { AcraApplication.context } catch (_: Throwable) { null }
    }

    fun log(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val line = "[$time][$tag] $message"
        Log.d(TAG, line)
        synchronized(logs) {
            logs.append(line).append("\n")
        }
    }

    fun logAndCopy(tag: String, message: String) {
        log(tag, message)
        copyToClipboard(tag, "=== [2embed Report: $tag] ===\n$message\n\n--- Toàn Bộ Lịch Sử Chi Tiết ---\n${getAllLogs()}")
    }

    fun copyToClipboard(label: String, text: String) {
        val ctx = getContext() ?: return
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText(label, text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(ctx, "📋 [2embed] Đã copy nhật ký vào Clipboard!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Lỗi ClipboardManager: ${e.message}")
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun getAllLogs(): String {
        synchronized(logs) {
            return logs.toString().takeLast(4000)
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.setLength(0)
        }
    }
}
