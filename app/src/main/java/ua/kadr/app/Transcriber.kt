package ua.kadr.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

enum class SpeechLang(val label: String, val modelName: String, val sizeMb: Int) {
    UK("Українська", "vosk-model-small-uk-v3-small", 133),
    EN("English", "vosk-model-small-en-us-0.15", 40),
    RU("Русский", "vosk-model-small-ru-0.22", 45);

    val url: String get() = "https://alphacephei.com/vosk/models/$modelName.zip"
}

/** Offline speech recognition with Vosk: downloads the language model once, then works without internet. */
class Transcriber(private val context: Context) {

    private fun modelDir(lang: SpeechLang) = File(context.filesDir, "models/${lang.modelName}")

    fun isModelReady(lang: SpeechLang): Boolean = File(modelDir(lang), "ready").exists()

    suspend fun downloadModel(lang: SpeechLang, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "models").apply { mkdirs() }
        val zip = File(root, "${lang.modelName}.zip")
        val conn = (URL(lang.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("Сервер моделі відповів ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (lang.sizeMb * 1_000_000L)
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress((done.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
        } finally { conn.disconnect() }

        // The archive holds one top folder named like the model; unpack it into models/.
        val dest = modelDir(lang)
        dest.deleteRecursively()
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(dest, rel)
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        File(dest, "ready").writeText("ok")
        onProgress(1f)
    }

    /** Decodes the video's sound to 16 kHz mono and returns every recognised word with its timing. */
    suspend fun transcribe(uri: Uri, lang: SpeechLang, onProgress: (Float) -> Unit): List<Word> =
        withContext(Dispatchers.Default) {
            val model = Model(modelDir(lang).absolutePath)
            val rec = Recognizer(model, 16000f).apply { setWords(true) }
            val words = mutableListOf<Word>()
            fun collect(json: String) {
                val arr = JSONObject(json).optJSONArray("result") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.getString("word").trim()
                    if (t.isNotEmpty()) words += Word(t, (o.getDouble("start") * 1000).toLong(), (o.getDouble("end") * 1000).toLong())
                }
            }
            try {
                decodeTo16kMono(uri, onProgress) { chunk, len ->
                    if (rec.acceptWaveForm(chunk, len)) collect(rec.result)
                }
                collect(rec.finalResult)
            } finally {
                rec.close(); model.close()
            }
            words
        }

    private suspend fun decodeTo16kMono(uri: Uri, onProgress: (Float) -> Unit, sink: (ShortArray, Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        if (track < 0) { ex.release(); error("У цьому відео немає звуку") }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        // Resampler state: position in source samples of the next output sample.
        var srcPos = 0.0
        var prevSample = 0f
        var srcIndexBase = 0L
        val out = ShortArray(8000)
        var outLen = 0

        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            if (durationUs > 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frames = if (isFloat) info.size / 4 / channels else info.size / 2 / channels
                        val mono = FloatArray(frames)
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            for (i in 0 until frames) { var s = 0f; for (ch in 0 until channels) s += fb.get(); mono[i] = s / channels }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (i in 0 until frames) { var s = 0f; for (ch in 0 until channels) s += sb.get() / 32768f; mono[i] = s / channels }
                        }
                        codec.releaseOutputBuffer(outIdx, false)

                        // Linear resampling to 16 kHz.
                        val step = srcRate / 16000.0
                        while (true) {
                            val local = srcPos - srcIndexBase
                            val i0 = kotlin.math.floor(local).toInt()
                            if (i0 + 1 >= frames) break
                            val frac = (local - i0).toFloat()
                            val a = if (i0 < 0) prevSample else mono[i0]
                            val b = mono[i0 + 1]
                            val v = (a + (b - a) * frac).coerceIn(-1f, 1f)
                            out[outLen++] = (v * 32767).toInt().toShort()
                            if (outLen == out.size) { sink(out, outLen); outLen = 0 }
                            srcPos += step
                        }
                        if (frames > 0) prevSample = mono[frames - 1]
                        srcIndexBase += frames
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (outLen > 0) sink(out, outLen)
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
    }
}
