package com.raj.hub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** What the page is playing right now. Written by the page (through the bridge), read by the notification and the floating window. */
object MediaBus {
    @Volatile var text: String = ""
    @Volatile var index: Int = 0
    @Volatile var total: Int = 0
    @Volatile var playing: Boolean = false
    @Volatile var uiVisible: Boolean = true

    fun reset() {
        text = ""
        index = 0
        total = 0
        playing = false
        uiVisible = true
    }
}

/**
 * WebView that keeps running when the screen is off or the app is in the background.
 * Android tells a hidden WebView to slow its timers down; the page's sentence timer must keep ticking.
 */
class BgWebView(ctx: Context) : WebView(ctx) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

/**
 * Owns the page (WebView) and the native video player.
 *
 * It lives as long as the playback does, NOT as long as the screen: when the screen is closed or cleared from
 * Recents while it plays, the sound keeps going (kept alive by [PlaybackService]) and opening the app again
 * simply puts the same live page back on the screen.
 */
class WebHost private constructor(val appCtx: Context, val app: AppInfo) {

    companion object {
        @Volatile private var inst: WebHost? = null

        fun current(): WebHost? = inst

        fun obtain(ctx: Context, app: AppInfo): WebHost {
            val cur = inst
            if (cur != null && cur.app.id == app.id && !cur.destroyed) return cur
            cur?.destroy()
            MediaBus.reset()
            val h = WebHost(ctx.applicationContext, app)
            inst = h
            return h
        }

        /** Fully closes the page and the player. */
        fun shutdown() {
            val h = inst
            inst = null
            h?.destroy()
            MediaBus.reset()
        }

        // Lets a page save files it creates (backups, PDFs) to Downloads. Android's WebView cannot do this alone.
        private const val DOWNLOAD_HOOK_JS = """
(function () {
  if (window.__rajHook) return;
  window.__rajHook = true;
  function send(url, name) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
      if (b.size > 120 * 1024 * 1024) { alert('This file is too big to save from inside the app.'); return; }
      var fr = new FileReader();
      fr.onloadend = function () {
        var s = String(fr.result);
        RajBridge.save(name || 'download', b.type || 'application/octet-stream', s.substring(s.indexOf(',') + 1));
      };
      fr.readAsDataURL(b);
    }).catch(function () { alert('Could not save the file.'); });
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.hasAttribute('download') && /^(blob:|data:)/.test(this.href)) {
      send(this.href, this.getAttribute('download'));
      return;
    }
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && /^(blob:|data:)/.test(a.href)) {
      e.preventDefault();
      send(a.href, a.getAttribute('download'));
    }
  }, true);
})();
"""

        // Gives the app three things from the page: the current subtitle (notification + floating window),
        // play/pause/previous/next commands from them, and the position of the page's cards (video width).
        private const val MEDIA_HOOK_JS = """
(function () {
  if (window.__rajMedia) return;
  var B = window.RajBridge;
  if (!B || typeof B.mediaState !== 'function') return;
  var last = '';
  var lastAlign = '';

  // ---- Free play ("unlink"): the film plays from start to end, the sentence repeater is stopped,
  // the subtitle on screen simply follows the film's time. Used by the full screen unlink button.
  var free = false, freeIdx = -2, freeTimer = null, sc = null;

  function vpl() { try { return videoPlayer; } catch (e) { return null; } }
  function byId(i) { return document.getElementById(i); }
  // sets a page input the same way typing would (the page saves it and reacts to the 'input' event)
  function setInput(id, v) {
    var el = byId(id);
    if (!el) return;
    el.value = String(v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }
  function cleanLine(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }

  // index of the subtitle showing at raw video time t (seconds), or -1 in a gap
  function subAt(t) {
    try {
      var subs = videoSubtitles;
      if (!subs || !subs.length) return -1;
      var mt = getSubtitleMatchingTime(t);
      var h = freeIdx;
      if (h >= 0 && h < subs.length && subs[h] && mt >= subs[h].start && mt < subs[h].end) return h;
      for (var i = 0; i < subs.length; i++) {
        var u = subs[i];
        if (u && mt >= u.start && mt < u.end) return i;
      }
    } catch (e) {}
    return -1;
  }

  // first subtitle that starts after raw video time t, or -1
  function subAfter(t) {
    try {
      var subs = videoSubtitles, mt = getSubtitleMatchingTime(t);
      for (var i = 0; i < subs.length; i++) if (subs[i] && subs[i].start > mt + 0.05) return i;
    } catch (e) {}
    return -1;
  }

  // last subtitle that started clearly before raw video time t, or -1
  function subBefore(t) {
    try {
      var subs = videoSubtitles, mt = getSubtitleMatchingTime(t), r = -1;
      for (var i = 0; i < subs.length; i++) { if (subs[i] && subs[i].start < mt - 0.7) r = i; else if (subs[i] && subs[i].start >= mt) break; }
      return r;
    } catch (e) {}
    return -1;
  }

  function seekToSub(i) {
    try { videoPlayer.currentTime = getVideoTimeForSubtitleTime(videoSubtitles[i].start); } catch (e) {}
  }

  // stops the page's sentence repeater completely (same pieces togglePlayPause stops, plus pending jump / seek callbacks)
  function stopEngine() {
    try { isPlaying = false; } catch (e) {}
    try { if (window.jumpTimeout) clearTimeout(window.jumpTimeout); isJumping = false; } catch (e) {}
    try { ++videoSeekToken; videoPlayer.onseeked = null; } catch (e) {}
    try { if (window.bgVideoInterval) clearInterval(window.bgVideoInterval); } catch (e) {}
    try { if (videoPollingFrame) cancelAnimationFrame(videoPollingFrame); } catch (e) {}
    try { clearTimeout(delayTimeout); } catch (e) {}
    try { synth.cancel(); } catch (e) {}
    try { stopPauseCountdown(); } catch (e) {}
    try { clearWordTimers(); } catch (e) {}
    try { updateFloatingBtn(); } catch (e) {}
  }

  function showFreeSentence(i) {
    try { sentenceIndex = i; } catch (e) {}
    try { progressText.innerText = 'Sentence: ' + (i + 1) + '/' + sentences.length + ' (free play)'; } catch (e) {}
    try {
      currentSentenceDiv.innerHTML = getPlaybackGroupDisplayHtml([i]) + renderSecondSrtCurrentLine() + renderStickyNoteCurrentLine(i);
    } catch (e) {}
    try { highlightActivePlaylistSentence(); } catch (e) {}
    try { updateActiveSentenceToolbar(); } catch (e) {}
    try { saveProgress(); } catch (e) {}
  }

  function freeTick() {
    if (!free) return;
    try {
      var v = vpl();
      var i = subAt(v ? v.currentTime : 0);
      if (i !== freeIdx) {
        freeIdx = i;
        if (i >= 0) showFreeSentence(i);
      }
    } catch (e) {}
    snap();
  }

  function enterFree() {
    if (free) return;
    free = true;
    freeIdx = -2;
    stopEngine();
    try { videoPlayer.playbackRate = parseFloat(speedInput.value) || 1.0; } catch (e) {}
    try { var pr = videoPlayer.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
    if (freeTimer) clearInterval(freeTimer);
    freeTimer = setInterval(freeTick, 120);
    setTimeout(freeTick, 60);
  }

  function exitFree() {
    if (!free) return;
    free = false;
    if (freeTimer) { clearInterval(freeTimer); freeTimer = null; }
    var was = false;
    try { was = !videoPlayer.paused; } catch (e) {}
    try { videoPlayer.pause(); } catch (e) {}
    try {
      var i = (freeIdx >= 0) ? freeIdx : sentenceIndex;
      if (typeof i !== 'number' || i < 0 || i >= sentences.length) i = sentenceIndex;
      if (!isPlayable(i)) {
        var n = getNextPlayableIndexAfter(i);
        if (n === -1) n = getFirstPlayableIndex();
        if (n !== -1) i = n;
      }
      isPlaying = was;                 // jumpToSpecificSentence keeps playing only if this is true
      jumpToSpecificSentence(i);
    } catch (e) {}
    freeIdx = -2;
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function freeToggle() {
    try { if (videoPlayer.paused) { var pr = videoPlayer.play(); if (pr && pr.catch) pr.catch(function () {}); } else videoPlayer.pause(); } catch (e) {}
    setTimeout(snap, 80);
  }

  // hide button while free play: same effect as the eye button (sentence is hidden), but the film just keeps playing
  function freeHide() {
    try {
      var i = (freeIdx >= 0) ? freeIdx : sentenceIndex;
      if (!sentences || i < 0 || i >= sentences.length) return;
      hiddenSentences.add(sentences[i]);
      localStorage.setItem('app_hidden', JSON.stringify(Array.from(hiddenSentences)));
      try { updateVisibilityStates(); } catch (e) {}
      try { renderPlaylist(); } catch (e) {}
    } catch (e) {}
    snap();
  }

  // position bar: finger down = pause the repeater, finger up = continue from the new place
  function scrubStart() {
    try {
      if (free) { sc = { was: !videoPlayer.paused }; videoPlayer.pause(); }
      else { sc = { was: !!isPlaying }; if (isPlaying) togglePlayPause(); }
    } catch (e) { sc = null; }
  }

  function scrubEnd(ms) {
    var s = sc; sc = null;
    if (!s || !(ms >= 0)) return;
    var t = ms / 1000;
    try {
      if (free) {
        videoPlayer.currentTime = t;
        freeIdx = -2;
        if (s.was) { var pr = videoPlayer.play(); if (pr && pr.catch) pr.catch(function () {}); }
        setTimeout(freeTick, 150);
      } else if (typeof videoSubtitles !== 'undefined' && videoSubtitles && videoSubtitles.length) {
        var i = subAt(t);
        if (i < 0) i = subAfter(t);
        if (i < 0) i = videoSubtitles.length - 1;
        if (!isPlayable(i)) {
          var n = getNextPlayableIndexAfter(i);
          if (n === -1) { n = i - 1; while (n >= 0 && !isPlayable(n)) n--; }
          if (n >= 0) i = n;
        }
        isPlaying = s.was;
        jumpToSpecificSentence(i);
      }
    } catch (e) {}
    setTimeout(snap, 100);
    setTimeout(snap, 500);
  }

  // [FS_FLAGS] bookmark / favorite state of the current sentence, repeat counts and delay -> full screen buttons
  var lastFlags = '';
  function flags() {
    try {
      if (typeof B.mediaFlags !== 'function') return;
      if (typeof sentences === 'undefined' || !sentences || !sentences.length) return;
      var ix = (typeof sentenceIndex === 'number') ? sentenceIndex : -1;
      var s = (ix >= 0 && ix < sentences.length) ? sentences[ix] : null;
      var st = (s !== null) && starredSentences.has(s);
      var bm = (s !== null) && bookmarkedSentences.has(s);
      var cr = (s !== null && customRepeats[s]) ? (parseInt(customRepeats[s], 10) || 0) : 0;
      var gr = parseInt(byId('sentenceRepeatInput').value, 10) || 1;
      var dl = parseFloat(byId('delayInput').value);
      if (!isFinite(dl) || dl < 0) dl = 0;
      var k = [st, bm, cr, gr, dl, ix].join('|');
      if (k === lastFlags) return;
      lastFlags = k;
      B.mediaFlags(st, bm, cr, gr, dl, ix);
    } catch (e) {}
  }

  // bookmark button: same as the page's bookmark button, but it always toggles (the page's own button opens the list while playing)
  function toggleBookmark() {
    try {
      if (!sentences || !sentences.length || sentenceIndex < 0 || sentenceIndex >= sentences.length) return;
      var s = sentences[sentenceIndex];
      if (bookmarkedSentences.has(s)) bookmarkedSentences.delete(s); else bookmarkedSentences.add(s);
      localStorage.setItem('app_bookmarks', JSON.stringify(Array.from(bookmarkedSentences)));
      try { updateActiveSentenceToolbar(); } catch (e) {}
    } catch (e) {}
  }

  // settings commands from the full screen menu / repeat dialog: delay:<sec>  repeatAll:<n>  repeatOne:<sentenceIndex>:<n>
  function setting(c) {
    try {
      if (c.indexOf('delay:') === 0) {
        var d = parseFloat(c.slice(6));
        if (isFinite(d) && d >= 0) setInput('delayInput', Math.min(d, 60));
      } else if (c.indexOf('repeatAll:') === 0) {
        var n = parseInt(c.slice(10), 10);
        if (n >= 1) setInput('sentenceRepeatInput', Math.min(n, 100));
      } else if (c.indexOf('repeatOne:') === 0) {
        var parts = c.slice(10).split(':');
        var ix = parseInt(parts[0], 10), cnt = parseInt(parts[1], 10);
        if (!(ix >= 0) || !sentences || ix >= sentences.length) return;
        var sen = sentences[ix];
        if (cnt > 0) customRepeats[sen] = Math.min(cnt, 100); else delete customRepeats[sen];
        localStorage.setItem('app_custom_repeats', JSON.stringify(customRepeats));
        try { updateActiveSentenceToolbar(); } catch (e) {}
        try { if (!isPlaying) updateScreenOnly(); } catch (e) {}
      }
    } catch (e) {}
    setTimeout(snap, 60);
  }

  // [FS_SUB2] second subtitle (translated SRT) text for the sentence(s) playing now -> shown below the main subtitle in full screen
  var lastSub2 = '';
  function plain(t) { return String(t || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim(); }
  function sub2(mainText, gs, ge) {
    try {
      if (typeof B.mediaSub2 !== 'function' || typeof secondSrtSubtitles === 'undefined') return;
      var has = !!(secondSrtSubtitles && secondSrtSubtitles.length);
      var on = has && !!showSecondSrtCurrentLine;
      var out = '';
      if (on && mainText) {
        var used = {}, parts = [];
        if (free) {
          var v = vpl(), f = v ? findSecondSrtAtTime(getSubtitleMatchingTime(v.currentTime)) : null;
          var fx = f ? f.index : ((freeIdx >= 0) ? findSecondSrtIndexForPrimaryIndex(freeIdx) : -1);
          if (fx >= 0 && secondSrtSubtitles[fx]) parts.push(plain(secondSrtSubtitles[fx].text));
        } else {
          for (var i = gs; i <= ge; i++) {
            var x = findSecondSrtIndexForPrimaryIndex(i);
            if (x >= 0 && !used[x] && secondSrtSubtitles[x]) { used[x] = 1; parts.push(plain(secondSrtSubtitles[x].text)); }
          }
        }
        out = parts.join(' ').trim();
      }
      var k = out + '|' + on + '|' + has;
      if (k === lastSub2) return;
      lastSub2 = k;
      B.mediaSub2(out, on, has);
    } catch (e) {}
  }

  function snap() {
    flags();
    pushFilters();
    try {
      var total = 0, idx = 0, text = '', pl = false;
      if (free && typeof sentences !== 'undefined' && sentences && sentences.length) {
        total = sentences.length;
        pl = !videoPlayer.paused;
        var fi = (freeIdx >= 0) ? freeIdx : -1;
        idx = (fi >= 0 ? fi : sentenceIndex) + 1;
        if (fi >= 0 && !hiddenSentences.has(sentences[fi])) text = cleanLine(sentences[fi]);
      } else if (typeof sentences !== 'undefined' && sentences && sentences.length) {
        total = sentences.length;
        pl = !!isPlaying;
        var gs = sentenceIndex, ge = sentenceIndex;
        if (pl && typeof playbackGroupStartIndex === 'number' && playbackGroupStartIndex >= 0 &&
            playbackGroupEndIndex >= playbackGroupStartIndex &&
            sentenceIndex >= playbackGroupStartIndex && sentenceIndex <= playbackGroupEndIndex) {
          gs = playbackGroupStartIndex; ge = playbackGroupEndIndex;
        }
        idx = gs + 1;
        var parts = [];
        for (var i = gs; i <= ge && i < total; i++) {
          var t = String(sentences[i] || '').replace(/\s+/g, ' ').trim();
          if (t) parts.push(t);
        }
        text = parts.join(' ');
      }
      sub2(text, (typeof gs === 'number') ? gs : Math.max(0, idx - 1), (typeof ge === 'number') ? ge : Math.max(0, idx - 1));
      var key = text + '|' + idx + '|' + total + '|' + pl;
      if (key === last) return;
      last = key;
      B.mediaState(text, idx, total, pl);
    } catch (e) {}
  }

  // ======================================================================================
  // [FS_TOOLS] full screen tools: edit / delete / jump / upload subtitles / words filter / range / starred / bulk delete.
  // The native full screen buttons call these. Everything reuses the page's own functions and storage.
  // ======================================================================================
  function toast(m) { try { if (typeof B.nvToast === 'function') B.nvToast(String(m)); } catch (e) {} }
  function sendData(kind, obj) { try { if (typeof B.nvData === 'function') B.nvData(kind, JSON.stringify(obj)); } catch (e) {} }

  // index (0 based) of the subtitle the learner is on right now (works in normal and free play), or -1
  function curIx() {
    try {
      var ix = (free && freeIdx >= 0) ? freeIdx : sentenceIndex;
      if (typeof ix !== 'number' || ix < 0 || !sentences || ix >= sentences.length) return -1;
      return ix;
    } catch (e) { return -1; }
  }

  function sendCur(kind) {
    var ix = curIx();
    if (ix < 0) { toast('No subtitle loaded yet'); return; }
    sendData(kind, { i: ix, t: String(sentences[ix] || '') });
  }

  // words filter + range text, so the full screen dialogs can show the current values
  var lastFilters = '';
  function pushFilters() {
    try {
      var w = byId('minWordsInput'), r = byId('rangeInput');
      var o = { words: w ? String(w.value || '').trim() : '', range: r ? String(r.value || '').trim() : '' };
      var k = o.words + '|' + o.range;
      if (k === lastFilters) return;
      lastFilters = k;
      sendData('filters', o);
    } catch (e) {}
  }

  // edit button: same as the page's pencil button (changes the text and saves it into the stored SRT), without prompt()
  function editSentence(ix, text) {
    try {
      text = String(text || '').replace(/\s+/g, ' ').trim();
      if (!text || !sentences || !(ix >= 0) || ix >= sentences.length || text === sentences[ix]) return;
      sentences[ix] = text;
      if (isVideoMode && videoSubtitles && videoSubtitles[ix]) {
        videoSubtitles[ix].text = text;
        try { saveToDB('srt', serializeSRTSubtitles(videoSubtitles)); } catch (e) {}
      }
      try { renderPlaylist(); } catch (e) {}
      try { if (!isPlaying) updateScreenOnly(); } catch (e) {}
      try { if (free && ix === freeIdx) showFreeSentence(ix); } catch (e) {}
      toast('Subtitle ' + (ix + 1) + ' updated');
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  // delete button: the page's permanent delete. The app already asked "Delete?", so the page's own question is answered yes.
  function delSentence(ix) {
    var oc = window.confirm;
    try {
      if (!sentences || !(ix >= 0) || ix >= sentences.length) return;
      window.confirm = function () { return true; };
      deleteSentencePermanently(ix);
      toast('Subtitle deleted');
    } catch (e) {
    } finally {
      window.confirm = oc;
    }
    if (free) freeIdx = -2;
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  // jump button: same rules as the page's "No." box (hidden / filtered subtitles cannot be played)
  function jumpTo(n) {
    try {
      var ix = n - 1;
      var total = (sentences && sentences.length) ? sentences.length : 0;
      if (!total || !(ix >= 0 && ix < total)) { toast('Enter a number from 1 to ' + total); return; }
      updateVisibilityStates();
      if (!isPlayable(ix)) { toast('Subtitle ' + n + ' is hidden or outside the range / words filter'); return; }
      if (free) {
        seekToSub(ix); freeIdx = -2;
        try { var pr = videoPlayer.play(); if (pr && pr.catch) pr.catch(function () {}); } catch (e) {}
      } else { isPlaying = true; jumpToSpecificSentence(ix); }
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function setWords(v) {
    try {
      v = String(v || '').replace(/\s+/g, '');
      setInput('minWordsInput', v);
      toast(v ? ('Words filter: ' + v) : 'Words filter off');
    } catch (e) {}
    setTimeout(snap, 60);
  }

  function setRange(v) {
    try {
      v = String(v || '').replace(/\s+/g, '');
      setInput('rangeInput', v);
      if (v && sentences && sentences.length) {
        updateVisibilityStates();
        var f = getFirstPlayableIndex();
        if (f !== -1) {
          if (free) { seekToSub(f); freeIdx = -2; }
          else jumpToSpecificSentence(f);
        }
        toast('Range ' + v);
      } else {
        toast('Range cleared');
      }
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  // sets the page's Range box only (no jump), used before pin / hide / delete range
  function rangeQuiet(v) {
    try { setInput('rangeInput', String(v || '').replace(/\s+/g, '')); } catch (e) {}
  }

  function rangeValue() { var r = byId('rangeInput'); return r ? String(r.value || '').trim() : ''; }

  function rangePin() {
    try {
      var v = rangeValue();
      if (!v) { toast('Enter a range first'); return; }
      if (typeof isValidRangeFormat === 'function' && !isValidRangeFormat(v)) { toast('Use a range like 1-5'); return; }
      addPinnedRange(v);
      toast('Range ' + v + ' pinned');
    } catch (e) {}
  }

  function rangeHide() {
    try {
      if (!rangeValue()) { toast('Enter a range first'); return; }
      var b = byId('globalHideBtn');
      if (b) b.click();
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function rangeDelete() {
    try {
      if (!rangeValue()) { toast('Enter a range first'); return; }
      var b = byId('globalDeleteRangeBtn');
      if (b) b.click();      // the page asks "Permanently delete sentences from x to y?" itself
    } catch (e) {}
    if (free) freeIdx = -2;
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function listPinned() {
    try { sendData('pinned', (typeof pinnedRanges !== 'undefined' && Array.isArray(pinnedRanges)) ? pinnedRanges : []); } catch (e) { sendData('pinned', []); }
  }

  function playPinned(r) {
    try {
      r = String(r || '').trim();
      if (free) {
        var n = parseInt(r, 10);
        setInput('rangeInput', r);
        updateVisibilityStates();
        if (n >= 1 && sentences && n <= sentences.length) { seekToSub(n - 1); freeIdx = -2; }
      } else {
        playPinnedRange(r);
      }
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function unpin(r) { try { removePinnedRange(String(r || '').trim()); } catch (e) {} }

  function listStarred() {
    var out = [];
    try {
      for (var i = 0; i < sentences.length; i++) if (starredSentences.has(sentences[i])) out.push([i, String(sentences[i])]);
    } catch (e) {}
    sendData('starred', out);
  }

  function unstar(ix) {
    try {
      if (!sentences || !(ix >= 0) || ix >= sentences.length) return;
      starredSentences.delete(sentences[ix]);
      localStorage.setItem('app_starred', JSON.stringify(Array.from(starredSentences)));
      try { updateVisibilityStates(); } catch (e) {}
      try { updateActiveSentenceToolbar(); } catch (e) {}
    } catch (e) {}
    setTimeout(snap, 60);
  }

  function listSentences() {
    var out = [];
    try {
      for (var i = 0; i < sentences.length; i++) out.push([i, String(sentences[i])]);
    } catch (e) {}
    sendData('sentences', out);
  }

  // bulk delete: fills the page's own bulk-delete selection and presses its button (the page asks "Permanently delete N sentences?")
  function bulkDelete(arr) {
    try {
      if (!sentences || !sentences.length || !arr || !arr.length) return;
      var before = sentences.length;
      selectedForDelete.clear();
      for (var i = 0; i < arr.length; i++) if (arr[i] >= 0 && arr[i] < sentences.length) selectedForDelete.add(arr[i]);
      if (!selectedForDelete.size) return;
      isBulkDeleteMode = true;
      byId('bulkDeleteToggleBtn').click();
      if (sentences.length !== before) {
        if (isVideoMode) { try { saveToDB('srt', serializeSRTSubtitles(videoSubtitles)); } catch (e) {} }
        toast((before - sentences.length) + ' subtitles deleted');
      }
    } catch (e) {}
    if (free) freeIdx = -2;
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  // upload main / translated subtitles picked in the app: handed to the page exactly like a file chosen in its own upload button
  function loadSrt(kind, name, b64) {
    try {
      var bin = atob(b64), u8 = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
      var second = (kind === 'second');
      var input = byId(second ? 'secondSrtUpload' : 'srtUpload');
      if (!input) return;
      var f = new File([u8], name || 'subtitles.srt', { type: 'application/x-subrip' });
      var dt = new DataTransfer();
      dt.items.add(f);
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      setTimeout(function () {
        try {
          if (second) toast(secondSrtSubtitles && secondSrtSubtitles.length ? ('Translated subtitles loaded: ' + secondSrtSubtitles.length + ' lines') : 'Translated subtitles not loaded');
          else toast(sentences && sentences.length ? ('Subtitles loaded: ' + sentences.length + ' lines') : 'Subtitles not loaded');
        } catch (e) {}
      }, 700);
    } catch (e) { toast('Could not read that subtitle file'); }
    setTimeout(snap, 800);
  }

  function cmd(c) {
    try {
      if (c === 'unlink:1') { enterFree(); return; }
      if (c === 'unlink:0') { exitFree(); return; }
      // these work the same in normal and free play
      if (c === 'bookmark') { toggleBookmark(); setTimeout(snap, 60); return; }
      if (c === 'sub2') {
        if (secondSrtSubtitles && secondSrtSubtitles.length) {
          showSecondSrtCurrentLine = !showSecondSrtCurrentLine;
          localStorage.setItem('app_show_second_srt', String(showSecondSrtCurrentLine));
          try { updateSecondSrtButtonStates(); } catch (e) {}
          try { updateScreenOnly(); } catch (e) {}
        }
        setTimeout(snap, 60); return;
      }
      if (c === 'star') { var sb = byId('actStarBtn'); if (sb) sb.click(); setTimeout(snap, 60); return; }
      if (c.indexOf('delay:') === 0 || c.indexOf('repeatAll:') === 0 || c.indexOf('repeatOne:') === 0) { setting(c); return; }
      if (c === 'edit:get') { sendCur('edit'); return; }
      if (c === 'del:get') { sendCur('delete'); return; }
      if (c.indexOf('jump:') === 0) { jumpTo(parseInt(c.slice(5), 10)); return; }
      if (free) {
        if (c === 'toggle') freeToggle();
        else if (c === 'hide') freeHide();
        else if (c === 'next') {
          var nx = subAfter(videoPlayer.currentTime);
          if (nx !== -1) { seekToSub(nx); freeIdx = -2; }
        } else if (c === 'prev') {
          var pv = subBefore(videoPlayer.currentTime);
          if (pv !== -1) { seekToSub(pv); freeIdx = -2; }
        }
        setTimeout(snap, 60);
        setTimeout(snap, 450);
        return;
      }
      if (c === 'toggle') {
        togglePlayPause();
      } else if (c === 'hide') {
        var hb = document.getElementById('actHideBtn');
        if (hb && sentences && sentences.length && sentenceIndex >= 0 &&
            !hiddenSentences.has(sentences[sentenceIndex])) hb.click();
      } else if (c === 'next') {
        var n = getNextPlayableIndexAfter(sentenceIndex);
        if (n !== -1) jumpToSpecificSentence(n);
      } else if (c === 'prev') {
        var p = sentenceIndex - 1;
        while (p >= 0 && !isPlayable(p)) p--;
        if (p >= 0) jumpToSpecificSentence(p);
      }
    } catch (e) {}
    setTimeout(snap, 60);
    setTimeout(snap, 450);
  }

  function align() {
    try {
      if (typeof B.nvAlign !== 'function') return;
      var el = document.getElementById('sentenceDisplayCard');
      var r = el ? el.getBoundingClientRect() : null;
      if (!r || r.width < 50) {
        el = document.querySelector('.fs-header');
        r = el ? el.getBoundingClientRect() : null;
      }
      if (!r || r.width < 50) return;
      var rad = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 16;
      var k = Math.round(r.left) + '|' + Math.round(r.width) + '|' + Math.round(rad);
      if (k === lastAlign) return;
      lastAlign = k;
      B.nvAlign(r.left, r.width, rad);
    } catch (e) {}
  }

  // anything else that calls togglePlayPause() (headset / lock-screen buttons) must not restart the repeater in free play
  try {
    if (typeof togglePlayPause === 'function' && !togglePlayPause.__rajWrapped) {
      var origToggle = togglePlayPause;
      var wrapped = function () { if (free) { freeToggle(); return; } return origToggle.apply(this, arguments); };
      wrapped.__rajWrapped = true;
      window.togglePlayPause = wrapped;
    }
  } catch (e) {}

  window.__rajMedia = {
    cmd: cmd, snap: snap, align: align, scrubStart: scrubStart, scrubEnd: scrubEnd,
    editSentence: editSentence, delSentence: delSentence, setWords: setWords, setRange: setRange, rangeQuiet: rangeQuiet,
    rangePin: rangePin, rangeHide: rangeHide, rangeDelete: rangeDelete,
    listPinned: listPinned, playPinned: playPinned, unpin: unpin,
    listStarred: listStarred, unstar: unstar, listSentences: listSentences, bulkDelete: bulkDelete, loadSrt: loadSrt
  };
  setInterval(snap, 300);
  setInterval(align, 1500);
  window.addEventListener('resize', align);
  setTimeout(align, 200);
  snap();
})();
"""
    }

