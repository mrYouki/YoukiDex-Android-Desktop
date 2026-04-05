package com.youki.dex.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.youki.dex.R
import com.youki.dex.activities.MainActivity
import com.youki.dex.utils.DeviceUtils

/**
 * BootReceiver — starts YoukiDex automatically after device reboot.
 *
 * If the accessibility service (DockService) is already enabled in system settings,
 * Android will restart it automatically on boot — no action needed.
 *
 * If it's NOT enabled (e.g. user never set it up, or it got reset),
 * we show a notification reminding the user to re-enable it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // If accessibility service is already enabled, Android restarts it automatically.
        // Our SecurityException fix (DockService.kt:673) ensures it won't crash on boot.
        if (DeviceUtils.isAccessibilityServiceEnabled(context)) return

        // Service not enabled — remind the user to turn it on
        showReEnableNotification(context)
    }

    private fun showReEnableNotification(context: Context) {
        val channelId = "youki_boot_channel"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Youki DEX Boot",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when Youki DEX needs to be re-enabled after reboot"
        }
        nm.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_dock)
            .setContentTitle("Youki DEX")
            .setContentText("Tap to re-enable the dock after reboot")
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notification)
    }
}
