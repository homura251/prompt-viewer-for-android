package com.promptreader.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptreader.android.parser.PromptReader
import com.promptreader.android.parser.SettingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val prefs = remember { ThemePrefs(this@MainActivity) }
            var themeMode by remember { mutableStateOf(prefs.load()) }
            val colorScheme = when (themeMode) {
                ThemeMode.Dark -> darkColorScheme()
                ThemeMode.Light -> lightColorScheme()
                ThemeMode.System -> if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                var selectedUri by remember { mutableStateOf<Uri?>(null) }
                var tool by remember { mutableStateOf("") }
                var positive by remember { mutableStateOf("") }
                var negative by remember { mutableStateOf("") }
                var setting by remember { mutableStateOf("") }
                var settingDetail by remember { mutableStateOf("") }
                var settingEntries by remember { mutableStateOf<List<SettingEntry>>(emptyList()) }
                var detectionPath by remember { mutableStateOf("") }
                var raw by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var isLoading by remember { mutableStateOf(false) }
                var tabIndex by remember { mutableIntStateOf(0) }

                var imageInfo by remember { mutableStateOf<SelectedImageInfo?>(null) }
                var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

                val snackbarHostState = remember { SnackbarHostState() }
                val appScope = rememberCoroutineScope()

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    selectedUri = uri
                }

                LaunchedEffect(selectedUri) {
                    val uri = selectedUri ?: return@LaunchedEffect

                    error = null
                    positive = ""
                    negative = ""
                    setting = ""
                    detectionPath = ""
                    raw = ""
                    tool = ""
                    isLoading = true
                    imageInfo = null
                    thumbnail = null
                    settingDetail = ""
                    settingEntries = emptyList()

                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }

                    val infoResult = withContext(Dispatchers.IO) {
                        runCatching { loadImageInfo(contentResolver, uri) }
                    }
                    infoResult.getOrNull()?.let { (info, thumb) ->
                        imageInfo = info
                        thumbnail = thumb
                    }

                    val result = withContext(Dispatchers.IO) {
                        runCatching { PromptReader.parse(this@MainActivity, uri) }
                    }
                    result.fold(
                        onSuccess = {
                            tool = it.tool
                            positive = it.positive
                            negative = it.negative
                            setting = it.setting
                            settingDetail = it.settingDetail
                            settingEntries = it.settingEntries
                            detectionPath = it.detectionPath
                            raw = it.raw
                        },
                        onFailure = {
                            error = it.message ?: it.toString()
                        }
                    )
                    isLoading = false
                }

                suspend fun copy(text: String, label: String) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("prompt", text))
                    snackbarHostState.showSnackbar("已复制：$label")
                }


                PromptReaderScreen(
                    selectedUri = selectedUri,
                    imageInfo = imageInfo,
                    thumbnail = thumbnail,
                    isLoading = isLoading,
                    tool = tool,
                    error = error,
                    positive = positive,
                    negative = negative,
                    setting = setting,
                    settingDetail = settingDetail,
                    settingEntries = settingEntries,
                    detectionPath = detectionPath,
                    raw = raw,
                    tabIndex = tabIndex,
                    onTabIndexChange = { tabIndex = it },
                    onPickImage = { launcher.launch(arrayOf("image/*")) },
                    onCopy = { text, label -> copy(text, label) },
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        prefs.save(it)
                        appScope.launch { snackbarHostState.showSnackbar("主题：${it.label}") }
                    },
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}

private enum class ViewMode {
    Simple,
    Normal,
}

private enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

private class ThemePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_reader_prefs", Context.MODE_PRIVATE)

    fun load(): ThemeMode {
        val v = prefs.getString("theme_mode", ThemeMode.System.name) ?: ThemeMode.System.name
        return runCatching { ThemeMode.valueOf(v) }.getOrDefault(ThemeMode.System)
    }

    fun save(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }
}

