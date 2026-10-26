package com.voiceclean.app.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * App-level debug log: in-memory ring buffer + append-only file in filesDir (survives native crashes: the last
 * lines show what was happening). Also installs an uncaught-exception handler that records the stack trace.
 */
object DebugLog {
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "debug.log")
        try {
            if (f.length() > MAX_FILE_BYTES) {
                val old = File(context.filesDir, "debug.log.1")
                old.delete(); f.renameTo(old)
            }
        } catch (_: Throwable) {}
        file = File(context.filesDir, "debug.log")
        i("App", "---- process start ----")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            try { DebugLog.e("Crash", "Uncaught exception in thread ${t.name}: ${Log.getStackTraceString(ex)}") } catch (_: Throwable) {}
            prev?.uncaughtException(t, ex)
        }
    }

    @Synchronized private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        try { file?.appendText(line + "\n") } catch (_: Throwable) {}
    }

    fun i(tag: String, msg: String) { Log.i("VoiceClean", "$tag: $msg"); add("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w("VoiceClean", "$tag: $msg"); add("W", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val m = if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg
        Log.e("VoiceClean", "$tag: $m", t); add("E", tag, m)
    }

    @Synchronized fun recent(): String = lines.joinToString("\n")

    /** Contents of the previous run's log file tail (useful after a crash). */
    @Synchronized fun fileTail(maxChars: Int = 6000): String = try {
        file?.readText()?.takeLast(maxChars) ?: ""
    } catch (_: Throwable) { "" }
}
