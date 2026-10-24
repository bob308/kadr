package ua.kadr.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.Surface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Range
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun hasPerms(ctx: Context) = neededPerms
    .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private enum class Sheet { NONE, SCRIPTS, SETTINGS }

private val DELAYS = listOf(0, 3, 5, 10, 15)

@SuppressLint("MissingPermission")
@Composable
fun TeleprompterScreen(onBack: () -> Unit, onAddSubtitles: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { Prefs(ctx) }
    val transcriber = remember { Transcriber(ctx) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var granted by remember { mutableStateOf(hasPerms(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(neededPerms) }

    // Scripts
    val scripts = remember { mutableStateListOf<Script>().apply { addAll(prefs.scripts) } }
    var currentId by remember { mutableLongStateOf(prefs.currentScriptId.takeIf { id -> scripts.any { it.id == id } } ?: scripts.first().id) }
    val script = scripts.firstOrNull { it.id == currentId }?.text ?: ""

    // Settings
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var font by remember { mutableFloatStateOf(prefs.fontSize) }
    var mirror by remember { mutableStateOf(prefs.mirror) }
    var front by remember { mutableStateOf(prefs.frontCamera) }
    var delaySec by remember { mutableIntStateOf(prefs.delaySec) }
    var landscape by remember { mutableStateOf(prefs.landscape) }
    var showSliders by remember { mutableStateOf(!prefs.landscape) }
    var mode by remember { mutableStateOf(prefs.scrollMode) }
    var panelPos by remember { mutableStateOf(prefs.panelPos) }
    var remoteOn by remember { mutableStateOf(prefs.remote) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var quality by remember { mutableStateOf(prefs.quality) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
    var grid by remember { mutableStateOf(prefs.grid) }
    var level by remember { mutableStateOf(prefs.level) }
    var paused by remember { mutableStateOf(false) }

    // Session state
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var scrolling by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recStart by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var lastVideo by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsH by remember { mutableIntStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var modelReady by remember { mutableStateOf(transcriber.isModelReady(lang)) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    val isLandscapeNow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scroll = rememberScrollState()
    val lineHeightPx = with(density) { (font * 1.35f).sp.toPx() }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val videoCapture = remember(quality, fps) {
        val q = when (quality) { "HD" -> Quality.HD; "UHD" -> Quality.UHD; else -> Quality.FHD }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .build()
        VideoCapture.Builder(recorder).apply { if (fps == 60) setTargetFrameRate(Range(60, 60)) }.build()
    }

    // Orientation lock: the video is recorded the same way the screen is turned.
    DisposableEffect(landscape) {
        ctx.findActivity()?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            ctx.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(granted, front, isLandscapeNow, videoCapture) {
        if (!granted) return@LaunchedEffect
        try {
            val provider = ctx.cameraProvider()
            delay(150) // let the view settle after a rotation
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            videoCapture.targetRotation = rotation
            val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
        } catch (e: Exception) {
            if (fps == 60) {
                error = "Ця камера не знімає 60 кадрів на секунду, повернув 30"
                fps = 30; prefs.fps = 30
            } else if (quality == "UHD") {
                error = "Ця камера не знімає 4K, повернув Full HD"
                quality = "FHD"; prefs.quality = "FHD"
            } else error = "Не вдалося увімкнути камеру"
        }
    }

    // ---------- Level: tilt of the phone relative to the screen's "down" ----------
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(level) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var gx = 0f; var gy = 9.8f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                gx = gx * 0.85f + e.values[0] * 0.15f
                gy = gy * 0.85f + e.values[1] * 0.15f
                val (sx, sy) = when (previewView.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> -gy to gx
                    Surface.ROTATION_180 -> -gx to -gy
                    Surface.ROTATION_270 -> gy to -gx
                    else -> gx to gy
                }
                roll = Math.toDegrees(kotlin.math.atan2(sx.toDouble(), sy.toDouble())).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (level && acc != null) sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    // ---------- Voice following ----------
    val words = remember(script) { ScriptWords(script) }
    var follower by remember { mutableStateOf<VoiceFollower?>(null) }
    val idleFlag = remember { MutableStateFlow(false) }
    val idlePos = remember { MutableStateFlow(-1) }
    val speaking by (follower?.speaking ?: idleFlag).collectAsState()
    val silenced by (follower?.silenced ?: idleFlag).collectAsState()
    val heardAt by (follower?.position ?: idlePos).collectAsState()

    fun wordAtReadingLine(): Int {
        val l = layout ?: return 0
        val line = l.getLineForVerticalPosition(scroll.value.toFloat())
        val off = l.getLineStart(line)
        return words.starts.indexOfFirst { it >= off }.coerceAtLeast(0)
    }

    LaunchedEffect(scrolling, mode, words, lang) {
        follower?.let { f -> withContext(Dispatchers.IO) { f.stop() } }
        follower = null
        if (!scrolling || mode != ScrollMode.VOICE || !granted) return@LaunchedEffect
        val model = withContext(Dispatchers.IO) {
            if (transcriber.isModelReady(lang)) ModelCache.get(transcriber.modelPath(lang), lang) else null
        }
        val f = VoiceFollower(words, model)
        val startAt = wordAtReadingLine()
        withContext(Dispatchers.IO) { f.start(startAt) }
        follower = f
    }
    DisposableEffect(Unit) { onDispose { follower?.stop() } }

    val tracking = follower?.tracking == true && !silenced
    // Word tracking: bring the line with the next word to the reading mark.
    LaunchedEffect(heardAt, tracking) {
        if (!tracking || heardAt < 0 || words.starts.isEmpty()) return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val idx = heardAt.coerceAtMost(words.starts.size - 1)
        val line = l.getLineForOffset(words.starts[idx])
        scroll.animateScrollTo(l.getLineTop(line).toInt())
    }

    // Steady scrolling: by speed, or while you speak when words can't be tracked.
    val moving = scrolling && when (mode) {
        ScrollMode.SPEED -> true
        ScrollMode.VOICE -> follower != null && (silenced || (!tracking && speaking))
    }
    val speedNow by rememberUpdatedState(speed)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { t ->
                if (last != 0L) {
                    val dt = (t - last) / 1_000_000_000f
                    scroll.dispatchRawDelta(with(density) { (speedNow * dt).dp.toPx() })
                }
                last = t
            }
            if (scroll.value >= scroll.maxValue && mode == ScrollMode.SPEED) scrolling = false
        }
    }

    fun startRecording() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Kadr_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kadr")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        recStart = System.currentTimeMillis(); elapsed = 0
        lastVideo = null
        recording = videoCapture.output.prepareRecording(ctx, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                elapsed = ev.recordingStats.recordedDurationNanos / 1_000_000
                if (ev is VideoRecordEvent.Pause) paused = true
                if (ev is VideoRecordEvent.Resume) paused = false
                if (ev is VideoRecordEvent.Finalize) {
                    paused = false
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        error = when (ev.error) {
                            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Запис зупинено: на телефоні закінчилося місце"
                            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "Не вдалося створити файл відео в галереї"
                            else -> "Запис не вдався (код ${ev.error})"
                        }
                    } else lastVideo = ev.outputResults.outputUri
                    recording = null
                }
            }
        scope.launch { scroll.scrollTo(0); scrolling = true }
    }

    fun onRecordPressed() {
        val r = recording
        if (r != null) { r.stop(); scrolling = false; paused = false; return }
        // A second tap during the countdown cancels it.
        if (countdown > 0) { countdownJob?.cancel(); countdownJob = null; countdown = 0; return }
        if (delaySec == 0) { startRecording(); return }
        countdownJob = scope.launch {
            scrolling = false
            scroll.scrollTo(0)
            for (i in delaySec downTo 1) { countdown = i; delay(1000) }
            countdown = 0
            startRecording()
        }
    }

    // ---------- Remote: volume keys, Bluetooth selfie remotes, headsets, keyboards ----------
    val onRecord by rememberUpdatedState({ onRecordPressed() })
    val lineNow by rememberUpdatedState(lineHeightPx)
    DisposableEffect(remoteOn, sheet) {
        RemoteKeys.handler = if (!remoteOn || sheet != Sheet.NONE) null else { code ->
            when (code) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { scrolling = !scrolling; true }
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { scope.launch { scroll.animateScrollBy(-lineNow * 2) }; true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT ->
                    { scope.launch { scroll.animateScrollBy(lineNow * 2) }; true }
                KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_MEDIA_RECORD -> { onRecord(); true }
                else -> false
            }
        }
        onDispose { RemoteKeys.handler = null }
    }

    if (sheet != Sheet.NONE) BackHandler { sheet = Sheet.NONE }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            if (grid) Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.35f)
                for (k in 1..2) {
                    drawLine(c, Offset(size.width * k / 3, 0f), Offset(size.width * k / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * k / 3), Offset(size.width, size.height * k / 3), 1.5f)
                }
            }
            if (level) {
                val ok = kotlin.math.abs(roll) < 1.5f
                val col = if (ok) Color(0xFF5BE37D) else Color.White
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val half = 70.dp.toPx(); val gap = 18.dp.toPx()
                    // Fixed marks show where level is; the line turns with the phone and goes green when level.
                    drawLine(col.copy(alpha = 0.7f), Offset(cx - half - gap - 24.dp.toPx(), cy), Offset(cx - half - gap, cy), 3f)
                    drawLine(col.copy(alpha = 0.7f), Offset(cx + half + gap, cy), Offset(cx + half + gap + 24.dp.toPx(), cy), 3f)
                    val a = Math.toRadians(-roll.toDouble())
                    val dx = (kotlin.math.cos(a) * half).toFloat(); val dy = (kotlin.math.sin(a) * half).toFloat()
                    drawLine(col, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), if (ok) 5f else 3f)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Потрібен доступ до камери й мікрофона", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(neededPerms) }) { Text("Дозволити") }
            }
        }

        // Prompter panel
        val panelAlign = when (panelPos) {
            PanelPos.TOP -> Alignment.TopCenter
            PanelPos.CENTER -> Alignment.Center
            PanelPos.BOTTOM -> Alignment.BottomCenter
        }
        val bottomGap = if (panelPos == PanelPos.BOTTOM) with(density) { controlsH.toDp() } else 0.dp
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(if (isLandscapeNow) 0.48f else 0.42f)
            .align(panelAlign).padding(bottom = bottomGap)) {
            val panelH = maxHeight
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { scrolling = !scrolling }
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll)
                        .graphicsLayer { if (mirror) scaleX = -1f }
                        .padding(start = 20.dp, end = 20.dp, top = panelH * 0.35f, bottom = panelH)
                ) {
                    Text(script, color = Color.White, fontSize = font.sp, lineHeight = (font * 1.35f).sp,
                        fontWeight = FontWeight.SemiBold, onTextLayout = { layout = it })
                }
                // Reading mark: the line next to it is the one to say now.
                Box(Modifier.padding(top = panelH * 0.35f).width(6.dp).height((font * 1.35f).dp).background(CapYellow).align(Alignment.TopStart))
            }
        }

        // Top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (recording == null) onBack() }) { Text("‹ Назад", color = Color.White) }
            Spacer(Modifier.weight(1f))
            if (mode == ScrollMode.VOICE && scrolling) {
                val label = when {
                    follower == null -> "Готую мікрофон…"
                    silenced -> "Мікрофон зайнятий записом: прокрутка за швидкістю"
                    speaking -> "Слухаю"
                    else -> "Чекаю на голос"
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (speaking && !silenced) Color(0xFF5BE37D) else Muted))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
            }
            if (recording != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                Spacer(Modifier.width(6.dp))
                Text(if (paused) "Пауза · ${formatTime(elapsed)}" else formatTime(elapsed), color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }
        }

        if (countdown > 0) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black)
                Text("Торкніться кнопки запису, щоб скасувати", color = Color.White, fontSize = 14.sp)
            }
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { controlsH = it.height }
                .background(Color.Black.copy(alpha = 0.6f)).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lastVideo?.let { uri ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Відео збережено в галерею", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAddSubtitles(uri) }) { Text("Додати субтитри", color = CapYellow) }
                }
                Spacer(Modifier.height(4.dp))
            }
            error?.let { Text(it, color = RecRed, modifier = Modifier.clickable { error = null }) }

            if (showSliders) {
                LabeledSlider("Швидкість", speed, 15f..200f) { speed = it; prefs.speed = it }
                LabeledSlider("Шрифт", font, 18f..56f) { font = it; prefs.fontSize = it }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.Start) {
                    val rec = recording
                    if (rec != null) {
                        SmallToggle(if (paused) "● Продовжити" else "❚❚ Пауза запису", paused) {
                            if (paused) { rec.resume(); scrolling = true } else { rec.pause(); scrolling = false }
                        }
                    } else {
                        SmallToggle("Сценарії", false) { scrolling = false; sheet = Sheet.SCRIPTS }
                    }
                    SmallToggle("Налаштування", false) { if (recording == null) { scrolling = false; sheet = Sheet.SETTINGS } }
                    SmallToggle(if (showSliders) "Сховати повзунки" else "Повзунки", showSliders) { showSliders = !showSliders }
                }
                Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(if (recording != null) RoundedCornerShape(8.dp) else CircleShape)
                    .background(RecRed).clickable(enabled = granted) { onRecordPressed() })
                Column(horizontalAlignment = Alignment.End) {
                    SmallToggle(if (scrolling) "Пауза" else "Пуск", scrolling) { scrolling = !scrolling }
                    SmallToggle("Камера ⟲", false) { if (recording == null) { front = !front; prefs.frontCamera = front } }
                    OrientationButton(landscape) {
                        if (recording == null && countdown == 0) {
                            landscape = !landscape; prefs.landscape = landscape; showSliders = !landscape
                        }
                    }
                }
            }
        }

        // ---------- Scripts ----------
        if (sheet == Sheet.SCRIPTS) {
            ScriptLibrary(prefs) {
                scripts.clear(); scripts.addAll(prefs.scripts)
                currentId = prefs.currentScriptId.takeIf { id -> scripts.any { it.id == id } } ?: scripts.first().id
                scope.launch { scroll.scrollTo(0) }
                sheet = Sheet.NONE
            }
        }

        // ---------- Settings ----------
        if (sheet == Sheet.SETTINGS) {
            SettingsSheet(
                mode = mode, onMode = { mode = it; prefs.scrollMode = it },
                lang = lang, onLang = { lang = it; prefs.lang = it; modelReady = transcriber.isModelReady(it) },
                modelReady = modelReady, downloadProgress = downloadProgress,
                onDownload = {
                    scope.launch {
                        downloadProgress = 0f
                        try {
                            transcriber.downloadModel(lang) { p -> downloadProgress = p }
                            modelReady = true
                        } catch (e: Exception) {
                            error = "Не вдалося завантажити модель. Перевірте інтернет."
                        }
                        downloadProgress = null
                    }
                },
                panelPos = panelPos, onPanelPos = { panelPos = it; prefs.panelPos = it },
                mirror = mirror, onMirror = { mirror = it; prefs.mirror = it },
                delaySec = delaySec, onDelay = { delaySec = it; prefs.delaySec = it },
                remoteOn = remoteOn, onRemote = { remoteOn = it; prefs.remote = it },
                quality = quality, onQuality = { quality = it; prefs.quality = it },
                fps = fps, onFps = { fps = it; prefs.fps = it },
                grid = grid, onGrid = { grid = it; prefs.grid = it },
                level = level, onLevel = { level = it; prefs.level = it },
                onClose = { sheet = Sheet.NONE },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(
    mode: ScrollMode, onMode: (ScrollMode) -> Unit,
    lang: SpeechLang, onLang: (SpeechLang) -> Unit,
    modelReady: Boolean, downloadProgress: Float?, onDownload: () -> Unit,
    panelPos: PanelPos, onPanelPos: (PanelPos) -> Unit,
    mirror: Boolean, onMirror: (Boolean) -> Unit,
    delaySec: Int, onDelay: (Int) -> Unit,
    remoteOn: Boolean, onRemote: (Boolean) -> Unit,
    quality: String, onQuality: (String) -> Unit,
    fps: Int, onFps: (Int) -> Unit,
    grid: Boolean, onGrid: (Boolean) -> Unit,
    level: Boolean, onLevel: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Налаштування", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text("Готово") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Прокрутка тексту")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrollMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(m.label) }) }
            }
            if (mode == ScrollMode.VOICE) {
                Hint(if (modelReady) "Текст іде точно за вашими словами. Можна робити паузи, відходити від тексту й повертатися."
                    else "Зараз текст рухається, поки ви говорите, і зупиняється на паузах. Щоб він ішов точно за словами, завантажте мовну модель (один раз).")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeechLang.entries.forEach { l -> FilterChip(selected = lang == l, onClick = { onLang(l) }, label = { Text(l.label) }) }
                }
                when {
                    downloadProgress != null -> {
                        LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Hint("Завантаження ${(downloadProgress * 100).toInt()}%")
                    }
                    !modelReady -> OutlinedButton(onClick = onDownload) { Text("Завантажити модель (~${lang.sizeMb} МБ)") }
                    else -> Hint("Модель «${lang.label}» завантажена ✓")
                }
                Hint("Якщо телефон не дає слухати мікрофон одночасно із записом, прокрутка автоматично перейде на швидкість із повзунка.")
            }

            Section("Якість відео")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HD" to "HD 720p", "FHD" to "Full HD 1080p", "UHD" to "4K").forEach { (k, l) ->
                    FilterChip(selected = quality == k, onClick = { onQuality(k) }, label = { Text(l) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { f -> FilterChip(selected = fps == f, onClick = { onFps(f) }, label = { Text("$f кадрів/с") }) }
            }
            Hint("4K і 60 кадрів/с працюють не на всіх телефонах. Якщо камера їх не підтримує, додаток сам поверне простіший режим.")
            SwitchLine("Сітка", "Лінії третин для рівної композиції", grid, onGrid)
            SwitchLine("Рівень", "Лінія по центру зеленіє, коли телефон тримається рівно", level, onLevel)

            Section("Де показувати текст")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelPos.entries.forEach { p -> FilterChip(selected = panelPos == p, onClick = { onPanelPos(p) }, label = { Text(p.label) }) }
            }
            Hint("Для фронтальної камери найкраще «Вгорі»: погляд залишається біля об'єктива.")

            Section("Відлік перед записом")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DELAYS.forEach { d -> FilterChip(selected = delaySec == d, onClick = { onDelay(d) }, label = { Text(if (d == 0) "Без відліку" else "$d с") }) }
            }

            SwitchLine("Дзеркальний текст", "Для суфлера зі склом-дзеркалом", mirror, onMirror)
            SwitchLine("Пульт і кнопки гучності",
                "Гучність + або кнопка Bluetooth-пульта: пуск і пауза. Гучність −: на два рядки назад. Кнопка камери: запис.",
                remoteOn, onRemote)
        }
    }
}

@Composable private fun Section(t: String) = Text(t, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
@Composable private fun Hint(t: String) = Text(t, color = Muted, fontSize = 13.sp)

@Composable
private fun SwitchLine(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = v, onCheckedChange = on)
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

/** A drawn frame (tall or wide) with its aspect ratio, so the button reads at a glance. */
@Composable
private fun OrientationButton(landscape: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
        Box(Modifier.size(if (landscape) 20.dp else 13.dp, if (landscape) 13.dp else 20.dp)
            .border(2.dp, Color.White, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(if (landscape) "16:9" else "9:16", fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun SmallToggle(label: String, on: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = if (on) CapYellow else Color.White)) {
        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
