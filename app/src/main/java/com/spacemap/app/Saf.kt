package com.spacemap.app
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
class Grant(val root: File, val tree: Uri)
class Saf(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("saf", Context.MODE_PRIVATE)
    private val protRx = Regex("/Android/(data|obb)(/.*)?$")
    fun prot(f: File): Boolean = protRx.containsMatchIn(f.path)
    fun rootOf(docId: String): File {
        val vol = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
        return if (rel.isEmpty()) base else File(base, rel)
    }
    fun grants(): List<Grant> {
        val out = ArrayList<Grant>()
        val set = prefs.getStringSet("trees", emptySet()) ?: emptySet()
        for (s in set) {
            try {
                val t = Uri.parse(s)
                out.add(Grant(rootOf(DocumentsContract.getTreeDocumentId(t)), t))
            } catch (e: Exception) {
            }
        }
        return out
    }
    fun find(f: File): Grant? = grants().firstOrNull {
        f.path == it.root.path || f.path.startsWith(it.root.path + "/")
    }
    fun save(u: Uri): Boolean {
        return try {
            ctx.contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val set = HashSet<String>(prefs.getStringSet("trees", emptySet()) ?: emptySet())
            set.add(u.toString())
            prefs.edit().putStringSet("trees", set).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
    private fun docId(g: Grant, f: File): String =
        DocumentsContract.getTreeDocumentId(g.tree) + f.path.removePrefix(g.root.path)
    fun list(g: Grant, dir: File, disk: Disk): List<Entry>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(g.tree, docId(g, dir))
        val out = ArrayList<Entry>()
        try {
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val c = ctx.contentResolver.query(uri, cols, null, null, null) ?: return null
            c.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val isDir = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val f = File(dir, name)
                    val doc = DocumentsContract.buildDocumentUriUsingTree(g.tree, id)
                    val mt = it.getLong(4)
                    if (isDir) {
                        out.add(Entry(f, Kind.FOLDER, disk.cache[f.path] ?: -1L, 0, false, doc, mt))
                    } else {
                        out.add(Entry(f, kindOf(f), maxOf(it.getLong(3), 0L), 0, false, doc, mt))
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }
    fun size(g: Grant, dir: File, disk: Disk): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val hit = disk.cache[dir.path]
        if (hit != null) return hit
        val kids = list(g, dir, disk) ?: return 0L
        var t = 0L
        for (k in kids) t += if (k.kind == Kind.FOLDER) size(g, k.file, disk) else k.size
        disk.cache[dir.path] = t
        return t
    }
    fun delete(doc: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, doc)
    } catch (e: Exception) {
        false
    }
}
