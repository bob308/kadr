package ua.kadr.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun SubtitlesScreen(vm: SubtitleViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setVideo(uri)
    }
    val srtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.srt().toByteArray()) }
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    val player = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = ExoPlayer.REPEAT_MODE_ONE } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(vm.videoUri) {
        vm.videoUri?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare() }
    }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { positionMs = player.currentPosition; playing = player.isPlaying; delay(33) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.pause(); onBack() }) { Text("‹ Назад", color = Color.White) }
            Spacer(Modifier.weight(1f))
            if (vm.videoUri != null && vm.phase !is Phase.Exporting) TextButton(onClick = { pick() }) { Text("Інше відео", color = Muted) }
        }

        if (vm.videoUri == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Субтитри", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Виберіть відео з голосом. Додаток розпізнає мову й покаже підписи, які можна виправити перед збереженням.",
                    color = Muted, fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { pick() }) { Text("Вибрати відео") }
            }
            return@Column
        }

        // Preview: video with the subtitle drawn exactly as it will be burned in.
        val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.42f
        Box(Modifier.fillMaxWidth().heightIn(max = maxH), contentAlignment = Alignment.Center) {
            val ratio = vm.info.width.toFloat() / vm.info.height
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(12.dp))
                .clickable { if (player.isPlaying) player.pause() else player.play() }) {
                AndroidView({ PlayerView(it).apply { useController = false; this.player = player } }, Modifier.fillMaxSize())
                val renderer = remember(vm.info) { CaptionRenderer(vm.info.width, vm.info.height) }
                val cue = CueBuilder.cueAt(vm.cues, positionMs)
                val bmp = renderer.render(cue, positionMs, vm.style)
                val img = remember(bmp) { bmp.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    if (bmp.width <= 2) return@Canvas
                    val k = size.width / vm.info.width
                    val w = bmp.width * k; val h = bmp.height * k
                    val cy = (1f - vm.style.position.y) / 2f * size.height
                    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(((size.width - w) / 2).toInt(), (cy - h / 2).toInt()),
                        dstSize = IntSize(w.toInt(), h.toInt()))
                }
                if (!playing) {
                    Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 24.sp) }
                }
            }
        }
        Text(formatTime(positionMs) + " / " + formatTime(vm.info.durationMs), color = Muted, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val ph = vm.phase) {
                Phase.NoVideo, Phase.Idle -> StartPanel(vm)
                is Phase.Downloading -> ProgressPanel("Завантаження мовної моделі (один раз)", ph.progress) { vm.cancel() }
                is Phase.Recognising -> ProgressPanel("Розпізнаю мову…", ph.progress) { vm.cancel() }
                is Phase.Exporting -> ProgressPanel("Вшиваю субтитри у відео…", ph.progress) { vm.cancel() }
                Phase.Ready -> EditPanel(vm, onSeek = { player.seekTo(it) },
                    onExport = { player.pause(); vm.export() },
                    onSrt = { srtSaver.launch("subtitles.srt") })
                is Phase.Saved -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Готово! Відео з субтитрами збережено в галерею, у папку Movies/Kadr.", color = Color.White, fontSize = 16.sp)
                    Button(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(ph.uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }) { Text("Відкрити відео") }
                    OutlinedButton(onClick = { vm.backToEditing() }) { Text("Повернутися до редагування") }
                }
                is Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ph.message, color = RecRed, fontSize = 15.sp)
                    OutlinedButton(onClick = { vm.backToEditing() }) { Text("Зрозуміло") }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun StartPanel(vm: SubtitleViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Мова у відео", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeechLang.entries.forEach { l ->
                FilterChip(selected = vm.lang == l, onClick = { vm.chooseLang(l) }, label = { Text(l.label) })
            }
        }
        if (!vm.modelReady()) {
            Text("Перший раз додаток завантажить мовну модель (~${vm.lang.sizeMb} МБ). Далі розпізнавання працює без інтернету.",
                color = Muted, fontSize = 13.sp)
        }
        Button(onClick = { vm.generate() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Створити субтитри", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProgressPanel(title: String, progress: Float?, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(progress * 100).toInt()}%", color = Muted, fontSize = 13.sp)
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onCancel) { Text("Скасувати", color = Muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun EditPanel(vm: SubtitleViewModel, onSeek: (Long) -> Unit, onExport: () -> Unit, onSrt: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TabRow(selectedTabIndex = tab, containerColor = Ink) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Стиль") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Текст (${vm.cues.size})") })
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            if (tab == 0) StyleEditor(vm) else CueList(vm, onSeek)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSrt, modifier = Modifier.height(50.dp)) { Text(".srt") }
            Button(onClick = onExport, modifier = Modifier.weight(1f).height(50.dp)) {
                Text("Зберегти відео", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun StyleEditor(vm: SubtitleViewModel) {
    val s = vm.style
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Вигляд")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionLook.entries.forEach { l -> FilterChip(selected = s.look == l, onClick = { vm.updateStyle(s.copy(look = l)) }, label = { Text(l.label) }) }
        }
        Label("Шрифт")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionFont.entries.forEach { f -> FilterChip(selected = s.font == f, onClick = { vm.updateStyle(s.copy(font = f)) }, label = { Text(f.label) }) }
        }
        Label("Анімація")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionAnim.entries.forEach { a -> FilterChip(selected = s.anim == a, onClick = { vm.updateStyle(s.copy(anim = a)) }, label = { Text(a.label) }) }
        }
        Label("Розташування")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionPosition.entries.forEach { p -> FilterChip(selected = s.position == p, onClick = { vm.updateStyle(s.copy(position = p)) }, label = { Text(p.label) }) }
        }
        Label("Розмір тексту")
        Slider(value = s.sizePct, onValueChange = { vm.updateStyle(s.copy(sizePct = it)) }, valueRange = 0.035f..0.11f)
        Label("Слів на екрані: ${s.maxWords}")
        Slider(value = s.maxWords.toFloat(), onValueChange = { vm.updateStyle(s.copy(maxWords = it.toInt())) }, valueRange = 1f..8f, steps = 6)
        Label("Колір тексту")
        ColorRow(s.textColor) { vm.updateStyle(s.copy(textColor = it)) }
        SwitchRow("Великі літери", s.uppercase) { vm.updateStyle(s.copy(uppercase = it)) }
        SwitchRow("Підсвічувати слово, яке звучить", s.highlightWord) { vm.updateStyle(s.copy(highlightWord = it)) }
        if (s.highlightWord) {
            Label("Колір підсвітки")
            ColorRow(s.highlightColor) { vm.updateStyle(s.copy(highlightColor = it)) }
        }
    }
}

