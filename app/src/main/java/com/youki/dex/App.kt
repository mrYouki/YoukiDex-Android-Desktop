package com.youki.dex

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.youki.dex.activities.DebugActivity
import kotlin.system.exitProcess
import android.os.Process

class App : Application() {
    private lateinit var uncaughtExceptionHandler: Thread.UncaughtExceptionHandler

    companion object {
        var intentionalShutdown = false

        // CIRCUIT BREAKER: Minimum time (ms) between restarts to break crash loops.
        // If the app crashes within 10 seconds of starting, do NOT auto-restart.
        private const val CRASH_LOOP_THRESHOLD_MS = 10_000L
        private const val PREF_LAST_START_TIME = "_app_last_start_time"

        /** Call this before any intentional shutdown to cancel the auto-restart alarm */
        fun cancelRestartAlarm(context: android.content.Context) {
            try {
                val intent = android.content.Intent(context, com.youki.dex.activities.DebugActivity::class.java)
                val pending = android.app.PendingIntent.getActivity(
                    context, 11111, intent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                am.cancel(pending)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Record startup time for crash-loop detection
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val startTime = System.currentTimeMillis()
        prefs.edit().putLong(PREF_LAST_START_TIME, startTime).apply()

        uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()!!
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            // If this is an intentional shutdown, do NOT schedule a restart
            if (intentionalShutdown) {
                Process.killProcess(Process.myPid())
                exitProcess(0)
                return@setDefaultUncaughtExceptionHandler
            }

            // CIRCUIT BREAKER: if we crashed within 10 seconds of starting,
            // we're likely in a boot loop — skip the auto-restart to prevent
            // the app from hammering itself on every device unlock/boot.
            val uptime = System.currentTimeMillis() - prefs.getLong(PREF_LAST_START_TIME, 0L)
            if (uptime < CRASH_LOOP_THRESHOLD_MS) {
                android.util.Log.e("YoukiDex", "Crash loop detected (uptime ${uptime}ms) — skipping auto-restart")
                Process.killProcess(Process.myPid())
                exitProcess(2)
                return@setDefaultUncaughtExceptionHandler
            }

            val report = StringBuilder("Exception: $exception\n")
            for (element in exception.stackTrace) report.append(element.toString()).append("\n")
            val cause = exception.cause
            if (cause != null) {
                report.append("Cause: ").append(cause).append("\n")
                for (element in cause.stackTrace) report.append(element.toString()).append("\n")
            }
            val message = exception.message
            if (message != null) report.append("Message: ").append(message)
            val intent = Intent(applicationContext, DebugActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra("report", report.toString())
            val pendingIntent = PendingIntent.getActivity(applicationContext, 11111, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am[AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000] = pendingIntent
            Process.killProcess(Process.myPid())
            exitProcess(2)
            uncaughtExceptionHandler.uncaughtException(thread, exception)
        }
        super.onCreate()
    }
}
