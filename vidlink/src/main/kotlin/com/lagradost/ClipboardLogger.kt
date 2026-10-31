package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.lagradost.cloudstream3.utils.AppContextUtils

object ClipboardLogger {
    fun log(text: String) {
        try {
            // Sử dụng AppContextUtils thay vì AppUtils cũ
            val context = AppContextUtils.context ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ClipboardLogger", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
