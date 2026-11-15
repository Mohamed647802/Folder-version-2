package com.spacemap.app
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.File
object Shz {
    @Volatile
    var svc: IShell? = null
    private var conn: ServiceConnection? = null
    fun ready(): Boolean = svc != null
    fun alive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }
    fun granted(): Boolean = try {
        alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }
    fun connect(pkg: String, onChange: () -> Unit) {
        if (svc != null) {
            onChange()
            return
        }
        if (conn != null) return
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                svc = if (binder != null && binder.pingBinder()) IShell.Stub.asInterface(binder) else null
                onChange()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                svc = null
                conn = null
            }
        }
        conn = c
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(pkg, ShellService::class.java.name))
                .daemon(false)
                .processNameSuffix("sh")
                .version(1)
            Shizuku.bindUserService(args, c)
        } catch (e: Throwable) {
            conn = null
        }
    }
    fun list(dir: File, disk: Disk): List<Entry>? {
        val s = svc ?: return null
        return try {
            val out = ArrayList<Entry>()
            for (line in s.list(dir.path)) {
                val p = line.split("|", limit = 4)
                if (p.size < 4) continue
                val f = File(dir, p[3])
                val mt = p[2].toLongOrNull() ?: 0L
                val e = if (p[0] == "d") {
                    Entry(f, Kind.FOLDER, disk.cache[f.path] ?: -1L, 0, false, null, mt)
                } else {
                    Entry(f, kindOf(f), p[1].toLongOrNull() ?: 0L, 0, false, null, mt)
                }
                e.priv = true
                out.add(e)
            }
            out
        } catch (e: Exception) {
            null
        }
    }
    fun size(f: File, disk: Disk): Long {
        val hit = disk.cache[f.path]
        if (hit != null) return hit
        val s = svc ?: return 0L
        return try {
            val v = s.size(f.path)
            disk.cache[f.path] = v
            v
        } catch (e: Exception) {
            0L
        }
    }
    fun delete(f: File): Boolean = try {
        svc?.delete(f.path) ?: false
    } catch (e: Exception) {
        false
    }
    fun copy(src: File, dst: File): Boolean = try {
        svc?.copy(src.path, dst.path) ?: false
    } catch (e: Exception) {
        false
    }
}
