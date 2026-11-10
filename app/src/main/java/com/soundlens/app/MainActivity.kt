package com.soundlens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.soundlens.app.audio.AudioCaptureService
import com.soundlens.app.data.Clip
import com.soundlens.app.data.Label
import com.soundlens.app.ml.Trainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SoundLensApp
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B5E7A))) {
                AppRoot(app)
            }
        }
    }
}

private enum class Tab(val label: String) {
    CAPTURE("采集"), LABELS("标注"), TRAIN("训练"), RECOGNIZE("识别")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: SoundLensApp) {
    var tab by remember { mutableStateOf(Tab.CAPTURE) }
    val mode by Bus.mode.collectAsState()

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val status = when (mode) {
                        CaptureMode.IDLE -> ""
                        CaptureMode.CAPTURE -> " · 采集中"
                        CaptureMode.RECOGNIZE -> " · 识别中"
                    }
                    Text("声纹透镜$status")
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val icon = when (t) {
                        Tab.CAPTURE -> Icons.Filled.PlayArrow
                        Tab.LABELS -> Icons.Filled.Edit
                        Tab.TRAIN -> Icons.Filled.Star
                        Tab.RECOGNIZE -> Icons.Filled.Search
                    }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.CAPTURE -> CaptureScreen(app)
                Tab.LABELS -> LabelScreen(app)
                Tab.TRAIN -> TrainScreen(app)
                Tab.RECOGNIZE -> RecognizeScreen(app)
            }
        }
    }
}

// ---------- permission helper ----------

@Composable
fun rememberMicPermission(): () -> Boolean {
    val context = LocalContext.current
    val needed = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    return {
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) launcher.launch(needed.toTypedArray())
        allGranted
    }
}

@Composable
fun ErrorBanner() {
    val err by Bus.error.collectAsState()
    if (err != null) {
        Card(
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err!!, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Bus.error.value = null }) { Text("知道了") }
            }
        }
    }
}

// ---------- Tab 1: capture ----------

