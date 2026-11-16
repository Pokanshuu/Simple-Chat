package com.simplechat.app.data

import android.content.Context
import android.os.Build
import com.simplechat.app.BuildConfig
import java.io.File
import java.util.Date

/** Local-only crash metadata. No prompts, response bodies, URLs, exception messages or API keys. */
object CrashDiagnostics {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(context.filesDir, "last-crash.txt").bufferedWriter().use { out ->
                    out.appendLine("SimpleChat ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    out.appendLine("${Date()} Android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
                    out.appendLine("thread=${thread.name}")
                    var cause: Throwable? = error
                    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                    while (cause != null && seen.add(cause)) {
                        out.appendLine(cause.javaClass.name)
                        cause.stackTrace.take(80).forEach { out.appendLine("  at $it") }
                        cause = cause.cause
                    }
                }
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    fun file(context: Context): File? = File(context.filesDir, "last-crash.txt").takeIf { it.isFile }
}
