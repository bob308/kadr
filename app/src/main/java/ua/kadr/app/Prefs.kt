package ua.kadr.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Script(val id: Long, val text: String) {
    val title: String
        get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > 40) it.take(40) + "…" else it } ?: "Без назви"
}

enum class ScrollMode(val label: String) { SPEED("За швидкістю"), VOICE("За голосом") }
enum class PanelPos(val label: String) { TOP("Вгорі"), CENTER("По центру"), BOTTOM("Внизу") }

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("kadr", Context.MODE_PRIVATE)

    /** All saved scripts. The single text from version 1.0 becomes the first script. */
    var scripts: List<Script>
        get() {
            val raw = p.getString("scripts", null)
            if (raw == null) return listOf(Script(1L, p.getString("script", null) ?: SAMPLE_SCRIPT))
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Script(o.getLong("id"), o.getString("text")) } }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text)) }
            p.edit().putString("scripts", arr.toString()).apply()
        }

    var currentScriptId: Long
        get() = p.getLong("current_script", 1L)
        set(v) = p.edit().putLong("current_script", v).apply()

    var scrollMode: ScrollMode
        get() = runCatching { ScrollMode.valueOf(p.getString("scroll_mode", "SPEED")!!) }.getOrDefault(ScrollMode.SPEED)
        set(v) = p.edit().putString("scroll_mode", v.name).apply()

    var panelPos: PanelPos
        get() = runCatching { PanelPos.valueOf(p.getString("panel_pos", "TOP")!!) }.getOrDefault(PanelPos.TOP)
        set(v) = p.edit().putString("panel_pos", v.name).apply()

    /** Video quality: "HD", "FHD" or "UHD". */
    var quality: String
        get() = p.getString("quality", "FHD") ?: "FHD"
        set(v) = p.edit().putString("quality", v).apply()

    var fps: Int
        get() = p.getInt("fps", 30)
        set(v) = p.edit().putInt("fps", v).apply()

    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) = p.edit().putBoolean("grid", v).apply()

    var level: Boolean
        get() = p.getBoolean("level", false)
        set(v) = p.edit().putBoolean("level", v).apply()

    var overlayFont: Float
        get() = p.getFloat("ov_font", 26f)
        set(v) = p.edit().putFloat("ov_font", v).apply()
    var overlayX: Int
        get() = p.getInt("ov_x", -1)
        set(v) = p.edit().putInt("ov_x", v).apply()
    var overlayY: Int
        get() = p.getInt("ov_y", -1)
        set(v) = p.edit().putInt("ov_y", v).apply()
    var overlayW: Int
        get() = p.getInt("ov_w", -1)
        set(v) = p.edit().putInt("ov_w", v).apply()
    var overlayH: Int
        get() = p.getInt("ov_h", -1)
        set(v) = p.edit().putInt("ov_h", v).apply()

    var remote: Boolean
        get() = p.getBoolean("remote", true)
        set(v) = p.edit().putBoolean("remote", v).apply()

    var speed: Float
        get() = p.getFloat("speed", 60f)
        set(v) = p.edit().putFloat("speed", v).apply()

    var fontSize: Float
        get() = p.getFloat("font", 30f)
        set(v) = p.edit().putFloat("font", v).apply()

    var mirror: Boolean
        get() = p.getBoolean("mirror", false)
        set(v) = p.edit().putBoolean("mirror", v).apply()

    var frontCamera: Boolean
        get() = p.getBoolean("front", true)
        set(v) = p.edit().putBoolean("front", v).apply()

    var landscape: Boolean
        get() = p.getBoolean("landscape", false)
        set(v) = p.edit().putBoolean("landscape", v).apply()

    /** Seconds before recording starts: 0, 3, 5, 10 or 15. */
    var delaySec: Int
        get() = p.getInt("delay_sec", 15)
        set(v) = p.edit().putInt("delay_sec", v).apply()

    var lang: SpeechLang
        get() = runCatching { SpeechLang.valueOf(p.getString("lang", "UK")!!) }.getOrDefault(SpeechLang.UK)
        set(v) = p.edit().putString("lang", v.name).apply()

    var captionStyle: CaptionStyle
        get() {
            val d = CaptionStyle()
            return CaptionStyle(
                sizePct = p.getFloat("c_size", d.sizePct),
                position = runCatching { CaptionPosition.valueOf(p.getString("c_pos", d.position.name)!!) }.getOrDefault(d.position),
                look = runCatching { CaptionLook.valueOf(p.getString("c_look", d.look.name)!!) }.getOrDefault(d.look),
                uppercase = p.getBoolean("c_upper", d.uppercase),
                highlightWord = p.getBoolean("c_hl", d.highlightWord),
                textColor = p.getInt("c_color", d.textColor),
                highlightColor = p.getInt("c_hlcolor", d.highlightColor),
                maxWords = p.getInt("c_words", d.maxWords),
                font = runCatching { CaptionFont.valueOf(p.getString("c_font", d.font.name)!!) }.getOrDefault(d.font),
                anim = runCatching { CaptionAnim.valueOf(p.getString("c_anim", d.anim.name)!!) }.getOrDefault(d.anim),
            )
        }
        set(s) = p.edit()
            .putFloat("c_size", s.sizePct).putString("c_pos", s.position.name).putString("c_look", s.look.name)
            .putBoolean("c_upper", s.uppercase).putBoolean("c_hl", s.highlightWord)
            .putInt("c_color", s.textColor).putInt("c_hlcolor", s.highlightColor).putInt("c_words", s.maxWords)
            .putString("c_font", s.font.name).putString("c_anim", s.anim.name)
            .apply()

    companion object {
        const val SAMPLE_SCRIPT = "Привіт! Це ваш телесуфлер.\n\n" +
            "Натисніть «Сценарії», щоб додати свої тексти. У «Налаштуваннях» можна ввімкнути прокрутку за голосом: текст сам іде за вашими словами.\n\n" +
            "Тримайте погляд біля камери: рядок, який треба читати, завжди на рівні жовтої позначки.\n\n" +
            "Після запису відео одразу можна відкрити в розділі «Субтитри» і додати до нього підписи."
    }
}
