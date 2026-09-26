package com.example.kernelsustyleuikit.flasher

import android.hardware.usb.*
import java.io.OutputStream

class FastbootException(msg: String) : Exception(msg)

class FastbootManager(private val usbManager: UsbManager, val device: UsbDevice) {
    private var connection: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var iface: UsbInterface? = null
    var serial: String = "unknown"
        private set

    fun open(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 0xFF && itf.interfaceSubclass == 0x42 && itf.interfaceProtocol == 0x03) {
                iface = itf
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                    }
                }
                break
            }
        }
        if (iface == null || epIn == null || epOut == null) return false
        connection = usbManager.openDevice(device) ?: return false
        if (!connection!!.claimInterface(iface, true)) {
            connection!!.close(); connection = null; return false
        }
        serial = connection!!.serial ?: "unknown"
        return true
    }

    fun close() {
        try {
            connection?.let { c -> iface?.let { c.releaseInterface(it) }; c.close() }
        } catch (_: Exception) {}
        connection = null
    }

    private fun write(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val n = connection!!.bulkTransfer(epOut, bytes.copyOfRange(sent, bytes.size), bytes.size - sent, 4000)
            if (n <= 0) throw FastbootException("USB write failed")
            sent += n
        }
    }

    private fun read(): String {
        val buf = ByteArray(512)
        val n = connection!!.bulkTransfer(epIn, buf, buf.size, 4000)
        if (n <= 0) throw FastbootException("USB read timeout")
        return String(buf, 0, n, Charsets.US_ASCII)
    }

    private fun parse(s: String): Pair<Int, String> = when {
        s.startsWith("OKAY") -> 0 to s.substring(4).trim()
        s.startsWith("FAIL") -> 1 to s.substring(4).trim()
        s.startsWith("INFO") -> 2 to s.substring(4).trim()
        s.startsWith("DATA") -> 3 to s.substring(4).trim()
        else -> 4 to s
    }

    fun execute(cmd: String): Pair<Boolean, String> {
        write(cmd.toByteArray(Charsets.US_ASCII))
        val info = StringBuilder()
        while (true) {
            val (code, msg) = parse(read())
            when (code) {
                2 -> info.append(msg).append('\n')
                0 -> {
                    if (msg.isNotEmpty()) info.append(msg)
                    return true to info.toString()
                }
                1 -> return false to msg
                else -> return false to "unknown: " + msg
            }
        }
    }

    fun getVar(name: String): String = try {
        val (_, msg) = execute("getvar:" + name)
        msg.lines().lastOrNull { it.contains(":") }?.substringAfter(":")?.trim() ?: ""
    } catch (_: Exception) { "" }

    fun hasInitBoot(): Boolean {
        val s = getVar("partition-size:init_boot")
        return s.isNotEmpty() && s != "0"
    }

    fun flashFile(partition: String, img: java.io.File, onProgress: (Int) -> Unit) {
        val total = img.length()
        val sizeHex = total.toString(16).padStart(8, '0')
        val (ok1, msg1) = execute("download:" + sizeHex)
        if (!ok1) throw FastbootException("download failed: " + msg1)
        img.inputStream().use { inp ->
            val buf = ByteArray(512 * 1024)
            var sent = 0L
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                write(buf.copyOfRange(0, n))
                sent += n
                if (total > 0) onProgress((sent * 100 / total).toInt().coerceIn(0, 100))
            }
        }
        val (code2, msg2) = parse(read())
        if (code2 != 0) throw FastbootException("download confirm failed: " + msg2)
        val (ok3, msg3) = execute("flash:" + partition)
        if (!ok3) throw FastbootException("flash failed: " + msg3)
    }

    fun fetch(partition: String, out: OutputStream, onProgress: (Int) -> Unit) {
        val sizeStr = getVar("partition-size:" + partition)
        val total = sizeStr.toLongOrNull(16) ?: sizeStr.toLongOrNull() ?: 0L
        write(("fetch:" + partition).toByteArray(Charsets.US_ASCII))
        var got = 0L
        while (true) {
            val (code, msg) = parse(read())
            when (code) {
                2 -> {}
                3 -> {
                    val want = msg.toIntOrNull(16) ?: 0
                    var recv = 0
                    val buf = ByteArray(64 * 1024)
                    while (recv < want) {
                        val n = minOf(buf.size, want - recv)
                        val g = connection!!.bulkTransfer(epIn, buf, n, 30000)
                        if (g <= 0) throw FastbootException("read failed")
                        out.write(buf, 0, g)
                        recv += g
                        got += g
                        if (total > 0) onProgress((got * 100 / total).toInt().coerceIn(0, 100))
                    }
                    write(ByteArray(0))
                }
                0 -> return
                1 -> throw FastbootException("fetch failed: " + msg)
                else -> throw FastbootException("unexpected: " + msg)
            }
        }
    }

    fun reboot(target: String? = null) {
        execute(if (target.isNullOrEmpty()) "reboot" else "reboot-" + target)
    }

    fun erase(p: String): Boolean = execute("erase:" + p).first
}

object PartitionDetector {
    data class Info(val androidVersion: String, val recommended: String, val reason: String)
    fun detect(fm: FastbootManager): Info {
        val hasInit = fm.hasInitBoot()
        return if (hasInit)
            Info("Android 13+", "init_boot", "检测到 init_boot，Root 补丁应刷此分区")
        else
            Info("Android 12 及以下", "boot", "无 init_boot，Root 补丁在 boot 中")
    }
}
