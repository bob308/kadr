package ua.kadr.app

import kotlin.math.abs

object TextMatch {
    fun normalize(w: String): String =
        w.lowercase().replace('ё', 'е').replace('’', '\'').replace('ʼ', '\'')
            .filter { it.isLetterOrDigit() || it == '\'' }

    /** Same word, a shared 4-letter stem, or one typo apart. */
    fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length >= 5 && b.length >= 5 && a.take(4) == b.take(4)) return true
        if (a.length < 4 || b.length < 4 || abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /**
     * Rewrites recognised words with the script's spelling and punctuation, keeping the timings.
     * Uses a global alignment where skipping the start or end of the script is free, so a video
     * that covers only part of the script still lines up. Script words the recogniser missed are
     * attached to the neighbouring word. Returns null when too little of the speech matches.
     */
    fun alignToScript(heard: List<Word>, scriptText: String): List<Word>? {
        val script = Regex("\\S+").findAll(scriptText).map { it.value }.toList()
        val sn = script.map { normalize(it) }
        val hn = heard.map { normalize(it.text) }
        val n = hn.size; val m = sn.size
        if (n == 0 || m == 0) return null

        val match = 3; val sub = -1; val gap = -2
        // score[i][j]: best score aligning heard[0 until i] with script[0 until j]
        val score = Array(n + 1) { IntArray(m + 1) }
        val move = Array(n + 1) { ByteArray(m + 1) } // 1 diag, 2 up (heard only), 3 left (script only)
        for (i in 1..n) { score[i][0] = i * gap; move[i][0] = 2 }
        for (j in 1..m) { score[0][j] = 0; move[0][j] = 3 } // free leading script
        for (i in 1..n) for (j in 1..m) {
            val d = score[i - 1][j - 1] + if (similar(hn[i - 1], sn[j - 1])) match else sub
            val u = score[i - 1][j] + gap
            val l = score[i][j - 1] + (if (i == n) 0 else gap) // free trailing script
            if (d >= u && d >= l) { score[i][j] = d; move[i][j] = 1 }
            else if (u >= l) { score[i][j] = u; move[i][j] = 2 }
            else { score[i][j] = l; move[i][j] = 3 }
        }

        // Walk back, pairing each heard word with a script word (or none).
        val pairs = ArrayList<Pair<Int, Int>>() // heard index or -1, script index or -1
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when (if (i == 0) 3 else if (j == 0) 2 else move[i][j].toInt()) {
                1 -> { pairs += (i - 1) to (j - 1); i--; j-- }
                2 -> { pairs += (i - 1) to -1; i-- }
                else -> { pairs += -1 to (j - 1); j-- }
            }
        }
        pairs.reverse()

        val matched = pairs.count { (h, s) -> h >= 0 && s >= 0 && similar(hn[h], sn[s]) }
        if (matched < maxOf(3, n / 3)) return null

        // Only fix the stretch of script the video actually covers.
        val firstS = pairs.first { it.first >= 0 && it.second >= 0 }.second
        val lastS = pairs.last { it.first >= 0 && it.second >= 0 }.second

        val out = ArrayList<Word>()
        var carry = ""
        for ((h, s) in pairs) {
            when {
                h >= 0 && s >= 0 -> {
                    val text = (if (carry.isNotEmpty()) "$carry " else "") + script[s]
                    carry = ""
                    out += heard[h].copy(text = text)
                }
                h >= 0 -> out += heard[h]
                s in firstS..lastS -> {
                    // A script word that was not heard: glue it to the previous word, or to the next one.
                    if (out.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(text = it.text + " " + script[s]) }
                    else carry = if (carry.isEmpty()) script[s] else "$carry ${script[s]}"
                }
            }
        }
        return out
    }
}
