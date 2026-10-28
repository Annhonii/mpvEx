package app.marlboroadvance.mpvex.utils.download

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.marlboroadvance.mpvex.utils.media.MediaLibraryEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Downloads videos using the NewPipe extractor engine.
 *
 * Pipeline: extract stream info -> choose best streams -> chunked download ->
 * (optional) mux video + audio with MediaMuxer -> save into MediaStore (Movies/mpvEx).
 *
 * The manager lives for the whole app lifetime, so downloads keep running when the
 * user leaves the Downloads tab. [DownloadService] keeps the process alive in background.
 */
class YouTubeDownloadManager(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true }
  private val client =
    OkHttpClient
      .Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  private val baseDir = File(appContext.filesDir, "downloads")
  private val storeFile = File(baseDir, "downloads.json")
  private val thumbDir = File(baseDir, "thumbs")
  private val storeLock = Any()

  private val _completed = MutableStateFlow<List<DownloadedItem>>(emptyList())
  val completed: StateFlow<List<DownloadedItem>> = _completed.asStateFlow()

  private val _active = MutableStateFlow<List<ActiveDownload>>(emptyList())
  val active: StateFlow<List<ActiveDownload>> = _active.asStateFlow()

  private val jobs = ConcurrentHashMap<String, Job>()

  @Volatile
  private var newPipeReady = false

  init {
    baseDir.mkdirs()
    thumbDir.mkdirs()
    _completed.value = readStore()
    scope.launch { refresh() }
  }

  // ---------------------------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------------------------

  /**
   * Starts a download for the first link found in [rawText].
   * @return an error message to show to the user, or null when the download was queued.
   */
  fun enqueue(rawText: String): String? {
    val url = URL_REGEX.find(rawText)?.value?.trimEnd('.', ',', ')', ']') ?: return "That doesn't look like a link"

    if (_active.value.any { it.sourceUrl == url && it.isRunning }) {
      return "This link is already downloading"
    }

    val id = UUID.randomUUID().toString()
    _active.update { it + ActiveDownload(id = id, sourceUrl = url) }
    startJob(id, url)
    return null
  }

  fun retry(id: String) {
    val item = _active.value.firstOrNull { it.id == id && !it.isRunning } ?: return
    update(id) { it.copy(stage = DownloadStage.Fetching, progress = -1f, error = null) }
    startJob(id, item.sourceUrl)
  }

  /** Cancels a running download or dismisses a failed one. */
  fun cancel(id: String) {
    jobs.remove(id)?.cancel()
    _active.update { list -> list.filterNot { it.id == id } }
    File(appContext.cacheDir, "dl_$id").deleteRecursively()
  }

  fun delete(item: DownloadedItem) {
    scope.launch {
      runCatching {
        if (item.contentUri.startsWith("content://")) {
          appContext.contentResolver.delete(Uri.parse(item.contentUri), null, null)
        } else {
          File(item.contentUri).delete()
        }
      }.onFailure { Log.w(TAG, "Could not delete ${item.contentUri}", it) }

      item.thumbnailPath?.let { File(it).delete() }
      _completed.update { list -> list.filterNot { it.id == item.id } }
      writeStore(_completed.value)
      MediaLibraryEvents.notifyChanged()
    }
  }

  /** Drops entries whose file was removed outside of the app. */
  fun refresh() {
    val existing = _completed.value.filter { fileStillExists(it) }
    if (existing.size != _completed.value.size) {
      _completed.value = existing
      writeStore(existing)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Download job
  // ---------------------------------------------------------------------------------------------

  private fun startJob(
    id: String,
    url: String,
  ) {
    DownloadService.start(appContext)
    jobs[id] =
      scope.launch {
        val tmpDir = File(appContext.cacheDir, "dl_$id").apply { mkdirs() }
        try {
          runDownload(id, url, tmpDir)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          Log.e(TAG, "Download failed for $url", e)
          update(id) {
            it.copy(
              stage = DownloadStage.Failed,
              error = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName,
            )
          }
        } finally {
          jobs.remove(id)
          tmpDir.deleteRecursively()
        }
      }
  }

  private suspend fun runDownload(
    id: String,
    url: String,
    tmpDir: File,
  ) {
    ensureNewPipe()

    update(id) { it.copy(stage = DownloadStage.Fetching, progress = -1f) }
    val info = StreamInfo.getInfo(url)
    val title = info.name?.takeIf { it.isNotBlank() } ?: "Video"
    val uploader = info.uploaderName ?: ""

    val thumbPath = saveThumbnail(id, info.thumbnails)
    update(id) { it.copy(title = title, thumbnailPath = thumbPath) }

    val plan = choosePlan(info) ?: throw IOException("No downloadable stream found for this link")
    update(id) { it.copy(quality = plan.quality, stage = DownloadStage.Downloading, progress = 0f) }

    val baseName = sanitizeFileName(title)

    val result: SavedFile =
      when (plan) {
        is Plan.Merge -> {
          val videoTmp = File(tmpDir, "video.part")
          val audioTmp = File(tmpDir, "audio.part")

          val videoUrl = plan.video.content
          val audioUrl = plan.audio.content
          val videoLen = probeLength(videoUrl)
          val audioLen = probeLength(audioUrl)
          val totalBytes = (maxOf(videoLen, 0L) + maxOf(audioLen, 0L)).toFloat()
          var doneBytes = 0L
          val onBytes: (Long) -> Unit = { delta ->
            doneBytes += delta
            if (totalBytes > 0f) {
              val fraction = (doneBytes / totalBytes).coerceIn(0f, 1f)
              update(id) { it.copy(progress = fraction * 0.97f) }
            }
          }

          downloadTo(videoUrl, videoTmp, videoLen, onBytes)
          downloadTo(audioUrl, audioTmp, audioLen, onBytes)

          update(id) { it.copy(stage = DownloadStage.Merging, progress = -1f) }
          val ext = if (plan.webm) "webm" else "mp4"
          val merged = File(tmpDir, "merged.$ext")
          mux(videoTmp, audioTmp, merged, plan.webm)
          videoTmp.delete()
          audioTmp.delete()

          update(id) { it.copy(stage = DownloadStage.Saving, progress = -1f) }
          saveToMediaStore(merged, "$baseName.$ext", if (plan.webm) "video/webm" else "video/mp4", isAudio = false)
        }

        is Plan.Single -> {
          val tmp = File(tmpDir, "single.part")
          val len = probeLength(plan.url)
          var doneBytes = 0L
          downloadTo(plan.url, tmp, len) { delta ->
            doneBytes += delta
            if (len > 0) {
              update(id) { it.copy(progress = (doneBytes.toFloat() / len).coerceIn(0f, 1f) * 0.98f) }
            }
          }
          update(id) { it.copy(stage = DownloadStage.Saving, progress = -1f) }
          saveToMediaStore(tmp, "$baseName.${plan.extension}", plan.mimeType, plan.isAudio)
        }
      }

    val item =
      DownloadedItem(
        id = id,
        sourceUrl = url,
        title = title,
        uploader = uploader,
        thumbnailPath = thumbPath,
        quality = plan.quality,
        isAudio = (plan as? Plan.Single)?.isAudio ?: false,
        sizeBytes = result.size,
        contentUri = result.location,
        createdAt = System.currentTimeMillis(),
      )

    _completed.update { listOf(item) + it }
    writeStore(_completed.value)
    _active.update { list -> list.filterNot { it.id == id } }
    MediaLibraryEvents.notifyChanged()
  }

  // ---------------------------------------------------------------------------------------------
  // Stream selection
  // ---------------------------------------------------------------------------------------------

  private sealed class Plan {
    abstract val quality: String

    class Merge(
      val video: VideoStream,
      val audio: AudioStream,
      val webm: Boolean,
      override val quality: String,
    ) : Plan()

    class Single(
      val url: String,
      val extension: String,
      val mimeType: String,
      val isAudio: Boolean,
      override val quality: String,
    ) : Plan()
  }

  private fun Stream.isDirect(): Boolean = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl

  private fun VideoStream.heightPx(): Int = resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

  private fun pickAudio(
    streams: List<AudioStream>,
    mime: String,
  ): AudioStream? =
    streams
      .filter { it.isDirect() && it.format?.mimeType == mime }
      // Prefer the original language track (YouTube can offer dubbed tracks), then highest bitrate.
      .sortedWith(
        compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }
          .thenByDescending { it.averageBitrate },
      ).firstOrNull()

  private fun choosePlan(info: StreamInfo): Plan? {
    val mp4Video =
      info.videoOnlyStreams
        .filter {
          it.isDirect() && it.format?.mimeType == "video/mp4" && it.codec?.startsWith("avc") == true &&
            it.heightPx() in 1..MAX_HEIGHT
        }.maxByOrNull { it.heightPx() }
    val m4a = pickAudio(info.audioStreams, "audio/mp4")

    val webmVideo =
      info.videoOnlyStreams
        .filter { it.isDirect() && it.format?.mimeType == "video/webm" && it.heightPx() in 1..MAX_HEIGHT }
        .maxByOrNull { it.heightPx() }
    val webmAudio = pickAudio(info.audioStreams, "audio/webm")

    val merge: Plan.Merge? =
      when {
        mp4Video != null && m4a != null -> Plan.Merge(mp4Video, m4a, false, "${mp4Video.heightPx()}p")
        webmVideo != null && webmAudio != null -> Plan.Merge(webmVideo, webmAudio, true, "${webmVideo.heightPx()}p")
        else -> null
      }

    val muxed =
      info.videoStreams
        .filter { it.isDirect() && it.heightPx() > 0 }
        .maxByOrNull { it.heightPx() }

    val mergeHeight = (merge?.video?.heightPx()) ?: 0
    val muxedHeight = muxed?.heightPx() ?: 0

    if (merge != null && mergeHeight >= muxedHeight) return merge

    if (muxed != null) {
      val format = muxed.format
      return Plan.Single(
        url = muxed.content,
        extension = format?.suffix ?: "mp4",
        mimeType = format?.mimeType ?: "video/mp4",
        isAudio = false,
        quality = "${muxed.heightPx()}p",
      )
    }

    val audio =
      info.audioStreams
        .filter { it.isDirect() }
        .maxByOrNull { it.averageBitrate }
    if (audio != null) {
      val format = audio.format
      return Plan.Single(
        url = audio.content,
        extension = format?.suffix ?: "m4a",
        mimeType = format?.mimeType ?: "audio/mp4",
        isAudio = true,
        quality = "Audio",
      )
    }

    return null
  }

  // ---------------------------------------------------------------------------------------------
  // Networking
  // ---------------------------------------------------------------------------------------------

  @Synchronized
  private fun ensureNewPipe() {
    if (newPipeReady) return
    NewPipe.init(NewPipeDownloader(client), Localization("en", "IN"), ContentCountry("IN"))
    newPipeReady = true
  }

  /** googlevideo URLs are tied to the client that requested them, so mimic that client's UA. */
  private fun userAgentFor(url: String): String {
    val clientName = Uri.parse(url).getQueryParameter("c")?.uppercase()
    val methodName =
      when {
        clientName == null -> null
        clientName.startsWith("ANDROID") -> "getAndroidUserAgent"
        clientName.startsWith("IOS") -> "getIosUserAgent"
        else -> null
      }
    if (methodName != null) {
      runCatching {
        val helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper")
        val method = helper.getMethod(methodName, Localization::class.java)
        (method.invoke(null, null as Localization?) as? String)?.let { return it }
      }
    }
    return NewPipeDownloader.USER_AGENT
  }

  /** Returns the size in bytes of the remote file or -1 when unknown. */
  private fun probeLength(url: String): Long {
    val request =
      Request
        .Builder()
        .url(url)
        .header("User-Agent", userAgentFor(url))
        .header("Range", "bytes=0-0")
        .build()
    return try {
      client.newCall(request).execute().use { response ->
        when {
          response.code == 206 -> {
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
          }

          response.isSuccessful -> response.header("Content-Length")?.toLongOrNull() ?: -1L
          else -> -1L
        }
      }
    } catch (e: IOException) {
      -1L
    }
  }

  /**
   * Downloads [url] into [dest] using ranged chunks (YouTube throttles unchunked requests).
   * [onBytes] receives the number of bytes added (negative when a failed chunk is rolled back).
   */
  private suspend fun downloadTo(
    url: String,
    dest: File,
    totalLength: Long,
    onBytes: (Long) -> Unit,
  ) {
    val userAgent = userAgentFor(url)
    dest.parentFile?.mkdirs()

    dest.outputStream().buffered().use { out ->
      var position = 0L
      while (totalLength < 0 || position < totalLength) {
        currentCoroutineContext().ensureActive()

        val end = if (totalLength > 0) minOf(position + CHUNK_SIZE - 1, totalLength - 1) else position + CHUNK_SIZE - 1
        var attempt = 0
        var finished = false
        var chunk = ByteArray(0)

        while (true) {
          var received = 0L
          try {
            val request =
              Request
                .Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=$position-$end")
                .build()

            val buffer = ByteArrayOutputStream()
            var fullBody = false
            client.newCall(request).execute().use { response ->
              if (response.code == 416) {
                finished = true
                return@use
              }
              if (response.code != 200 && response.code != 206) {
                throw IOException("Server returned HTTP ${response.code}")
              }
              fullBody = response.code == 200
              val source = response.body.byteStream()
              val bytes = ByteArray(64 * 1024)
              while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(bytes)
                if (read == -1) break
                buffer.write(bytes, 0, read)
                received += read
                onBytes(read.toLong())
              }
            }
            chunk = buffer.toByteArray()
            if (fullBody) {
              // Server ignored the Range header and sent everything.
              if (position != 0L) throw IOException("Server does not support partial downloads")
              finished = true
            } else if (totalLength <= 0 && chunk.size < CHUNK_SIZE) {
              finished = true
            }
            break
          } catch (e: IOException) {
            onBytes(-received)
            if (++attempt >= MAX_RETRIES) throw e
            Log.w(TAG, "Chunk $position-$end failed (attempt $attempt): ${e.message}")
            kotlinx.coroutines.delay(1000L * attempt)
          }
        }

        out.write(chunk)
        position += chunk.size
        if (finished || chunk.isEmpty()) break
      }
    }
  }

  private fun saveThumbnail(
    id: String,
    thumbnails: List<Image>?,
  ): String? {
    val best = thumbnails?.maxByOrNull { it.height } ?: thumbnails?.lastOrNull() ?: return null
    return runCatching {
      val request = Request.Builder().url(best.url).header("User-Agent", NewPipeDownloader.USER_AGENT).build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val file = File(thumbDir, "$id.jpg")
        file.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        file.absolutePath
      }
    }.getOrNull()
  }

  // ---------------------------------------------------------------------------------------------
  // Muxing
  // ---------------------------------------------------------------------------------------------

  private suspend fun mux(
    video: File,
    audio: File,
    output: File,
    webm: Boolean,
  ) {
    val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    val muxer = MediaMuxer(output.absolutePath, format)
    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()
    var started = false
    try {
      videoExtractor.setDataSource(video.absolutePath)
      audioExtractor.setDataSource(audio.absolutePath)

      val videoTrack = findTrack(videoExtractor, "video/")
      val audioTrack = findTrack(audioExtractor, "audio/")
      videoExtractor.selectTrack(videoTrack)
      audioExtractor.selectTrack(audioTrack)

      val muxVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
      val muxAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
      muxer.start()
      started = true

      val buffer = ByteBuffer.allocateDirect(MUX_BUFFER_SIZE)
      val info = MediaCodec.BufferInfo()

      // Interleave both tracks by timestamp so the resulting file is seek friendly.
      while (true) {
        currentCoroutineContext().ensureActive()
        val videoTime = videoExtractor.sampleTime
        val audioTime = audioExtractor.sampleTime
        if (videoTime < 0 && audioTime < 0) break

        val useVideo = audioTime < 0 || (videoTime in 0..audioTime)
        val extractor = if (useVideo) videoExtractor else audioExtractor
        val track = if (useVideo) muxVideo else muxAudio

        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
          // Make sure we never spin on a track that reports a time but no data.
          extractor.advance()
          continue
        }
        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        info.set(0, size, extractor.sampleTime, flags)
        muxer.writeSampleData(track, buffer, info)
        extractor.advance()
      }
    } finally {
      runCatching { if (started) muxer.stop() }
      runCatching { muxer.release() }
      videoExtractor.release()
      audioExtractor.release()
    }
  }

  private fun findTrack(
    extractor: MediaExtractor,
    mimePrefix: String,
  ): Int {
    for (i in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
      if (mime != null && mime.startsWith(mimePrefix)) return i
    }
    throw IOException("No $mimePrefix track found in downloaded stream")
  }

  // ---------------------------------------------------------------------------------------------
  // Saving
  // ---------------------------------------------------------------------------------------------

  private class SavedFile(
    val location: String,
    val size: Long,
  )

  @SuppressLint("InlinedApi")
  @Suppress("DEPRECATION")
  private fun saveToMediaStore(
    source: File,
    displayName: String,
    mimeType: String,
    isAudio: Boolean,
  ): SavedFile {
    val size = source.length()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val collection =
        if (isAudio) {
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
          MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
      val values =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
          put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
          put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (isAudio) "${Environment.DIRECTORY_MUSIC}/$APP_FOLDER" else "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER",
          )
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
      val resolver = appContext.contentResolver
      val uri = resolver.insert(collection, values) ?: throw IOException("Could not create file in MediaStore")
      try {
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
          ?: throw IOException("Could not open output stream")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
      } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
      }
      return SavedFile(uri.toString(), size)
    }

    val dir =
      File(
        Environment.getExternalStoragePublicDirectory(
          if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES,
        ),
        APP_FOLDER,
      )
    dir.mkdirs()
    var target = File(dir, displayName)
    var counter = 1
    while (target.exists()) {
      target = File(dir, "${displayName.substringBeforeLast('.')} ($counter).${displayName.substringAfterLast('.')}")
      counter++
    }
    source.copyTo(target)
    MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(mimeType), null)
    return SavedFile(target.absolutePath, size)
  }

  private fun fileStillExists(item: DownloadedItem): Boolean =
    runCatching {
      if (item.contentUri.startsWith("content://")) {
        appContext.contentResolver
          .query(Uri.parse(item.contentUri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
          ?.use { it.count > 0 } ?: false
      } else {
        File(item.contentUri).exists()
      }
    }.getOrDefault(true)

  private fun sanitizeFileName(title: String): String {
    val cleaned =
      title
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
    return cleaned.take(100).ifBlank { "video" }
  }

  // ---------------------------------------------------------------------------------------------
  // State helpers
  // ---------------------------------------------------------------------------------------------

  private fun update(
    id: String,
    block: (ActiveDownload) -> ActiveDownload,
  ) {
    _active.update { list -> list.map { if (it.id == id) block(it) else it } }
  }

  private fun readStore(): List<DownloadedItem> =
    synchronized(storeLock) {
      runCatching {
        if (storeFile.exists()) {
          json.decodeFromString(ListSerializer(DownloadedItem.serializer()), storeFile.readText())
        } else {
          emptyList()
        }
      }.getOrElse {
        Log.w(TAG, "Could not read downloads store", it)
        emptyList()
      }
    }

  private fun writeStore(items: List<DownloadedItem>) {
    synchronized(storeLock) {
      runCatching {
        baseDir.mkdirs()
        val tmp = File(baseDir, "downloads.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(DownloadedItem.serializer()), items))
        if (!tmp.renameTo(storeFile)) {
          storeFile.writeText(tmp.readText())
          tmp.delete()
        }
      }.onFailure { Log.w(TAG, "Could not write downloads store", it) }
    }
  }

  companion object {
    private const val TAG = "YouTubeDownloads"
    private const val APP_FOLDER = "mpvEx"
    private const val MAX_HEIGHT = 1080
    private const val CHUNK_SIZE = 10L * 1024 * 1024
    private const val MAX_RETRIES = 3
    private const val MUX_BUFFER_SIZE = 4 * 1024 * 1024
    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
  }
}
