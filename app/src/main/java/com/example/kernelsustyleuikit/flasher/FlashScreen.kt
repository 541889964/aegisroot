package com.example.kernelsustyleuikit.flasher

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FlashScreen(bottomInnerPadding: Dp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var progress by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf("等待操作") }
    val images: SnapshotStateList<File> = remember { mutableStateListOf() }
    var selected by remember { mutableStateOf<File?>(null) }
    var partition by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backups: SnapshotStateList<File> = remember { mutableStateListOf() }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(AppBus.recommendedPartition, AppBus.connected) {
        if (AppBus.connected && AppBus.recommendedPartition.isNotEmpty()) {
            partition = AppBus.recommendedPartition
        }
    }

    LaunchedEffect(Unit) {
        if (!loaded) {
            loaded = true
            val list = withContext(Dispatchers.IO) {
                File("/sdcard/Download").listFiles { f -> f.extension.equals("img", true) }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
            }
            images.clear(); images.addAll(list)
        }
    }

    LaunchedEffect(reload) {
        val list = withContext(Dispatchers.IO) { Backup.list() }
        backups.clear(); backups.addAll(list)
    }

    fun extractOne(p: String) {
        val fm = AppBus.fm ?: return
        scope.launch {
            try {
                AppBus.busy = true; progress = 0; stage = "提取 " + p
                val out = Backup.path(p)
                LogBus.add("extract " + p)
                withContext(Dispatchers.IO) {
                    out.outputStream().use { os -> fm.fetch(p, os) { pr -> progress = pr } }
                }
                if (out.length() < 1024) { out.delete(); throw Exception("too small") }
                Backup.writeSha(out)
                LogBus.add("OK " + p); stage = "done"; reload++
            } catch (e: Exception) {
                LogBus.add("fail: " + (e.message ?: "")); stage = "fail"
            } finally { AppBus.busy = false }
        }
    }

    fun smartExtract() {
        val fm = AppBus.fm ?: return
        scope.launch {
            try {
                AppBus.busy = true; progress = 0; stage = "分析设备"
                val info = withContext(Dispatchers.IO) { PartitionDetector.detect(fm) }
                AppBus.hasInitBoot = fm.hasInitBoot()
                AppBus.detectedAndroid = info.androidVersion
                AppBus.recommendedPartition = info.recommended
                LogBus.add(info.androidVersion + " -> " + info.recommended)
                val out = Backup.path(info.recommended)
                stage = "提取 " + info.recommended
                withContext(Dispatchers.IO) {
                    out.outputStream().use { os -> fm.fetch(info.recommended, os) { p -> progress = p } }
                }
                if (out.length() < 1024) { out.delete(); throw Exception("too small") }
                Backup.writeSha(out)
                LogBus.add("OK " + info.recommended); stage = "done"; reload++
            } catch (e: Exception) {
                LogBus.add("fail: " + (e.message ?: "")); stage = "fail"
            } finally { AppBus.busy = false }
        }
    }

    fun doFlash() {
        val fm = AppBus.fm ?: return
        val img = selected ?: return
        val part = partition.ifBlank { "boot" }
        scope.launch {
            try {
                AppBus.busy = true; progress = 0; stage = "校验"
                LogBus.add(">>> " + img.name + " -> " + part)
                if (img.length() < 1024) throw Exception("file too small")
                val ok = withContext(Dispatchers.IO) { IoUtil.isAndroidImage(img) }
                if (!ok) throw Exception("not valid image")
                stage = "刷入中"
                var att = 0; var done = false; var lastMsg = ""
                while (att < 3 && !done) {
                    att++
                    try {
                        withContext(Dispatchers.IO) { fm.flashFile(part, img) { p -> progress = p } }
                        done = true
                    } catch (e: Exception) {
                        lastMsg = e.message ?: ""
                        if (att < 3) { progress = 0; delay(800) }
                    }
                }
                if (!done) throw Exception(lastMsg)
                stage = "done"; LogBus.add("OK " + part)
                HistoryLogger.log("flash", part, "ok")
                delay(500)
                withContext(Dispatchers.IO) { fm.reboot() }
            } catch (e: Exception) {
                stage = "fail"; LogBus.add("error: " + (e.message ?: ""))
                HistoryLogger.log("flash", part, "fail")
            } finally { AppBus.busy = false }
        }
    }

    fun doRestore(img: File) {
        val fm = AppBus.fm ?: return
        val part = Backup.namePartition(img)
        scope.launch {
            try {
                AppBus.busy = true; progress = 0; stage = "恢复 " + part
                val v = withContext(Dispatchers.IO) { Backup.verify(img) }
                if (!v.first) throw Exception("checksum fail")
                LogBus.add("restore " + part + " <- " + img.name)
                withContext(Dispatchers.IO) { fm.flashFile(part, img) { p -> progress = p } }
                stage = "done"
                HistoryLogger.log("restore", part, "ok")
            } catch (e: Exception) {
                LogBus.add("fail: " + (e.message ?: "")); stage = "fail"
            } finally { AppBus.busy = false }
        }
    }

    fun doErase(p: String) {
        val fm = AppBus.fm ?: return
        scope.launch {
            try {
                AppBus.busy = true
                val ok = withContext(Dispatchers.IO) { fm.erase(p) }
                LogBus.add((if (ok) "OK " else "fail ") + p)
            } catch (e: Exception) { LogBus.add("fail: " + (e.message ?: "")) }
            finally { AppBus.busy = false }
        }
    }

    fun doReboot(target: String?) {
        val fm = AppBus.fm ?: return
        scope.launch {
            try { withContext(Dispatchers.IO) { fm.reboot(target) } }
            catch (e: Exception) { LogBus.add("fail: " + (e.message ?: "")) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomInnerPadding + 16.dp)
    ) {
        item(key = "dev") {
            AegisCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (AppBus.connected) Color(0xFF36D167) else Color(0xFFF72727)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (AppBus.connected) "已连接设备" else "等待连接",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (AppBus.connected)
                            Text("序列号: " + AppBus.serial, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        try {
                            val act = (ctx as Activity)
                            val m = act.javaClass.getMethod("autoDetect")
                            m.invoke(act)
                        } catch (_: Exception) {}
                    }) { Text("刷新", fontSize = 12.sp) }
                }
                if (AppBus.connected) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        KV("产品", AppBus.product)
                        KV("系统", AppBus.detectedAndroid)
                        KV("推荐分区", AppBus.recommendedPartition)
                    }
                } else {
                    Text("让 B 进 fastboot 模式，OTG 连接", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }

        item(key = "etitle") { SectionLabel("智能提取") }
        item(key = "ecard") {
            AegisCard {
                AegisItem("智能分析并提取",
                    sub = if (AppBus.detectedAndroid.isEmpty()) "连接后自动判断 Android 版本"
                          else AppBus.detectedAndroid + " -> " + AppBus.recommendedPartition,
                    enabled = AppBus.connected && !AppBus.busy,
                    onClick = { smartExtract() })
                AegisItem("提取 init_boot", enabled = AppBus.connected && !AppBus.busy,
                    onClick = { extractOne("init_boot") })
                AegisItem("提取 boot", enabled = AppBus.connected && !AppBus.busy,
                    onClick = { extractOne("boot") })
            }
        }

        item(key = "ftitle") { SectionLabel("刷入 Root") }
        item(key = "fcard") {
            AegisCard {
                if (images.isEmpty()) {
                    Text("未找到 .img 文件\n把修补后的镜像放到 /sdcard/Download/",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                } else {
                    images.forEach { f ->
                        val sel = selected?.absolutePath == f.absolutePath
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selected = f }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = sel, onClick = { selected = f })
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(IoUtil.sizeHuman(f.length()), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (selected != null) {
            item(key = "ptitle") { SectionLabel("目标分区") }
            item(key = "pcard") {
                AegisCard {
                    val parts = if (AppBus.hasInitBoot)
                        listOf("init_boot", "boot", "recovery", "vbmeta")
                    else listOf("boot", "recovery", "vbmeta")
                    Row(Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        parts.forEach { p ->
                            FilterChip(
                                selected = partition == p,
                                onClick = { partition = p },
                                label = { Text(p, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
            item(key = "fbtn") {
                Button(
                    onClick = { confirm = true },
                    enabled = !AppBus.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("刷入 " + partition.ifBlank { "boot" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        item(key = "btitle") { SectionLabel("备份列表") }
        item(key = "bcard") {
            AegisCard {
                if (backups.isEmpty()) {
                    Text("暂无备份", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                } else {
                    backups.forEach { f ->
                        val p = Backup.namePartition(f)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(IoUtil.sizeHuman(f.length()), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { restoreTarget = f; confirmRestore = true }) { Text("恢复") }
                            TextButton(onClick = { Backup.delete(f); reload++ }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        item(key = "ertitle") { SectionLabel("擦除分区") }
        item(key = "ercard") {
            AegisCard {
                listOf("userdata", "cache", "metadata", "misc", "frp").forEach { p ->
                    AegisItem("erase " + p,
                        sub = if (p == "userdata") "会清空所有数据" else null,
                        danger = p == "userdata",
                        enabled = AppBus.connected && !AppBus.busy,
                        onClick = { doErase(p) })
                }
            }
        }

        item(key = "rtitle") { SectionLabel("重启") }
        item(key = "rcard") {
            AegisCard {
                AegisItem("重启到系统", enabled = AppBus.connected, onClick = { doReboot(null) })
                AegisItem("重启到 Bootloader", enabled = AppBus.connected, onClick = { doReboot("bootloader") })
                AegisItem("重启到 Recovery", enabled = AppBus.connected, onClick = { doReboot("recovery") })
            }
        }

        item(key = "prog") {
            AegisCard {
                Column(Modifier.padding(16.dp)) {
                    LinearProgressIndicator(progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stage, fontSize = 12.sp, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$progress%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item(key = "ltitle") { SectionLabel("日志") }
        item(key = "lcard") {
            AegisCard {
                Text(LogBus.text.ifEmpty { "暂无日志" }, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }

    if (confirm && selected != null) {
        val img = selected!!
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认刷入？") },
            text = {
                Text("将把 " + img.name + " (" + IoUtil.sizeHuman(img.length()) +
                    ") 刷入分区 " + partition.ifBlank { "boot" } + "，完成后自动重启。")
            },
            confirmButton = {
                TextButton(onClick = { doFlash(); confirm = false }) { Text("开始刷入") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }

    if (confirmRestore && restoreTarget != null) {
        val img = restoreTarget!!
        val p = Backup.namePartition(img)
        AlertDialog(
            onDismissRequest = { confirmRestore = false; restoreTarget = null },
            title = { Text("恢复 " + p + "？") },
            text = { Text("将从 " + img.name + " 恢复分区 " + p) },
            confirmButton = {
                TextButton(onClick = {
                    doRestore(img); confirmRestore = false; restoreTarget = null
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmRestore = false; restoreTarget = null
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AegisCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column(content = content) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun AegisItem(
    title: String, sub: String? = null,
    enabled: Boolean = true, danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                })
            if (sub != null) Text(sub, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "-" }, fontSize = 12.sp)
    }
}
