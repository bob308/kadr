package ua.kadr.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

object VideoProbe {
    /** Display size of the video, with rotation already applied. */
    fun read(context: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return if (rot % 180 != 0) VideoInfo(h, w, d) else VideoInfo(w, h, d)
        } finally { r.release() }
    }
}

@UnstableApi
private class CaptionOverlay(
    private val renderer: CaptionRenderer,
    private val cues: List<Cue>,
    private val style: CaptionStyle,
) : BitmapOverlay() {
    private val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, style.position.y)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        return renderer.render(CueBuilder.cueAt(cues, ms), ms, style)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

/** Burns the subtitles into a copy of the video. Must be started on the main thread. */
@UnstableApi
class Exporter(private val context: Context) {
    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    fun start(
        uri: Uri, info: VideoInfo, cues: List<Cue>, style: CaptionStyle,
        onDone: (Uri) -> Unit, onError: (String) -> Unit,
    ) {
        val out = File(context.cacheDir, "kadr_export.mp4").apply { delete() }
        val overlay = CaptionOverlay(CaptionRenderer(info.width, info.height), cues, style)
        val effects = Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()

        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    try { onDone(saveToGallery(out, "Kadr_${System.currentTimeMillis()}.mp4")) }
                    catch (e: Exception) { onError("Не вдалося зберегти в галерею: ${e.message}") }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    onError("Помилка обробки відео: ${exportException.errorCodeName}")
                }
            })
            .build()
        transformer!!.start(item, out.absolutePath)
    }

    /** Progress from 0 to 1, or null when unknown. */
    fun progress(): Float? {
        val t = transformer ?: return null
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else null
    }

    fun cancel() { transformer?.cancel(); transformer = null }

    private fun saveToGallery(file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Kadr")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val dest = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("немає доступу до галереї")
        resolver.openOutputStream(dest)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(dest, values, null, null)
        }
        file.delete()
        return dest
    }
}
