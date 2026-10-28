package app.marlboroadvance.mpvex.ui.browser.downloads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight
import app.marlboroadvance.mpvex.utils.download.ActiveDownload
import app.marlboroadvance.mpvex.utils.download.DownloadStage
import app.marlboroadvance.mpvex.utils.download.DownloadedItem
import app.marlboroadvance.mpvex.utils.download.YouTubeDownloadManager
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.Locale

/**
 * 4th tab of the main screen. Paste a YouTube (or other supported) link and it gets
 * downloaded with the NewPipe extractor engine.
 */
object DownloadsScreen {
  @Composable
  fun Content() {
    val context = LocalContext.current
    val manager = koinInject<YouTubeDownloadManager>()

    val completed by manager.completed.collectAsState()
    val active by manager.active.collectAsState()

    var link by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadedItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val bottomPadding = LocalNavigationBarHeight.current

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { manager.refresh() } }
    LaunchedEffect(message) {
      message?.let {
        snackbarHostState.showSnackbar(it)
        message = null
      }
    }

    fun startDownload(text: String) {
      val error = manager.enqueue(text)
      if (error != null) {
        message = error
      } else {
        link = ""
      }
    }

    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "header") {
          Header(count = completed.size)
        }

        item(key = "input") {
          Spacer(Modifier.height(8.dp))
          LinkInput(
            value = link,
            onValueChange = { link = it },
            onSubmit = { if (link.isNotBlank()) startDownload(link) },
            onPaste = {
              val clip = readClipboard(context)
              if (clip.isNullOrBlank()) {
                message = "Clipboard is empty"
              } else {
                link = clip
                startDownload(clip)
              }
            },
          )
          Spacer(Modifier.height(8.dp))
        }

        if (active.isNotEmpty()) {
          item(key = "active_label") { SectionLabel("Downloading") }
          items(active, key = { "active_${it.id}" }) { download ->
            ActiveDownloadCard(
              download = download,
              onCancel = { manager.cancel(download.id) },
              onRetry = { manager.retry(download.id) },
            )
          }
        }

        if (completed.isNotEmpty()) {
          item(key = "done_label") { SectionLabel("Downloaded") }
          items(completed, key = { "done_${it.id}" }) { item ->
            DownloadedCard(
              item = item,
              onPlay = { MediaUtils.playFile(item.contentUri, context, "downloads") },
              onCopyLink = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", item.sourceUrl))
                message = "Link copied"
              },
              onDelete = { pendingDelete = item },
            )
          }
        }

        if (active.isEmpty() && completed.isEmpty()) {
          item(key = "empty") { EmptyHint() }
        }
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding),
      )
    }

    pendingDelete?.let { item ->
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("Delete download?") },
        text = { Text("\"${item.title}\" will be removed from your device.") },
        confirmButton = {
          TextButton(
            onClick = {
              manager.delete(item)
              pendingDelete = null
            },
          ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
      )
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Pieces
// -------------------------------------------------------------------------------------------------

@Composable
private fun Header(count: Int) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "Downloads",
      fontSize = 32.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.width(14.dp))
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(
        text = "($count)",
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun LinkInput(
  value: String,
  onValueChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onPaste: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().height(60.dp),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(start = 20.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.Link,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(26.dp),
      )
      Spacer(Modifier.width(14.dp))
      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          singleLine = true,
          textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(onGo = { onSubmit() }),
          modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
          Text(
            text = "Paste YouTube or web link...",
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(Modifier.width(8.dp))
      Surface(
        onClick = { if (value.isBlank()) onPaste() else onSubmit() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Box(modifier = Modifier.height(44.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
          Text(
            text = if (value.isBlank()) "Paste" else "Download",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    fontSize = 16.sp,
    fontWeight = FontWeight.Medium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun DownloadedCard(
  item: DownloadedItem,
  onPlay: () -> Unit,
  onCopyLink: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onPlay),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Thumbnail(path = item.thumbnailPath, badge = item.quality)
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.title,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "${item.quality} • ${if (item.isAudio) "Audio" else "Video"} • ${formatSize(item.sizeBytes)}",
          fontSize = 14.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("Play") },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = {
              menuOpen = false
              onPlay()
            },
          )
          DropdownMenuItem(
            text = { Text("Copy link") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = {
              menuOpen = false
              onCopyLink()
            },
          )
          DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = {
              menuOpen = false
              onDelete()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun ActiveDownloadCard(
  download: ActiveDownload,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
) {
  val failed = download.stage == DownloadStage.Failed

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Thumbnail(path = download.thumbnailPath, badge = download.quality.ifBlank { null })
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = download.title.ifBlank { download.sourceUrl },
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = statusText(download),
          fontSize = 14.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!failed) {
          Spacer(Modifier.height(8.dp))
          if (download.progress >= 0f) {
            LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
        }
      }
      if (failed) {
        IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry") }
      }
      IconButton(onClick = onCancel) {
        Icon(Icons.Filled.Close, contentDescription = if (failed) "Dismiss" else "Cancel")
      }
    }
  }
}

@Composable
private fun Thumbnail(
  path: String?,
  badge: String?,
) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
    value =
      path?.let {
        withContext(Dispatchers.IO) {
          runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(it, options)?.asImageBitmap()
          }.getOrNull()
        }
      }
  }

  Box(
    modifier =
      Modifier
        .width(120.dp)
        .height(68.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    val image = bitmap
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        imageVector = Icons.Filled.FileDownload,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center).size(28.dp),
      )
    }

    if (!badge.isNullOrBlank()) {
      Surface(
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
      ) {
        Text(
          text = badge,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun EmptyHint() {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = Icons.Filled.FileDownload,
      contentDescription = null,
      modifier = Modifier.size(48.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      text = "No downloads yet",
      fontSize = 18.sp,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = "Paste a YouTube link above to save the video on your device.",
      fontSize = 14.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// -------------------------------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------------------------------

private fun statusText(download: ActiveDownload): String =
  when (download.stage) {
    DownloadStage.Fetching -> "Fetching video info…"
    DownloadStage.Downloading -> {
      val percent = if (download.progress >= 0f) " • ${(download.progress * 100).toInt()}%" else ""
      "Downloading ${download.quality}$percent"
    }
    DownloadStage.Merging -> "Merging audio and video…"
    DownloadStage.Saving -> "Saving…"
    DownloadStage.Failed -> "Failed: ${download.error ?: "unknown error"}"
  }

private fun readClipboard(context: Context): String? {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip = cm.primaryClip ?: return null
  if (clip.itemCount == 0) return null
  return clip.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun formatSize(bytes: Long): String {
  if (bytes <= 0) return "0 MB"
  val mb = bytes / (1024.0 * 1024.0)
  return if (mb >= 1024) {
    String.format(Locale.US, "%.2f GB", mb / 1024.0)
  } else {
    String.format(Locale.US, "%.1f MB", mb)
  }
}
