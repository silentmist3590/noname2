package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClipboardLogger {
    private const val TAG = "NontonGoPlugin"
    private var appContextRef: Context? = null
    private val logs = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        appContextRef = context.applicationContext ?: context
    }

    fun appContext(): Context? {
        return appContextRef
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
        copyToClipboard(tag, "[$tag]\n$message")
    }

    fun copyToClipboard(label: String, text: String) {
        val ctx = appContextRef ?: return
        try {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText(label, text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(ctx, "[$label] Đã sao chép vào Clipboard!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi sao chép clipboard: ${e.message}")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun copyAllLogs() {
        val all: String
        synchronized(logs) {
            all = logs.toString()
        }
        copyToClipboard("NontonGo Logs", all)
    }

    fun clear() {
        synchronized(logs) {
            logs.setLength(0)
        }
    }
}