    private val main = Handler(Looper.getMainLooper())
    val store = AppStore(appCtx)

    /** Context of the views: the visible screen while the app is open, the application otherwise. */
    private val wrap = MutableContextWrapper(appCtx)

    val web: BgWebView = BgWebView(wrap)

    private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(app.host)
        .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(appCtx, store.siteDir(app)))
        .build()

    /** The screen showing the page right now, or null when the app is closed / cleared from Recents. */
    var activity: AppActivity? = null
        private set

    val nv: NativeVideo = NativeVideo(
        wrap,
        app.id,
        web,
        { activity },
        { js -> web.evaluateJavascript(js, null) }
    )

    @Volatile var destroyed = false
        private set

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var anchor: WebAnchor? = null

    init {
        setupWebView()
        web.loadUrl("https://${app.host}/${app.entry}")
        checkForUpdate()
    }

    // ------------------------------------------------------------------ screen attach / detach

    /** Puts the live page + video panel into [column] of the screen. */
    fun attach(a: AppActivity, column: LinearLayout) {
        if (destroyed) return
        activity = a
        wrap.setBaseContext(a)
        hideAnchor()
        (nv.panel.parent as? ViewGroup)?.removeView(nv.panel)
        (web.parent as? ViewGroup)?.removeView(web)
        column.addView(
            nv.panel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nv.relayout()
        nv.reapplyFullscreen()
        web.onResume()
        web.resumeTimers()
    }

    /** The screen is going away. The page stays alive (and keeps playing) if the player is running. */
    fun detach(a: AppActivity) {
        if (activity !== a) return
        hideCustomView()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (nv.panel.parent as? ViewGroup)?.removeView(nv.panel)
        (web.parent as? ViewGroup)?.removeView(web)
        activity = null
        wrap.setBaseContext(appCtx)
        if (!destroyed) showAnchor()
    }

    /** Without a screen the page is parked in an invisible overlay window so Android does not slow it down. */
    private fun showAnchor() {
        if (!MediaBus.playing || web.parent != null || anchor != null) return
        val a = WebAnchor(appCtx)
        if (a.show(web)) anchor = a
    }

    private fun hideAnchor() {
        anchor?.remove(web)
        anchor = null
    }

    /** True while the app screen is in front. */
    fun setUiVisible(v: Boolean) {
        MediaBus.uiVisible = v
        nv.setBackground(!v)
        PlaybackService.refreshNow()
    }

    /** Called when the page reports a new playing / paused state. */
    fun onPlayingChanged() {
        if (activity == null) {
            if (MediaBus.playing) showAnchor() else hideAnchor()
        }
    }

    // ------------------------------------------------------------------ commands from notification / floating window

    /** cmd: toggle | next | prev */
    fun mediaCmd(c: String) {
        main.post {
            if (!destroyed) web.evaluateJavascript("window.__rajMedia&&window.__rajMedia.cmd('$c')", null)
        }
    }

    // ------------------------------------------------------------------ file chooser result (screen -> page)

    fun onChooserResult(resultCode: Int, data: Intent?) {
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
    }

    fun hideCustomView(): Boolean {
        val v = customView ?: return false
        activity?.root?.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
        return true
    }

    // ------------------------------------------------------------------ remembered video served to the page

    private fun videoMime(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".avi") -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))

    /** Streams the remembered video to the page with HTTP Range support so seeking works on multi-GB files. */
    private fun serveVideo(req: WebResourceRequest): WebResourceResponse {
        val sp = appCtx.getSharedPreferences("rajhub", Context.MODE_PRIVATE)
        val uriStr = sp.getString("vid_uri_" + app.id, null) ?: return notFound()
        return try {
            val pfd = appCtx.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r") ?: return notFound()
            var total = pfd.statSize
            if (total <= 0) total = sp.getLong("vid_size_" + app.id, 0L)
            if (total <= 0) {
                pfd.close()
                return notFound()
            }
            var start = 0L
            var end = total - 1
            var partial = false
            val range = req.requestHeaders["Range"] ?: req.requestHeaders["range"]
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.substring(6).split(",")[0].trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val a = spec.substring(0, dash)
                    val b = spec.substring(dash + 1)
                    if (a.isEmpty()) {
                        val n = b.toLongOrNull() ?: 0L
                        start = maxOf(0L, total - n)
                    } else {
                        start = a.toLongOrNull() ?: 0L
                        if (b.isNotEmpty()) end = minOf(end, b.toLongOrNull() ?: end)
                    }
                    partial = true
                }
            }
            if (start < 0 || start > end || start >= total) {
                pfd.close()
                val h = HashMap<String, String>()
                h["Content-Range"] = "bytes */$total"
                return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", h, ByteArrayInputStream(ByteArray(0)))
            }
            val fis = FileInputStream(pfd.fileDescriptor)
            fis.channel.position(start)
            val length = end - start + 1
            val headers = HashMap<String, String>()
            headers["Accept-Ranges"] = "bytes"
            headers["Content-Length"] = length.toString()
            headers["Access-Control-Allow-Origin"] = "*"
            if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
            val mime = videoMime(sp.getString("vid_name_" + app.id, "") ?: "")
            WebResourceResponse(
                mime, null,
                if (partial) 206 else 200,
                if (partial) "Partial Content" else "OK",
                headers,
                RangeStream(pfd, fis, length)
            )
        } catch (e: Exception) {
            notFound()
        }
    }

    // ------------------------------------------------------------------ WebView setup

    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        // Needed so the Android file picker's content:// files (big MKV, SRT) can be read by the page
        s.allowContentAccess = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.textZoom = 100
        s.setGeolocationEnabled(false)
        s.loadWithOverviewMode = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        // Lets you debug the page from Chrome on a PC: chrome://inspect (USB debugging)
        WebView.setWebContentsDebuggingEnabled(true)

        web.setBackgroundColor(Color.parseColor("#0B1020"))
        web.addJavascriptInterface(Bridge(), "RajBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val u = req.url
                if (u.host == app.host && u.path == "/__video__") return serveVideo(req)
                val local = loader.shouldInterceptRequest(u)
                if (local != null) return local
                if (u.host == app.host) return notFound()
                if (req.method == "GET") {
                    val saved = store.extResponse(app, u.toString())
                    if (saved != null) return saved
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.host == app.host) return false
                return openExternal(u)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
                view?.evaluateJavascript(MEDIA_HOOK_JS, null)
            }

            // A very big video can make Android kill the page's render process. Without this the whole app crashes.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Toast.makeText(appCtx, "The page ran out of memory and was restarted.", Toast.LENGTH_LONG).show()
                main.post {
                    if (!destroyed) {
                        try {
                            web.loadUrl("https://${app.host}/${app.entry}")
                        } catch (e: Exception) {
                            activity?.finish()
                        }
                    }
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                val a = activity
                if (customView != null || a == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                a.root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                web.visibility = View.GONE
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                if (m != null) Log.d("RajHubWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val r = result ?: return false
                val a = activity
                if (a == null || a.isFinishing || a.isDestroyed) {
                    r.confirm()
                    return true
                }
                AlertDialog.Builder(a)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                val r = result ?: return false
                val a = activity
                if (a == null || a.isFinishing || a.isDestroyed) {
                    r.cancel()
                    return true
                }
                AlertDialog.Builder(a)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val r = result ?: return false
                val a = activity
                if (a == null || a.isFinishing || a.isDestroyed) {
                    r.cancel()
                    return true
                }
                val input = EditText(a)
                input.setText(defaultValue ?: "")
                input.setSelection(input.text.length)
                AlertDialog.Builder(a)
                    .setMessage(message ?: "")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                val a = activity
                if (a == null) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    val intent = try {
                        fileChooserParams.createIntent()
                    } catch (e: Exception) {
                        Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    // Never filter by type: .srt has no Android MIME type and big .mkv files are often hidden by filters.
                    intent.type = "*/*"
                    intent.removeExtra(Intent.EXTRA_MIME_TYPES)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.launchChooser(intent)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    Toast.makeText(appCtx, "Could not open the file picker", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val temp = WebView(appCtx)
                temp.webViewClient = ExternalLinkClient()
                transport.webView = temp
                msg.sendToTarget()
                return true
            }
        }
    }

    /** Links that go outside the app (WhatsApp, websites) open in the phone's own apps. */
    private inner class ExternalLinkClient : WebViewClient() {
        private var handled = false

        private fun handle(view: WebView?, uri: Uri?) {
            if (handled || uri == null) return
            val scheme = uri.scheme ?: return
            if (scheme == "about" || scheme == "blob" || scheme == "javascript") return
            handled = true
            openExternal(uri)
            view?.let { v -> v.post { v.destroy() } }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            handle(view, request?.url)
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            if (url != null) handle(view, Uri.parse(url))
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val ok = scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel" || scheme == "sms" || scheme == "geo"
        if (!ok) return false
        try {
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(appCtx, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /** Quietly refreshes the saved copy from GitHub (at most every 6 hours) when online. */
    private fun checkForUpdate() {
        if (!Net.isOnline(appCtx)) return
        val sixHours = 6L * 60 * 60 * 1000
        if (System.currentTimeMillis() - Prefs.lastCheck(appCtx, app.id) < sixHours) return
        Bg.run {
            try {
                val changed = store.sync(app) { }
                Prefs.setLastCheck(appCtx, app.id)
                if (changed) {
                    main.post {
                        Toast.makeText(appCtx, "${app.name} updated. It will use the new version next time you open it.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                // offline or GitHub unreachable: keep using the saved copy
            }
        }
    }

    // ------------------------------------------------------------------ bridge (called from the page's JavaScript)

    private inner class Bridge {
        /** Opens Android's file picker and remembers the chosen video between app launches. */
        @JavascriptInterface
        fun pickVideo() {
            main.post {
                val a = activity
                if (a == null) return@post
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "*/*"
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    a.launchVideoPicker(i)
                } catch (e: Exception) {
                    Toast.makeText(appCtx, "Could not open the file picker", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** JSON of the remembered video, or an empty string (also empty when the file can no longer be opened). */
        @JavascriptInterface
        fun getSavedVideo(): String = if (!destroyed) nv.savedJson() else ""

        /** Name of a remembered video that can no longer be opened, so the page can tell the user which one to pick again. */
        @JavascriptInterface
        fun getMissingVideoName(): String = if (!destroyed) nv.missingName() else ""

        @JavascriptInterface
        fun forgetVideo() {
            main.post { if (!destroyed) nv.forget() }
            appCtx.getSharedPreferences("rajhub", Context.MODE_PRIVATE).edit()
                .remove("vid_uri_" + app.id).remove("vid_name_" + app.id)
                .remove("vid_size_" + app.id).remove("vid_stamp_" + app.id).apply()
        }

        // ---- native player (ExoPlayer) controls used by the page ----

        /** Opens the remembered video in the native player. Returns false when there is nothing usable to open. */
        @JavascriptInterface
        fun nvOpen(): Boolean {
            if (destroyed) return false
            val ok = nv.canResolve()
            if (ok) main.post { if (!destroyed) nv.openSaved() }
            return ok
        }

        /** op: play | pause | seek (arg = milliseconds) | speed (arg = 1.0 normal) | retry. seq lets the page match replies. */
        @JavascriptInterface
        fun nvCmd(seq: Double, op: String?, arg: Double) {
            val o = op ?: return
            main.post { if (!destroyed) nv.cmd(seq.toInt(), o, arg) }
        }

        /** Toggles the video's landscape full screen (video on top, the page's subtitle line below it). */
        @JavascriptInterface
        fun nvFullscreen(on: Boolean) {
            main.post { if (!destroyed) nv.setFullscreenFromPage(on) }
        }

        @JavascriptInterface
        fun nvVisible(on: Boolean) {
            main.post { if (!destroyed) nv.setVisible(on) }
        }

        @JavascriptInterface
        fun nvControls(on: Boolean) {
            main.post { if (!destroyed) nv.setControls(on) }
        }

        @JavascriptInterface
        fun nvMute(on: Boolean) {
            main.post { if (!destroyed) nv.setMuted(on) }
        }

        @JavascriptInterface
        fun nvOverlay(text: String?, sizePct: Double, opacity: Double, on: Boolean) {
            main.post { if (!destroyed) nv.setOverlay(text, sizePct, opacity, on) }
        }

        @JavascriptInterface
        fun nvFx(mirror: Boolean, blurCssPx: Double, oldTv: Boolean) {
            main.post { if (!destroyed) nv.setFx(mirror, blurCssPx, oldTv) }
        }

        /** The page's cards: left edge, width and corner radius in CSS px. The video panel copies them. */
        @JavascriptInterface
        fun nvAlign(left: Double, width: Double, radius: Double) {
            main.post { if (!destroyed) nv.setAlign(left.toFloat(), width.toFloat(), radius.toFloat()) }
        }

        /** The sentence that is playing right now, for the notification and the floating window. */
        @JavascriptInterface
        fun mediaState(text: String?, index: Double, total: Double, playing: Boolean) {
            val wasPlaying = MediaBus.playing
            MediaBus.text = text ?: ""
            MediaBus.index = index.toInt()
            MediaBus.total = total.toInt()
            MediaBus.playing = playing
            main.post {
                if (destroyed) return@post
                if (wasPlaying != playing) onPlayingChanged()
                nv.setSubtitle(MediaBus.text, MediaBus.playing)
                PlaybackService.refreshNow()
            }
        }

        /** Star / bookmark state of the current sentence + repeat counts + delay, for the full screen buttons. */
        @JavascriptInterface
        fun mediaFlags(starred: Boolean, bookmarked: Boolean, customRepeat: Double, globalRepeat: Double, delaySec: Double, curIndex: Double) {
            main.post {
                if (destroyed) return@post
                nv.setFlags(starred, bookmarked, customRepeat.toInt(), globalRepeat.toInt(), delaySec, curIndex.toInt())
            }
        }

        /** Second subtitle (translated SRT) line for the sentence playing now, plus on / loaded state, for the full screen view. */
        @JavascriptInterface
        fun mediaSub2(text: String?, on: Boolean, has: Boolean) {
            main.post { if (!destroyed) nv.setSub2(text ?: "", on, has) }
        }

        /** Data the full screen dialogs asked for (current subtitle for edit / delete, starred list, pinned ranges, all sentences, filters). */
        @JavascriptInterface
        fun nvData(kind: String?, json: String?) {
            val k = kind ?: return
            val j = json ?: return
            main.post { if (!destroyed) nv.onPageData(k, j) }
        }

        /** Short message from the page's full screen tools. */
        @JavascriptInterface
        fun nvToast(msg: String?) {
            val m = msg ?: return
            main.post { if (!destroyed) Toast.makeText(appCtx, m, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun notify(title: String, text: String) {
            Notifier.show(appCtx, title, text)
        }

        @JavascriptInterface
        fun save(name: String, mime: String, base64: String) {
            Bg.run {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    values.put(MediaStore.Downloads.MIME_TYPE, if (mime.isBlank()) "application/octet-stream" else mime)
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + app.name)
                    values.put(MediaStore.Downloads.IS_PENDING, 1)
                    val uri = appCtx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("Could not create file")
                    val out = appCtx.contentResolver.openOutputStream(uri) ?: throw IOException("Could not open file")
                    out.use { it.write(bytes) }
                    val done = ContentValues()
                    done.put(MediaStore.Downloads.IS_PENDING, 0)
                    appCtx.contentResolver.update(uri, done, null, null)
                    main.post {
                        Toast.makeText(appCtx, "Saved to Downloads/${app.name}/$safe", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    main.post {
                        Toast.makeText(appCtx, "Could not save the file", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ teardown

    fun destroy() {
        if (destroyed) return
        destroyed = true
        hideAnchor()
        try { nv.release() } catch (e: Exception) { }
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        (nv.panel.parent as? ViewGroup)?.removeView(nv.panel)
        (web.parent as? ViewGroup)?.removeView(web)
        activity = null
        try { web.destroy() } catch (e: Exception) { }
    }
}

/** Reads at most [remaining] bytes from an already-positioned file; closes the descriptor when done. */
private class RangeStream(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
    private var remaining: Long
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val r = fis.read()
        if (r >= 0) remaining--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = fis.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n.toLong()
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        try { fis.close() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }
}
