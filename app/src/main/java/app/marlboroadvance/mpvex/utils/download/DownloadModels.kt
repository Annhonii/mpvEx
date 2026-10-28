package app.marlboroadvance.mpvex.utils.download

import kotlinx.serialization.Serializable

/**
 * A finished download. Persisted to disk (see [YouTubeDownloadManager]).
 */
@Serializable
data class DownloadedItem(
  val id: String,
  val sourceUrl: String,
  val title: String,
  val uploader: String = "",
  val thumbnailPath: String? = null,
  /** Label shown on the thumbnail badge, e.g. "1080p" or "Audio". */
  val quality: String,
  val isAudio: Boolean = false,
  val sizeBytes: Long,
  /** content:// URI (Android 10+) or absolute file path (older versions). */
  val contentUri: String,
  val createdAt: Long,
)

enum class DownloadStage { Fetching, Downloading, Merging, Saving, Failed }

/**
 * A download that is running (or failed and waiting to be retried/dismissed). Memory only.
 */
data class ActiveDownload(
  val id: String,
  val sourceUrl: String,
  val title: String = "",
  val thumbnailPath: String? = null,
  val quality: String = "",
  val stage: DownloadStage = DownloadStage.Fetching,
  /** 0..1, or a negative value when the progress is unknown. */
  val progress: Float = -1f,
  val error: String? = null,
) {
  val isRunning: Boolean get() = stage != DownloadStage.Failed
}
