package com.spacemap.app
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.util.concurrent.ConcurrentHashMap
class Row(val name: String, val dir: Boolean, val size: Long, val count: Int, val date: String)
object Snap {
    val dirs = ConcurrentHashMap<String, List<Row>>()
    val scanned = HashSet<String>()
    @Volatile
    var running: ReaderService? = null
    @Volatile
    var armed = false
    @Volatile
    var armedAt = 0L
    @Volatile
    var returnOnBase = false
    @Volatile
    var base = ""
    @Volatile
    var last = ""
    @Volatile
    var dump = ""
    fun on(): Boolean = running != null
    fun arm(b: String, ret: Boolean) {
        base = b
        last = b
        returnOnBase = ret
        armed = true
        armedAt = System.currentTimeMillis()
        scanned.clear()
    }
}
class ReaderService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var scanning = false
    private var fails = 0
    private var path = ""
    private var passes = 0
    private var stagnant = 0
    private val rows = LinkedHashMap<String, Row>()
    private val kick = Runnable { startScan() }
    private val protRx = Regex("/Android/(data|obb)(/.*)?$")
    private val sizeRx = Regex(
        "([0-9]+(?:[.,][0-9]+)?)\\s*(bytes|byte|KB|MB|GB|TB|B|بايت|ك\\.?\\s?ب\\.?|كيلوبايت|م\\.?\\s?ب\\.?|ميغابايت|ميجابايت|ج\\.?\\s?ب\\.?|جيجابايت|غيغابايت|ت\\.?\\s?ب\\.?|تيرابايت)(?![A-Za-z])",
        RegexOption.IGNORE_CASE
    )
    private val cntRx = Regex("([0-9]+)\\s*(items?|files?|عنصر|عناصر|ملف|ملفات)", RegexOption.IGNORE_CASE)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Snap.running = this
    }
    override fun onUnbind(intent: Intent?): Boolean {
        Snap.running = null
        scanning = false
        h.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }
    override fun onInterrupt() {
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Snap.armed || scanning) return
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.contains("documentsui")) return
        if (System.currentTimeMillis() - Snap.armedAt > 600000L) {
            Snap.armed = false
            return
        }
        h.removeCallbacks(kick)
        h.postDelayed(kick, 900)
    }
    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
    private fun startScan() {
        if (!Snap.armed || scanning) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString()?.contains("documentsui") != true) return
        val p = readPath(root)
        Snap.dump = "pkg=" + root.packageName + "\npath=" + p + "\n" + dumpTree(root)
        if (p == null) {
            fails++
            if (fails == 3) toast("تعذر معرفة المجلد من شاشة Files — ارجع لخريطة المساحة واضغط 👁 مطولًا لنسخ التشخيص")
            return
        }
        if (!protRx.containsMatchIn(p)) return
        if (Snap.scanned.contains(p)) return
        if (p == Snap.base && !Snap.returnOnBase) return
        path = p
        rows.clear()
        passes = 0
        stagnant = 0
        scanning = true
        toast("👁 جارٍ قراءة المجلد…")
        rewind(0)
    }
    private fun readPath(root: AccessibilityNodeInfo): String? {
        val ext = Environment.getExternalStorageDirectory().path
        val seg = ArrayList<String>()
        crumbs(root, seg, false)
        val i = seg.indexOfFirst { it.equals("Android", true) }
        if (i >= 0) {
            val parts = seg.drop(i).filter { it != ">" && it != "›" && it != "/" && it != "<" }
            return ext + "/" + parts.joinToString("/")
        }
        val t = title(root) ?: return null
        val last = Snap.last
        if (last.isNotEmpty()) {
            val lf = File(last)
            if (lf.name == t) return last
            val par = lf.parentFile
            if (par != null && par.name == t) return par.path
            val kids = Snap.dirs[last]
            if (kids != null && kids.any { it.name == t }) return "$last/$t"
        }
        if (t == "data" || t == "obb") return "$ext/Android/$t"
        return null
    }
    private fun crumbs(n: AccessibilityNodeInfo, out: MutableList<String>, inside: Boolean) {
        val id = (n.viewIdResourceName ?: "").lowercase()
        val ins = inside || id.contains("breadcrumb")
        if (ins) {
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) out.add(t.trim())
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            crumbs(c, out, ins)
        }
    }
    private fun title(root: AccessibilityNodeInfo): String? {
        val out = ArrayList<String>()
        titleWalk(root, out, false)
        return out.firstOrNull()
    }
    private fun titleWalk(n: AccessibilityNodeInfo, out: MutableList<String>, inside: Boolean) {
        val id = (n.viewIdResourceName ?: "").lowercase()
        val ins = inside || id.contains("toolbar") || id.contains("action_bar") || id.contains("app_bar")
        if (ins && n.className?.toString()?.contains("TextView") == true) {
            val t = n.text?.toString()
            if (!t.isNullOrBlank()) out.add(t.trim())
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            titleWalk(c, out, ins)
        }
    }
    private fun lists(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val id = (n.viewIdResourceName ?: "").lowercase()
        val cn = n.className?.toString() ?: ""
        val bad = id.contains("breadcrumb") || id.contains("chip") || id.contains("roots") || id.contains("drawer") || id.contains("tab")
        if (n.isScrollable && !bad && (cn.contains("RecyclerView") || cn.contains("ListView") || cn.contains("GridView"))) out.add(n)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            lists(c, out)
        }
    }
    private fun listNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString()?.contains("documentsui") != true) return null
        val c = ArrayList<AccessibilityNodeInfo>()
        lists(root, c)
        return c.maxByOrNull { r ->
            val b = Rect()
            r.getBoundsInScreen(b)
            b.height()
        }
    }
    private fun rewind(n: Int) {
        val l = listNode()
        if (l != null && n < 60 && l.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            h.postDelayed({ rewind(n + 1) }, 120)
        } else {
            h.postDelayed({ step() }, 250)
        }
    }
    private fun step() {
        val rt = rootInActiveWindow
        if (rt == null || readPath(rt) != path) {
            scanning = false
            return
        }
        val l = listNode()
        if (l == null) {
            finish(false)
            return
        }
        val before = rows.size
        for (i in 0 until l.childCount) {
            val c = l.getChild(i) ?: continue
            val r = parseRow(c) ?: continue
            if (!rows.containsKey(r.name)) rows[r.name] = r
        }
        passes++
        if (rows.size == before) stagnant++ else stagnant = 0
        val moved = stagnant < 3 && passes < 250 && l.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (moved) h.postDelayed({ step() }, 350) else finish(true)
    }
    private fun finish(ok: Boolean) {
        scanning = false
        if (!ok) {
            toast("تعذر قراءة قائمة الملفات — اضغط 👁 مطولًا في خريطة المساحة لنسخ التشخيص")
            return
        }
        val list = ArrayList<Row>(rows.values)
        Snap.dirs[path] = list
        Snap.scanned.add(path)
        Snap.last = path
        Snap.armed = false
        toast("✅ تمت قراءة " + list.size + " عنصر")
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            i.putExtra("snap", path)
            startActivity(i)
        } catch (e: Exception) {
            toast("تعذر الرجوع للتطبيق — افتحه يدويًا")
        }
    }
    private fun texts(n: AccessibilityNodeInfo, out: MutableList<Pair<String, String>>) {
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) out.add(Pair(n.viewIdResourceName ?: "", t.trim()))
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            texts(c, out)
        }
    }
    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> '.'
                    '٬' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
    private fun toBytes(num: String, unit: String): Long {
        val v = num.replace(',', '.').toDoubleOrNull() ?: 0.0
        val u = unit.lowercase()
        val f = when {
            u.startsWith("k") || u.startsWith("ك") -> 1024.0
            u.startsWith("m") || u.startsWith("م") -> 1024.0 * 1024.0
            u.startsWith("g") || u.startsWith("ج") || u.startsWith("غ") -> 1024.0 * 1024.0 * 1024.0
            u.startsWith("t") || u.startsWith("ت") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (v * f).toLong()
    }
    private fun parseRow(c: AccessibilityNodeInfo): Row? {
        val ts = ArrayList<Pair<String, String>>()
        texts(c, ts)
        if (ts.isEmpty()) return null
        val np = ts.firstOrNull { it.first.endsWith("/title") } ?: ts[0]
        var size = -1L
        var count = -1
        var date = ""
        for (p in ts) {
            if (p === np) continue
            val t = norm(p.second)
            val sm = sizeRx.find(t)
            if (sm != null && size < 0) {
                size = toBytes(sm.groupValues[1], sm.groupValues[2])
                val rest = t.replace(sm.value, "").trim(' ', ',', '•', '·', '|', '،')
                if (rest.isNotEmpty() && date.isEmpty()) date = rest
                continue
            }
            val cm = cntRx.find(t)
            if (cm != null && count < 0) {
                count = cm.groupValues[1].toIntOrNull() ?: 0
                continue
            }
            if (date.isEmpty()) date = p.second
        }
        return Row(np.second, size < 0, maxOf(size, 0L), maxOf(count, 0), date)
    }
    private fun dumpTree(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        dumpNode(root, 0, sb)
        return sb.toString()
    }
    private fun dumpNode(n: AccessibilityNodeInfo, d: Int, sb: StringBuilder) {
        if (sb.length > 14000) return
        for (k in 0 until d) sb.append(' ')
        sb.append((n.className?.toString() ?: "?").substringAfterLast('.'))
        val id = n.viewIdResourceName
        if (id != null) sb.append(" #").append(id.substringAfter(":id/"))
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) sb.append(" \"").append(t.take(60)).append('"')
        val cd = n.contentDescription?.toString()
        if (!cd.isNullOrBlank()) sb.append(" [").append(cd.take(40)).append(']')
        if (n.isScrollable) sb.append(" SCROLL")
        sb.append('\n')
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            dumpNode(c, d + 1, sb)
        }
    }
}
