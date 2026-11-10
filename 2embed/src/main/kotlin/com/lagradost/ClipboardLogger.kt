package com.lagradost

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClipboardLogger {
    private const val TAG = "TwoNembed"
    private const val MAX_LOG_LENGTH = 4000
    private val buffer = StringBuilder()
    private val lock = Any()
    private var appContextRef: Context? = null

    /**
     * Khởi tạo context khi CloudStream tải plugin
     */
    fun init(ctx: Context?) {
        if (ctx != null && appContextRef == null) {
            appContextRef = ctx.applicationContext ?: ctx
        }
    }

    /**
     * Lấy Context sạch:
     * 1. Từ Plugin load(context)
     * 2. Từ CommonActivity.activity
     * 3. Từ ActivityThread.currentApplication() của Android OS
     */
    @SuppressLint("PrivateApi")
    fun appContext(): Context? {
        appContextRef?.let { return it }
        CommonActivity.activity?.applicationContext?.let { return it }
        CommonActivity.activity?.let { return it }

        return try {
            val actThreadClass = Class.forName("android.app.ActivityThread")
            val method = actThreadClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    fun log(category: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$time][$category] $message\n"
        Log.d(TAG, entry.trimEnd())
        synchronized(lock) {
            buffer.append(entry)
            if (buffer.length > MAX_LOG_LENGTH) {
                val keepStart = buffer.length - MAX_LOG_LENGTH
                buffer.delete(0, keepStart)
            }
        }
    }

    fun getLogs(): String {
        synchronized(lock) {
            return buffer.toString()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
        }
    }

    fun logAndCopy(category: String, message: String) {
        log(category, message)
        val fullDump = getLogs()
        copyToClipboard(fullDump)
    }

    fun copyToClipboard(text: String, showToast: Boolean = true) {
        val ctx = appContext()
        if (ctx == null) {
            Log.w(TAG, "Không tìm thấy Context để copy vào Clipboard!")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    val clip = ClipData.newPlainText("2embed_logs", text)
                    cm.setPrimaryClip(clip)
                    if (showToast) {
                        Toast.makeText(ctx, "📋 [2embed] Đã copy nhật ký vào Clipboard!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi khi copy clipboard: ${t.message}")
            }
        }
    }
}
