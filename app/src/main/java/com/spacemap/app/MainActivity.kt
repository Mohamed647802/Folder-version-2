package com.spacemap.app
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
private object C {
    val BG = 0xFF0B1220.toInt()
    val CARD = 0xFF131C2E.toInt()
    val CARD_SEL = 0xFF12304A.toInt()
    val STROKE = 0xFF22304A.toInt()
    val TRACK = 0xFF243049.toInt()
    val TEXT = 0xFFE5E7EB.toInt()
    val SUB = 0xFF94A3B8.toInt()
    val CYAN = 0xFF22D3EE.toInt()
    val PURPLE = 0xFFA78BFA.toInt()
    val GREEN = 0xFF22C55E.toInt()
    val AMBER = 0xFFF59E0B.toInt()
    val RED = 0xFFEF4444.toInt()
}
class MainActivity : Activity() {
    private val disk by lazy { Disk(applicationContext) }
    private val ui = Handler(Looper.getMainLooper())
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var pool: ExecutorService? = null
    private var cur = File("/")
    private var all = ArrayList<Entry>()
    private var entries = ArrayList<Entry>()
    private val selected = LinkedHashSet<String>()
    private var sortMode = 0
    private var gen = 0
    private var pending = 0
    private var started = false
    private var asking = false
    private var requested = false
    private var scheduled = false
    private var protectedDir = false
    private var needGrant = false
    private var home = false
    private var showHidden = true
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var stats: LinearLayout
    private lateinit var distBar: LinearLayout
    private lateinit var list: ListView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var grantBtn: TextView
    private lateinit var busy: ProgressBar
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView
    private lateinit var sortBtn: TextView
    private lateinit var selAllBtn: TextView
    private lateinit var hidBtn: TextView
    private val adapter = Adapter()
    private val refreshRun = Runnable {
        scheduled = false
        refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }
    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!started) {
                started = true
                go(Environment.getExternalStorageDirectory())
            }
        } else if (!asking) {
            askAccess()
        }
    }
    override fun onDestroy() {
        pool?.shutdownNow()
        super.onDestroy()
    }
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    private fun askAccess() {
        if (Build.VERSION.SDK_INT < 30 && !requested) {
            requested = true
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
            return
        }
        asking = true
        AlertDialog.Builder(this)
            .setTitle("صلاحية الوصول لكل الملفات")
            .setMessage("لعرض كل المجلدات والملفات وحذفها يجب السماح للتطبيق بالوصول إلى كل الملفات. اضغط «فتح الإعدادات» ثم فعّل الخيار.")
            .setCancelable(false)
            .setPositiveButton("فتح الإعدادات") { _, _ -> openSettings() }
            .setOnDismissListener { asking = false }
            .show()
    }
    private fun openSettings() {
        try {
            val action = if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    private fun askSaf() {
        AlertDialog.Builder(this)
            .setTitle("🔓 فتح المجلد المحمي")
            .setMessage("سيفتح منتقي ملفات النظام عند هذا المجلد. اضغط «استخدام هذا المجلد» ثم «السماح». إذا رفض النظام (أندرويد 13+) فهذه حدود النظام.")
            .setPositiveButton("متابعة") { _, _ -> launchTree() }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private fun launchTree() {
        try {
            val sub = if (cur.path.contains("/Android/obb")) "Android/obb" else "Android/data"
            val ext = Environment.getExternalStorageDirectory().path
            val vol = if (cur.path.startsWith(ext)) "primary" else (cur.path.split("/").getOrNull(2) ?: "primary")
            val auth = "com.android.externalstorage.documents"
            val tree = DocumentsContract.buildTreeDocumentUri(auth, "$vol:$sub")
            val init = DocumentsContract.buildDocumentUriUsingTree(tree, "$vol:$sub")
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, init)
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            toast("تعذر فتح منتقي النظام")
        }
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77) return
        val u = data?.data
        if (resultCode == RESULT_OK && u != null) {
            if (disk.saf.save(u)) {
                toast("✅ تم منح الوصول")
                disk.cache.clear()
                load()
            } else {
                toast("رفض النظام هذا المجلد")
            }
        } else {
            toast("لم يتم منح الوصول — أندرويد 13+ قد يمنع هذا المجلد")
        }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun box(color: Int, r: Int, stroke: Int = 0, dash: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) {
            if (dash) setStroke(dp(1), stroke, dp(4).toFloat(), dp(3).toFloat()) else setStroke(dp(1), stroke)
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun lp(w: Int, h: Int, weight: Float = 0f, mh: Int = 0, mt: Int = 0, mb: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(mh), dp(mt), dp(mh), dp(mb)) }
    private fun chip(text: String, color: Int = C.TEXT, bg: Int = C.CARD, onClick: () -> Unit): TextView =
        tv(text, 13f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = box(bg, 12, C.STROKE)
            setOnClickListener { onClick() }
        }
    private fun stat(title: String, value: String, sub: String, color: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = box(C.CARD, 14, C.STROKE)
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.addView(tv(title, 11f, C.SUB))
        c.addView(tv(value, 16f, color, true))
        c.addView(tv(sub, 10f, C.SUB))
        return c
    }
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(10), dp(10), dp(10), 0)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("🗂️ خريطة المساحة", 20f, C.TEXT, true), lp(0, WRAP, 1f))
        head.addView(chip("🔄") {
            disk.cache.clear()
            if (home) goHome() else load()
        }, lp(WRAP, WRAP, 0f, 3))
        head.addView(chip("🗑 المهملات") { showTrash() }, lp(WRAP, WRAP, 0f, 3))
        root.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 8))
        crumbScroll = HorizontalScrollView(this)
        crumbScroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbScroll.background = box(C.CARD, 12, C.STROKE)
        crumbs = LinearLayout(this)
        crumbs.gravity = Gravity.CENTER_VERTICAL
        crumbs.setPadding(dp(6), dp(4), dp(6), dp(4))
        crumbScroll.addView(crumbs)
        root.addView(crumbScroll, lp(MATCH, WRAP, 0f, 0, 0, 6))
        val copyRow = LinearLayout(this)
        copyRow.addView(chip("📋 المسار") { copyPath() }, lp(0, WRAP, 1f, 2))
        copyRow.addView(chip("📋 مسار My Files") { copyMyFiles() }, lp(0, WRAP, 1f, 2))
        copyRow.addView(chip("📄 المحتوى") { copyContent() }, lp(0, WRAP, 1f, 2))
        root.addView(copyRow, lp(MATCH, WRAP, 0f, 0, 0, 8))
        stats = LinearLayout(this)
        root.addView(stats, lp(MATCH, WRAP, 0f, 0, 0, 8))
        distBar = LinearLayout(this)
        distBar.background = box(C.TRACK, 6)
        root.addView(distBar, lp(MATCH, dp(14), 0f, 0, 0, 8))
        sortBtn = chip("") {
            sortMode = (sortMode + 1) % 3
            refresh()
        }
        selAllBtn = chip("☑ تحديد الكل") { toggleAll() }
        hidBtn = chip("") {
            showHidden = !showHidden
            refresh()
        }
        val tools = LinearLayout(this)
        tools.addView(sortBtn, lp(0, WRAP, 1f, 2))
        tools.addView(selAllBtn, lp(0, WRAP, 1f, 2))
        tools.addView(hidBtn, lp(0, WRAP, 1f, 2))
        root.addView(tools, lp(MATCH, WRAP, 0f, 0, 0, 6))
        busy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        busy.isIndeterminate = true
        busy.visibility = View.GONE
        root.addView(busy, lp(MATCH, dp(4), 0f, 0, 0, 4))
        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.setSelector(android.R.color.transparent)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> clickEntry(entries[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            toggle(entries[pos])
            true
        }
        root.addView(list, lp(MATCH, 0, 1f))
        emptyView = tv("", 15f, C.SUB)
        emptyView.gravity = Gravity.CENTER
        grantBtn = chip("🔓 فتح المجلد المحمي", C.BG, C.CYAN) { askSaf() }
        emptyBox = LinearLayout(this)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER
        emptyBox.visibility = View.GONE
        emptyBox.addView(emptyView, lp(MATCH, WRAP))
        emptyBox.addView(grantBtn, lp(WRAP, WRAP, 0f, 0, 12, 0))
        root.addView(emptyBox, lp(MATCH, 0, 1f))
        selBar = LinearLayout(this)
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.background = box(C.CARD, 14, C.CYAN)
        selBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        selText = tv("", 14f, C.TEXT, true)
        selBar.addView(selText, lp(0, WRAP, 1f))
        selBar.addView(chip("📋") { copySelected() }, lp(WRAP, WRAP, 0f, 3))
        selBar.addView(chip("✕") {
            selected.clear()
            updateSel()
            adapter.notifyDataSetChanged()
        }, lp(WRAP, WRAP, 0f, 3))
        selBar.addView(chip("🗑 حذف", C.TEXT, C.RED) { askDelete() }, lp(WRAP, WRAP, 0f, 3))
        selBar.visibility = View.GONE
        root.addView(selBar, lp(MATCH, WRAP, 0f, 0, 4, 8))
        return root
    }
    private fun volumes(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        out.add(Pair("Internal storage", Environment.getExternalStorageDirectory()))
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (v in sm.storageVolumes) {
                    if (v.isPrimary) continue
                    val d = v.directory ?: continue
                    out.add(Pair(v.getDescription(this), d))
                }
            } else {
                File("/storage").listFiles()?.forEach {
                    if (it.isDirectory && it.name != "emulated" && it.name != "self") out.add(Pair("SD card", it))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
    private fun volOf(f: File): Pair<String, File>? =
        volumes().filter { f.path == it.second.path || f.path.startsWith(it.second.path + "/") }
            .maxByOrNull { it.second.path.length }
    private fun isVolRoot(f: File): Boolean = volumes().any { it.second.path == f.path }
    private fun myFilesPath(): String {
        val v = volOf(cur) ?: return cur.absolutePath
        return v.first + cur.path.removePrefix(v.second.path)
    }
    private fun volEntry(label: String, f: File): Entry {
        var used = 0L
        var note = f.path
        try {
            val s = StatFs(f.path)
            used = s.totalBytes - s.availableBytes
            note = "المتاح " + Fmt.size(s.availableBytes) + " من " + Fmt.size(s.totalBytes) + " • " + f.path
        } catch (e: Exception) {
        }
        val e = Entry(f, Kind.FOLDER, used, 0, false)
        e.label = label
        e.note = note
        return e
    }
    private fun goHome() {
        gen++
        pool?.shutdownNow()
        ui.removeCallbacks(refreshRun)
        scheduled = false
        pending = 0
        busy.visibility = View.GONE
        home = true
        selected.clear()
        protectedDir = false
        needGrant = false
        val l = ArrayList<Entry>()
        for ((label, f) in volumes()) l.add(volEntry(label, f))
        l.add(volEntry("System root /", File("/")))
        all = l
        refreshCrumbs()
        refresh()
        list.setSelection(0)
    }
    private fun refreshCrumbs() {
        crumbs.removeAllViews()
        if (home) {
            addCrumb("🏠 الرئيسية", null, true)
        } else {
            addCrumb("🏠", null, false)
            val v = volOf(cur)
            if (v != null) {
                crumbs.addView(tv("›", 16f, C.SUB), lp(WRAP, WRAP, 0f, 2))
                addCrumb(v.first, v.second, cur.path == v.second.path)
                addParts(v.second, cur.path.removePrefix(v.second.path))
            } else {
                crumbs.addView(tv("›", 16f, C.SUB), lp(WRAP, WRAP, 0f, 2))
                addCrumb("/", File("/"), cur.path == "/")
                addParts(File("/"), cur.path)
            }
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }
    private fun addParts(base: File, rest: String) {
        var acc = base.path.trimEnd('/')
        val parts = rest.split("/").filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            acc += "/$p"
            crumbs.addView(tv("›", 16f, C.SUB), lp(WRAP, WRAP, 0f, 2))
            addCrumb(p, File(acc), i == parts.lastIndex)
        }
    }
    private fun addCrumb(label: String, target: File?, last: Boolean) {
        val t = tv(label, 14f, if (last) C.BG else C.TEXT, true)
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        t.background = box(if (last) C.CYAN else C.TRACK, 10)
        t.setOnClickListener { if (target == null) goHome() else go(target) }
        crumbs.addView(t)
    }
    private fun clip(label: String, text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(msg)
    }
    private fun copyPath() {
        if (home) {
            val t = volumes().joinToString("\n") { it.first + ": " + it.second.path } + "\nSystem root: /"
            clip("path", t, "تم نسخ مسارات التخزين")
        } else {
            clip("path", cur.absolutePath, "تم نسخ المسار:\n" + cur.absolutePath)
        }
    }
    private fun copyMyFiles() {
        if (home) {
            copyPath()
        } else {
            val p = myFilesPath()
            clip("path", p, "تم نسخ المسار:\n$p")
        }
    }
    private fun copySelected() {
        val items = entries.filter { selected.contains(it.file.path) }
        if (items.isEmpty()) return
        clip("paths", items.joinToString("\n") { it.file.absolutePath }, "تم نسخ ${items.size} مسار")
    }
    private fun copyContent() {
        val sb = StringBuilder()
        sb.append("📂 المسار: ").append(if (home) "الرئيسية" else cur.absolutePath).append('\n')
        if (!home) sb.append("📍 My Files: ").append(myFilesPath()).append('\n')
        val known = entries.filter { it.size > 0 }.sumOf { it.size }
        sb.append("العناصر: ").append(entries.size).append(" • الحجم: ").append(Fmt.size(known)).append('\n')
        sb.append("الترتيب: ").append(sortBtn.text).append('\n')
        sb.append("------\n")
        val shown = entries.take(1000)
        for (e in shown) {
            sb.append(e.kind.icon).append(' ').append(e.label ?: e.file.name)
            if (e.kind == Kind.FOLDER) sb.append('/')
            sb.append(" | ").append(if (e.size < 0) "…" else Fmt.size(e.size))
            val p = if (e.size < 0) 0.0 else e.size * 100.0 / adapter.total
            sb.append(" | ").append(String.format(Locale.US, "%.1f%%", p))
            if (e.kind == Kind.FOLDER && e.label == null) sb.append(" | ").append(e.count).append(" عنصر")
            if (e.hidden) sb.append(" | 👻 مخفي")
            if (e.locked) sb.append(" | 🔒 محمي")
            sb.append('\n')
        }
        if (entries.size > shown.size) sb.append("... و").append(entries.size - shown.size).append(" عنصر آخر\n")
        clip("content", sb.toString(), "تم نسخ محتوى المجلد (${shown.size} عنصر)")
    }
    private fun go(dir: File) {
        cur = dir
        selected.clear()
        load()
        list.setSelection(0)
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val parent = cur.parentFile
        when {
            selected.isNotEmpty() -> {
                selected.clear()
                updateSel()
                adapter.notifyDataSetChanged()
            }
            home -> super.onBackPressed()
            started && (parent == null || isVolRoot(cur)) -> goHome()
            started && parent != null -> go(parent)
            else -> super.onBackPressed()
        }
    }
    private fun load() {
        gen++
        val myGen = gen
        pool?.shutdownNow()
        ui.removeCallbacks(refreshRun)
        scheduled = false
        home = false
        val kids = disk.children(cur)
        val isProt = disk.saf.prot(cur)
        protectedDir = kids == null || (kids.isEmpty() && isProt)
        needGrant = isProt && disk.saf.find(cur) == null && (kids == null || kids.isEmpty())
        all = ArrayList(kids ?: emptyList<Entry>())
        refreshCrumbs()
        val todo = all.filter { it.size < 0 }
        pending = todo.size
        busy.visibility = if (pending > 0) View.VISIBLE else View.GONE
        refresh()
        if (todo.isEmpty()) return
        val p = Executors.newFixedThreadPool(4)
        pool = p
        for (e in todo) {
            p.execute {
                var s = 0L
                try {
                    s = disk.size(e.file)
                } catch (x: InterruptedException) {
                    return@execute
                } catch (x: Exception) {
                }
                ui.post {
                    if (myGen == gen) {
                        e.size = s
                        pending--
                        if (pending <= 0) {
                            busy.visibility = View.GONE
                            refresh()
                        } else {
                            scheduleRefresh()
                        }
                    }
                }
            }
        }
        p.shutdown()
    }
    private fun scheduleRefresh() {
        if (!scheduled) {
            scheduled = true
            ui.postDelayed(refreshRun, 300)
        }
    }
    private fun refresh() {
        entries = ArrayList(if (showHidden) all else all.filter { !it.hidden })
        when (sortMode) {
            0 -> entries.sortWith(compareByDescending<Entry> { it.size })
            1 -> entries.sortWith(compareBy<Entry> { if (it.size < 0) Long.MAX_VALUE else it.size })
            else -> entries.sortWith(compareBy<Entry> { (it.label ?: it.file.name).lowercase() })
        }
        sortBtn.text = arrayOf("⇅ الأكبر أولاً", "⇅ الأصغر أولاً", "⇅ حسب الاسم")[sortMode]
        hidBtn.text = if (showHidden) "👻 المخفي: ظاهر" else "👻 المخفي: مخفي"
        val known = entries.filter { it.size > 0 }.sumOf { it.size }
        adapter.total = if (known > 0) known else 1L
        val hiddenCount = all.count { it.hidden }
        var free = 0L
        var total = 0L
        try {
            val dir = if (home || !cur.exists()) Environment.getExternalStorageDirectory().path else cur.path
            val s = StatFs(dir)
            free = s.availableBytes
            total = s.totalBytes
        } catch (e: Exception) {
        }
        stats.removeAllViews()
        stats.addView(
            stat(if (home) "المستخدم" else "حجم المجلد", Fmt.size(known) + if (pending > 0) " …" else "", "${entries.size} عنصر", C.CYAN),
            lp(0, WRAP, 1f, 3)
        )
        stats.addView(
            stat(
                "مجلدات / ملفات",
                "${entries.count { it.kind == Kind.FOLDER }} / ${entries.count { it.kind != Kind.FOLDER }}",
                "في هذا المسار" + if (hiddenCount > 0) " • 👻$hiddenCount" else "", C.PURPLE
            ),
            lp(0, WRAP, 1f, 3)
        )
        stats.addView(
            stat("المتاح بالجهاز", if (total > 0) Fmt.size(free) else "—", if (total > 0) "من " + Fmt.size(total) else "", C.GREEN),
            lp(0, WRAP, 1f, 3)
        )
        distBar.removeAllViews()
        if (known > 0) {
            val top = entries.filter { it.size > 0 }.sortedByDescending { it.size }.take(6)
            var used = 0L
            for (e in top) {
                used += e.size
                val seg = View(this)
                seg.background = box(e.kind.color, 4)
                distBar.addView(seg, lp(0, MATCH, e.size.toFloat() / known * 100f, 1))
            }
            if (known - used > 0) {
                val seg = View(this)
                seg.background = box(C.SUB, 4)
                distBar.addView(seg, lp(0, MATCH, (known - used).toFloat() / known * 100f, 1))
            }
        }
        val empty = entries.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        grantBtn.visibility = if (empty && needGrant) View.VISIBLE else View.GONE
        emptyView.text = when {
            all.isNotEmpty() -> "👻\nكل العناصر هنا مخفية\nاضغط زر «المخفي» لإظهارها"
            needGrant -> "🔒\nهذا المجلد محمي من النظام\nاضغط الزر بالأسفل لفتحه بصلاحية النظام"
            protectedDir -> "🔒\nلا يمكن قراءة هذا المجلد"
            else -> "📭\nالمجلد فارغ"
        }
        adapter.notifyDataSetChanged()
        updateSel()
    }
    private fun clickEntry(e: Entry) {
        if (e.kind == Kind.FOLDER) go(e.file) else openFile(e)
    }
    private fun openFile(e: Entry) {
        try {
            val f = e.file
            val uri = e.doc ?: FileProvider.getUriForFile(this, "$packageName.provider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (x: Exception) {
            toast("لا يوجد تطبيق يفتح هذا النوع من الملفات")
        }
    }
    private fun toggle(e: Entry) {
        if (home || e.label != null) return
        val k = e.file.path
        if (!selected.remove(k)) selected.add(k)
        updateSel()
        adapter.notifyDataSetChanged()
    }
    private fun toggleAll() {
        if (home) return
        if (selected.size == entries.size) {
            selected.clear()
        } else {
            entries.forEach { selected.add(it.file.path) }
        }
        updateSel()
        adapter.notifyDataSetChanged()
    }
    private fun updateSel() {
        val n = selected.size
        selBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            val sz = entries.filter { selected.contains(it.file.path) }.sumOf { maxOf(it.size, 0L) }
            selText.text = "محدد: $n • ${Fmt.size(sz)}"
        }
        selAllBtn.text = if (n > 0 && n == entries.size) "☐ إلغاء التحديد" else "☑ تحديد الكل"
    }
    private fun askDelete() {
        val items = entries.filter { selected.contains(it.file.path) }
        if (items.isEmpty()) return
        val sz = items.sumOf { maxOf(it.size, 0L) }
        val b = AlertDialog.Builder(this).setTitle("حذف ${items.size} عنصر")
        if (items.any { it.doc != null }) {
            b.setMessage("الحجم: ${Fmt.size(sz)}\n\nعناصر محمية (Android/data): الحذف نهائي فقط ولا يمكن التراجع.")
                .setPositiveButton("⚠ حذف نهائي") { _, _ -> doDelete(items, true) }
                .setNegativeButton("إلغاء", null)
        } else {
            b.setMessage(
                "الحجم: ${Fmt.size(sz)}\n\n" +
                    "• سلة المهملات: يمكن استعادتها من داخل التطبيق، لكنها تظل تشغل المساحة حتى تحذفها نهائياً من السلة.\n\n" +
                    "• حذف نهائي: يحرر المساحة فوراً ولا يمكن التراجع."
            )
                .setPositiveButton("🗑 سلة المهملات") { _, _ -> doDelete(items, false) }
                .setNegativeButton("⚠ حذف نهائي") { _, _ -> doDelete(items, true) }
                .setNeutralButton("إلغاء", null)
        }
        b.show()
    }
    private fun doDelete(items: List<Entry>, permanent: Boolean) {
        pool?.shutdownNow()
        busy.visibility = View.VISIBLE
        Thread {
            var ok = 0
            val media = ArrayList<String>()
            for (e in items) {
                try {
                    val sz = if (e.size >= 0) e.size else disk.size(e.file)
                    val viaSaf = e.doc != null
                    val moved: File? = if (permanent || viaSaf) null else Trash.moveIn(e.file)
                    val good = if (viaSaf) disk.saf.delete(e.doc!!) else if (permanent) Fs.deleteTree(e.file) else moved != null
                    if (good) {
                        ok++
                        disk.removed(e.file, sz)
                        if (moved != null) disk.adjust(moved.parentFile, sz)
                        if (e.kind != Kind.FOLDER) media.add(e.file.path)
                    } else {
                        disk.cache.clear()
                    }
                } catch (x: Exception) {
                    disk.cache.clear()
                }
            }
            if (media.isNotEmpty()) {
                MediaScannerConnection.scanFile(this@MainActivity, media.toTypedArray(), null, null)
            }
            ui.post {
                toast("تمت المعالجة: $ok من ${items.size}" + if (ok < items.size) " — الباقي محمي أو مستخدم" else "")
                selected.clear()
                load()
            }
        }.start()
    }
    private fun showTrash() {
        val boxes = (Trash.dir().listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedBy { it.name }
        if (boxes.isEmpty()) {
            toast("سلة المهملات فارغة")
            return
        }
        val names = boxes.map { b ->
            val o = Trash.orig(b)
            File(o).name + "\n" + o
        }.toTypedArray()
        val chk = BooleanArray(boxes.size)
        AlertDialog.Builder(this)
            .setTitle("🗑 سلة المهملات (${boxes.size})")
            .setMultiChoiceItems(names, chk) { _, i, c -> chk[i] = c }
            .setPositiveButton("استعادة") { _, _ -> trashAct(boxes, chk, true) }
            .setNegativeButton("حذف نهائي") { _, _ -> trashAct(boxes, chk, false) }
            .setNeutralButton("تفريغ الكل") { _, _ -> trashAct(boxes, BooleanArray(boxes.size) { true }, false) }
            .show()
    }
    private fun trashAct(boxes: List<File>, chk: BooleanArray, restore: Boolean) {
        val sel = boxes.filterIndexed { i, _ -> chk[i] }
        if (sel.isEmpty()) return
        busy.visibility = View.VISIBLE
        Thread {
            var ok = 0
            for (b in sel) {
                val good = if (restore) Trash.restore(b) else Fs.deleteTree(b)
                if (good) ok++
            }
            ui.post {
                disk.cache.clear()
                toast("تمت المعالجة: $ok من ${sel.size}")
                if (home) goHome() else load()
            }
        }.start()
    }
    private inner class Holder {
        val root = FrameLayout(this@MainActivity)
        val card = LinearLayout(this@MainActivity)
        val check = CheckBox(this@MainActivity)
        val icon = TextView(this@MainActivity)
        val name = tv("", 15f, C.TEXT, true)
        val pct = tv("", 13f, C.SUB, true)
        val meta = tv("", 12f, C.SUB)
        val track = LinearLayout(this@MainActivity)
        val fill = View(this@MainActivity)
        val rest = View(this@MainActivity)
        init {
            root.setPadding(0, dp(4), 0, dp(4))
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(dp(10), dp(10), dp(12), dp(10))
            check.isFocusable = false
            check.isFocusableInTouchMode = false
            icon.gravity = Gravity.CENTER
            icon.textSize = 22f
            name.setSingleLine(true)
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            track.background = box(C.TRACK, 4)
            track.addView(fill)
            track.addView(rest)
            val top = LinearLayout(this@MainActivity)
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(name, lp(0, WRAP, 1f))
            top.addView(pct, lp(WRAP, WRAP, 0f, 4))
            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            col.addView(top, lp(MATCH, WRAP))
            col.addView(meta, lp(MATCH, WRAP, 0f, 0, 2, 4))
            col.addView(track, lp(MATCH, dp(8)))
            card.addView(check, lp(WRAP, WRAP, 0f, 2))
            card.addView(icon, lp(dp(48), dp(48), 0f, 8))
            card.addView(col, lp(0, WRAP, 1f, 4))
            root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
            root.tag = this
        }
    }
    private fun bind(h: Holder, e: Entry) {
        val sel = selected.contains(e.file.path)
        val hid = e.hidden
        h.card.background = when {
            sel -> box(C.CARD_SEL, 14, C.CYAN)
            hid -> box(C.CARD, 14, C.PURPLE, true)
            else -> box(C.CARD, 14, C.STROKE)
        }
        h.check.visibility = if (home) View.GONE else View.VISIBLE
        h.check.isChecked = sel
        h.check.setOnClickListener { toggle(e) }
        h.icon.text = e.kind.icon
        h.icon.alpha = if (hid) 0.55f else 1f
        h.icon.background = box((e.kind.color and 0x00FFFFFF) or 0x33000000, 12)
        h.name.text = e.label ?: (if (e.file.name.isEmpty()) e.file.path else e.file.name)
        h.name.setTextColor(if (hid) C.PURPLE else C.TEXT)
        val p = if (e.size < 0) 0.0 else e.size * 100.0 / adapter.total
        val col = if (p < 15) C.GREEN else if (p < 40) C.AMBER else C.RED
        val base = when {
            e.note.isNotEmpty() -> e.note
            e.locked -> "🔒 محمي من النظام"
            e.size < 0 -> "⏳ جارٍ حساب الحجم…"
            e.kind == Kind.FOLDER -> "${Fmt.size(e.size)} • ${e.count} عنصر"
            else -> "${Fmt.size(e.size)} • ${e.kind.label} • ${df.format(Date(e.mtime))}"
        }
        h.meta.text = (if (hid) "👻 مخفي • " else "") + base
        h.pct.text = if (e.size < 0) "…" else String.format(Locale.US, "%.1f%%", p)
        h.pct.setTextColor(col)
        val w = if (e.size < 0) 0f else maxOf(p.toFloat(), 1.5f)
        h.fill.background = box(col, 4)
        h.fill.layoutParams = LinearLayout.LayoutParams(0, MATCH, w)
        h.rest.layoutParams = LinearLayout.LayoutParams(0, MATCH, maxOf(100f - w, 0.01f))
    }
    private inner class Adapter : BaseAdapter() {
        var total = 1L
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? Holder) ?: Holder()
            bind(h, entries[position])
            return h.root
        }
    }
}
