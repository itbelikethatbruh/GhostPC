package com.radiant.pcbrowser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps the last crash so the menu's Status tab can show it, with no USB cable or logcat needed.
 *
 * App-side (Kotlin) crashes are saved with their stack trace. Native crashes inside the app process
 * (for example in the WebView engine) cannot be caught; on Android 11+ they are read back from the
 * system's exit records, which only say that one happened and when.
 */
object CrashLog {

    data class Report(val time: Long, val summary: String, val details: String)

    private const val FILE = "last_crash.txt"
    private const val PREFS = "radiant_crash"
    private const val K_ANNOUNCED = "announced"
    private const val K_CLEARED_AT = "cleared_at"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val text = "Build " + BuildConfig.VERSION_NAME + ", Android " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                    "Thread: " + thread.name + "\n" + trace
                File(app.filesDir, FILE).writeText(text.take(24_000))
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ANNOUNCED, false).commit()
            } catch (_: Throwable) {
                // Never let the crash reporter hide the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** True once after a new app-side crash was saved, so the next launch can mention it. */
    fun takeNewCrashNotice(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(K_ANNOUNCED, true)) return false
        sp.edit().putBoolean(K_ANNOUNCED, true).apply()
        return File(context.filesDir, FILE).exists()
    }

    /** The most recent crash from the last week, or null. */
    fun last(context: Context): Report? {
        val clearedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_CLEARED_AT, 0L)
        val now = System.currentTimeMillis()
        var best: Report? = null

        val file = File(context.filesDir, FILE)
        if (file.exists()) {
            val details = try { file.readText() } catch (_: Throwable) { "" }
            if (details.isNotEmpty()) best = Report(file.lastModified(), summarize(details), details)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                val native = am?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    ?.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
                if (native != null && native.timestamp > (best?.time ?: 0L) + 5_000) {
                    val desc = native.description?.takeIf { it.isNotBlank() } ?: "no details from Android"
                    best = Report(
                        native.timestamp,
                        "Native crash (usually inside Android System WebView)",
                        "Native crash at " + stamp(native.timestamp) + "\nAndroid's note: " + desc +
                            "\nBuild " + BuildConfig.VERSION_NAME + ", WebView " +
                            (androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "unknown")
                    )
                }
            } catch (_: Throwable) {
                // Exit records are best-effort.
            }
        }

        val report = best ?: return null
        if (report.time <= clearedAt || now - report.time > MAX_AGE_MS) return null
        return report
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_CLEARED_AT, System.currentTimeMillis())
            .putBoolean(K_ANNOUNCED, true)
            .apply()
    }

    fun stamp(time: Long): String = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(time))

    /** First exception line plus the first frame in this app, e.g. "UninitializedPropertyAccess… at MainActivity.kt:812". */
    private fun summarize(details: String): String {
        val lines = details.lines()
        val exception = lines.firstOrNull { line ->
            val t = line.trim()
            !t.startsWith("Build ") && !t.startsWith("Thread:") && !t.startsWith("at ") && t.isNotEmpty()
        }?.trim()?.substringAfterLast("Caused by: ") ?: "Unknown error"
        val shortException = exception.substringAfterLast('.', exception).let {
            // Keep the class name readable: "kotlin.UninitializedPropertyAccessException: msg" -> "UninitializedPropertyAccessException: msg"
            val colon = exception.indexOf(':')
            if (colon > 0) exception.substring(0, colon).substringAfterLast('.') + exception.substring(colon) else it
        }
        val frame = lines.map { it.trim() }.firstOrNull { it.startsWith("at com.radiant.") }
            ?.substringAfter('(')?.substringBefore(')')
        return if (frame != null) "$shortException\nat $frame" else shortException
    }
}
