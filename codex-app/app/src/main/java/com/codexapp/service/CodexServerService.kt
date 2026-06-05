package com.codexapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codexapp.server.CodexHttpServer
import com.codexapp.R

class CodexServerService : Service() {
    companion object {
        private const val CHANNEL_ID = "codex_server_channel"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "CodexServerService"

        @Volatile
        private var instance: CodexServerService? = null
        val isRunning: Boolean
            get() = instance != null

        @Volatile
        private var server: CodexHttpServer? = null

        fun startService(context: Context) {
            val intent = Intent(context, CodexServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CodexServerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        Log.i(TAG, "Starting server within service...")
        synchronized(this) {
            if (server == null) {
                server = CodexHttpServer(applicationContext)
                server?.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the service running
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping server within service...")
        synchronized(this) {
            server?.stop()
            server = null
        }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.server_service_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.server_running_title))
            .setContentText(getString(R.string.server_running_desc))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
