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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptreader.android.parser.PromptReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var selectedUri by remember { mutableStateOf<Uri?>(null) }
                var tool by remember { mutableStateOf("") }
                var positive by remember { mutableStateOf("") }
                var negative by remember { mutableStateOf("") }
                var setting by remember { mutableStateOf("") }
                var raw by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var isLoading by remember { mutableStateOf(false) }
                var tabIndex by remember { mutableIntStateOf(0) }

                var imageInfo by remember { mutableStateOf<SelectedImageInfo?>(null) }
                var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

                val snackbarHostState = remember { SnackbarHostState() }

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
                    raw = ""
                    tool = ""
                    isLoading = true
                    imageInfo = null
                    thumbnail = null

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
                    raw = raw,
                    tabIndex = tabIndex,
                    onTabIndexChange = { tabIndex = it },
                    onPickImage = { launcher.launch(arrayOf("image/*")) },
                    onCopy = { text, label -> copy(text, label) },
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
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
    raw: String,
    tabIndex: Int,
    onTabIndexChange: (Int) -> Unit,
    onPickImage: () -> Unit,
    onCopy: suspend (String, String) -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

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

                    if (!error.isNullOrBlank()) {
                        Text(
                            text = "错误：$error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            val tabs = listOf(
                "正向" to positive,
                "反向" to negative,
                "参数" to setting,
                "Raw" to raw,
            )

            TabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, (title, _) ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { onTabIndexChange(index) },
                        text = { Text(title) },
                    )
                }
            }

            val (title, content) = tabs[tabIndex]
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
                        IconButton(
                            onClick = {
                                if (content.isNotBlank()) {
                                    scope.launch { onCopy(content, title) }
                                }
                            },
                            enabled = content.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                        }
                    }

                    if (tabIndex == 2) {
                        val pairs = remember(setting) { parseSettingPairs(setting) }
                        if (pairs.isNotEmpty()) {
                            pairs.forEach { (k, v) ->
                                ListItem(
                                    headlineContent = { Text(k) },
                                    supportingContent = { Text(v) },
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = content,
                                onValueChange = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                label = { Text(title) },
                                readOnly = true,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = content,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (tabIndex == 3) 320.dp else 220.dp),
                            label = { Text(title) },
                            readOnly = true,
                        )
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
