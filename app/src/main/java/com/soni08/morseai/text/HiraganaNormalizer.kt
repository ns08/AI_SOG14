package com.soni08.morseai.text

object HiraganaNormalizer {

    fun toHiragana(input: String): String {
        if (input.isBlank()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in 'ァ'..'ヶ' -> sb.append((ch.code - 0x60).toChar())
                'ヴ' -> sb.append("う゛")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace("AI", "えーあい")
            .replace("ai", "えーあい")
            .replace("LINE", "らいん")
            .replace("line", "らいん")
            .replace("Teams", "てぃーむず")
            .replace("teams", "てぃーむず")
            .replace("Amazon", "あまぞん")
            .replace("amazon", "あまぞん")
            .replace(Regex("\s+"), " ")
            .trim()
    }

    fun isApproval(hiraText: String): Boolean {
        val t = hiraText.trim()
        return t.contains("おっけい") ||
            t.contains("きょか") ||
            t.contains("はい") ||
            t.contains("いいよ") ||
            t.contains("じっこう")
    }

    fun isCancellation(hiraText: String): Boolean {
        val t = hiraText.trim()
        return t.contains("ちゅうし") ||
            t.contains("きゃんせる") ||
            t.contains("やめる") ||
            t.contains("だめ") ||
            t.contains("いいえ")
    }
}
