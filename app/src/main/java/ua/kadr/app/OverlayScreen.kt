package ua.kadr.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Starts the floating prompter that shows the script on top of other camera apps. */
@Composable
fun OverlayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var canDraw by remember { mutableStateOf(OverlayService.canDraw(ctx)) }
    var running by remember { mutableStateOf(OverlayService.running) }
    var showLibrary by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canDraw = OverlayService.canDraw(ctx)
        running = OverlayService.running
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun launch(openCamera: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Restart so the window picks up the chosen script.
        if (OverlayService.running) OverlayService.stop(ctx)
        OverlayService.start(ctx)
        running = true
        if (openCamera) {
            try {
                ctx.startActivity(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                note = "Не знайшов камеру. Відкрийте потрібний додаток самі: суфлер уже на екрані."
            }
        } else {
            note = "Суфлер запущено. Тепер відкрийте TikTok, Instagram або камеру: текст буде поверх них."
        }
    }

    if (showLibrary) {
        ScriptLibrary(prefs) { showLibrary = false; refresh++ }
        return
    }
    BackHandler { onBack() }

    val script = remember(refresh) { prefs.scripts.firstOrNull { it.id == prefs.currentScriptId } ?: prefs.scripts.first() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("‹ Назад", color = Color.White) }
        Text("Суфлер", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Текст висить поверх будь-якого додатка: TikTok, Instagram, звичайної камери. Вікно можна пересувати за верхню смужку й змінювати розмір за жовтий куточок.",
            color = Muted, fontSize = 15.sp)

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Сценарій", color = Muted, fontSize = 13.sp)
            Text(script.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("${wordCount(script.text)} слів · ${readMinutes(script.text)} хв", color = Muted, fontSize = 13.sp)
            TextButton(onClick = { showLibrary = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Змінити або вибрати інший", color = CapYellow)
            }
        }

        if (!canDraw) {
            Text("Щоб показувати текст поверх інших додатків, Android потребує дозволу «Показ поверх інших додатків». Увімкніть його для «Кадр» і поверніться сюди.",
                color = Color.White, fontSize = 15.sp)
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Надати дозвіл", fontWeight = FontWeight.Bold) }
        } else {
            Button(onClick = { launch(openCamera = true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Запустити й відкрити камеру", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { launch(openCamera = false) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Лише запустити суфлер")
            }
            if (running) TextButton(onClick = { OverlayService.stop(ctx); running = false; note = null }) {
                Text("Закрити суфлер", color = RecRed)
            }
        }
        note?.let { Text(it, color = CapYellow, fontSize = 14.sp) }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Керування у вікні", color = Color.White, fontWeight = FontWeight.Bold)
            Text("▶ / ❚❚ або дотик до тексту: пуск і пауза\n↺: на початок\n− / +: швидкість прокрутки\nA− / A+: розмір шрифту\n✕: закрити\nЗакрити можна й зі сповіщення.",
                color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
