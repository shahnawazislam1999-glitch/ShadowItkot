package com.raj.shadowplayer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.Inflater

/**
 * Reads TEXT subtitle tracks that are stored inside a video file (MKV / WebM and MP4 / MOV) and turns them into
 * SubRip (.srt) text, so the page can fill its Sentence Index without the user uploading a separate .srt.
 *
 * Plain JVM code (no Android classes) so it can be tested on its own. It only READS the file: it jumps from
 * element to element and skips the picture / sound data, so it never decodes or changes the video.
 *
 * Not supported (reported as "picture subtitles"): image-based tracks (PGS, VobSub, DVB). They are pictures, not text.
 */
object EmbeddedSubs {

    class Cue(val startMs: Long, var endMs: Long, val text: String)

    class Track(
        val name: String,
        val lang: String,
        val codec: String,
        val forced: Boolean,
        val cues: List<Cue>
    ) {
        /** What the user sees in the track list. */
        fun label(): String = name.ifBlank { langName(lang) } + (if (forced && !name.contains("forced", true)) " (forced)" else "")
        fun isEnglish(): Boolean = lang.startsWith("en", true) || name.contains("english", true)
        fun isHearingImpaired(): Boolean =
            name.contains("sdh", true) || name.contains("hearing", true) || name.contains("caption", true) || name.contains("commentary", true)
    }

    class Result(val tracks: List<Track>, val pictureTracks: Int)

    private val EMPTY = Result(emptyList(), 0)

    /** Returns every readable text subtitle track of the file ([cancelled] is polled now and then). Never throws. */
    fun scan(ch: FileChannel, cancelled: () -> Boolean): Result {
        return try {
            val rd = Rd(ch)
            val head = rd.peek(0, 12) ?: return EMPTY
            if (head.size >= 4 && (head[0].toInt() and 0xFF) == 0x1A && (head[1].toInt() and 0xFF) == 0x45 &&
                (head[2].toInt() and 0xFF) == 0xDF && (head[3].toInt() and 0xFF) == 0xA3
            ) {
                val s = MkvScanner(rd, cancelled)
                try { s.run() } catch (t: Throwable) { /* keep what was read so far */ }
                s.result()
            } else if (head.size >= 8 && isMp4Type(String(head, 4, 4, Charsets.ISO_8859_1))) {
                val s = Mp4Scanner(rd, cancelled)
                try { s.run() } catch (t: Throwable) { /* keep what was read so far */ }
                s.result()
            } else EMPTY
        } catch (t: Throwable) {
            EMPTY
        }
    }

    private fun isMp4Type(t: String) = t == "ftyp" || t == "moov" || t == "mdat" || t == "free" || t == "wide" || t == "skip"

    /** Best track to load without asking: normal English, then the biggest one. */
    fun bestIndex(tracks: List<Track>): Int {
        if (tracks.isEmpty()) return -1
        var best = 0
        var bestScore = Long.MIN_VALUE
        for ((i, t) in tracks.withIndex()) {
            var score = t.cues.size.toLong()
            if (t.isEnglish()) score += 1_000_000L
            if (t.forced) score -= 500_000L
            if (t.isHearingImpaired()) score -= 100_000L
            if (t.cues.size < 3) score -= 2_000_000L
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /** SubRip text of the cues (one blank line between cues, no blank lines inside a cue). */
    fun toSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        var n = 0
        for (c in cues) {
            val text = c.text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n{2,}"), "\n").trim()
            if (text.isEmpty()) continue
            n++
            sb.append(n).append('\n')
            sb.append(fmt(c.startMs)).append(" --> ").append(fmt(maxOf(c.endMs, c.startMs + 200))).append('\n')
            sb.append(text).append("\n\n")
        }
        return sb.toString()
    }

    private fun fmt(ms: Long): String {
        val t = if (ms < 0) 0 else ms
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val f = t % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, f)
    }

