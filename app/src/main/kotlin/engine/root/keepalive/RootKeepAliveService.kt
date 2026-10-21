// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.R
import app.modes.isRootRunMode
import features.logs.AndroidAppLogger
import system.AndroidLauncherIcon

/**
 * Lightweight foreground service kept while a ROOT core is running and no other AsteriskMETA
 * foreground service (the optional traffic statistics notification) holds the process.
 *
 * In ROOT mode the VPN service does not exist, so aggressive OEM task killers (ColorOS
 * `o-stop`/`o-kill`) treated the app as an idle background process and killed it, stopping the
 * app-side status watch. The supervisor (asteriskd) restarts a crashed core on its own, so this
 * service is only about keeping the app-side monitoring/UI state alive; it performs no ROOT
 * operation and holds no wake lock.
 */
class RootKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must reach startForeground() before the service stops.
        val entered = runCatching {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NotificationId, notification)
            }
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to enter ROOT keep-alive foreground", error)
        }.isSuccess
        synchronized(lock) {
            foreground = entered
            pendingStart = false
            if (!entered || !desired) {
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) {
            foreground = false
            pendingStart = false
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.root_keepalive_notification_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            AndroidLauncherIcon.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.root_keepalive_notification_title))
            .setContentText(getString(R.string.root_keepalive_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ChannelId = "root_keepalive"
        private const val NotificationId = 3002
        private const val LogTag = "RootKeepAlive"
        private val lock = Any()
        private var desired = false
        private var pendingStart = false
        private var foreground = false

        /** Pure policy: keep the process foreground while ROOT runs without the stats service. */
        internal fun shouldRun(runMode: Int, proxyRunning: Boolean, trafficStatsNotificationActive: Boolean): Boolean =
            runMode.isRootRunMode() && proxyRunning && !trafficStatsNotificationActive

        internal fun reconcile(context: Context, active: Boolean) {
            if (active) start(context) else stop(context)
        }

        private fun start(context: Context) {
            val appContext = context.applicationContext
            val request = synchronized(lock) {
                desired = true
                val request = !foreground && !pendingStart
                if (request) pendingStart = true
                request
            }
            if (!request) return
            try {
                ContextCompat.startForegroundService(appContext, Intent(appContext, RootKeepAliveService::class.java))
            } catch (error: Exception) {
                synchronized(lock) { pendingStart = false }
                AndroidAppLogger.warn(LogTag, "Failed to request ROOT keep-alive foreground service", error)
            }
        }

        private fun stop(context: Context) {
            val appContext = context.applicationContext
            val stopNow = synchronized(lock) {
                desired = false
                // An undelivered start must still reach startForeground(); the service stops itself.
                foreground && !pendingStart
            }
            if (stopNow) appContext.stopService(Intent(appContext, RootKeepAliveService::class.java))
        }
    }
}
