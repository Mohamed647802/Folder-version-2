package com.spacemap.app
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
object Fmt {
    fun size(b: Long): String {
        if (b < 1024L) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
enum class Kind(val icon: String, val color: Int, val label: String) {
    FOLDER("📁", 0xFF3B82F6.toInt(), "مجلد"),
    IMAGE("🖼️", 0xFF10B981.toInt(), "صورة"),
    VIDEO("🎬", 0xFFEF4444.toInt(), "فيديو"),
    AUDIO("🎵", 0xFFA855F7.toInt(), "صوت"),
    DOC("📄", 0xFFF59E0B.toInt(), "مستند"),
    ARCHIVE("📦", 0xFFEC4899.toInt(), "أرشيف"),
    APK("📱", 0xFF22C55E.toInt(), "تطبيق"),
    OTHER("📎", 0xFF64748B.toInt(), "ملف")
}
fun kindOf(f: File): Kind {
    if (f.isDirectory) return Kind.FOLDER
    return when (f.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "dng", "raw" -> Kind.IMAGE
        "mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v" -> Kind.VIDEO
        "mp3", "wav", "aac", "flac", "ogg", "m4a", "opus", "amr", "wma" -> Kind.AUDIO
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "epub", "json", "xml", "log", "html" -> Kind.DOC
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> Kind.ARCHIVE
        "apk", "xapk", "apks", "obb" -> Kind.APK
        else -> Kind.OTHER
    }
}
class Entry(
    val file: File,
    val kind: Kind,
    var size: Long,
    val count: Int,
    val locked: Boolean,
    val doc: Uri? = null,
    val mtime: Long = file.lastModified()
) {
    var label: String? = null
    var note: String = ""
    val hidden: Boolean get() = file.name.startsWith(".")
}
object Fs {
    fun isLink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (e: Exception) {
        false
    }
    fun deleteTree(f: File): Boolean {
        var ok = true
        if (f.isDirectory && !isLink(f)) {
            f.listFiles()?.forEach { if (!deleteTree(it)) ok = false }
        }
        return (f.delete() || !f.exists()) && ok
    }
}
object Trash {
    fun dir(): File {
        val d = File(Environment.getExternalStorageDirectory(), ".SpaceMapTrash")
        d.mkdirs()
        try {
            File(d, ".nomedia").createNewFile()
        } catch (e: Exception) {
        }
        return d
    }
    fun orig(box: File): String = try {
        File(box, "orig.txt").readText()
    } catch (e: Exception) {
        box.name
    }
    fun moveIn(f: File): File? {
        try {
            if ((dir().absolutePath + "/").startsWith(f.absolutePath + "/")) return null
            val box = File(dir(), System.nanoTime().toString())
            val data = File(box, "data")
            if (!data.mkdirs()) return null
            File(box, "orig.txt").writeText(f.absolutePath)
            val t = File(data, f.name)
            val ok = f.renameTo(t) || f.copyRecursively(t, true).also { if (it) Fs.deleteTree(f) }
            if (ok) return t
            Fs.deleteTree(box)
        } catch (e: Exception) {
        }
        return null
    }
    fun restore(box: File): Boolean {
        try {
            val src = File(box, "data").listFiles()?.firstOrNull() ?: return false
            val o = File(orig(box))
            val ext = if (o.extension.isEmpty()) "" else "." + o.extension
            var t = o
            var n = 0
            while (t.exists()) {
                n++
                t = File(o.parentFile, o.nameWithoutExtension + "_" + n + ext)
            }
            t.parentFile?.mkdirs()
            val ok = src.renameTo(t) || src.copyRecursively(t, true).also { if (it) Fs.deleteTree(src) }
            if (ok) Fs.deleteTree(box)
            return ok
        } catch (e: Exception) {
            return false
        }
    }
}
class Disk(ctx: Context) {
    val cache = ConcurrentHashMap<String, Long>()
    val saf = Saf(ctx)
    private val skip = setOf("/proc", "/sys", "/dev")
    fun children(dir: File): List<Entry>? {
        if (saf.prot(dir)) {
            val g = saf.find(dir)
            if (g != null) return saf.list(g, dir, this)
        }
        val kids = dir.listFiles() ?: return null
        return kids.map { entry(it) }
    }
    fun entry(f: File): Entry {
        if (saf.prot(f) && saf.find(f) != null) return Entry(f, Kind.FOLDER, cache[f.path] ?: -1L, 0, false)
        if (!f.isDirectory) return Entry(f, kindOf(f), f.length(), 0, false)
        val l = f.list()
        val size = if (l == null) 0L else (cache[f.path] ?: -1L)
        return Entry(f, Kind.FOLDER, size, l?.size ?: 0, l == null)
    }
    fun size(f: File): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (saf.prot(f)) {
            val g = saf.find(f)
            if (g != null) return saf.size(g, f, this)
        }
        if (!f.isDirectory) return f.length()
        if (Fs.isLink(f)) return 0L
        val key = f.path
        val hit = cache[key]
        if (hit != null) return hit
        if (key in skip) return 0L
        val kids = f.listFiles() ?: return 0L
        var total = 0L
        for (k in kids) total += size(k)
        cache[key] = total
        return total
    }
    fun removed(f: File, size: Long) {
        val p = f.path
        val dead = cache.keys.filter { it == p || it.startsWith("$p/") }
        for (k in dead) cache.remove(k)
        adjust(f.parentFile, -size)
    }
    fun adjust(d: File?, delta: Long) {
        var x = d
        while (x != null) {
            val v = cache[x.path]
            if (v != null) cache[x.path] = maxOf(0L, v + delta)
            x = x.parentFile
        }
    }
}