    fun langName(code: String): String {
        val c = code.trim().lowercase(Locale.US)
        return when (c) {
            "", "und", "mis", "zxx" -> "Unknown language"
            "en", "eng" -> "English"
            "hi", "hin" -> "Hindi"
            "bn", "ben" -> "Bengali"
            "ta", "tam" -> "Tamil"
            "te", "tel" -> "Telugu"
            "mr", "mar" -> "Marathi"
            "ur", "urd" -> "Urdu"
            "es", "spa" -> "Spanish"
            "fr", "fre", "fra" -> "French"
            "de", "ger", "deu" -> "German"
            "it", "ita" -> "Italian"
            "pt", "por" -> "Portuguese"
            "ru", "rus" -> "Russian"
            "ja", "jpn" -> "Japanese"
            "ko", "kor" -> "Korean"
            "zh", "chi", "zho" -> "Chinese"
            "ar", "ara" -> "Arabic"
            "tr", "tur" -> "Turkish"
            "nl", "dut", "nld" -> "Dutch"
            "pl", "pol" -> "Polish"
            "id", "ind" -> "Indonesian"
            "th", "tha" -> "Thai"
            "vi", "vie" -> "Vietnamese"
            "sv", "swe" -> "Swedish"
            "el", "gre", "ell" -> "Greek"
            "he", "heb" -> "Hebrew"
            else -> c.uppercase(Locale.US)
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Positional reads on the file (does not move the channel's own position). */
    private class Rd(private val ch: FileChannel) {
        val size: Long = ch.size()
        private val hdr = ByteArray(16)

        /** Up to [n] bytes at [pos]; shorter at the end of the file; null when [pos] is outside the file. */
        fun peek(pos: Long, n: Int): ByteArray? {
            if (pos < 0 || pos >= size || n <= 0) return null
            val len = minOf(n.toLong(), size - pos).toInt()
            val arr = if (len <= hdr.size) hdr else ByteArray(len)
            val bb = ByteBuffer.wrap(arr, 0, len)
            var p = pos
            while (bb.hasRemaining()) {
                val r = ch.read(bb, p)
                if (r < 0) break
                p += r
            }
            val got = bb.position()
            return if (arr === hdr) hdr.copyOf(got) else if (got == len) arr else arr.copyOf(got)
        }

        fun bytes(pos: Long, n: Int): ByteArray? {
            if (n < 0) return null
            if (n == 0) return ByteArray(0)
            val b = peek(pos, n) ?: return null
            return b
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

    private val ASS_TAGS = Regex("\\{[^}]*\\}")
    private val HTML_TAGS = Regex("<[^>]+>")

    private fun decodeUtf(data: ByteArray): String {
        if (data.size >= 2) {
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) return String(data, 2, data.size - 2, Charset.forName("UTF-16BE"))
            if (b0 == 0xFF && b1 == 0xFE) return String(data, 2, data.size - 2, Charset.forName("UTF-16LE"))
        }
        return String(data, Charsets.UTF_8)
    }

    /** Gives cues without a known end time one, and sorts them. */
    private fun finish(cues: ArrayList<Cue>): List<Cue> {
        cues.sortBy { it.startMs }
        for (i in cues.indices) {
            val c = cues[i]
            if (c.endMs <= c.startMs) {
                val guess = (1000L + c.text.length * 60L).coerceAtMost(7000L)
                var end = c.startMs + guess
                if (i + 1 < cues.size && cues[i + 1].startMs > c.startMs) end = minOf(end, cues[i + 1].startMs)
                c.endMs = end
            }
        }
        return cues
    }

    // ------------------------------------------------------------------ MKV / WebM

    private class MkvTrack {
        var number = -1L
        var type = -1
        var codec = ""
        var lang = ""
        var bcp47 = ""
        var name = ""
        var forced = false
        var compAlgo = -1
        var compSettings: ByteArray? = null
        val cues = ArrayList<Cue>()
    }

    private class MkvScanner(private val rd: Rd, private val cancelled: () -> Boolean) {
        private var scaleNs = 1_000_000L
        private val all = ArrayList<MkvTrack>()
        private var cur: MkvTrack? = null
        private val textTracks = HashMap<Long, MkvTrack>()
        private var registered = false
        private var picture = 0
        private var clusterTime = 0L

        private var pendTrack: MkvTrack? = null
        private var pendStart = 0L
        private var pendText = ""
        private var pendEnd = -1L

        fun result(): Result {
            flush()
            val list = ArrayList<Track>()
            for (t in all) {
                if (!isTextCodec(t.codec) || t.cues.isEmpty()) continue
                val cues = finish(t.cues)
                val lang = if (t.bcp47.isNotEmpty()) t.bcp47 else t.lang
                list.add(Track(t.name, lang, t.codec, t.forced, cues))
            }
            return Result(list, if (!registered) countPictures() else picture)
        }

        private fun countPictures(): Int = all.count { isPicture(it.codec) }

        private fun isTextCodec(codec: String) =
            codec.startsWith("S_TEXT/") || codec == "D_WEBVTT/SUBTITLES" || codec == "D_WEBVTT/CAPTIONS"

        private fun isPicture(codec: String) =
            codec.startsWith("S_HDMV") || codec.startsWith("S_VOBSUB") || codec.startsWith("S_DVBSUB") || codec.startsWith("S_IMAGE")

        private fun register() {
            if (registered) return
            registered = true
            picture = 0
            for (t in all) {
                if (t.type != 17) continue
                if (isTextCodec(t.codec)) textTracks[t.number] = t
                else if (isPicture(t.codec)) picture++
            }
        }

        private fun flush() {
            val t = pendTrack ?: return
            pendTrack = null
            if (pendText.isNotBlank()) t.cues.add(Cue(pendStart, pendEnd, pendText))
        }

        private fun readUInt(pos: Long, n: Int): Long {
            if (n <= 0 || n > 8) return 0
            val b = rd.peek(pos, n) ?: return 0
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }

        private fun readStr(pos: Long, n: Int): String {
            if (n <= 0 || n > 4096) return ""
            val b = rd.peek(pos, n) ?: return ""
            var len = b.size
            while (len > 0 && b[len - 1].toInt() == 0) len--
            return String(b, 0, len, Charsets.UTF_8)
        }

        fun run() {
            var pos = 0L
            val size = rd.size
            var steps = 0
            while (pos < size) {
                if ((++steps and 0x3FF) == 0 && cancelled()) return
                val h = rd.peek(pos, 12) ?: break
                val b0 = h[0].toInt() and 0xFF
                val idLen = when {
                    b0 >= 0x80 -> 1
                    b0 >= 0x40 -> 2
                    b0 >= 0x20 -> 3
                    b0 >= 0x10 -> 4
                    else -> break
                }
                if (h.size < idLen + 1) break
                var id = 0L
                for (i in 0 until idLen) id = (id shl 8) or (h[i].toLong() and 0xFF)
                val s0 = h[idLen].toInt() and 0xFF
                var sLen = 1
                var mask = 0x80
                while (sLen <= 8 && (s0 and mask) == 0) { sLen++; mask = mask shr 1 }
                if (sLen > 8 || h.size < idLen + sLen) break
                var sz = (s0 and (mask - 1)).toLong()
                var allOnes = (s0 and (mask - 1)) == mask - 1
                for (i in 1 until sLen) {
                    val v = h[idLen + i].toLong() and 0xFF
                    sz = (sz shl 8) or v
                    if (v != 0xFFL) allOnes = false
                }
                val hdr = idLen + sLen
                val dataPos = pos + hdr
                val unknown = allOnes

                // a block's duration comes after the block itself, so a pending block is closed by the next element
                // that is not part of the same group
                if (pendTrack != null && id != 0x9BL && id != 0xFBL && id != 0x75A2L && id != 0x75A1L) flush()

                when (id) {
                    // containers we walk into
                    0x18538067L, 0xA0L, 0x1654AE6BL, 0x6D80L, 0x6240L, 0x5034L, 0x1549A966L -> { pos = dataPos; continue }
                    0x1F43B675L -> { register(); pos = dataPos; continue }
                    0xAEL -> { val t = MkvTrack(); all.add(t); cur = t; pos = dataPos; continue }
                }
                if (unknown || sz < 0) break
                val next = dataPos + sz
                val n = sz.toInt()
                when (id) {
                    0x2AD7B1L -> { val v = readUInt(dataPos, n); if (v > 0) scaleNs = v }
                    0xE7L -> clusterTime = readUInt(dataPos, n)
                    0xD7L -> cur?.number = readUInt(dataPos, n)
                    0x83L -> cur?.type = readUInt(dataPos, n).toInt()
                    0x86L -> cur?.codec = readStr(dataPos, n)
                    0x22B59CL -> cur?.lang = readStr(dataPos, n)
                    0x22B59DL -> cur?.bcp47 = readStr(dataPos, n)
                    0x536EL -> cur?.name = readStr(dataPos, n)
                    0x55AAL -> cur?.forced = readUInt(dataPos, n) == 1L
                    0x4254L -> cur?.compAlgo = readUInt(dataPos, n).toInt()
                    0x4255L -> if (n in 1..64) cur?.compSettings = rd.peek(dataPos, n)
                    0x9BL -> if (pendTrack != null) pendEnd = pendStart + readUInt(dataPos, n) * scaleNs / 1_000_000L
                    0xA3L -> { register(); block(dataPos, n, false) }
                    0xA1L -> { register(); block(dataPos, n, true) }
                }
                if (next <= pos) break
                pos = next
            }
        }

        /** [inGroup]: a block inside a BlockGroup (its duration follows); otherwise a SimpleBlock. */
        private fun block(dataPos: Long, size: Int, inGroup: Boolean) {
            if (size < 4 || size > 2_000_000) return
            val h = rd.peek(dataPos, 12) ?: return
            val b0 = h[0].toInt() and 0xFF
            var tl = 1
            var m = 0x80
            while (tl <= 8 && (b0 and m) == 0) { tl++; m = m shr 1 }
            if (tl > 8 || h.size < tl + 3) return
            var num = (b0 and (m - 1)).toLong()
            for (i in 1 until tl) num = (num shl 8) or (h[i].toLong() and 0xFF)
            val ti = textTracks[num] ?: return
            val rel = ((h[tl].toInt() shl 8) or (h[tl + 1].toInt() and 0xFF)).toShort().toInt()
            val flags = h[tl + 2].toInt() and 0xFF
            if (((flags shr 1) and 3) != 0) return // laced blocks are not used for subtitles
            val len = size - (tl + 3)
            if (len <= 0) return
            val payload = rd.bytes(dataPos + tl + 3, len) ?: return
            val text = cueText(ti, payload)
            if (text.isBlank()) return
            val start = (clusterTime + rel) * scaleNs / 1_000_000L
            if (inGroup) {
                pendTrack = ti
                pendStart = start
                pendText = text
                pendEnd = -1L
            } else {
                ti.cues.add(Cue(start, -1L, text))
            }
        }

        private fun cueText(t: MkvTrack, payload: ByteArray): String {
            var data = payload
            try {
                if (t.compAlgo == 0) {
                    val inf = Inflater()
                    inf.setInput(data)
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!inf.finished()) {
                        val r = inf.inflate(buf)
                        if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        out.write(buf, 0, r)
                    }
                    inf.end()
                    data = out.toByteArray()
                } else if (t.compAlgo == 3) {
                    val pre = t.compSettings
                    if (pre != null) data = pre + data
                }
            } catch (e: Exception) {
                return ""
            }
            val s = String(data, Charsets.UTF_8)
            return when {
                t.codec == "S_TEXT/ASS" || t.codec == "S_TEXT/SSA" -> assText(s)
                t.codec == "S_TEXT/WEBVTT" -> vttText(s)
                t.codec.startsWith("D_WEBVTT/") -> vttOldText(s)
                else -> s.trim()
            }
        }

        /** "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text" */
        private fun assText(s: String): String {
            val parts = s.split(",", limit = 9)
            if (parts.size < 9) return ""
            var t = parts[8]
            if (t.contains("{\\p1") || t.contains("{\\p2")) return "" // drawing, not text
            t = ASS_TAGS.replace(t, "")
            t = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            return t.trim()
        }

        /** Older WebVTT mapping: "identifier\nsettings\npayload". */
        private fun vttOldText(s: String): String {
            val t = s.replace("\r\n", "\n")
            val a = t.indexOf('\n')
            if (a < 0) return t.trim()
            val b = t.indexOf('\n', a + 1)
            if (b < 0) return ""
            return t.substring(b + 1).trim()
        }

        /** WebVTT in Matroska (S_TEXT/WEBVTT): the block holds the cue payload. */
        private fun vttText(s: String): String {
            return s.replace("\r\n", "\n").trim()
        }
    }

    // ------------------------------------------------------------------ MP4 / MOV

    private class Mp4Track {
        var timescale = 1000L
        var lang = ""
        var handler = ""
        var format = ""
        var stts: ByteArray? = null
        var stsz: ByteArray? = null
        var stsc: ByteArray? = null
        var stco: ByteArray? = null
        var co64: ByteArray? = null
    }

    private class Mp4Scanner(private val rd: Rd, private val cancelled: () -> Boolean) {
        private val tracks = ArrayList<Track>()
        private var picture = 0

        fun result(): Result = Result(tracks, picture)

        /** Calls [fn] for each box between [start] and [end]: (type, dataStart, dataEnd). */
        private fun children(start: Long, end: Long, fn: (String, Long, Long) -> Unit) {
            var pos = start
            var steps = 0
            while (pos + 8 <= end) {
                if ((++steps and 0xFF) == 0 && cancelled()) return
                val h = rd.peek(pos, 16) ?: return
                if (h.size < 8) return
                var len = u32(h, 0)
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                var hdr = 8
                if (len == 1L) {
                    if (h.size < 16) return
                    len = u64(h, 8)
                    hdr = 16
                } else if (len == 0L) {
                    len = end - pos
                }
                if (len < hdr || pos + len > end) len = minOf(end - pos, maxOf(len, hdr.toLong()))
                if (len < hdr) return
                fn(type, pos + hdr, pos + len)
                pos += len
            }
        }

        fun run() {
            children(0, rd.size) { type, s, e ->
                if (type == "moov") children(s, e) { t2, s2, e2 -> if (t2 == "trak") track(s2, e2) }
            }
        }

        private fun box(s: Long, e: Long, cap: Int = 64 * 1024 * 1024): ByteArray? {
            val n = e - s
            if (n < 0 || n > cap) return null
            return rd.bytes(s, n.toInt())
        }

        private fun track(start: Long, end: Long) {
            val t = Mp4Track()
            children(start, end) { type, s, e ->
                if (type == "mdia") children(s, e) { t2, s2, e2 ->
                    when (t2) {
                        "mdhd" -> {
                            val b = box(s2, e2) ?: return@children
                            if (b.size >= 24) {
                                val v = b[0].toInt() and 0xFF
                                if (v == 1 && b.size >= 36) {
                                    t.timescale = u32(b, 20)
                                    t.lang = langOf(u16(b, 32))
                                } else if (v == 0) {
                                    t.timescale = u32(b, 12)
                                    t.lang = langOf(u16(b, 20))
                                }
                            }
                        }
                        "hdlr" -> {
                            val b = box(s2, e2) ?: return@children
                            if (b.size >= 12) t.handler = String(b, 8, 4, Charsets.ISO_8859_1)
                        }
                        "minf" -> children(s2, e2) { t3, s3, e3 ->
                            if (t3 == "stbl") children(s3, e3) { t4, s4, e4 ->
                                when (t4) {
                                    "stsd" -> {
                                        val b = box(s4, minOf(e4, s4 + 64)) ?: return@children
                                        if (b.size >= 16) t.format = String(b, 12, 4, Charsets.ISO_8859_1)
                                    }
                                    "stts" -> t.stts = box(s4, e4)
                                    "stsz" -> t.stsz = box(s4, e4)
                                    "stsc" -> t.stsc = box(s4, e4)
                                    "stco" -> t.stco = box(s4, e4)
                                    "co64" -> t.co64 = box(s4, e4)
                                }
                            }
                        }
                    }
                }
            }
            val isSubHandler = t.handler == "sbtl" || t.handler == "text" || t.handler == "subt"
            if (!isSubHandler) return
            if (t.format == "mp4s") { picture++; return }
            if (t.format != "tx3g" && t.format != "text" && t.format != "wvtt") return
            val cues = samples(t)
            if (cues.isNotEmpty()) tracks.add(Track("", t.lang, t.format, false, finish(cues)))
        }

        private fun langOf(v: Int): String {
            if (v == 0 || v == 0x7FFF) return ""
            val a = ((v shr 10) and 31) + 0x60
            val b = ((v shr 5) and 31) + 0x60
            val c = (v and 31) + 0x60
            if (a !in 97..122 || b !in 97..122 || c !in 97..122) return ""
            return "" + a.toChar() + b.toChar() + c.toChar()
        }

        private fun samples(t: Mp4Track): ArrayList<Cue> {
            val out = ArrayList<Cue>()
            val stsz = t.stsz ?: return out
            val stts = t.stts ?: return out
            val stsc = t.stsc ?: return out
            if (stsz.size < 12 || stts.size < 8 || stsc.size < 8) return out
            val fixedSize = u32(stsz, 4)
            val count = u32(stsz, 8).toInt()
            if (count <= 0 || count > 5_000_000) return out
            if (fixedSize == 0L && stsz.size < 12 + count * 4) return out

            // sample sizes
            val sizes = IntArray(count)
            for (i in 0 until count) sizes[i] = if (fixedSize != 0L) fixedSize.toInt() else u32(stsz, 12 + i * 4).toInt()

            // sample start times / durations
            val starts = LongArray(count)
            val durs = LongArray(count)
            run {
                val entries = u32(stts, 4).toInt()
                var idx = 0
                var time = 0L
                var o = 8
                var k = 0
                while (k < entries && o + 8 <= stts.size && idx < count) {
                    val n = u32(stts, o).toInt()
                    val d = u32(stts, o + 4)
                    var j = 0
                    while (j < n && idx < count) {
                        starts[idx] = time
                        durs[idx] = d
                        time += d
                        idx++
                        j++
                    }
                    o += 8
                    k++
                }
            }

            // chunk offsets
            val chunkOffsets: LongArray
            val co = t.stco
            val c64 = t.co64
            if (co != null && co.size >= 8) {
                val n = u32(co, 4).toInt().coerceAtMost((co.size - 8) / 4)
                chunkOffsets = LongArray(n) { u32(co, 8 + it * 4) }
            } else if (c64 != null && c64.size >= 8) {
                val n = u32(c64, 4).toInt().coerceAtMost((c64.size - 8) / 8)
                chunkOffsets = LongArray(n) { u64(c64, 8 + it * 8) }
            } else return out

            // sample -> file offset through the sample-to-chunk table
            val stscCount = u32(stsc, 4).toInt().coerceAtMost((stsc.size - 8) / 12)
            if (stscCount <= 0) return out
            var sample = 0
            var entry = 0
            for (chunk in chunkOffsets.indices) {
                while (entry + 1 < stscCount && u32(stsc, 8 + (entry + 1) * 12) <= (chunk + 1).toLong()) entry++
                val perChunk = u32(stsc, 8 + entry * 12 + 4).toInt()
                var off = chunkOffsets[chunk]
                var j = 0
                while (j < perChunk && sample < count) {
                    val sz = sizes[sample]
                    if (sz > 2 && sz < 1_000_000) {
                        val b = rd.bytes(off, sz)
                        if (b != null && b.size == sz) {
                            val text = if (t.format == "wvtt") wvtt(b) else tx3g(b)
                            if (text.isNotBlank()) {
                                val st = starts[sample] * 1000L / maxOf(1L, t.timescale)
                                val en = (starts[sample] + durs[sample]) * 1000L / maxOf(1L, t.timescale)
                                out.add(Cue(st, en, text))
                            }
                        }
                    }
                    off += sz
                    sample++
                    j++
                }
                if (sample >= count) break
                if ((chunk and 0x3FF) == 0x3FF && cancelled()) break
            }
            return out
        }

        /** 2-byte length + text (+ optional style boxes). */
        private fun tx3g(b: ByteArray): String {
            val len = u16(b, 0)
            if (len == 0 || len + 2 > b.size) return ""
            return decodeUtf(b.copyOfRange(2, 2 + len)).trim()
        }

        /** WebVTT sample: 'vttc' boxes holding a 'payl' box with the text. */
        private fun wvtt(b: ByteArray): String {
            val sb = StringBuilder()
            var o = 0
            while (o + 8 <= b.size) {
                val len = u32(b, o).toInt()
                if (len < 8 || o + len > b.size) break
                val type = String(b, o + 4, 4, Charsets.ISO_8859_1)
                if (type == "vttc") {
                    var p = o + 8
                    val end = o + len
                    while (p + 8 <= end) {
                        val l2 = u32(b, p).toInt()
                        if (l2 < 8 || p + l2 > end) break
                        if (String(b, p + 4, 4, Charsets.ISO_8859_1) == "payl") {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(String(b, p + 8, l2 - 8, Charsets.UTF_8))
                        }
                        p += l2
                    }
                }
                o += len
            }
            return sb.toString().trim()
        }
    }

    /** Text that the page will show: tags removed, one line. (Kept here so the preview / dialog can use it.) */
    fun plain(s: String): String = HTML_TAGS.replace(s, "").replace(Regex("\\s+"), " ").trim()
}
