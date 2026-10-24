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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class Sheet { NONE, SCRIPTS, SETTINGS, MEDIA }
private enum class Drawer { NONE, SCRIPT, SPEED, FONT, CAMERA, TIMER, MORE }

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
    var drawer by remember { mutableStateOf(Drawer.NONE) }
    var textHidden by remember { mutableStateOf(false) }
    var panelFrac by remember { mutableFloatStateOf(prefs.panelHeight) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintJob by remember { mutableStateOf<Job?>(null) }

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

    fun toggleDrawer(d: Drawer) { drawer = if (drawer == d) Drawer.NONE else d }
    fun showHint(text: String) {
        hint = text
        hintJob?.cancel()
        hintJob = scope.launch { delay(2200); hint = null }
    }

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

    // ---------- Audio meter ----------
    var meterOn by remember { mutableStateOf(prefs.audioMeter) }
    var levelDb by remember { mutableFloatStateOf(-90f) }
    var peakDb by remember { mutableFloatStateOf(-90f) }
    var peakAt by remember { mutableLongStateOf(0L) }
    var quietSince by remember { mutableLongStateOf(0L) }
    var lastLoudHint by remember { mutableLongStateOf(0L) }
    val hintNow by rememberUpdatedState<(String) -> Unit>({ showHint(it) })
    fun feedLevel(db: Float) {
        val now = System.currentTimeMillis()
        // Fast attack, slow release, like a hardware meter.
        levelDb = if (db > levelDb) db else maxOf(db, levelDb - 1.5f)
        if (db >= peakDb || now - peakAt > 600) { peakDb = db; peakAt = now }
        if (db > -45f) quietSince = now
        else if (quietSince == 0L) quietSince = now
        else if (now - quietSince > 4000) { hintNow("Тихо: підійдіть ближче до мікрофона"); quietSince = now }
        if (db > -1.5f && now - lastLoudHint > 5000) { hintNow("Занадто гучно: звук може спотворитися"); lastLoudHint = now }
    }
    val meterActive = meterOn && granted
    // Before recording the meter listens to the microphone itself; during recording it reads the recording.
    LaunchedEffect(meterActive, recording == null, follower == null) {
        if (!meterActive || recording != null || follower != null) return@LaunchedEffect
        quietSince = 0L
        withContext(Dispatchers.IO) {
            val rec = try {
                android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, 16000)
            } catch (e: Exception) { null } ?: return@withContext
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext }
            val buf = ShortArray(800) // 50 ms
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0
                    for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
                    val db = if (peak == 0) -90f else (20 * kotlin.math.log10(peak / 32768.0)).toFloat()
                    withContext(Dispatchers.Main) { feedLevel(db) }
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
    }

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
                if (meterOn && ev is VideoRecordEvent.Status) {
                    val amp = ev.recordingStats.audioStats.audioAmplitude
                    feedLevel(if (amp <= 0.0) -90f else (20 * kotlin.math.log10(amp)).toFloat())
                }
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

    // ---------- Minimal pro-camera layout ----------
    val previewArea: @Composable (Modifier) -> Unit = { mod ->
        BoxWithConstraints(mod.background(Color.Black)) {
            val areaH = maxHeight
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
                        drawLine(col.copy(alpha = 0.7f), Offset(cx - half - gap - 24.dp.toPx(), cy), Offset(cx - half - gap, cy), 3f)
                        drawLine(col.copy(alpha = 0.7f), Offset(cx + half + gap, cy), Offset(cx + half + gap + 24.dp.toPx(), cy), 3f)
                        val a = Math.toRadians(-roll.toDouble())
                        val dx = (kotlin.math.cos(a) * half).toFloat(); val dy = (kotlin.math.sin(a) * half).toFloat()
                        drawLine(col, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), if (ok) 5f else 3f)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Потрібен доступ до камери й мікрофона", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(neededPerms) }) { Text("Дозволити") }
                }
            }

            // Prompter text: thin, see-through panel; height dragged by its handle.
            val topBarH = 64.dp
            if (!textHidden) {
                val panelH = areaH * panelFrac
                val align = when (panelPos) {
                    PanelPos.TOP -> Alignment.TopCenter
                    PanelPos.CENTER -> Alignment.Center
                    PanelPos.BOTTOM -> Alignment.BottomCenter
                }
                Box(Modifier.align(align).fillMaxWidth()
                    .padding(top = if (panelPos == PanelPos.TOP) topBarH else 0.dp,
                        bottom = if (panelPos == PanelPos.BOTTOM && drawer != Drawer.NONE) 130.dp else 0.dp)
                    .height(panelH)
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(remember { MutableInteractionSource() }, null) { scrolling = !scrolling }
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll)
                            .graphicsLayer { if (mirror) scaleX = -1f }
                            .padding(start = 18.dp, end = 18.dp, top = panelH * 0.3f, bottom = panelH)
                    ) {
                        Text(script, color = Color.White, fontSize = font.sp, lineHeight = (font * 1.3f).sp,
                            fontWeight = FontWeight.SemiBold, onTextLayout = { layout = it },
                            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 2f), 6f)))
                    }
                    Box(Modifier.padding(top = panelH * 0.3f).width(4.dp).height((font * 1.3f).dp).background(CapYellow).align(Alignment.TopStart))
                    // Resize handle
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp)
                        .pointerInput(areaH) {
                            detectVerticalDragGestures { _, dy ->
                                val frac = (panelFrac + dy / areaH.toPx() * (if (panelPos == PanelPos.BOTTOM) -1 else 1)).coerceIn(0.12f, 0.75f)
                                panelFrac = frac; prefs.panelHeight = frac
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.6f)))
                    }
                }
            }

            // Top line: back, format badge, timecode, clips.
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(topBarH).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconTap(KIcons.Back, onClick = { if (recording == null) onBack() })
                Spacer(Modifier.weight(1f))
                Column(Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(qualityLabel(quality), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black, lineHeight = 14.sp)
                    Text("${if (landscape) "16:9" else "9:16"} · $fps", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(timecode(elapsed), color = if (recording != null && !paused) RecRed else Color.White,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                Spacer(Modifier.weight(1f))
                IconTap(KIcons.Gallery, onClick = { if (recording == null) sheet = Sheet.MEDIA })
            }

            if (meterActive) {
                AudioMeter(levelDb, peakDb, Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = topBarH + 2.dp, end = 12.dp))
            }

            // Status pills under the top line.
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = topBarH + 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (paused) Pill("ПАУЗА ЗАПИСУ", RecRed)
                if (mode == ScrollMode.VOICE && scrolling) Pill(
                    when {
                        follower == null -> "Готую мікрофон…"
                        silenced -> "Мікрофон зайнятий: прокрутка за швидкістю"
                        speaking -> "● Слухаю"
                        else -> "Чекаю на голос"
                    }, if (speaking && !silenced) Color(0xFF5BE37D) else Color.White)
                hint?.let { Pill(it, Color.White) }
                error?.let { Pill(it, RecRed, onClick = { error = null }) }
            }

            if (countdown > 0) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black,
                        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 3f), 12f)))
                    Text("Натисніть запис ще раз, щоб скасувати", color = Color.White, fontSize = 13.sp)
                }
            }

            // Drawer: transparent strip over the bottom of the frame.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))) {
                lastVideo?.let { uri ->
                    Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.6f)).clickable { onAddSubtitles(uri) }
                        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Збережено", color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.width(10.dp))
                        KIcon(KIcons.Captions, CapYellow, 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("Субтитри", color = CapYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                when (drawer) {
                    Drawer.NONE -> {}
                    Drawer.SCRIPT -> DrawerRow(
                        left = { IconTap(KIcons.Script, onClick = { sheet = Sheet.SCRIPTS }) },
                        right = { IconTap(KIcons.Plus, onClick = { sheet = Sheet.SCRIPTS }) },
                    ) {
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                            items(scripts, key = { it.id }) { sc ->
                                OptionChip(sc.title, sc.id == currentId) {
                                    currentId = sc.id; prefs.currentScriptId = sc.id
                                    scope.launch { scroll.scrollTo(0) }
                                }
                            }
                        }
                    }
                    Drawer.SPEED -> DrawerRow(
                        left = { IconTap(if (scrolling) KIcons.Pause else KIcons.Play, onClick = { scrolling = !scrolling }) },
                        right = {
                            AutoBox(mode == ScrollMode.VOICE, "ГОЛОС") {
                                mode = if (mode == ScrollMode.VOICE) ScrollMode.SPEED else ScrollMode.VOICE
                                prefs.scrollMode = mode
                                if (mode == ScrollMode.VOICE && !modelReady)
                                    showHint("Текст іде, поки ви говорите. Для стеження за словами: ⋯ → ⚙ → модель")
                            }
                        },
                    ) {
                        RulerSlider(speed, 10f..200f, 5f, 4, { it.toInt().toString() }, { speed = it; prefs.speed = it })
                    }
                    Drawer.FONT -> Column {
                        DrawerRow(
                            left = { IconTap(if (textHidden) KIcons.EyeOff else KIcons.Eye, active = textHidden, onClick = { textHidden = !textHidden }) },
                            right = { IconTap(KIcons.Mirror, active = mirror, onClick = { mirror = !mirror; prefs.mirror = mirror }) },
                        ) {
                            RulerSlider(font, 16f..60f, 1f, 5, { it.toInt().toString() }, { font = it; prefs.fontSize = it })
                        }
                        OptionsLine(PanelPos.entries.map { it.label }, PanelPos.entries.indexOf(panelPos)) {
                            panelPos = PanelPos.entries[it]; prefs.panelPos = panelPos
                        }
                    }
                    Drawer.CAMERA -> Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionsLine(listOf("Фронтальна", "Основна", "9:16", "16:9"),
                            selected = setOf(if (front) 0 else 1, if (landscape) 3 else 2)) { i ->
                            if (recording != null || countdown > 0) return@OptionsLine
                            when (i) {
                                0, 1 -> { front = i == 0; prefs.frontCamera = front }
                                else -> { landscape = i == 3; prefs.landscape = landscape }
                            }
                        }
                        OptionsLine(listOf("HD", "Full HD", "4K", "30", "60"),
                            selected = setOf(listOf("HD", "FHD", "UHD").indexOf(quality), if (fps == 60) 4 else 3)) { i ->
                            if (recording != null) return@OptionsLine
                            when (i) {
                                0, 1, 2 -> { quality = listOf("HD", "FHD", "UHD")[i]; prefs.quality = quality }
                                else -> { fps = if (i == 4) 60 else 30; prefs.fps = fps }
                            }
                        }
                    }
                    Drawer.TIMER -> DrawerRow(left = { KIcon(KIcons.Timer, Color.White) }, right = {}) {
                        OptionsLine(DELAYS.map { if (it == 0) "Вимк" else "$it с" }, DELAYS.indexOf(delaySec)) {
                            delaySec = DELAYS[it]; prefs.delaySec = delaySec
                        }
                    }
                    Drawer.MORE -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconTap(KIcons.Grid, active = grid, hint = "Сітка", onHint = ::showHint, onClick = { grid = !grid; prefs.grid = grid })
                        IconTap(KIcons.Level, active = level, hint = "Рівень", onHint = ::showHint, onClick = { level = !level; prefs.level = level })
                        IconTap(KIcons.Remote, active = remoteOn, hint = "Пульт і кнопки гучності", onHint = ::showHint,
                            onClick = { remoteOn = !remoteOn; prefs.remote = remoteOn; showHint(if (remoteOn) "Пульт увімкнено: гучність + = пуск/пауза" else "Пульт вимкнено") })
                        IconTap(KIcons.Mic, active = mode == ScrollMode.VOICE, hint = "Прокрутка за голосом", onHint = ::showHint,
                            onClick = { sheet = Sheet.SETTINGS })
                        IconTap(KIcons.Meter, active = meterOn, hint = "Індикатор гучності", onHint = ::showHint,
                            onClick = { meterOn = !meterOn; prefs.audioMeter = meterOn })
                        IconTap(KIcons.Gear, hint = "Усі налаштування", onHint = ::showHint, onClick = { sheet = Sheet.SETTINGS })
                    }
                }
            }
        }
    }

    val toolbar: @Composable (Boolean) -> Unit = { vertical ->
        val items: List<@Composable () -> Unit> = listOf(
            {
                val rec = recording
                if (rec != null) IconTap(if (paused) KIcons.Play else KIcons.Pause, active = paused, hint = "Пауза запису", onHint = ::showHint) {
                    if (paused) { rec.resume(); scrolling = true } else { rec.pause(); scrolling = false }
                } else IconTap(KIcons.Script, active = drawer == Drawer.SCRIPT, hint = "Сценарій", onHint = ::showHint) { toggleDrawer(Drawer.SCRIPT) }
            },
            { IconTap(KIcons.Speed, active = drawer == Drawer.SPEED, hint = "Швидкість і пуск тексту", onHint = ::showHint) { toggleDrawer(Drawer.SPEED) } },
            { TextTap("Aa", active = drawer == Drawer.FONT, hint = "Шрифт і панель тексту", onHint = ::showHint) { toggleDrawer(Drawer.FONT) } },
            { RecordButton(recording != null, paused, countdown > 0, granted) { onRecordPressed() } },
            { IconTap(KIcons.Flip, active = drawer == Drawer.CAMERA, hint = "Камера, орієнтація, якість", onHint = ::showHint) { toggleDrawer(Drawer.CAMERA) } },
            { IconTap(KIcons.Timer, active = drawer == Drawer.TIMER || delaySec > 0, hint = "Відлік перед записом", onHint = ::showHint) { toggleDrawer(Drawer.TIMER) } },
            { IconTap(KIcons.More, active = drawer == Drawer.MORE, hint = "Сітка, рівень, пульт, налаштування", onHint = ::showHint) { toggleDrawer(Drawer.MORE) } },
        )
        if (vertical) {
            Column(Modifier.fillMaxHeight().width(84.dp).background(BarColor).navigationBarsPadding().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                items.reversed().forEach { it() }
            }
        } else {
            Row(Modifier.fillMaxWidth().background(BarColor).navigationBarsPadding().height(96.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                items.forEach { it() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BarColor)) {
        if (isLandscapeNow) {
            Row(Modifier.fillMaxSize()) {
                previewArea(Modifier.weight(1f).fillMaxHeight())
                toolbar(true)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                previewArea(Modifier.weight(1f).fillMaxWidth())
                toolbar(false)
            }
        }

        if (sheet == Sheet.SCRIPTS) {
            ScriptLibrary(prefs) {
                scripts.clear(); scripts.addAll(prefs.scripts)
                currentId = prefs.currentScriptId.takeIf { id -> scripts.any { it.id == id } } ?: scripts.first().id
                scope.launch { scroll.scrollTo(0) }
                sheet = Sheet.NONE
            }
        }
        if (sheet == Sheet.MEDIA) {
            MediaScreen(onClose = { sheet = Sheet.NONE }, onSubtitles = { uri -> sheet = Sheet.NONE; onAddSubtitles(uri) })
        }
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

private val BarColor = Color(0xFF0B0E14)

private fun qualityLabel(q: String) = when (q) { "HD" -> "HD"; "UHD" -> "4K"; else -> "FHD" }

private fun timecode(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

/** Icon button: tap acts, long press shows what it does. */
@Composable
private fun IconTap(
    icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean = false,
    hint: String? = null, onHint: (String) -> Unit = {}, onClick: () -> Unit,
) {
    Box(Modifier.size(48.dp).clip(CircleShape).pointerInput(hint) {
        detectTapGestures(onTap = { onClick() }, onLongPress = { hint?.let(onHint) })
    }, contentAlignment = Alignment.Center) {
        KIcon(icon, if (active) CapYellow else Color.White, 26.dp)
    }
}

@Composable
private fun TextTap(label: String, active: Boolean, hint: String, onHint: (String) -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).pointerInput(hint) {
        detectTapGestures(onTap = { onClick() }, onLongPress = { onHint(hint) })
    }, contentAlignment = Alignment.Center) {
        Text(label, color = if (active) CapYellow else Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordButton(recording: Boolean, paused: Boolean, counting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).border(3.dp, if (recording) RecRed else Color.White, CircleShape).padding(7.dp)
        .clip(if (recording) RoundedCornerShape(10.dp) else CircleShape)
        .background(if (recording && !paused) RecRed else if (counting) RecRed.copy(alpha = 0.5f) else Color(0xFF7A1414))
        .clickable(enabled = enabled, onClick = onClick))
}

@Composable
private fun Pill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

/** A drawer line: icon on the left, the control in the middle, an action on the right. */
@Composable
private fun DrawerRow(left: @Composable () -> Unit, right: @Composable () -> Unit, center: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { left() }
        Box(Modifier.weight(1f)) { center() }
        Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) { right() }
    }
}

@Composable
private fun AutoBox(on: Boolean, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) CapYellow else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AUTO", color = if (on) Ink else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(label, color = if (on) Ink else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, maxLines = 1, color = if (selected) Ink else Color.White, fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) CapYellow else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
}

@Composable
private fun OptionsLine(labels: List<String>, selected: Int, onPick: (Int) -> Unit) =
    OptionsLine(labels, setOf(selected), onPick)

@Composable
private fun OptionsLine(labels: List<String>, selected: Set<Int>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l -> OptionChip(l, i in selected) { onPick(i) } }
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

/** Level meter in dBFS: green up to −12, yellow to −3, red above; a thin line holds the recent peak. */
@Composable
private fun AudioMeter(levelDb: Float, peakDb: Float, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End) {
        Canvas(Modifier.width(110.dp).height(7.dp)) {
            fun x(db: Float) = ((db.coerceIn(-60f, 0f) + 60f) / 60f) * size.width
            drawRect(Color.White.copy(alpha = 0.15f), size = size)
            val lv = x(levelDb)
            val g = x(-12f); val y = x(-3f)
            drawRect(Color(0xFF3DDC6A), topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(minOf(lv, g), size.height))
            if (lv > g) drawRect(CapYellow, topLeft = Offset(g, 0f), size = androidx.compose.ui.geometry.Size(minOf(lv, y) - g, size.height))
            if (lv > y) drawRect(RecRed, topLeft = Offset(y, 0f), size = androidx.compose.ui.geometry.Size(lv - y, size.height))
            val px = x(peakDb)
            if (peakDb > -60f) drawLine(if (peakDb > -3f) RecRed else Color.White, Offset(px, 0f), Offset(px, size.height), 2.5f)
        }
        Text(if (levelDb <= -60f) "−∞ dB" else "${levelDb.toInt()} dB", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
    }
}