@Composable
fun CaptureScreen(app: SoundLensApp) {
    val mode by Bus.mode.collectAsState()
    val count by Bus.sessionClipCount.collectAsState()
    val captureLabelId by Bus.captureLabelId.collectAsState()
    val labels = remember { mutableStateOf(listOf<Label>()) }
    val askPermission = rememberMicPermission()
    val context = LocalContext.current

    LaunchedEffect(mode) {
        if (mode == CaptureMode.IDLE) {
            labels.value = withContext(Dispatchers.IO) { app.db.getLabels() }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorBanner()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("采集状态", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (mode) {
                        CaptureMode.IDLE -> "未运行。开启后 app 会在后台以约每秒一片的速度录制环境声音并保存为样本。"
                        CaptureMode.CAPTURE -> "采集中，本会话已保存 $count 个片段。去【标注】页给样本打标签。"
                        CaptureMode.RECOGNIZE -> "识别运行中，请切到【识别】页查看结果。"
                    }
                )
                if (mode == CaptureMode.CAPTURE) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        Text("采集时自动打上标签：", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            FilterChip(
                selected = captureLabelId == 0,
                onClick = { Bus.captureLabelId.value = 0 },
                label = { Text("未标注") }
            )
            labels.value.forEach { l ->
                FilterChip(
                    selected = captureLabelId == l.id,
                    onClick = { Bus.captureLabelId.value = l.id },
                    label = { Text(l.name) }
                )
            }
        }
        Text(
            "提示：先选好标签再开启采集，现场采集的样本会直接带上标签；留空也可以稍后在【标注】页补标。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val running = mode != CaptureMode.IDLE
            Button(
                enabled = !running,
                onClick = {
                    if (askPermission()) {
                        context.startForegroundService(
                            Intent(context, AudioCaptureService::class.java)
                                .setAction(AudioCaptureService.ACTION_START_CAPTURE)
                                .putExtra(AudioCaptureService.EXTRA_LABEL_ID, captureLabelId)
                        )
                    }
                }
            ) { Text("开始采集") }
            OutlinedButton(
                enabled = running,
                onClick = {
                    context.startService(
                        Intent(context, AudioCaptureService::class.java)
                            .setAction(AudioCaptureService.ACTION_STOP)
                    )
                }
            ) { Text("停止") }
        }

        if (mode == CaptureMode.IDLE && labels.value.isEmpty()) {
            Text(
                "还没有标签。建议先到【标注】页创建几个环境声音类别（如：键盘声、说话声、风扇声…）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- Tab 2: labeling ----------

@Composable
fun LabelScreen(app: SoundLensApp) {
    val labels = remember { mutableStateOf(listOf<Label>()) }
    val clips = remember { mutableStateOf(listOf<Triple<Clip, String, Long>>()) }
    val newLabel = remember { mutableStateOf("") }
    val renaming = remember { mutableStateOf<Clip?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val ls = app.db.getLabels()
            val cs = app.db.getClipsWithLabel(300)
            labels.value = ls
            clips.value = cs
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { player.value?.release() }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("声音标签", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newLabel.value,
                onValueChange = { newLabel.value = it },
                label = { Text("新标签，如：键盘声") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                enabled = newLabel.value.isNotBlank(),
                onClick = {
                    val name = newLabel.value.trim()
                    newLabel.value = ""
                    scope.launch(Dispatchers.IO) {
                        app.db.addLabel(name)
                        labels.value = app.db.getLabels()
                    }
                }
            ) { Text("添加") }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            labels.value.forEach { l ->
                AssistChip(
                    onClick = {},
                    label = { Text(l.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除标签",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        app.db.deleteLabel(l.id)
                                        labels.value = app.db.getLabels()
                                    }
                                }
                        )
                    }
                )
            }
        }

        androidx.compose.material3.HorizontalDivider()
        Text("录音片段（${clips.value.size}）", style = MaterialTheme.typography.titleMedium)

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(clips.value, key = { it.first.id }) { triple ->
                val clip = triple.first
                val labelName = triple.second
                val df = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                labelName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (labelName == "未标注") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                df.format(Date(clip.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            try {
                                player.value?.release()
                                player.value = MediaPlayer().apply {
                                    setDataSource(clip.path)
                                    setOnCompletionListener { it.release() }
                                    prepare()
                                    start()
                                }
                            } catch (_: Exception) {
                            }
                        }) { Text("播放") }
                        TextButton(onClick = { renaming.value = clip }) { Text("标注") }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                app.db.deleteClip(clip.id, clip.path)
                                clips.value = app.db.getClipsWithLabel(300)
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除片段") }
                    }
                }
            }
        }
    }

    renaming.value?.let { clip ->
        AlertDialog(
            onDismissRequest = { renaming.value = null },
            title = { Text("给片段打标签") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = clip.labelId == 0,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    app.db.updateClipLabel(clip.id, 0)
                                    withContext(Dispatchers.Main) { renaming.value = null }
                                    clips.value = app.db.getClipsWithLabel(300)
                                }
                            }
                        )
                        Text("未标注")
                    }
                    labels.value.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = clip.labelId == l.id,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        app.db.updateClipLabel(clip.id, l.id)
                                        withContext(Dispatchers.Main) { renaming.value = null }
                                        clips.value = app.db.getClipsWithLabel(300)
                                    }
                                }
                            )
                            Text(l.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { renaming.value = null }) { Text("关闭") }
            }
        )
    }
}

// ---------- Tab 3: training ----------

