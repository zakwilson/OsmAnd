package net.osmand.plus.plugins.glassnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the OsmAnd process alive while [GlassNavController] is streaming
 * to the paired Glass headset over BLE/RFCOMM.
 *
 * Important: this service does NOT own the BLE transport — the controller (plugin-scoped) does.
 * The service exists purely to signal `connectedDevice` FGS type to the OS so the process is not
 * killed while OsmAnd is backgrounded mid-ride. Start/stop is driven by
 * [GlassNavController.startStreaming]/[GlassNavController.stopStreaming].
 */
class GlassStreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GlassStreamingService"
        private const val CHANNEL_ID = "glass_nav_stream"
        private const val CHANNEL_NAME = "Glass navigation streaming"
        private const val NOTIF_ID = 0x6C61_7373 // arbitrary; avoid collisions with OsmAnd ids

        /** Start the foreground service. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, GlassStreamingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (t: Throwable) {
                // startForegroundService can throw on Android 12+ if app is in background without
                // an appropriate exemption. Log and proceed — the controller will still try to
                // talk to the transport; the OS may kill us once OsmAnd is backgrounded but for a
                // foreground MapActivity that's the common path.
                Log.w(TAG, "startForegroundService failed", t)
            }
        }

        /** Stop the foreground service. Safe to call when not running. */
        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, GlassStreamingService::class.java)
            try {
                context.applicationContext.stopService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "stopService failed", t)
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active while OsmGlass is streaming turn-by-turn data to a paired headset."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Glass navigation")
                .setContentText("Streaming to paired Glass headset")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
