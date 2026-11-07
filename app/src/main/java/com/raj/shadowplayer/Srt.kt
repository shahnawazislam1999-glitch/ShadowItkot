package com.raj.shadowplayer

import java.util.Locale

/** SubRip (.srt) reading / writing. Same rules as the old page: a block needs a time line, empty text is dropped. */
object Srt {
    class Cue(var start: Double, var end: Double, var text: String)

    private val TIME = Regex("""((?:\d{1,2}:)?\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[,.]\d{1,3})""")
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val BLANK_LINE_SPACES = Regex("\n[ \\t]+(?=\n)")
    private val BLOCK_SPLIT = Regex("\n{2,}")

    fun parse(data: String): MutableList<Cue> {
        val norm = data.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace(BLANK_LINE_SPACES, "\n")
        val out = ArrayList<Cue>()
        for (block in norm.split(BLOCK_SPLIT)) {
            val lines = block.trim().split('\n')
            if (lines.size < 2) continue
            val ti = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val m = TIME.find(lines[ti]) ?: continue
            val start = toSeconds(m.groupValues[1])
            val end = toSeconds(m.groupValues[2])
            if (start.isNaN() || end.isNaN() || end < start) continue
            val text = lines.drop(ti + 1).joinToString(" ").replace(TAGS, "").replace(SPACES, " ").trim()
            if (text.isNotEmpty()) out.add(Cue(start, end, text))
        }
        return out
    }

    private fun toSeconds(t: String): Double {
        val p = t.split(':').toMutableList()
        if (p.size == 2) p.add(0, "0")
        if (p.size != 3) return Double.NaN
        val s = p[2].split(',', '.')
        val frac = if (s.size > 1) s[1] else "0"
        val h = p[0].toIntOrNull() ?: return Double.NaN
        val m = p[1].toIntOrNull() ?: return Double.NaN
        val sec = s[0].toIntOrNull() ?: return Double.NaN
        val f = frac.toIntOrNull() ?: return Double.NaN
        return h * 3600.0 + m * 60.0 + sec + f / Math.pow(10.0, frac.length.toDouble())
    }

    private fun stamp(total: Double): String {
        var t = if (total.isNaN() || total < 0) 0.0 else total
        var whole = Math.floor(t).toLong()
        var ms = Math.round((t - whole) * 1000.0)
        if (ms >= 1000L) {
            ms -= 1000L
            whole += 1L
        }
        val s = whole % 60L
        val m = (whole / 60L) % 60L
        val h = whole / 3600L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    fun serialize(cues: List<Cue>): String {
        val sb = StringBuilder()
        for ((i, c) in cues.withIndex()) {
            if (i > 0) sb.append("\n\n")
            sb.append(i + 1).append('\n').append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n').append(c.text)
        }
        sb.append('\n')
        return sb.toString()
    }

    /** Subtitle files come in UTF-8, UTF-16 (with BOM) or an old 8-bit code page; this picks the right one. */
    fun decode(b: ByteArray): String {
        if (b.size >= 2) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(b, Charsets.UTF_8)
        // a wrongly decoded file is full of U+FFFD; fall back to Latin-1 in that case
        return if (utf8.count { it == '\uFFFD' } > 3) String(b, Charsets.ISO_8859_1) else utf8
    }
}
