package ua.kadr.app

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

val Ink = Color(0xFF12141B)
val Panel = Color(0xFF1D2029)
val Line = Color(0xFF2C303B)
val Muted = Color(0xFF9AA0AE)
val RecRed = Color(0xFFFF4B4B)
val CapYellow = Color(0xFFFFD43B)

private val scheme = darkColorScheme(
    primary = CapYellow, onPrimary = Ink,
    secondary = RecRed, onSecondary = Color.White,
    background = Ink, onBackground = Color.White,
    surface = Panel, onSurface = Color.White,
    surfaceVariant = Line, onSurfaceVariant = Muted,
)

private enum class Screen { HOME, PROMPTER, SUBTITLES }

/** Hardware buttons (volume keys, selfie remotes, headsets) handed to the screen that wants them. */
object RemoteKeys {
    var handler: ((Int) -> Boolean)? = null
}

@UnstableApi
class MainActivity : ComponentActivity() {
    private val consumed = mutableSetOf<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = RemoteKeys.handler
        if (h != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount > 0 && event.keyCode in consumed) return true
                    if (h(event.keyCode)) { consumed += event.keyCode; return true }
                }
                KeyEvent.ACTION_UP -> if (consumed.remove(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = scheme) {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val subs: SubtitleViewModel = viewModel()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            onPrompter = { screen = Screen.PROMPTER },
                            onSubtitles = { screen = Screen.SUBTITLES },
                        )
                        Screen.PROMPTER -> {
                            BackHandler { screen = Screen.HOME }
                            TeleprompterScreen(
                                onBack = { screen = Screen.HOME },
                                onAddSubtitles = { uri: Uri -> subs.setVideo(uri); screen = Screen.SUBTITLES },
                            )
                        }
                        Screen.SUBTITLES -> {
                            BackHandler { screen = Screen.HOME }
                            SubtitlesScreen(subs, onBack = { screen = Screen.HOME })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onPrompter: () -> Unit, onSubtitles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Кадр", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
        Text("Запис з телесуфлером і субтитри для ваших відео", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        HomeTile(
            title = "Телесуфлер",
            text = "Текст прокручується поверх камери, поки ви записуєте відео",
            mark = { Box(Modifier.size(22.dp).clip(CircleShape).background(RecRed)) },
            onClick = onPrompter,
        )
        HomeTile(
            title = "Субтитри",
            text = "Розпізнає мову у відео й вшиває підписи. Працює без інтернету",
            mark = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(28.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(CapYellow))
                    Box(Modifier.size(18.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                }
            },
            onClick = onSubtitles,
        )
    }
}

@Composable
private fun HomeTile(title: String, text: String, mark: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).clickable(onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Ink), contentAlignment = Alignment.Center) { mark() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text, fontSize = 14.sp, color = Muted)
        }
    }
}
