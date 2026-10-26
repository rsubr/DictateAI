package com.voiceclean.app.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voiceclean.app.MainActivity
import com.voiceclean.app.VoiceCleanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) that keeps the process alive and shows a progress notification while
 * [DownloadManager] has active transfers. Stops itself when idle. The transfers themselves run in the manager's scope.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, build("Preparing download...", 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true) {
            val dm = (application as VoiceCleanApp).container.downloadManager
            val repo = (application as VoiceCleanApp).container.modelRepository
            job = scope.launch {
                dm.states.collect { states ->
                    val active = states.filterValues { it.isActive }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val done = active.values.sumOf { it.downloaded }
                    val total = active.values.sumOf { it.total }
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    val names = active.keys.map { repo.find(it)?.displayName ?: it }
                    val text = (if (names.size == 1) names[0] else "${names.size} models") + " - $pct%"
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text, pct, total <= 0))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading models")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "model_downloads"
        private const val NOTIF_ID = 4201
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
