package com.raj.shadowplayer

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import java.util.Random

/**
 * The sentence player: a Kotlin port of the engine that used to live in the page's JavaScript.
 * It plays one subtitle at a time (seek to its start, play until its end), repeats it, waits the delay,
 * moves on, loops the passage, and keeps stars / bookmarks / hidden lines / filters.
 * Everything runs on the main thread.
 */
class SentenceEngine(
    private val host: Host,
    private val settings: PlayerSettings,
    private val player: () -> ExoPlayer?
) {
    interface Host {
        fun onSubtitle(text: String, playing: Boolean)
        fun onFlags(starred: Boolean, bookmarked: Boolean, customRepeat: Int, globalRepeat: Int, delaySec: Double, curIndex: Int)
        fun onSub2(text: String, on: Boolean, has: Boolean)
        fun onFilters(words: String, range: String)
        fun toast(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private var data: VideoData? = null
    private val subs = ArrayList<Srt.Cue>()
    private val second = ArrayList<Srt.Cue>()

    private var playing = false
    private var jumping = false
    private var jumpWant = false
    private var free = false
    private var freeIdx = -2
    private var sentenceIndex = 0
    private var repeatCount = 0
    private var passageCount = 0
    private var playedInLoop = 0
    private var groupStart = -1
    private var groupEnd = -1
    private var startIdx = 0
    private var endIdx = -1
    private var token = 0
    private var scrubWas: Boolean? = null

    val total: Int get() = subs.size
    val hasSubs: Boolean get() = subs.isNotEmpty()
    val isFree: Boolean get() = free
    val hasSecond: Boolean get() = second.isNotEmpty()

    /** What the play / pause button should show right now. */
    val uiPlaying: Boolean
        get() {
            val p = player()
            if (!hasSubs || free) return p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
            return if (jumping) jumpWant else playing
        }

    /** Index (0 based) of the subtitle the learner is on, or -1. */
    val currentIndex: Int
        get() {
            val ix = if (free && freeIdx >= 0) freeIdx else sentenceIndex
            return if (ix in subs.indices) ix else -1
        }

    fun textAt(i: Int): String = if (i in subs.indices) subs[i].text else ""

    // ------------------------------------------------------------------ loading

    fun attach(d: VideoData) {
        stop()
        data = d
        subs.clear()
        second.clear()
        sentenceIndex = d.index
        free = false
        freeIdx = -2
    }

    fun loadMain(list: List<Srt.Cue>, persist: Boolean) {
        stop()
        subs.clear()
        subs.addAll(list)
        if (persist) data?.saveMainSrt(Srt.serialize(subs))
        if (sentenceIndex !in subs.indices) sentenceIndex = 0
        repeatCount = 0
        passageCount = 0
        updateVisibility()
        pushFilters()
        if (free) main.post(freeRunnable)
        publish()
    }

    fun loadSecond(list: List<Srt.Cue>, persist: Boolean) {
        second.clear()
        second.addAll(list)
        if (persist) data?.saveSecondSrt(Srt.serialize(second))
        publish()
    }

    /** Starts the sentence player: at the remembered sentence, or (fromVideo) at the sentence that is on the picture now. */
    fun begin(play: Boolean, fromVideo: Boolean) {
        if (subs.isEmpty()) {
            publish()
            return
        }
        updateVisibility()
        var i = sentenceIndex
        if (fromVideo) {
            val p = player()
            if (p != null) {
                val t = p.currentPosition / 1000.0
                var j = subAt(t)
                if (j < 0) j = subAfter(t)
                if (j >= 0) i = j
            }
        }
        if (i !in subs.indices) i = 0
        if (!isPlayable(i)) {
            val f = firstPlayable()
            if (f != -1) i = f
        }
        if (free) {
            publish()
            return
        }
        jumpTo(i, play)
    }

    /** Stops every timer (the video is left as it is). */
    fun stop() {
        cancelAll()
        playing = false
        jumping = false
        scrubWas = null
    }

    fun release() {
        stop()
        free = false
    }

    /** The screen left the foreground: everything pauses. */
    fun onPause() {
        if (free) main.removeCallbacks(freeRunnable) else stop()
        player()?.pause()
        publish()
    }

    fun onResume() {
        if (free) {
            main.removeCallbacks(freeRunnable)
            main.post(freeRunnable)
        }
    }

    private fun cancelAll() {
        token++
        main.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ time helpers

    private fun matchTime(raw: Double): Double {
        val t = raw + settings.syncOffset
        return if (t.isNaN() || t < 0.0) 0.0 else t
    }

    private fun videoTime(subTime: Double): Double {
        val t = subTime - settings.syncOffset
        return if (t.isNaN() || t < 0.0) 0.0 else t
    }

    private fun seekToSub(i: Int) {
        val p = player() ?: return
        if (i !in subs.indices) return
        p.seekTo((videoTime(subs[i].start) * 1000.0).toLong())
    }

    /** Subtitle showing at raw video time t (seconds), or -1 in a gap. */
    private fun subAt(t: Double): Int {
        if (subs.isEmpty()) return -1
        val mt = matchTime(t)
        val h = freeIdx
        if (h >= 0 && h < subs.size && mt >= subs[h].start && mt < subs[h].end) return h
        for (i in subs.indices) {
            val u = subs[i]
            if (mt >= u.start && mt < u.end) return i
        }
        return -1
    }

    private fun subAfter(t: Double): Int {
        val mt = matchTime(t)
        for (i in subs.indices) if (subs[i].start > mt + 0.05) return i
        return -1
    }

    private fun subBefore(t: Double): Int {
        val mt = matchTime(t)
        var r = -1
        for (i in subs.indices) {
            if (subs[i].start < mt - 0.7) r = i else if (subs[i].start >= mt) break
        }
        return r
    }

    // ------------------------------------------------------------------ what may be played

    private fun wordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    fun updateVisibility() {
        val maxIdx = subs.size - 1
        startIdx = 0
        endIdx = maxIdx
        val r = data?.range?.trim().orEmpty()
        if (r.isEmpty() || maxIdx < 0) return
        val parts = r.split('-').map { it.trim().toIntOrNull() }
        if (parts.size == 2) {
            val a = parts[0]
            val b = parts[1]
            if (a != null && a - 1 in 0..maxIdx) startIdx = a - 1
            if (b != null) {
                if (b - 1 >= startIdx && b - 1 <= maxIdx) endIdx = b - 1 else if (b - 1 > maxIdx) endIdx = maxIdx
            }
        } else if (parts.size == 1) {
            val a = parts[0]
            if (a != null && a - 1 in 0..maxIdx) {
                startIdx = a - 1
                endIdx = a - 1
            }
        }
    }

    private fun wordsOk(text: String): Boolean {
        val w = data?.words?.trim().orEmpty()
        if (w.isEmpty()) return true
        val parts = w.split('-')
        val minW = parts[0].trim().toIntOrNull()
        val maxW = if (parts.size > 1) parts[1].trim().toIntOrNull() else null
        val count = wordCount(text)
        if (minW != null && minW > 0 && count < minW) return false
        if (maxW != null && count > maxW) return false
        return true
    }

    private fun isPlayable(i: Int): Boolean {
        if (i < 0 || i >= subs.size) return false
        if (i < startIdx || i > endIdx) return false
        val d = data ?: return true
        val text = subs[i].text
        if (d.hidden.contains(text)) return false
        if (settings.starredOnly && !d.starred.contains(text)) return false
        return wordsOk(text)
    }

    private fun playableIndices(): List<Int> {
        val out = ArrayList<Int>()
        if (endIdx < startIdx) return out
        for (i in startIdx..endIdx) if (isPlayable(i)) out.add(i)
        return out
    }

    private fun firstPlayable(): Int {
        playedInLoop = 0
        val arr = playableIndices()
        if (arr.isEmpty()) return -1
        if (settings.shuffle) {
            playedInLoop = 1
            return arr[rnd.nextInt(arr.size)]
        }
        return arr[0]
    }

    private fun nextPlayableAfter(cur: Int): Int {
        val arr = playableIndices()
        if (arr.isEmpty()) return -1
        if (settings.shuffle) {
            playedInLoop++
            if (playedInLoop >= arr.size) {
                playedInLoop = 0
                return -1
            }
            return arr[rnd.nextInt(arr.size)]
        }
        val pos = arr.indexOf(cur)
        if (pos == -1) return arr.firstOrNull { it > cur } ?: -1
        val nextPos = pos + 1
        return if (nextPos >= arr.size) -1 else arr[nextPos]
    }

    private fun maxRepeats(sentence: String): Int {
        val c = data?.customRepeats?.get(sentence)
        return if (c != null && c > 0) c else settings.repeatAll.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ the sentence loop

    private fun startSentence() {
        if (!playing) return
        val p = player() ?: return
        if (subs.isEmpty()) {
            playing = false
            publish()
            return
        }
        updateVisibility()
        if (!isPlayable(sentenceIndex)) {
            val f = firstPlayable()
            if (f != -1) {
                sentenceIndex = f
            } else {
                playing = false
                host.toast("No playable subtitles (check Words, Range, hidden lines)")
                publish()
                return
            }
        }
        val group = playableGroup(sentenceIndex)
        if (group.isEmpty()) {
            val n = firstPlayable()
            if (n != -1 && n != sentenceIndex) {
                sentenceIndex = n
                startSentence()
            } else {
                playing = false
                publish()
            }
            return
        }
        groupStart = group[0]
        groupEnd = group[group.size - 1]
        publish()
        val startSub = subs[groupStart]
        val endSub = subs[groupEnd]
        val my = ++token
        p.pause()
        p.setPlaybackSpeed(settings.speed.coerceIn(0.25f, 4f))
        p.seekTo((videoTime(startSub.start) * 1000.0).toLong())
        p.play()
        val endSec = endSub.end
        val watcher = object : Runnable {
            override fun run() {
                if (my != token || !playing) return
                val q = player() ?: return
                val t = matchTime(q.currentPosition / 1000.0)
                if (t >= endSec || q.playbackState == Player.STATE_ENDED) {
                    q.pause()
                    handleSentenceEnd()
                    return
                }
                main.postDelayed(this, 40)
            }
        }
        main.postDelayed(watcher, 40)
    }

    private fun playableGroup(start: Int): List<Int> {
        val count = settings.lineCount.coerceIn(1, 1000)
        val out = ArrayList<Int>()
        val maxEnd = minOf(subs.size, start + count)
        for (i in start until maxEnd) if (isPlayable(i)) out.add(i)
        return out
    }

    private fun handleSentenceEnd() {
        if (jumping || !playing) return
        val completed = sentenceIndex
        if (completed !in subs.indices) return
        val groupEndIdx = if (groupEnd >= completed) groupEnd else completed
        val maxRep = maxRepeats(subs[completed].text)
        val maxPass = settings.passageLoops.coerceAtLeast(1)
        repeatCount++
        if (repeatCount >= maxRep) {
            repeatCount = 0
            val next = nextPlayableAfter(groupEndIdx)
            if (next != -1) {
                sentenceIndex = next
            } else {
                passageCount++
                if (passageCount >= maxPass) {
                    playing = false
                    var f = firstPlayable()
                    if (f == -1) f = startIdx
                    sentenceIndex = f
                    passageCount = 0
                    groupStart = -1
                    groupEnd = -1
                    saveIndex()
                    host.toast("Finished all passage loops")
                    publish()
                    return
                } else {
                    val r = firstPlayable()
                    if (r != -1) sentenceIndex = r
                }
            }
            saveIndex()
        }
        groupStart = -1
        groupEnd = -1
        publish()
        val delay = settings.delaySec
        if (delay <= 0.0) {
            startSentence()
        } else {
            val my = ++token
            main.postDelayed({ if (my == token && playing) startSentence() }, (delay * 1000.0).toLong())
        }
    }

    private fun saveIndex() {
        val d = data ?: return
        d.index = sentenceIndex
        d.save()
    }

    /** Moves to a subtitle. want = keep playing afterwards. Short pause so quick taps on next / previous do not pile up. */
    private fun jumpTo(index: Int, want: Boolean) {
        if (index !in subs.indices) return
        val d = data
        updateVisibility()
        if ((index < startIdx || index > endIdx) && d != null && d.range.isNotEmpty()) {
            d.range = ""
            d.save()
            updateVisibility()
            pushFilters()
        }
        if (settings.starredOnly && d != null && !d.starred.contains(subs[index].text)) settings.starredOnly = false
        jumpWant = want
        jumping = true
        playing = false
        cancelAll()
        player()?.pause()
        sentenceIndex = index
        repeatCount = 0
        playedInLoop = 0
        groupStart = -1
        groupEnd = -1
        saveIndex()
        publish()
        val my = token
        main.postDelayed({
            if (my == token) {
                jumping = false
                if (jumpWant) {
                    playing = true
                    startSentence()
                } else {
                    playing = false
                    showStatic()
                }
            }
        }, 200)
    }

    /** Paused on a sentence: show its first picture and its text. */
    private fun showStatic() {
        val p = player()
        if (p != null && sentenceIndex in subs.indices) {
            p.pause()
            seekToSub(sentenceIndex)
        }
        publish()
    }

    // ------------------------------------------------------------------ commands from the buttons and gestures

    fun cmd(op: String) {
        val p = player() ?: return
        if (!hasSubs) {
            when (op) {
                "toggle" -> togglePlain(p)
                "next" -> p.seekTo((p.currentPosition + 10_000L).coerceAtLeast(0L))
                "prev" -> p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
            }
            publishLater()
            return
        }
        when (op) {
            "bookmark" -> { toggleBookmark(); return }
            "star" -> { toggleStar(); return }
            "sub2" -> { toggleSecond(); return }
        }
        if (free) {
            when (op) {
                "toggle" -> togglePlain(p)
                "hide" -> freeHide()
                "next" -> {
                    val nx = subAfter(p.currentPosition / 1000.0)
                    if (nx != -1) { seekToSub(nx); freeIdx = -2 }
                }
                "prev" -> {
                    val pv = subBefore(p.currentPosition / 1000.0)
                    if (pv != -1) { seekToSub(pv); freeIdx = -2 }
                }
            }
            publishLater()
            return
        }
        when (op) {
            "toggle" -> toggle()
            "hide" -> hideCurrent()
            "next" -> {
                val n = nextPlayableAfter(sentenceIndex)
                if (n != -1) jumpTo(n, uiPlaying)
            }
            "prev" -> {
                var q = sentenceIndex - 1
                while (q >= 0 && !isPlayable(q)) q--
                if (q >= 0) jumpTo(q, uiPlaying)
            }
        }
    }

    private fun togglePlain(p: ExoPlayer) {
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            p.play()
        }
        publishLater()
    }

    private fun toggle() {
        val p = player() ?: return
        if (jumping) {
            jumpWant = !jumpWant
            publish()
            return
        }
        if (playing) {
            playing = false
            cancelAll()
            p.pause()
        } else {
            playing = true
            startSentence()
        }
        publish()
    }

    private fun publishLater() {
        main.postDelayed({ publish() }, 80)
    }

    private fun hideCurrent() {
        val d = data ?: return
        if (sentenceIndex !in subs.indices) return
        val text = subs[sentenceIndex].text
        if (d.hidden.contains(text)) return
        d.hidden.add(text)
        d.save()
        updateVisibility()
        if (!isPlayable(sentenceIndex)) {
            var n = nextPlayableAfter(sentenceIndex)
            if (n == -1) n = firstPlayable()
            if (n != -1) {
                jumpTo(n, uiPlaying)
            } else {
                stop()
                host.toast("Every subtitle is hidden now")
                publish()
            }
        } else {
            publish()
        }
    }

    private fun freeHide() {
        val d = data ?: return
        val i = if (freeIdx >= 0) freeIdx else sentenceIndex
        if (i !in subs.indices) return
        d.hidden.add(subs[i].text)
        d.save()
        publish()
    }

    private fun toggleBookmark() {
        val d = data ?: return
        if (sentenceIndex !in subs.indices) return
        val t = subs[sentenceIndex].text
        if (!d.bookmarked.remove(t)) d.bookmarked.add(t)
        d.save()
        publish()
    }

    private fun toggleStar() {
        val d = data ?: return
        if (sentenceIndex !in subs.indices) return
        val t = subs[sentenceIndex].text
        if (!d.starred.remove(t)) d.starred.add(t)
        d.save()
        publish()
    }

    private fun toggleSecond() {
        val d = data ?: return
        if (second.isEmpty()) return
        d.showSecond = !d.showSecond
        d.save()
        publish()
    }

    // ------------------------------------------------------------------ unlink (free play): the film just plays, subtitles follow it

    fun setUnlinked(on: Boolean) {
        if (!hasSubs) return
        if (on) enterFree() else exitFree()
    }

    private val freeRunnable = object : Runnable {
        override fun run() {
            if (!free) return
            val p = player()
            if (p != null) {
                val i = subAt(p.currentPosition / 1000.0)
                if (i != freeIdx) {
                    freeIdx = i
                    if (i >= 0) {
                        sentenceIndex = i
                        repeatCount = 0
                        saveIndex()
                    }
                }
            }
            publish()
            main.postDelayed(this, 120)
        }
    }

    private fun enterFree() {
        if (free) return
        stop()
        free = true
        freeIdx = -2
        val p = player()
        if (p != null) {
            p.setPlaybackSpeed(settings.speed.coerceIn(0.25f, 4f))
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            p.play()
        }
        main.post(freeRunnable)
    }

    private fun exitFree() {
        if (!free) return
        free = false
        main.removeCallbacks(freeRunnable)
        val p = player()
        val was = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        p?.pause()
        var i = if (freeIdx >= 0) freeIdx else sentenceIndex
        if (i !in subs.indices) i = sentenceIndex
        updateVisibility()
        if (!isPlayable(i)) {
            var n = nextPlayableAfter(i)
            if (n == -1) n = firstPlayable()
            if (n != -1) i = n
        }
        freeIdx = -2
        jumpTo(i, was)
    }

    // ------------------------------------------------------------------ position bar / swipe

    fun scrubStart() {
        if (!hasSubs) return
        val p = player() ?: return
        if (free) {
            scrubWas = p.playWhenReady
            p.pause()
        } else {
            scrubWas = uiPlaying
            if (playing || jumping) {
                playing = false
                jumping = false
                cancelAll()
                p.pause()
                publish()
            }
        }
    }

    /** ms = where the finger ended, or -1 when the position is unknown. */
    fun scrubEnd(ms: Long) {
        val was = scrubWas
        scrubWas = null
        if (was == null || ms < 0L) {
            if (was == true && !free) {
                playing = true
                startSentence()
            }
            return
        }
        val t = ms / 1000.0
        val p = player() ?: return
        if (free) {
            p.seekTo(ms)
            freeIdx = -2
            if (was) p.play()
            return
        }
        var i = subAt(t)
        if (i < 0) i = subAfter(t)
        if (i < 0) i = subs.size - 1
        updateVisibility()
        if (!isPlayable(i)) {
            var n = nextPlayableAfter(i)
            if (n == -1) {
                n = i - 1
                while (n >= 0 && !isPlayable(n)) n--
            }
            if (n >= 0) i = n
        }
        jumpTo(i, was)
    }

    // ------------------------------------------------------------------ edit / delete / jump / filters

    fun editSentence(ix: Int, newText: String) {
        val t = newText.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || ix !in subs.indices || t == subs[ix].text) return
        subs[ix].text = t
        persistSubs()
        host.toast("Subtitle " + (ix + 1) + " updated")
        publish()
    }

    fun deleteSentence(ix: Int) {
        if (ix !in subs.indices) return
        subs.removeAt(ix)
        afterDelete()
        host.toast("Subtitle deleted")
    }

    fun deleteRange(v: String): Boolean {
        val r = rangeOf(v) ?: return false
        val n = r.second - r.first + 1
        for (k in 0 until n) subs.removeAt(r.first)
        afterDelete()
        host.toast("$n subtitles deleted")
        return true
    }

    fun bulkDelete(list: List<Int>) {
        val sorted = list.filter { it in subs.indices }.distinct().sortedDescending()
        if (sorted.isEmpty()) return
        for (i in sorted) subs.removeAt(i)
        afterDelete()
        host.toast(sorted.size.toString() + " subtitles deleted")
    }

    private fun afterDelete() {
        persistSubs()
        val was = uiPlaying
        if (free) freeIdx = -2
        if (sentenceIndex >= subs.size) sentenceIndex = maxOf(0, subs.size - 1)
        if (subs.isEmpty()) {
            stop()
            publish()
            return
        }
        updateVisibility()
        if (free) {
            publish()
            return
        }
        if (!isPlayable(sentenceIndex)) {
            var n = nextPlayableAfter(sentenceIndex - 1)
            if (n == -1) n = firstPlayable()
            if (n != -1) {
                jumpTo(n, was)
            } else {
                stop()
                publish()
            }
        } else {
            jumpTo(sentenceIndex, was)
        }
    }

    private fun persistSubs() {
        data?.saveMainSrt(Srt.serialize(subs))
    }

    fun jumpToNumber(n: Int) {
        val ix = n - 1
        if (subs.isEmpty() || ix !in subs.indices) {
            host.toast("Enter a number from 1 to " + subs.size)
            return
        }
        updateVisibility()
        if (!isPlayable(ix)) {
            host.toast("Subtitle $n is hidden or outside the range / words filter")
            return
        }
        if (free) {
            seekToSub(ix)
            freeIdx = -2
            player()?.play()
        } else {
            jumpTo(ix, true)
        }
    }

    fun filterWords(): String = data?.words ?: ""
    fun filterRange(): String = data?.range ?: ""

    fun setWords(v: String) {
        val d = data ?: return
        d.words = v.replace(" ", "")
        d.save()
        updateVisibility()
        pushFilters()
        host.toast(if (d.words.isNotEmpty()) "Words filter: " + d.words else "Words filter off")
    }

    fun setRange(v: String) {
        val d = data ?: return
        d.range = v.replace(" ", "")
        d.save()
        updateVisibility()
        pushFilters()
        if (d.range.isNotEmpty() && subs.isNotEmpty()) {
            val f = firstPlayable()
            if (f != -1) {
                if (free) {
                    seekToSub(f)
                    freeIdx = -2
                } else {
                    jumpTo(f, uiPlaying)
                }
            }
            host.toast("Range " + d.range)
        } else {
            host.toast("Range cleared")
        }
    }

    private fun rangeOf(v: String): Pair<Int, Int>? {
        val m = Regex("^\\s*(\\d+)\\s*(?:-\\s*(\\d+))?\\s*$").find(v) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val bs = m.groupValues[2]
        val b = if (bs.isEmpty()) a else (bs.toIntOrNull() ?: return null)
        if (subs.isEmpty()) return null
        val s = (a - 1).coerceIn(0, subs.size - 1)
        val e = (b - 1).coerceIn(s, subs.size - 1)
        return Pair(s, e)
    }

    fun pinRange(v: String) {
        val d = data ?: return
        val clean = v.replace(" ", "")
        if (!Regex("^\\d+(-\\d+)?$").matches(clean)) {
            host.toast("Use a range like 1-5")
            return
        }
        d.pinned.remove(clean)
        d.pinned.add(0, clean)
        d.save()
        host.toast("Range $clean pinned")
    }

    fun unpin(v: String) {
        val d = data ?: return
        d.pinned.remove(v)
        d.save()
    }

    fun playPinned(r: String) {
        val d = data ?: return
        val rr = rangeOf(r) ?: return
        d.range = r.replace(" ", "")
        d.save()
        updateVisibility()
        pushFilters()
        var target = rr.first
        if (!isPlayable(target)) {
            val f = playableIndices().firstOrNull { it >= startIdx } ?: return
            target = f
        }
        if (free) {
            seekToSub(target)
            freeIdx = -2
            player()?.play()
        } else {
            jumpTo(target, true)
        }
    }

    /** Hide the whole range, or show it again when everything in it is already hidden. */
    fun toggleHideRange(v: String): Boolean {
        val d = data ?: return false
        val r = rangeOf(v) ?: return false
        var allHidden = true
        for (i in r.first..r.second) if (!d.hidden.contains(subs[i].text)) { allHidden = false; break }
        for (i in r.first..r.second) {
            if (allHidden) d.hidden.remove(subs[i].text) else d.hidden.add(subs[i].text)
        }
        d.save()
        updateVisibility()
        host.toast(if (allHidden) "Range shown again" else "Range hidden")
        if (!free && !isPlayable(sentenceIndex)) {
            var n = nextPlayableAfter(sentenceIndex)
            if (n == -1) n = firstPlayable()
            if (n != -1) jumpTo(n, uiPlaying) else { stop(); publish() }
        } else {
            publish()
        }
        return true
    }

    fun unstar(ix: Int) {
        val d = data ?: return
        if (ix !in subs.indices) return
        d.starred.remove(subs[ix].text)
        d.save()
        publish()
    }

    fun starredJson(): JSONArray {
        val a = JSONArray()
        val d = data ?: return a
        for (i in subs.indices) if (d.starred.contains(subs[i].text)) a.put(JSONArray().put(i).put(subs[i].text))
        return a
    }

    fun sentencesJson(): JSONArray {
        val a = JSONArray()
        for (i in subs.indices) a.put(JSONArray().put(i).put(subs[i].text))
        return a
    }

    fun pinnedJson(): JSONArray = JSONArray(data?.pinned?.toList() ?: emptyList<String>())

    // ------------------------------------------------------------------ settings

    fun setDelay(d: Double) {
        settings.delaySec = d.coerceIn(0.0, 60.0)
        publish()
    }

    fun setRepeatAll(n: Int) {
        settings.repeatAll = n.coerceIn(1, 100)
        publish()
    }

    fun setRepeatOne(ix: Int, n: Int) {
        val d = data ?: return
        if (ix !in subs.indices) return
        if (n > 0) d.customRepeats[subs[ix].text] = n.coerceAtMost(100) else d.customRepeats.remove(subs[ix].text)
        d.save()
        publish()
    }

    /** Speed / sync offset changed: apply to the picture right away. */
    fun settingsChanged() {
        val p = player()
        if (p != null && (playing || free)) p.setPlaybackSpeed(settings.speed.coerceIn(0.25f, 4f))
        publish()
    }

    // ------------------------------------------------------------------ state -> screen

    private fun pushFilters() {
        host.onFilters(data?.words ?: "", data?.range ?: "")
    }

    fun publishNow() = publish()

    private fun publish() {
        val d = data
        if (subs.isEmpty()) {
            host.onSubtitle("", uiPlaying)
            host.onFlags(false, false, 0, settings.repeatAll, settings.delaySec, -1)
            host.onSub2("", false, false)
            return
        }
        var gs = sentenceIndex
        var ge = sentenceIndex
        val text: String
        if (free) {
            val fi = freeIdx
            text = if (fi in subs.indices && (d == null || !d.hidden.contains(subs[fi].text))) subs[fi].text else ""
            gs = if (fi >= 0) fi else sentenceIndex
            ge = gs
        } else {
            if (playing && groupStart >= 0 && groupEnd >= groupStart && sentenceIndex >= groupStart && sentenceIndex <= groupEnd) {
                gs = groupStart
                ge = groupEnd
            }
            val parts = ArrayList<String>()
            for (i in gs..ge) if (i in subs.indices) parts.add(subs[i].text)
            text = parts.joinToString(" ")
        }
        host.onSubtitle(text, uiPlaying)
        val ix = if (sentenceIndex in subs.indices) sentenceIndex else -1
        if (d != null && ix >= 0) {
            val s = subs[ix].text
            host.onFlags(d.starred.contains(s), d.bookmarked.contains(s), d.customRepeats[s] ?: 0, settings.repeatAll, settings.delaySec, ix)
        } else {
            host.onFlags(false, false, 0, settings.repeatAll, settings.delaySec, ix)
        }
        publishSecond(text, gs, ge)
    }

    private fun publishSecond(mainText: String, gs: Int, ge: Int) {
        val has = second.isNotEmpty()
        val on = has && (data?.showSecond ?: true)
        var out = ""
        if (on && mainText.isNotEmpty()) {
            val parts = ArrayList<String>()
            if (free) {
                val p = player()
                val f = if (p != null) secondAtTime(matchTime(p.currentPosition / 1000.0)) else -1
                val fx = if (f >= 0) f else (if (freeIdx >= 0) secondForPrimary(freeIdx) else -1)
                if (fx in second.indices) parts.add(second[fx].text)
            } else {
                val used = HashSet<Int>()
                for (i in gs..ge) {
                    val x = secondForPrimary(i)
                    if (x >= 0 && used.add(x) && x in second.indices) parts.add(second[x].text)
                }
            }
            out = parts.joinToString(" ").trim()
        }
        host.onSub2(out, on, has)
    }

    private fun secondAtTime(t: Double): Int {
        for (i in second.indices) if (t >= second[i].start && t < second[i].end) return i
        return -1
    }

    private fun secondForPrimary(pi: Int): Int {
        if (pi < 0 || pi >= subs.size || second.isEmpty()) return -1
        val pr = subs[pi]
        var best = -1
        var bestOverlap = 0.0
        for (i in second.indices) {
            val c = second[i]
            val ov = maxOf(0.0, minOf(pr.end, c.end) - maxOf(pr.start, c.start))
            if (ov > bestOverlap) {
                bestOverlap = ov
                best = i
            }
        }
        if (best != -1) return best
        val center = (pr.start + pr.end) / 2.0
        var bestD = Double.MAX_VALUE
        for (i in second.indices) {
            val d = Math.abs((second[i].start + second[i].end) / 2.0 - center)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }
}
