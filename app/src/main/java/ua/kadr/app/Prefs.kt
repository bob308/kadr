package ua.kadr.app

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("kadr", Context.MODE_PRIVATE)

    var script: String
        get() = p.getString("script", null) ?: SAMPLE_SCRIPT
        set(v) = p.edit().putString("script", v).apply()

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
            )
        }
        set(s) = p.edit()
            .putFloat("c_size", s.sizePct).putString("c_pos", s.position.name).putString("c_look", s.look.name)
            .putBoolean("c_upper", s.uppercase).putBoolean("c_hl", s.highlightWord)
            .putInt("c_color", s.textColor).putInt("c_hlcolor", s.highlightColor).putInt("c_words", s.maxWords)
            .apply()

    companion object {
        const val SAMPLE_SCRIPT = "Привіт! Це ваш телесуфлер.\n\n" +
            "Натисніть «Текст», щоб вставити свій сценарій. Швидкість прокрутки й розмір шрифту налаштовуються повзунками внизу.\n\n" +
            "Тримайте погляд біля камери: рядок, який треба читати, завжди на рівні жовтої позначки.\n\n" +
            "Після запису відео одразу можна відкрити в розділі «Субтитри» і додати до нього підписи."
    }
}
