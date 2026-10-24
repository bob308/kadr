package ua.kadr.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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

fun wordCount(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }

fun readMinutes(text: String): String {
    val min = wordCount(text) / 130f
    return if (min < 1) "<1" else "%.1f".format(min)
}

/**
 * Full-screen list of saved scripts with an editor. Picking a script makes it current
 * and closes the list; everything is saved to [Prefs] straight away.
 */
@Composable
fun ScriptLibrary(prefs: Prefs, onClose: () -> Unit) {
    val scripts = remember { mutableStateListOf<Script>().apply { addAll(prefs.scripts) } }
    var currentId by remember { mutableLongStateOf(prefs.currentScriptId) }
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableLongStateOf(0L) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Script?>(null) }

    fun save() { prefs.scripts = scripts.toList(); prefs.currentScriptId = currentId }

    if (editing) {
        BackHandler { editing = false }
        Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editId == 0L) "Новий сценарій" else "Редагування", color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = false }) { Text("Скасувати", color = Muted) }
                Button(onClick = {
                    if (editText.isNotBlank()) {
                        if (editId == 0L) {
                            val s = Script(System.currentTimeMillis(), editText)
                            scripts.add(0, s); currentId = s.id
                        } else {
                            val i = scripts.indexOfFirst { it.id == editId }
                            if (i >= 0) scripts[i] = scripts[i].copy(text = editText)
                            currentId = editId
                        }
                        save()
                    }
                    editing = false
                }) { Text("Готово") }
            }
            OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("Вставте або напишіть сценарій. Перший рядок стане назвою.") })
            Text("Слів: ${wordCount(editText)} · приблизно ${readMinutes(editText)} хв читання", color = Muted, fontSize = 13.sp)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Сценарії", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Закрити", color = Muted) }
        }
        Button(onClick = { editId = 0L; editText = ""; editing = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Новий сценарій") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scripts, key = { it.id }) { s ->
                val selected = s.id == currentId
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Line else Panel)
                    .clickable { currentId = s.id; save(); onClose() }
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = if (selected) CapYellow else Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${wordCount(s.text)} слів · ${readMinutes(s.text)} хв", color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { editId = s.id; editText = s.text; editing = true }) { Text("Змінити", color = Color.White) }
                    if (scripts.size > 1) TextButton(onClick = { deleting = s }) { Text("✕", color = Muted) }
                }
            }
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Видалити сценарій?") },
            text = { Text("«${s.title}» буде видалено назавжди.") },
            confirmButton = {
                TextButton(onClick = {
                    scripts.removeAll { it.id == s.id }
                    if (currentId == s.id) currentId = scripts.first().id
                    save(); deleting = null
                }) { Text("Видалити", color = RecRed) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Скасувати") } },
        )
    }
}
