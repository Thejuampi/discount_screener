package com.discountscreener.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process working while the user is somewhere else.
 *
 * A load of five hundred symbols takes longer than a user stays on the screen. Android gives a
 * process it believes is idle no promise of CPU and no promise of a socket, so the load Juan
 * started stops the moment he leaves the app and starts again from nothing when he comes back.
 *
 * A foreground service is the one way to say the work is the user's and is still running. It costs
 * a notification, which is the honest price: the user can see the load and can leave it.
 *
 * This service holds nothing and loads nothing. The repository owns the work and keeps running on
 * its own scope; this only tells the platform not to freeze it.
 */
class RefreshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        // The work is not owned here, so a restart with no load behind it would show a notification
        // for nothing.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.description = CHANNEL_DESCRIPTION
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "refresh-in-progress"
        private const val CHANNEL_NAME = "Loading prices"
        private const val CHANNEL_DESCRIPTION = "Shown while the screener is reading a universe."
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_TITLE = "Discount Screener"
        private const val NOTIFICATION_TEXT = "Reading prices and fundamentals."

        /**
         * Android refuses a foreground service started from the background, and the refusal is an
         * exception rather than a return value. A load that cannot hold the process up still has to
         * run, so the refusal is swallowed: the user gets the old behaviour, never a crash.
         */
        fun start(context: Context) {
            val intent = Intent(context, RefreshForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RefreshForegroundService::class.java)) }
        }
    }
}
