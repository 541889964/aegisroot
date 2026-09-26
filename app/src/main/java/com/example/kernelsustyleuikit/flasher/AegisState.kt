package com.example.kernelsustyleuikit.flasher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppBus {
    var fm: FastbootManager? by mutableStateOf(null)
    var serial: String by mutableStateOf("")
    var product: String by mutableStateOf("")
    var hasInitBoot: Boolean by mutableStateOf(false)
    var connected: Boolean by mutableStateOf(false)
    var busy: Boolean by mutableStateOf(false)
    var detectedAndroid: String by mutableStateOf("")
    var recommendedPartition: String by mutableStateOf("")
}

object LogBus {
    var text: String by mutableStateOf("")
        private set
    private val sb = StringBuilder()
    private const val MAX = 25

    fun add(line: String) {
        sb.append(line).append('\n')
        val lines = sb.split('\n')
        if (lines.size > MAX) {
            sb.setLength(0)
            for (i in (lines.size - MAX) until lines.size) {
                if (lines[i].isNotEmpty()) sb.append(lines[i]).append('\n')
            }
        }
        text = sb.toString()
    }
    fun clear() { sb.setLength(0); text = "" }
}

object IoUtil {
    fun readN(s: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = s.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }
    fun sizeHuman(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
        n >= 1024L -> "%.1f KB".format(n / 1024.0)
        else -> n.toString() + " B"
    }
    fun isAndroidImage(f: File): Boolean = try {
        f.inputStream().use {
            val b = readN(it, 8)
            b.size == 8 && String(b, Charsets.US_ASCII) == "ANDROID!"
        }
    } catch (_: Exception) { false }
    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(8192)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object Backup {
    private const val DIR = "/sdcard/Download/aegis_backup"
    fun dir(): File { val d = File(DIR); if (!d.exists()) d.mkdirs(); return d }
    fun list(): List<File> = dir().listFiles { f -> f.extension.equals("img", true) }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun path(partition: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir(), partition + "_" + ts + ".img")
    }
    fun writeSha(img: File) {
        try { File(img.absolutePath + ".sha256").writeText(IoUtil.sha256(img)) } catch (_: Exception) {}
    }
    fun delete(img: File) {
        try { img.delete() } catch (_: Exception) {}
        try { File(img.absolutePath + ".sha256").delete() } catch (_: Exception) {}
    }
    fun namePartition(img: File): String = img.name.substringBefore("_")
    fun verify(img: File): Triple<Boolean, String, String> {
        if (!img.exists()) return Triple(false, "not exist", "")
        val shaFile = File(img.absolutePath + ".sha256")
        val actual = IoUtil.sha256(img)
        if (!shaFile.exists()) return Triple(true, "no checksum", actual)
        val expected = shaFile.readText().trim()
        return if (expected == actual) Triple(true, "ok", actual)
               else Triple(false, "mismatch", actual)
    }
}

object HistoryLogger {
    private const val FILE = "/sdcard/Download/aegis_history.log"
    fun log(type: String, partition: String, result: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            File(FILE).appendText(ts + " | " + type + " | " + partition + " | " + result + "\n")
        } catch (_: Exception) {}
    }
}
