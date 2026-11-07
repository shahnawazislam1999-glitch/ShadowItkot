package com.raj.shadowplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Size
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** The library: scans the phone for videos (all formats Android knows), lists them, opens the player. */
class MainActivity : AppCompatActivity() {

    private class Row(val folder: String?, val count: Int, val video: VideoItem?)

    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L).toInt().coerceAtLeast(1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val prefs by lazy { getSharedPreferences("shadowplayer", Context.MODE_PRIVATE) }

    private var all: List<VideoItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private var openFolder: String? = null
    private var foldersMode = false
    private var query = ""
    private var sortMode = 0          // 0 newest, 1 name, 2 size, 3 length
    private var scanning = false

    private lateinit var titleView: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyBtn: Button
    private lateinit var foldersBtn: TextView
    private val adapter = Adapter()

    private val askPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasPerm()) scan() else showEmpty("File access is needed to list your videos.", true)
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPerm(): Boolean = neededPerms().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun iconBtn(label: String, desc: String, onClick: (View) -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        t.gravity = Gravity.CENTER
        t.contentDescription = desc
        t.setOnClickListener { onClick(it) }
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sortMode = prefs.getInt("lib_sort", 0)
        foldersMode = prefs.getBoolean("lib_folders", false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16), dp(8), dp(8), dp(8))
        titleView = TextView(this)
        titleView.text = "ShadowPlayer"
        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconBtn("\uD83D\uDD0D", "Search") { toggleSearch() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        foldersBtn = iconBtn("\uD83D\uDCC1", "Folders / all videos") { toggleFolders() }
        bar.addView(foldersBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconBtn("\u21C5", "Sort") { showSortMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconBtn("\u22EE", "More") { showMoreMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        searchBox = EditText(this)
        searchBox.hint = "Search videos"
        searchBox.setHintTextColor(Color.argb(120, 255, 255, 255))
        searchBox.setTextColor(Color.WHITE)
        searchBox.setSingleLine(true)
        searchBox.visibility = View.GONE
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                rebuild()
            }
        })
        val sl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sl.setMargins(dp(16), 0, dp(16), dp(4))
        root.addView(searchBox, sl)

        val body = FrameLayout(this)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyBox = LinearLayout(this)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER
        emptyBox.setPadding(dp(32), dp(32), dp(32), dp(32))
        emptyText = TextView(this)
        emptyText.setTextColor(Color.argb(200, 255, 255, 255))
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        emptyText.gravity = Gravity.CENTER
        emptyBtn = Button(this)
        emptyBtn.text = "Allow file access"
        emptyBtn.isAllCaps = false
        emptyBtn.setOnClickListener { askPerm.launch(neededPerms()) }
        emptyBox.addView(emptyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyBox.addView(emptyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyBox.visibility = View.GONE
        body.addView(emptyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchBox.visibility == View.VISIBLE) {
                    toggleSearch()
                } else if (openFolder != null && foldersMode) {
                    openFolder = null
                    rebuild()
                } else {
                    finish()
                }
            }
        })

        if (hasPerm()) scan() else askPerm.launch(neededPerms())
    }

    override fun onResume() {
        super.onResume()
        if (hasPerm() && all.isNotEmpty() && !scanning) scan()   // picks up new videos and refreshes "resume" times
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ scanning

    private fun scan() {
        if (scanning) return
        scanning = true
        if (all.isEmpty()) showEmpty("Looking for videos\u2026", false)
        Thread {
            val found = ArrayList<VideoItem>()
            try {
                val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Video.Media.DATA
                )
                contentResolver.query(base, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        found.add(
                            VideoItem(
                                "v$id",
                                ContentUris.withAppendedId(base, id),
                                c.getString(iName) ?: "video",
                                c.getLong(iDur),
                                c.getLong(iSize),
                                c.getString(iBucket) ?: "Other",
                                c.getLong(iDate),
                                c.getString(iData)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // an empty list is shown below
            }
            runOnUiThread {
                scanning = false
                if (!isFinishing) {
                    all = found
                    rebuild()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ list

    private fun showEmpty(msg: String, askButton: Boolean) {
        emptyText.text = msg
        emptyBtn.visibility = if (askButton) View.VISIBLE else View.GONE
        emptyBox.visibility = View.VISIBLE
        list.visibility = View.GONE
    }

    private fun sorted(src: List<VideoItem>): List<VideoItem> = when (sortMode) {
        1 -> src.sortedBy { it.name.lowercase() }
        2 -> src.sortedByDescending { it.size }
        3 -> src.sortedByDescending { it.durationMs }
        else -> src.sortedByDescending { it.dateAdded }
    }

    private fun rebuild() {
        val q = query.trim().lowercase()
        val src = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        val out = ArrayList<Row>()
        if (foldersMode && openFolder == null && q.isEmpty()) {
            val groups = src.groupBy { it.folder }
            for (e in groups.entries.sortedBy { it.key.lowercase() }) out.add(Row(e.key, e.value.size, null))
            titleView.text = "ShadowPlayer"
        } else {
            val of = openFolder
            val vs = sorted(if (of != null && foldersMode) src.filter { it.folder == of } else src)
            for (v in vs) out.add(Row(null, 0, v))
            titleView.text = if (of != null && foldersMode) of else "ShadowPlayer"
        }
        rows = out
        foldersBtn.alpha = if (foldersMode) 1f else 0.6f
        adapter.notifyDataSetChanged()
        if (out.isEmpty()) {
            if (!hasPerm()) showEmpty("File access is needed to list your videos.", true)
            else showEmpty(if (all.isEmpty()) "No videos found on this phone." else "Nothing matches your search.", false)
        } else {
            emptyBox.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
    }

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            searchBox.visibility = View.GONE
            query = ""
            rebuild()
        } else {
            searchBox.visibility = View.VISIBLE
            searchBox.requestFocus()
        }
    }

    private fun toggleFolders() {
        foldersMode = !foldersMode
        openFolder = null
        prefs.edit().putBoolean("lib_folders", foldersMode).apply()
        rebuild()
    }

    private fun showSortMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "Newest first")
        m.menu.add(0, 1, 1, "Name")
        m.menu.add(0, 2, 2, "Size")
        m.menu.add(0, 3, 3, "Length")
        m.setOnMenuItemClickListener {
            sortMode = it.itemId
            prefs.edit().putInt("lib_sort", sortMode).apply()
            rebuild()
            true
        }
        m.show()
    }

    private fun showMoreMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "Rescan videos")
        m.menu.add(0, 1, 1, "Find .srt files next to videos (all files access)")
        m.setOnMenuItemClickListener {
            if (it.itemId == 0) {
                if (hasPerm()) scan() else askPerm.launch(neededPerms())
            } else {
                askAllFilesAccess(this)
            }
            true
        }
        m.show()
    }

    private fun openVideo(item: VideoItem) {
        val vids = ArrayList<VideoItem>()
        for (r in rows) r.video?.let { vids.add(it) }
        Playlist.items = vids
        Playlist.index = vids.indexOf(item).coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------------ thumbnails

    private fun loadThumb(item: VideoItem, iv: ImageView) {
        iv.tag = item.key
        val cached = thumbs.get(item.key)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        try {
            pool.execute {
                val bmp: Bitmap? = try {
                    contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    thumbs.put(item.key, bmp)
                    runOnUiThread { if (iv.tag == item.key) iv.setImageBitmap(bmp) }
                }
            }
        } catch (e: Exception) {
            // pool is shut down while leaving
        }
    }

    // ------------------------------------------------------------------ adapter

    private inner class VH(
        val root: LinearLayout,
        val thumb: ImageView,
        val glyph: TextView,
        val duration: TextView,
        val title: TextView,
        val meta: TextView
    ) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context)
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(14), dp(7), dp(14), dp(7))
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val box = FrameLayout(parent.context)
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#1C2547"))
            bg.cornerRadius = dp(8).toFloat()
            box.background = bg
            box.clipToOutline = true
            val thumb = ImageView(parent.context)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            box.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val glyph = TextView(parent.context)
            glyph.text = "\uD83D\uDCC1"
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            glyph.gravity = Gravity.CENTER
            glyph.visibility = View.GONE
            box.addView(glyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val dur = TextView(parent.context)
            dur.setTextColor(Color.WHITE)
            dur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            dur.setPadding(dp(5), dp(1), dp(5), dp(1))
            val dbg = GradientDrawable()
            dbg.setColor(Color.argb(170, 0, 0, 0))
            dbg.cornerRadius = dp(4).toFloat()
            dur.background = dbg
            val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            dlp.setMargins(0, 0, dp(4), dp(4))
            box.addView(dur, dlp)
            root.addView(box, LinearLayout.LayoutParams(dp(128), dp(72)))

            val col = LinearLayout(parent.context)
            col.orientation = LinearLayout.VERTICAL
            val title = TextView(parent.context)
            title.setTextColor(Color.WHITE)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            val meta = TextView(parent.context)
            meta.setTextColor(Color.argb(170, 255, 255, 255))
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            meta.maxLines = 1
            meta.ellipsize = TextUtils.TruncateAt.END
            meta.setPadding(0, dp(3), 0, 0)
            col.addView(title)
            col.addView(meta)
            val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            clp.setMargins(dp(12), 0, 0, 0)
            root.addView(col, clp)
            return VH(root, thumb, glyph, dur, title, meta)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val v = r.video
            if (v == null) {
                h.thumb.setImageDrawable(null)
                h.thumb.tag = null
                h.glyph.visibility = View.VISIBLE
                h.duration.visibility = View.GONE
                h.title.text = r.folder ?: ""
                h.meta.text = r.count.toString() + (if (r.count == 1) " video" else " videos")
                h.root.setOnClickListener {
                    openFolder = r.folder
                    rebuild()
                }
            } else {
                h.glyph.visibility = View.GONE
                h.duration.visibility = if (v.durationMs > 0L) View.VISIBLE else View.GONE
                h.duration.text = fmtDuration(v.durationMs)
                h.title.text = v.name
                val resume = prefs.getLong("pos_" + v.key, 0L)
                val sb = StringBuilder()
                sb.append(fmtSize(v.size))
                if (!(foldersMode && openFolder != null)) sb.append("  \u2022  ").append(v.folder)
                if (resume > 5000L) sb.append("  \u2022  \u25B6 ").append(fmtDuration(resume))
                h.meta.text = sb.toString()
                loadThumb(v, h.thumb)
                h.root.setOnClickListener { openVideo(v) }
            }
        }
    }
}
