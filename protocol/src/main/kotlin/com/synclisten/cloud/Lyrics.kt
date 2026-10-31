package com.synclisten.cloud

data class LyricLine(val timeMs: Long, val text: String)

data class ParsedLyrics(val lines: List<LyricLine>, val plainText: String)

fun parseLyrics(text: String): ParsedLyrics {
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-600_000, 600_000) ?: 0L
    val lines = mutableListOf<LyricLine>()
    val plain = mutableListOf<String>()
    for (raw in text.lineSequence()) {
        val tags = timestamp.findAll(raw).toList()
        if (tags.isEmpty()) {
            val value = raw.trim()
            if (value.isNotEmpty() && !value.matches(Regex("\\[[a-zA-Z]+:.*]"))) plain += value
            continue
        }
        val content = timestamp.replace(raw, "").trim()
        if (content.isEmpty()) continue
        for (tag in tags) {
            val minute = tag.groupValues[1].toLongOrNull() ?: continue
            val second = tag.groupValues[2].toLongOrNull() ?: continue
            if (second >= 60) continue
            val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            lines += LyricLine((minute * 60_000 + second * 1_000 + fraction + offset).coerceAtLeast(0), content)
        }
    }
    return ParsedLyrics(lines.sortedBy { it.timeMs }, plain.joinToString("\n"))
}

fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timeMs <= positionMs }
