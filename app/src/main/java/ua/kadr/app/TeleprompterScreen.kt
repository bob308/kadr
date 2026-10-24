package ua.kadr.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val f = ProcessCameraProvider.getInstance(this)
    f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(this))
}

private val neededPerms: Array<String> = buildList {
    add(Manifest.permission.CAMERA); add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

private fun hasPerms(ctx: Context) = neededPerms
    .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission")
@Composable
fun TeleprompterScreen(onBack: () -> Unit, onAddSubtitles: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(neededPerms) }

    var script by remember { mutableStateOf(prefs.script) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var font by remember { mutableFloatStateOf(prefs.fontSize) }
    var mirror by remember { mutableStateOf(prefs.mirror) }
    var front by remember { mutableStateOf(prefs.frontCamera) }
    var countdownOn by remember { mutableStateOf(prefs.countdown) }
    var editing by remember { mutableStateOf(false) }
    var scrolling by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var lastVideo by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val scroll = rememberScrollState()
    val density = LocalDensity.current

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                .build()
        )
    }

    LaunchedEffect(granted, front) {
        if (!granted) return@LaunchedEffect
        try {
            val provider = ctx.cameraProvider()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
        } catch (e: Exception) {
            error = "Не вдалося увімкнути камеру"
        }
    }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    // Smooth auto-scroll: speed is in dp per second.
    LaunchedEffect(scrolling) {
        if (!scrolling) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1_000_000_000f
                    scroll.dispatchRawDelta(with(density) { (speed * dt).dp.toPx() })
                }
                last = t
            }
            if (scroll.value >= scroll.maxValue) scrolling = false
        }
    }
    LaunchedEffect(recording) {
        while (recording != null) { elapsed = System.currentTimeMillis() - recStart; delay(250) }
    }

    fun startRecording() {
        val name = "Kadr_${System.currentTimeMillis()}"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kadr")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        recStart = System.currentTimeMillis(); elapsed = 0
        lastVideo = null
        recording = videoCapture.output.prepareRecording(ctx, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        error = "Запис не вдався (код ${ev.error})"
                    } else lastVideo = ev.outputResults.outputUri
                    recording = null
                }
            }
        scope.launch { scroll.scrollTo(0); scrolling = true }
    }

    fun onRecordPressed() {
        val r = recording
        if (r != null) { r.stop(); scrolling = false; return }
        if (countdown > 0) return
        if (!countdownOn) { startRecording(); return }
        scope.launch {
            scroll.scrollTo(0)
            for (i in 3 downTo 1) { countdown = i; delay(1000) }
            countdown = 0
            startRecording()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Потрібен доступ до камери й мікрофона", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(neededPerms) }) {
                    Text("Дозволити")
                }
            }
        }

        // Prompter panel near the top, close to the front camera.
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.TopCenter)) {
            val panelH = maxHeight
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { scrolling = !scrolling }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .graphicsLayer { if (mirror) scaleX = -1f }
                        .padding(start = 20.dp, end = 20.dp, top = panelH * 0.45f, bottom = panelH)
                ) {
                    Text(script, color = Color.White, fontSize = font.sp, lineHeight = (font * 1.35f).sp, fontWeight = FontWeight.SemiBold)
                }
                // Reading line: the text crossing this mark is the line to say now.
                Box(Modifier.padding(top = panelH * 0.45f).width(6.dp).height((font * 1.35f).dp).background(CapYellow).align(Alignment.TopStart))
            }
        }

        // Top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (recording == null) onBack() }) { Text("‹ Назад", color = Color.White) }
            Spacer(Modifier.weight(1f))
            if (recording != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                Spacer(Modifier.width(6.dp))
                Text(formatTime(elapsed), color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }
        }

        if (countdown > 0) {
            Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center))
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f)).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lastVideo?.let { uri ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Відео збережено в галерею", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAddSubtitles(uri) }) { Text("Додати субтитри", color = CapYellow) }
                }
                Spacer(Modifier.height(6.dp))
            }
            error?.let { Text(it, color = RecRed, modifier = Modifier.clickable { error = null }) }

            LabeledSlider("Швидкість", speed, 15f..200f) { speed = it; prefs.speed = it }
            LabeledSlider("Шрифт", font, 18f..56f) { font = it; prefs.fontSize = it }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.Start) {
                    SmallToggle("Текст", false) { if (recording == null) { scrolling = false; editing = true } }
                    SmallToggle("Дзеркало", mirror) { mirror = !mirror; prefs.mirror = mirror }
                }
                // Record button
                Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(if (recording != null) RoundedCornerShape(8.dp) else CircleShape)
                    .background(RecRed).clickable(enabled = granted) { onRecordPressed() })
                Column(horizontalAlignment = Alignment.End) {
                    SmallToggle(if (scrolling) "Пауза" else "Пуск", scrolling) { scrolling = !scrolling }
                    SmallToggle("Камера ⟲", false) { if (recording == null) { front = !front; prefs.frontCamera = front } }
                    SmallToggle("Відлік 3с", countdownOn) { countdownOn = !countdownOn; prefs.countdown = countdownOn }
                }
            }
        }

        if (editing) {
            Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Текст для суфлера", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { script = ""; }) { Text("Очистити", color = Muted) }
                    Button(onClick = { prefs.script = script; editing = false; scope.launch { scroll.scrollTo(0) } }) { Text("Готово") }
                }
                OutlinedTextField(value = script, onValueChange = { script = it }, modifier = Modifier.fillMaxWidth().weight(1f),
                    placeholder = { Text("Вставте або напишіть свій сценарій") })
                Text("Слів: ${script.split(Regex("\\s+")).count { it.isNotBlank() }} · приблизно ${readMinutes(script)} хв читання",
                    color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f).height(32.dp))
        Text("${value.toInt()}", color = Muted, fontSize = 13.sp, modifier = Modifier.width(36.dp).padding(start = 8.dp))
    }
}

@Composable
fun SmallToggle(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = if (on) CapYellow else Color.White)) {
        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun readMinutes(text: String): String {
    val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
    val min = words / 130f
    return if (min < 1) "<1" else "%.1f".format(min)
}
