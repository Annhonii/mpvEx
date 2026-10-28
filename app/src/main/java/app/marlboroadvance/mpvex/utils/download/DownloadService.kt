package app.marlboroadvance.mpvex.utils.download

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.marlboroadvance.mpvex.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the process alive (and shows a progress notification)
 * while [YouTubeDownloadManager] has running downloads.
 */
class DownloadService : Service() {
  private val manager: YouTubeDownloadManager by inject()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observeJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(manager.active.value.filter { it.isRunning }),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    observeJob?.cancel()
    observeJob =
      scope.launch {
        manager.active.collect { list ->
          val running = list.filter { it.isRunning }
          if (running.isEmpty()) {
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
          } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(running))
          }
        }
      }

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Progress of video downloads"
          setShowBadge(false)
        }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun buildNotification(running: List<ActiveDownload>): Notification {
    val first = running.firstOrNull()
    val title =
      when {
        running.size > 1 -> "Downloading ${running.size} videos"
        first?.stage == DownloadStage.Merging -> "Merging audio and video"
        first?.stage == DownloadStage.Saving -> "Saving to gallery"
        else -> "Downloading video"
      }
    val text = first?.title?.takeIf { it.isNotBlank() } ?: first?.sourceUrl.orEmpty()

    val openApp =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val builder =
      NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openApp)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    val progress = if (running.size == 1) first?.progress ?: -1f else -1f
    if (progress >= 0f) {
      builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
    } else {
      builder.setProgress(0, 0, true)
    }
    return builder.build()
  }

  companion object {
    private const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 4711

    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }
  }
}