private enum class RawViewMode {
    Chunked,
    Compact,
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun PromptReaderScreen(
    selectedUri: Uri?,
    imageInfo: SelectedImageInfo?,
    thumbnail: ImageBitmap?,
    isLoading: Boolean,
    tool: String,
    error: String?,
    positive: String,
    negative: String,
    setting: String,
    settingDetail: String,
    settingEntries: List<SettingEntry>,
    detectionPath: String,
    raw: String,
    tabIndex: Int,
    onTabIndexChange: (Int) -> Unit,
    onPickImage: () -> Unit,
    onCopy: suspend (String, String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var rawViewMode by rememberSaveable { mutableStateOf(RawViewMode.Chunked) }

    var positiveMode by remember { mutableStateOf(ViewMode.Normal) }
    var negativeMode by remember { mutableStateOf(ViewMode.Normal) }
    var paramsMode by remember { mutableStateOf(ViewMode.Simple) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Prompt Reader")
                        if (!tool.isBlank()) {
                            Text(
                                text = tool,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val next = when (themeMode) {
                                ThemeMode.System -> ThemeMode.Light
                                ThemeMode.Light -> ThemeMode.Dark
                                ThemeMode.Dark -> ThemeMode.System
                            }
                            onThemeModeChange(next)
                        },
                    ) {
                        val icon = when (themeMode) {
                            ThemeMode.System -> Icons.Filled.SettingsBrightness
                            ThemeMode.Light -> Icons.Filled.LightMode
                            ThemeMode.Dark -> Icons.Filled.DarkMode
                        }
                        Icon(icon, contentDescription = "主题")
                    }
                    IconButton(onClick = onPickImage) {
                        Icon(Icons.Filled.Image, contentDescription = "选择图片")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = onPickImage) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("选择图片")
                        }

                        val canCopyAll = raw.isNotBlank()
                        Button(
                            onClick = {
                                if (canCopyAll) {
                                    scope.launch { onCopy(raw, "全部") }
                                }
                            },
                            enabled = canCopyAll,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("复制全部")
                        }
                    }

                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    if (imageInfo != null) {
                        ListItem(
                            headlineContent = { Text("文件") },
                            supportingContent = {
                                Text(imageInfo.displayName ?: selectedUri?.toString().orEmpty())
                            },
                            leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                        )
                        ListItem(
                            headlineContent = { Text("类型") },
                            supportingContent = { Text(imageInfo.mimeType ?: "未知") },
                        )
                        ListItem(
                            headlineContent = { Text("尺寸") },
                            supportingContent = {
                                val dim = if (imageInfo.width != null && imageInfo.height != null) {
                                    "${imageInfo.width}×${imageInfo.height}"
                                } else {
                                    "未知"
                                }
                                val size = imageInfo.sizeBytes?.let { formatBytes(it) } ?: "未知"
                                Text("$dim，$size")
                            },
                        )
                        if (detectionPath.isNotBlank()) {
                            ListItem(
                                headlineContent = { Text("识别路径") },
                                supportingContent = { Text(detectionPath) },
                                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                                trailingContent = {
                                    IconButton(
                                        onClick = { scope.launch { onCopy(detectionPath, "识别路径") } },
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制识别路径")
                                    }
                                },
                            )
                        }
                    } else {
                        if (selectedUri != null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Info, contentDescription = null)
                                Text(
                                    text = selectedUri.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                text = "未选择图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (error.isNullOrBlank() && positive.isBlank() && negative.isBlank() && raw.isNotBlank()) {
                        Text(
                            text = "提示：未能解析到正向/反向提示词，可能仅包含 workflow 元数据或使用了自定义节点；可复制 Raw + 识别路径用于报错。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!error.isNullOrBlank()) {
                        Text(
                            text = "错误：$error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            val tabTitles = listOf("正向", "反向", "参数", "Raw")

            TabRow(selectedTabIndex = tabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { onTabIndexChange(index) },
                        text = { Text(title) },
                    )
                }
            }

            val title = tabTitles[tabIndex]
            val orderedSettingEntries = remember(setting, settingEntries) {
                orderSettingEntriesForDisplay(settingEntries.ifEmpty { parseSettingEntriesFromText(setting) })
            }
            val contentToCopy = when (tabIndex) {
                0 -> positive
                1 -> negative
                2 -> when (paramsMode) {
                    ViewMode.Simple -> buildSettingCopyText(orderedSettingEntries)
                    ViewMode.Normal -> (settingDetail.ifBlank { setting }).ifBlank { "" }
                }
                else -> raw
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )

                        val canToggleMode = tabIndex in 0..2
                        if (canToggleMode) {
                            val mode = when (tabIndex) {
                                0 -> positiveMode
                                1 -> negativeMode
                                else -> paramsMode
                            }
                            IconButton(
                                onClick = {
                                    val next = if (mode == ViewMode.Simple) ViewMode.Normal else ViewMode.Simple
                                    when (tabIndex) {
                                        0 -> positiveMode = next
                                        1 -> negativeMode = next
                                        2 -> paramsMode = next
                                    }
                                },
                            ) {
                                if (mode == ViewMode.Simple) {
                                    Icon(Icons.Filled.Lightbulb, contentDescription = "切换为 Normal")
                                } else {
                                    Icon(Icons.Outlined.Lightbulb, contentDescription = "切换为 Simple")
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                if (contentToCopy.isNotBlank()) {
                                    scope.launch { onCopy(contentToCopy, title) }
                                }
                            },
                            enabled = contentToCopy.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                        }
                    }

                    when (tabIndex) {
                        0 -> {
                            when (positiveMode) {
                                ViewMode.Simple -> PromptTokenList(text = positive)
                                ViewMode.Normal -> PromptTextBox(title = title, text = positive, tall = false)
                            }
                        }

                        1 -> {
                            when (negativeMode) {
                                ViewMode.Simple -> PromptTokenList(text = negative)
                                ViewMode.Normal -> PromptTextBox(title = title, text = negative, tall = false)
                            }
                        }

                        2 -> {
                            when (paramsMode) {
                                ViewMode.Simple -> {
                                    if (orderedSettingEntries.isNotEmpty()) {
                                        orderedSettingEntries.forEach { (k, v) ->
                                            ListItem(
                                                headlineContent = { Text(k) },
                                                supportingContent = { Text(v) },
                                            )
                                        }
                                    } else {
                                        PromptTextBox(title = title, text = setting, tall = false)
                                    }
                                }

                                ViewMode.Normal -> {
                                    val detail = settingDetail.ifBlank { setting }
                                    PromptTextBox(title = title, text = detail, tall = true)
                                }
                            }
                        }

                        else -> {
                            RawViewer(
                                raw = raw,
                                viewMode = rawViewMode,
                                onViewModeChange = { rawViewMode = it },
                            )
                        }
                    }
                }
            }

            // Provide a simple "copy all" action at the bottom too.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        if (positive.isNotBlank()) {
                            scope.launch { onCopy(positive, "正向") }
                        }
                    },
                    enabled = positive.isNotBlank(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制正向")
                }
                FilledTonalButton(
                    onClick = {
                        if (negative.isNotBlank()) {
                            scope.launch { onCopy(negative, "反向") }
                        }
                    },
                    enabled = negative.isNotBlank(),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制反向")
                }
            }

            Button(
                onClick = {
                    if (raw.isNotBlank()) {
                        scope.launch { onCopy(raw, "全部") }
                    }
                },
                enabled = raw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("复制全部（Raw）")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun RawViewer(
    raw: String,
    viewMode: RawViewMode,
    onViewModeChange: (RawViewMode) -> Unit,
) {
    val stats = remember(raw) {
        val chars = raw.length
        val lines = raw.count { it == '\n' } + 1
        chars to lines
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { onViewModeChange(RawViewMode.Chunked) },
            enabled = viewMode != RawViewMode.Chunked,
        ) {
            Text("全文")
        }
        FilledTonalButton(
            onClick = { onViewModeChange(RawViewMode.Compact) },
            enabled = viewMode != RawViewMode.Compact,
        ) {
            Text("简洁")
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "${stats.first} chars / ${stats.second} lines",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }

    Spacer(Modifier.height(8.dp))

    when (viewMode) {
        RawViewMode.Compact -> PromptTextBox(title = "Raw", text = raw, tall = true, maxDisplayChars = 50_000)
        RawViewMode.Chunked -> ChunkedTextViewer(text = raw, heightDp = 320)
    }
}

@androidx.compose.runtime.Composable
private fun ChunkedTextViewer(text: String, heightDp: Int) {
    val shape = RoundedCornerShape(6.dp)
    val chunkSize = 2000
    val chunkCount = remember(text) { if (text.isEmpty()) 0 else (text.length + chunkSize - 1) / chunkSize }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        shape = shape,
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            if (text.isBlank()) {
                Text(
                    text = "（空）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Box
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(count = chunkCount, key = { it }) { index ->
                    val start = index * chunkSize
                    val end = min(start + chunkSize, text.length)
                    Text(
                        text = text.substring(start, end),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private data class SelectedImageInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
)

private fun loadImageInfo(
    resolver: android.content.ContentResolver,
    uri: Uri,
): Pair<SelectedImageInfo, ImageBitmap?> {
    var displayName: String? = null
    var sizeBytes: Long? = null

    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) displayName = c.getString(nameIndex)

            val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0) {
                val v = c.getLong(sizeIndex)
                if (v >= 0) sizeBytes = v
            }
        }
    }

    val mimeType = resolver.getType(uri)

    // Bounds decode for dimensions
    var width: Int? = null
    var height: Int? = null
    resolver.openInputStream(uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            width = opts.outWidth
            height = opts.outHeight
        }
    }

    // Decode a lightweight thumbnail for preview
    val thumb = resolver.openInputStream(uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeStream(stream, null, opts)
        bmp?.asImageBitmap()
    }

    val info = SelectedImageInfo(
        displayName = displayName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        width = width,
        height = height,
    )
    return info to thumb
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun parseSettingPairs(setting: String): List<Pair<String, String>> {
    if (setting.isBlank()) return emptyList()

    // Common patterns: "Steps: 20, Sampler: Euler a, CFG scale: 7, Seed: 123, Size: 512x512, Model: xxx"
    val parts = setting.split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val pairs = ArrayList<Pair<String, String>>()
    for (p in parts) {
        val idx = p.indexOf(':')
        if (idx <= 0 || idx >= p.length - 1) continue
        val key = p.substring(0, idx).trim()
        val value = p.substring(idx + 1).trim()
        if (key.isNotBlank() && value.isNotBlank()) pairs += key to value
    }
    return pairs
}

private fun parseSettingEntriesFromText(setting: String): List<SettingEntry> {
    return parseSettingPairs(setting).map { SettingEntry(it.first, it.second) }
}

private fun orderSettingEntriesForDisplay(entries: List<SettingEntry>): List<SettingEntry> {
    if (entries.isEmpty()) return entries
    val priorityKeys = setOf("model", "checkpoint", "checkpoint_name", "ckpt_name", "ckpt")
    val (prioritized, rest) = entries.partition { it.key.trim().lowercase() in priorityKeys }
    return if (prioritized.isEmpty()) entries else prioritized + rest
}

private fun buildSettingCopyText(entries: List<SettingEntry>): String {
    if (entries.isEmpty()) return ""
    return entries.joinToString("\n") { "${it.key}: ${it.value}" }
}

@androidx.compose.runtime.Composable
private fun PromptTextBox(title: String, text: String, tall: Boolean, maxDisplayChars: Int? = null) {
    val displayText = remember(text, maxDisplayChars) { truncateForDisplay(text, maxDisplayChars) }
    OutlinedTextField(
        value = displayText,
        onValueChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tall) 320.dp else 220.dp),
        label = { Text(title) },
        readOnly = true,
    )
}

private fun truncateForDisplay(text: String, maxChars: Int?): String {
    if (maxChars == null || maxChars <= 0) return text
    if (text.length <= maxChars) return text
    val head = text.take(maxChars)
    return head + "\n\n…(内容过大，已截断显示；共 ${text.length} 字符，复制按钮可复制完整内容)"
}

@androidx.compose.runtime.Composable
private fun PromptTokenList(text: String) {
    val tokens = remember(text) {
        text.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(400)
    }
    if (tokens.isEmpty()) {
        Text(
            text = "（空）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    tokens.forEachIndexed { index, token ->
        ListItem(
            headlineContent = { Text(token) },
            supportingContent = { Text("#${index + 1}") },
        )
    }
}
