package com.raj.shadowplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings that apply to every video (delay, repeats, speed ...). */
class PlayerSettings(private val prefs: SharedPreferences) {
    var delaySec: Double = prefs.getFloat("s_delay", 0f).toDouble()
        set(v) { field = v; prefs.edit().putFloat("s_delay", v.toFloat()).apply() }
    var repeatAll: Int = prefs.getInt("s_repeat", 1)
        set(v) { field = v; prefs.edit().putInt("s_repeat", v).apply() }
    var passageLoops: Int = prefs.getInt("s_passage", 10)
        set(v) { field = v; prefs.edit().putInt("s_passage", v).apply() }
    var lineCount: Int = prefs.getInt("s_lines", 1)
        set(v) { field = v; prefs.edit().putInt("s_lines", v).apply() }
    var speed: Float = prefs.getFloat("s_speed", 1f)
        set(v) { field = v; prefs.edit().putFloat("s_speed", v).apply() }
    var syncOffset: Double = prefs.getFloat("s_sync", 0f).toDouble()
        set(v) { field = v; prefs.edit().putFloat("s_sync", v.toFloat()).apply() }
    var shuffle: Boolean = prefs.getBoolean("s_shuffle", false)
        set(v) { field = v; prefs.edit().putBoolean("s_shuffle", v).apply() }
    var starredOnly: Boolean = prefs.getBoolean("s_starred_only", false)
        set(v) { field = v; prefs.edit().putBoolean("s_starred_only", v).apply() }
}

/** Everything remembered about ONE video: its subtitles, stars, bookmarks, hidden lines, repeat counts, filters. */
class VideoData(private val ctx: Context, val key: String) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)

    val starred = LinkedHashSet<String>()
    val bookmarked = LinkedHashSet<String>()
    val hidden = LinkedHashSet<String>()
    val customRepeats = LinkedHashMap<String, Int>()
    val pinned = ArrayList<String>()
    var words = ""
    var range = ""
    var index = 0
    var showSecond = true

    init { load() }

    private fun subsDir(): File = File(ctx.filesDir, "subs").also { it.mkdirs() }
    private fun mainFile() = File(subsDir(), "$key.srt")
    private fun secondFile() = File(subsDir(), "$key.2.srt")

    fun loadMainSrt(): String? = try { if (mainFile().isFile) mainFile().readText() else null } catch (e: Exception) { null }
    fun loadSecondSrt(): String? = try { if (secondFile().isFile) secondFile().readText() else null } catch (e: Exception) { null }
    fun saveMainSrt(text: String) { try { mainFile().writeText(text) } catch (e: Exception) { } }
    fun saveSecondSrt(text: String) { try { secondFile().writeText(text) } catch (e: Exception) { } }

    private fun readSet(o: JSONObject, name: String, into: MutableCollection<String>) {
        val a = o.optJSONArray(name) ?: return
        for (i in 0 until a.length()) into.add(a.optString(i, ""))
    }

    private fun load() {
        val raw = prefs.getString("vd_$key", null) ?: return
        try {
            val o = JSONObject(raw)
            readSet(o, "starred", starred)
            readSet(o, "bookmarked", bookmarked)
            readSet(o, "hidden", hidden)
            readSet(o, "pinned", pinned)
            val cr = o.optJSONObject("repeats")
            if (cr != null) {
                val keys = cr.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    customRepeats[k] = cr.optInt(k, 0)
                }
            }
            words = o.optString("words", "")
            range = o.optString("range", "")
            index = o.optInt("index", 0)
            showSecond = o.optBoolean("second", true)
        } catch (e: Exception) {
            // unreadable: start clean
        }
    }

    fun save() {
        try {
            val o = JSONObject()
            o.put("starred", JSONArray(starred.toList()))
            o.put("bookmarked", JSONArray(bookmarked.toList()))
            o.put("hidden", JSONArray(hidden.toList()))
            o.put("pinned", JSONArray(pinned.toList()))
            val cr = JSONObject()
            for ((k, v) in customRepeats) cr.put(k, v)
            o.put("repeats", cr)
            o.put("words", words)
            o.put("range", range)
            o.put("index", index)
            o.put("second", showSecond)
            prefs.edit().putString("vd_$key", o.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    var positionMs: Long
        get() = prefs.getLong("pos_$key", 0L)
        set(v) { prefs.edit().putLong("pos_$key", v).apply() }
}
