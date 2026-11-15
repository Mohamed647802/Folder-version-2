package com.spacemap.app
import android.content.Context
import java.io.File
class ShellService() : IShell.Stub() {
    constructor(context: Context) : this()
    override fun destroy() {
        System.exit(0)
    }
    override fun list(path: String): Array<String> {
        val kids = File(path).listFiles() ?: return emptyArray<String>()
        return Array(kids.size) { i ->
            val k = kids[i]
            val d = k.isDirectory
            (if (d) "d" else "f") + "|" + (if (d) 0L else k.length()) + "|" + k.lastModified() + "|" + k.name
        }
    }
    override fun size(path: String): Long = total(File(path))
    private fun total(f: File): Long {
        if (Fs.isLink(f)) return 0L
        if (!f.isDirectory) return f.length()
        var t = 0L
        f.listFiles()?.forEach { t += total(it) }
        return t
    }
    override fun delete(path: String): Boolean = Fs.deleteTree(File(path))
    override fun copy(src: String, dst: String): Boolean = try {
        val d = File(dst)
        d.parentFile?.mkdirs()
        File(src).inputStream().use { i -> d.outputStream().use { o -> i.copyTo(o) } }
        d.setReadable(true, false)
        true
    } catch (e: Exception) {
        false
    }
}