@Composable
fun TrainScreen(app: SoundLensApp) {
    val counts = remember { mutableStateOf(mapOf<Int, Int>()) }
    val labels = remember { mutableStateOf(listOf<Label>()) }
    val training = remember { mutableStateOf(false) }
    val progress = remember { mutableStateOf(0f) }
    val resultText = remember { mutableStateOf<String?>(null) }
    val modelInfo = remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            counts.value = app.db.labeledCounts()
            labels.value = app.db.getLabels()
            modelInfo.value = if (app.classifier.isTrained) {
                val acc = "%.0f%%".format(app.classifier.trainAccuracy * 100)
                val at = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(app.classifier.trainedAt))
                "已训练模型：${app.classifier.numLabels} 类 · ${app.classifier.trainSamples} 个样本 · 训练准确率 $acc · $at"
            } else null
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("训练", style = MaterialTheme.typography.titleMedium)
        Text("用已标注的样本训练识别模型。之后采集到新样本、打上标签后，随时回来重新训练——模型就是这样\u201c不断学习\u201d的。")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.value.forEach { l ->
                    val c = counts.value[l.id] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.weight(1f))
                        Text(
                            "$c 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (labels.value.isEmpty()) Text("还没有标签，先去【标注】页创建。")
            }
        }

        modelInfo.value?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (training.value) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("训练中… ${"%.0f".format(progress.value * 100)}%")
                LinearProgressIndicator(
                    progress = { progress.value },
                    Modifier.fillMaxWidth()
                )
            }
        }

        resultText.value?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    it, Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            enabled = !training.value,
            onClick = {
                training.value = true
                resultText.value = null
                progress.value = 0f
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.Default) {
                            Trainer.trainAndSave(app) { p -> progress.value = p }
                        }
                        resultText.value =
                            "训练完成 ✓\n${r.labels.joinToString("、")} · ${r.samples} 个样本 · 训练准确率 ${"%.0f%%".format(r.accuracy * 100)}"
                    } catch (e: Exception) {
                        resultText.value = "训练失败：${e.message}"
                    } finally {
                        training.value = false
                        refresh()
                    }
                }
            }
        ) { Text("开始训练") }
    }
}

// ---------- Tab 4: recognition ----------

@Composable
fun RecognizeScreen(app: SoundLensApp) {
    val mode by Bus.mode.collectAsState()
    val snapshot by Bus.recognition.collectAsState()
    val labelNames = remember { mutableStateOf(mapOf<Int, String>()) }
    val askPermission = rememberMicPermission()
    val context = LocalContext.current

    LaunchedEffect(mode) {
        if (mode == CaptureMode.IDLE) {
            labelNames.value = withContext(Dispatchers.IO) {
                app.db.getLabels().associate { it.id to it.name }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorBanner()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实时识别", style = MaterialTheme.typography.titleMedium)
                Text("开启后 app 持续监听环境声音，用你训练好的模型判断当前是什么声音（约每秒刷新一次）。")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val running = mode != CaptureMode.IDLE
            Button(
                enabled = !running,
                onClick = {
                    if (!app.classifier.isTrained) {
                        Bus.error.value = "请先到【训练】页完成训练"
                        return@Button
                    }
                    if (askPermission()) {
                        context.startForegroundService(
                            Intent(context, AudioCaptureService::class.java)
                                .setAction(AudioCaptureService.ACTION_START_RECOGNIZE)
                        )
                    }
                }
            ) { Text("开始识别") }
            OutlinedButton(
                enabled = running,
                onClick = {
                    context.startService(
                        Intent(context, AudioCaptureService::class.java)
                            .setAction(AudioCaptureService.ACTION_STOP)
                    )
                }
            ) { Text("停止") }
        }

        if (!app.classifier.isTrained) {
            Text(
                "⚠ 尚未训练模型。请先采集并标注样本，然后到【训练】页训练。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        snapshot?.let { snap ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val bestProb = snap.top.firstOrNull()?.second ?: 0f
                    val bestName = snap.top.firstOrNull()?.let {
                        labelNames.value[it.first] ?: "未知"
                    } ?: ""
                    Text(
                        if (bestProb >= 0.5f) "当前最可能的声音：$bestName" else "环境声音（不确定）",
                        style = MaterialTheme.typography.titleMedium
                    )
                    snap.top.forEach { (labelId, prob) ->
                        val name = labelNames.value[labelId] ?: "未知"
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(name, Modifier.weight(1f))
                                Text("%.0f%%".format(prob * 100))
                            }
                            LinearProgressIndicator(
                                progress = { prob },
                                Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
