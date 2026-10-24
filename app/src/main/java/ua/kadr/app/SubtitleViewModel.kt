package ua.kadr.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Phase {
    data object NoVideo : Phase
    data object Idle : Phase
    data class Downloading(val progress: Float) : Phase
    data class Recognising(val progress: Float) : Phase
    data object Ready : Phase
    data class Exporting(val progress: Float?) : Phase
    data class Saved(val uri: Uri) : Phase
    data class Failed(val message: String) : Phase
}

@UnstableApi
class SubtitleViewModel(app: Application) : AndroidViewModel(app) {
    private val transcriber = Transcriber(app)
    private val exporter = Exporter(app)
    private val prefs = Prefs(app)
    private var job: Job? = null

    var videoUri by mutableStateOf<Uri?>(null); private set
    var info by mutableStateOf(VideoInfo(1080, 1920, 0)); private set
    var phase by mutableStateOf<Phase>(Phase.NoVideo); private set
    var lang by mutableStateOf(prefs.lang)
    var style by mutableStateOf(prefs.captionStyle)
    private var words: List<Word> = emptyList()
    val cues = mutableStateListOf<Cue>()

    fun updateStyle(s: CaptionStyle) {
        val regroup = s.maxWords != style.maxWords
        style = s; prefs.captionStyle = s
        if (regroup && words.isNotEmpty()) { cues.clear(); cues += CueBuilder.build(words, s.maxWords) }
    }

    fun chooseLang(l: SpeechLang) { lang = l; prefs.lang = l }

    fun setVideo(uri: Uri) {
        job?.cancel()
        videoUri = uri
        words = emptyList(); cues.clear()
        phase = try { info = VideoProbe.read(getApplication(), uri); Phase.Idle }
        catch (e: Exception) { Phase.Failed("Не вдалося відкрити відео") }
    }

    fun modelReady(l: SpeechLang = lang) = transcriber.isModelReady(l)

    fun generate() {
        val uri = videoUri ?: return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                if (!transcriber.isModelReady(lang)) {
                    phase = Phase.Downloading(0f)
                    transcriber.downloadModel(lang) { p -> phase = Phase.Downloading(p) }
                }
                phase = Phase.Recognising(0f)
                val w = transcriber.transcribe(uri, lang) { p -> phase = Phase.Recognising(p) }
                words = w
                cues.clear(); cues += CueBuilder.build(w, style.maxWords)
                phase = if (w.isEmpty()) Phase.Failed("Мову не розпізнано. Перевірте, чи є в відео голос і чи правильно вибрана мова.") else Phase.Ready
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                phase = Phase.Failed("Немає з'єднання з інтернетом для завантаження мовної моделі. Потрібно один раз, потім працює офлайн.")
            } catch (e: Throwable) {
                phase = Phase.Failed(e.message ?: "Сталася помилка під час розпізнавання")
            }
        }
    }

    fun cancel() {
        job?.cancel(); exporter.cancel()
        phase = if (cues.isNotEmpty()) Phase.Ready else Phase.Idle
    }

    fun editCue(index: Int, text: String) {
        val c = cues.getOrNull(index) ?: return
        val original = c.words.joinToString(" ") { it.text }
        cues[index] = c.copy(textOverride = text.takeIf { it != original })
    }

    fun deleteCue(index: Int) { if (index in cues.indices) cues.removeAt(index) }

    fun export() {
        val uri = videoUri ?: return
        phase = Phase.Exporting(0f)
        exporter.start(uri, info, cues.toList(), style,
            onDone = { phase = Phase.Saved(it) },
            onError = { phase = Phase.Failed(it) })
        job = viewModelScope.launch {
            while (phase is Phase.Exporting) {
                phase = Phase.Exporting(exporter.progress())
                delay(300)
            }
        }
    }

    fun backToEditing() { phase = if (cues.isNotEmpty()) Phase.Ready else if (videoUri != null) Phase.Idle else Phase.NoVideo }

    fun srt(): String = CueBuilder.toSrt(cues)
}
