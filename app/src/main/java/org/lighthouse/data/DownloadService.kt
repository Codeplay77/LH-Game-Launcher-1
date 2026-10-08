// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process, CPU and Wi-Fi awake while [DownloadQueue] has work. Without it Android
 * dozes the app when the screen goes off and a long download simply stalls.
 * Stops itself as soon as nothing is queued or running.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads do catálogo", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = build("Preparando downloads...", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightHouse:downloads")
            .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LightHouse:downloads")
            .apply { setReferenceCounted(false); acquire() }

        scope.launch {
            DownloadQueue.states.collect { states ->
                val active = states.values.filter { it is DownloadQueue.State.Queued || it is DownloadQueue.State.Running }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val running = active.filterIsInstance<DownloadQueue.State.Running>().firstOrNull()
                val waiting = active.count { it is DownloadQueue.State.Queued }
                val text = (running?.text ?: "Na fila") + if (waiting > 0) " · +$waiting na fila" else ""
                nm.notify(ID, build(text, running?.percent))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), even when already running.
        val n = build("Preparando downloads...", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun build(text: String, percent: Int?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Baixando jogos")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .apply { if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4201
        /** Safety net: a wake lock must never outlive a hung download forever. */
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
