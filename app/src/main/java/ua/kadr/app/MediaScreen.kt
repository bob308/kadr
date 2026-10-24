package ua.kadr.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(val uri: Uri, val name: String, val durationMs: Long, val dateSec: Long)

private fun loadClips(ctx: Context): List<Clip> {
    val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED)
    val (sel, args) = if (Build.VERSION.SDK_INT >= 29)
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Movies/Kadr%")
    else @Suppress("DEPRECATION") "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/Kadr/%")
    val out = mutableListOf<Clip>()
    ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, sel, args,
        "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Clip(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                c.getString(1) ?: "", c.getLong(2), c.getLong(3))
        }
    }
    return out
}

private fun thumbnail(ctx: Context, clip: Clip): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(clip.uri, Size(360, 360), null)
    else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver,
        ContentUris.parseId(clip.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
}.getOrNull()

/** Clips recorded by the app (Movies/Kadr), newest first. */
@Composable
fun MediaScreen(onClose: () -> Unit, onSubtitles: (Uri) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onClose() }
    val clips by produceState<List<Clip>?>(null) { value = withContext(Dispatchers.IO) { loadClips(ctx) } }
    var chosen by remember { mutableStateOf<Clip?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("uk")) }

    Column(Modifier.fillMaxSize().background(Color(0xFF15181E)).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClose).padding(12.dp)) { KIcon(KIcons.Back) }
            Text("Медіа", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        val list = clips
        when {
            list == null -> {}
            list.isEmpty() -> Text("Тут з'являться відео, записані в «Суфлер + камера».", color = Muted,
                fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            else -> LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.uri.toString() }) { clip ->
                    Column(Modifier.clickable { chosen = clip }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                            val thumb by produceState<Bitmap?>(null, clip) { value = withContext(Dispatchers.IO) { thumbnail(ctx, clip) } }
                            thumb?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                            Text(formatTime(clip.durationMs), color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                        }
                        Text(fmt.format(Date(clip.dateSec * 1000)), color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                    }
                }
            }
        }
    }

    chosen?.let { clip ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(fmt.format(Date(clip.dateSec * 1000)) + " · " + formatTime(clip.durationMs)) },
            text = {
                Column {
                    TextButton(onClick = {
                        chosen = null
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(clip.uri, "video/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Переглянути") }
                    TextButton(onClick = { chosen = null; onSubtitles(clip.uri) }) { Text("Додати субтитри") }
                    TextButton(onClick = {
                        chosen = null
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*")
                            .putExtra(Intent.EXTRA_STREAM, clip.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поділитися"))
                    }) { Text("Поділитися") }
                }
            },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text("Закрити") } },
        )
    }
}
