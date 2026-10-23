package com.dsh.overlaytranslator.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 極簡的記憶體日誌。
 *
 * 為什麼不只用 Logcat？因為懸浮窗和無障礙服務出問題時，
 * 使用者多半正在別的 App 裡打字，沒有電腦可以看 Logcat。
 * 把最近幾百行留在記憶體裡，App 內就有一個「紀錄」頁可以自我診斷。
 */
object AppLog {

    private const val MAX_LINES = 400
    private const val TAG = "OverlayTranslator"

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun d(tag: String, message: String) = add(false, tag, message)

    fun e(tag: String, message: String) = add(true, tag, message)

    fun e(tag: String, message: String, throwable: Throwable) =
        add(true, tag, "$message → ${throwable.javaClass.simpleName}: ${throwable.message}")

    fun clear() {
        synchronized(this) { _lines.value = emptyList() }
    }

    private fun add(isError: Boolean, tag: String, message: String) {
        if (isError) Log.e(TAG, "[$tag] $message") else Log.d(TAG, "[$tag] $message")
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp ${if (isError) "E" else "D"}/$tag: $message"
        synchronized(this) {
            _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        }
    }
}