@UnstableApi
@Composable
private fun CueList(vm: SubtitleViewModel, onSeek: (Long) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vm.scriptNote ?: "Записували за сценарієм? Звірте текст із ним, і помилки розпізнавання виправляться, а розділові знаки стануть як у сценарії.",
                    color = if (vm.scriptApplied) CapYellow else Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosing = true }) { Text(if (vm.scriptApplied) "Інший сценарій" else "Звірити зі сценарієм") }
                    if (vm.scriptApplied) TextButton(onClick = { vm.undoScript() }) { Text("Скасувати", color = Muted) }
                }
            }
        }
        itemsIndexed(vm.cues, key = { _, c -> "${c.startMs}_${c.endMs}" }) { i, cue ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(cue.startMs), color = CapYellow, fontSize = 13.sp,
                    modifier = Modifier.width(48.dp).clickable { onSeek(cue.startMs) })
                OutlinedTextField(value = cue.text, onValueChange = { vm.editCue(i, it) },
                    modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color.White))
                TextButton(onClick = { vm.deleteCue(i) }) { Text("✕", color = Muted) }
            }
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Виберіть сценарій") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.scripts()) { sc ->
                        Text(sc.title, color = Color.White, modifier = Modifier.fillMaxWidth()
                            .clickable { vm.applyScript(sc); choosing = false }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Закрити") } },
        )
    }
}

@Composable private fun Label(t: String) = Text(t, color = Muted, fontSize = 13.sp)

@Composable
private fun SwitchRow(t: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = on)
    }
}

private val swatches = listOf(0xFFFFFFFF, 0xFFFFD43B, 0xFF4BE0FF, 0xFF7CFF6B, 0xFFFF6BD0, 0xFFFF4B4B).map { it.toInt() }

@Composable
private fun ColorRow(selected: Int, on: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        swatches.forEach { c ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (c == selected) Color.White else Line).padding(3.dp)
                .clip(CircleShape).background(Color(c)).clickable { on(c) })
        }
    }
}
