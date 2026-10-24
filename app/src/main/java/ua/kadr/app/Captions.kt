package ua.kadr.app

import java.util.Locale

/** One recognised word with its time in milliseconds. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/** One subtitle on screen: a few words shown together. */
data class Cue(val words: List<Word>, val textOverride: String? = null) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
    val text: String get() = textOverride ?: words.joinToString(" ") { it.text }

    /** Index of the word being spoken at [timeMs], or -1. Edited cues have no per-word timing. */
    fun activeWord(timeMs: Long): Int {
        if (textOverride != null) return -1
        for (i in words.indices) {
            val next = words.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            if (timeMs >= words[i].startMs && timeMs < next) return i
        }
        return -1
    }
}

enum class CaptionPosition(val label: String, val y: Float) {
    TOP("Вгорі", 0.62f), CENTER("По центру", 0f), BOTTOM("Внизу", -0.58f)
}

enum class CaptionLook(val label: String) {
    OUTLINE("Контур"), BOX("Плашка"), PLAIN("Тінь")
}

data class CaptionStyle(
    /** Text height as a share of the video's shorter side. */
    val sizePct: Float = 0.065f,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    val look: CaptionLook = CaptionLook.OUTLINE,
    val uppercase: Boolean = true,
    val highlightWord: Boolean = true,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val highlightColor: Int = 0xFFFFD43B.toInt(),
    val maxWords: Int = 4,
)

object CueBuilder {
    /** Groups words into short cues: a pause, too many words or too long a span starts a new one. */
    fun build(words: List<Word>, maxWords: Int, maxChars: Int = 28): List<Cue> {
        val cues = mutableListOf<Cue>()
        var cur = mutableListOf<Word>()
        for (w in words) {
            if (cur.isNotEmpty()) {
                val last = cur.last()
                val chars = cur.sumOf { it.text.length + 1 } + w.text.length
                val pause = w.startMs - last.endMs > 600
                val tooLong = w.endMs - cur.first().startMs > 3200
                val endsSentence = last.text.endsWith('.') || last.text.endsWith('?') || last.text.endsWith('!')
                if (pause || tooLong || endsSentence || cur.size >= maxWords || chars > maxChars) {
                    cues += Cue(cur); cur = mutableListOf()
                }
            }
            cur += w
        }
        if (cur.isNotEmpty()) cues += Cue(cur)
        return cues
    }

    fun cueAt(cues: List<Cue>, timeMs: Long): Cue? {
        for (i in cues.indices) {
            val c = cues[i]
            // Hold each cue until the next one starts, at most 700 ms past its last word.
            val nextStart = cues.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            val until = minOf(nextStart, c.endMs + 700)
            if (timeMs >= c.startMs && timeMs < until) return c
        }
        return null
    }

    fun toSrt(cues: List<Cue>): String {
        fun ts(ms: Long): String {
            val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val r = ms % 1000
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r)
        }
        return buildString {
            cues.forEachIndexed { i, c ->
                val end = minOf(cues.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE, c.endMs + 700)
                append(i + 1).append('\n')
                append(ts(c.startMs)).append(" --> ").append(ts(end)).append('\n')
                append(c.text).append("\n\n")
            }
        }
    }
}
