package ua.kadr.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Splits a script into words and remembers where each word starts in the text. */
class ScriptWords(text: String) {
    val starts = mutableListOf<Int>()
    val words = mutableListOf<String>()
    init {
        Regex("\\S+").findAll(text).forEach { m ->
            val n = normalize(m.value)
            if (n.isNotEmpty()) { starts += m.range.first; words += n }
        }
    }
    companion object {
        fun normalize(w: String): String =
            w.lowercase().replace('ё', 'е').replace('’', '\'').replace('ʼ', '\'')
                .filter { it.isLetterOrDigit() || it == '\'' }
    }
}

/**
 * Listens to the microphone while the prompter runs.
 * With a speech model it follows the script word by word; without one it only
 * reports whether you are speaking, so the text moves while you talk and stops when you pause.
 */
class VoiceFollower(private val script: ScriptWords, private val model: Model?) {
    val speaking = MutableStateFlow(false)
    /** Index in [ScriptWords.words] of the next word to say; -1 until the first match. */
    val position = MutableStateFlow(-1)
    /** True when another recording (the camera) holds the microphone and we only hear silence. */
    val silenced = MutableStateFlow(false)
    val tracking: Boolean get() = recognizer != null

    private var recognizer: Recognizer? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var pointer = 0

    @SuppressLint("MissingPermission")
    fun start(startAt: Int) {
        if (running) return
        pointer = startAt.coerceAtLeast(0)
        recognizer = model?.let { m ->
            val vocab = JSONArray().apply { script.words.toSet().forEach { put(it) }; put("[unk]") }
            runCatching { Recognizer(m, 16000f, vocab.toString()) }.getOrNull()
                ?: runCatching { Recognizer(m, 16000f) }.getOrNull()
        }
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 3200) * 2)
        } catch (e: Exception) { silenced.value = true; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); silenced.value = true; return }
        if (Build.VERSION.SDK_INT >= 29) {
            rec.registerAudioRecordingCallback(Executors.newSingleThreadExecutor(), object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
                    silenced.value = mine.isClientSilenced
                }
            })
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "voice-follow").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
        record?.let { runCatching { it.stop() }; it.release() }; record = null
        recognizer?.close(); recognizer = null
        speaking.value = false
    }

    private fun loop(rec: AudioRecord) {
        rec.startRecording()
        val buf = ShortArray(1600) // 100 ms
        var lastVoiceAt = 0L
        var zeroSince = System.currentTimeMillis()
        var noise = 300.0
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            var sum = 0.0; var allZero = true
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v; if (buf[i].toInt() != 0) allZero = false }
            val rms = sqrt(sum / n)
            val now = System.currentTimeMillis()

            // Without the system callback, a long run of exact zeros means another recording took the microphone.
            if (!allZero) zeroSince = now
            if (Build.VERSION.SDK_INT < 29) silenced.value = now - zeroSince > 1500

            // Adaptive threshold: track the background level, call it speech when clearly above it.
            if (rms < noise * 1.5) noise = noise * 0.95 + rms * 0.05
            if (rms > maxOf(noise * 2.6, 500.0)) lastVoiceAt = now
            speaking.value = now - lastVoiceAt < 700

            recognizer?.let { r ->
                val final = r.acceptWaveForm(buf, n)
                val json = JSONObject(if (final) r.result else r.partialResult)
                val text = if (final) json.optString("text") else json.optString("partial")
                val heard = text.split(' ').map { ScriptWords.normalize(it) }.filter { it.isNotEmpty() && it != "unk" }
                if (heard.isNotEmpty()) match(heard, commit = final)
            }
        }
    }

    /** Finds where the last heard words sit in the script, looking a little behind and ahead of the current place. */
    private fun match(heard: List<String>, commit: Boolean) {
        val words = script.words
        val tail = heard.takeLast(4)
        var best = -1; var bestScore = 0
        val from = (pointer - 4).coerceAtLeast(0)
        val to = (pointer + 30).coerceAtMost(words.size - 1)
        for (j in from..to) {
            var score = 0
            var k = tail.size - 1; var i = j
            while (k >= 0 && i >= 0 && similar(tail[k], words[i])) { score++; k--; i-- }
            if (score == 0) continue
            val better = score > bestScore || (score == bestScore && best >= 0 && abs(j - pointer) < abs(best - pointer))
            if (better) { best = j; bestScore = score }
        }
        if (best < 0) return
        val confident = bestScore >= 2 || (words[best].length >= 4 && best - pointer in -1..8)
        if (!confident) return
        position.value = best + 1
        if (commit || bestScore >= 2) pointer = best + 1
    }

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 5 && b.length >= 5 && a.take(4) == b.take(4)) return true
        if (a.length < 4 || b.length < 4 || abs(a.length - b.length) > 1) return false
        // One edit apart.
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}

/** Keeps one loaded speech model in memory for the prompter. */
object ModelCache {
    private var lang: SpeechLang? = null
    private var model: Model? = null
    @Synchronized fun get(path: String, l: SpeechLang): Model? {
        if (lang == l && model != null) return model
        model?.close()
        model = runCatching { Model(path) }.getOrNull()
        lang = l
        return model
    }
}
