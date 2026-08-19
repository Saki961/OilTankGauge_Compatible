package com.oilterminal.tankcalc.util

object NameNormalizer {
    fun toHalfWidth(input: String): String {
        val out = StringBuilder(input.length)
        input.forEach { ch ->
            when {
                ch.code == 12288 -> out.append(' ')
                ch.code in 65281..65374 -> out.append((ch.code - 65248).toChar())
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun vessel(input: String): String {
        return toHalfWidth(input)
            .uppercase()
            .filter { ch ->
                ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF
            }
    }

    fun safeFileName(input: String): String {
        val value = toHalfWidth(input).trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
        return value.ifBlank { "未命名舱容表.xlsx" }.take(100)
    }

    fun chineseNumber(number: Int): String {
        val values = mapOf(
            1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五",
            6 to "六", 7 to "七", 8 to "八", 9 to "九", 10 to "十"
        )
        return values[number] ?: number.toString()
    }

    fun parseChineseNumber(value: String): Int? {
        val normalized = toHalfWidth(value).trim()
        normalized.toIntOrNull()?.let { return it }
        return when (normalized) {
            "一" -> 1
            "二", "两" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "七" -> 7
            "八" -> 8
            "九" -> 9
            "十" -> 10
            else -> null
        }
    }
}
