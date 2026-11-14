package com.faceswap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process alive while a long photo/video job runs. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("work", "Processing", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "work")
            .setContentTitle("FaceSwap")
            .setContentText("Processing on your device...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) { runCatching { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } }
    }
}
