package com.promptreader.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptreader.android.parser.PromptReader
import com.promptreader.android.parser.RawPart
import com.promptreader.android.parser.ParseEvidence
import com.promptreader.android.parser.SettingEntry
import com.promptreader.android.parser.buildCombinedRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val ctx = LocalContext.current
            val prefs = remember { ThemePrefs(this@MainActivity) }
            var themeMode by remember { mutableStateOf(prefs.loadThemeMode()) }
            var palette by remember { mutableStateOf(prefs.loadPalette()) }
            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> systemDark
            }
            val colorScheme = when {
                palette == ColorPalette.Dynamic && dynamicAvailable -> {
                    if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                }

                isDark -> palette.darkScheme()
                else -> palette.lightScheme()
            }
            val shapes = remember {
                Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(24.dp),
                    large = RoundedCornerShape(28.dp),
                    extraLarge = RoundedCornerShape(32.dp),
                )
            }
            MaterialTheme(colorScheme = colorScheme, shapes = shapes) {
                var selectedUri by remember { mutableStateOf<Uri?>(null) }
                var tool by remember { mutableStateOf("") }
                var positive by remember { mutableStateOf("") }
                var negative by remember { mutableStateOf("") }
                var setting by remember { mutableStateOf("") }
                var settingDetail by remember { mutableStateOf("") }
                var settingEntries by remember { mutableStateOf<List<SettingEntry>>(emptyList()) }
                var detectionPath by remember { mutableStateOf("") }
                var detectionEvidence by remember { mutableStateOf<List<ParseEvidence>>(emptyList()) }
                var raw by remember { mutableStateOf("") }
                var rawParts by remember { mutableStateOf<List<RawPart>>(emptyList()) }
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
                    detectionEvidence = emptyList()
                    raw = ""
                    tool = ""
                    isLoading = true
                    imageInfo = null
                    thumbnail = null
                    settingDetail = ""
                    settingEntries = emptyList()
                    rawParts = emptyList()

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
                            detectionEvidence = it.detectionEvidence
                            raw = it.raw
                            rawParts = it.rawParts
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
                    detectionEvidence = detectionEvidence,
                    raw = raw,
                    rawParts = rawParts,
                    tabIndex = tabIndex,
                    onTabIndexChange = { tabIndex = it },
                    onPickImage = { launcher.launch(arrayOf("image/*")) },
                    onCopy = { text, label -> copy(text, label) },
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        prefs.saveThemeMode(it)
                        appScope.launch { snackbarHostState.showSnackbar("主题：${it.label}") }
                    },
                    palette = palette,
                    onPaletteChange = {
                        palette = it
                        prefs.savePalette(it)
                        appScope.launch { snackbarHostState.showSnackbar("配色：${it.label}") }
                    },
                    dynamicAvailable = dynamicAvailable,
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

private enum class ColorPalette(val label: String) {
    Dynamic("动态（Material You）"),
    Blue("海盐蓝"),
    Purple("雾紫"),
    Green("薄荷绿"),
    Orange("暖橙"),
}

private fun ColorPalette.lightScheme(): ColorScheme {
    return when (this) {
        ColorPalette.Dynamic -> lightColorScheme()
        ColorPalette.Blue -> lightColorScheme(
            primary = Color(0xFF2157FF),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDDE5FF),
            onPrimaryContainer = Color(0xFF00174A),
            secondary = Color(0xFF3F5AA9),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDAE2FF),
            onSecondaryContainer = Color(0xFF00184B),
            tertiary = Color(0xFF006A6A),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFF9CF1F0),
            onTertiaryContainer = Color(0xFF002020),
        )
        ColorPalette.Purple -> lightColorScheme(
            primary = Color(0xFF6C4DFF),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE8DDFF),
            onPrimaryContainer = Color(0xFF1F0061),
            secondary = Color(0xFF6750A4),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFEADDFF),
            onSecondaryContainer = Color(0xFF21005D),
            tertiary = Color(0xFF8B4A9F),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD7FF),
            onTertiaryContainer = Color(0xFF32003C),
        )
        ColorPalette.Green -> lightColorScheme(
            primary = Color(0xFF1B6D3A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFA6F2BF),
            onPrimaryContainer = Color(0xFF00210F),
            secondary = Color(0xFF4D6354),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFCFE9D5),
            onSecondaryContainer = Color(0xFF0A1F13),
            tertiary = Color(0xFF006C51),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFF7EF8CF),
            onTertiaryContainer = Color(0xFF002116),
        )
        ColorPalette.Orange -> lightColorScheme(
            primary = Color(0xFF9C4500),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDBC8),
            onPrimaryContainer = Color(0xFF321300),
            secondary = Color(0xFF775A4A),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDBC8),
            onSecondaryContainer = Color(0xFF2C160A),
            tertiary = Color(0xFF5E6300),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE3EA7B),
            onTertiaryContainer = Color(0xFF1B1D00),
        )
    }
}

private fun ColorPalette.darkScheme(): ColorScheme {
    return when (this) {
        ColorPalette.Dynamic -> darkColorScheme()
        ColorPalette.Blue -> darkColorScheme(
            primary = Color(0xFFB6C4FF),
            onPrimary = Color(0xFF002A78),
            primaryContainer = Color(0xFF003DB0),
            onPrimaryContainer = Color(0xFFDDE5FF),
            secondary = Color(0xFFB4C5FF),
            onSecondary = Color(0xFF112C79),
            secondaryContainer = Color(0xFF2A4290),
            onSecondaryContainer = Color(0xFFDAE2FF),
            tertiary = Color(0xFF7DD5D4),
            onTertiary = Color(0xFF003737),
            tertiaryContainer = Color(0xFF004F4F),
            onTertiaryContainer = Color(0xFF9CF1F0),
        )
        ColorPalette.Purple -> darkColorScheme(
            primary = Color(0xFFD0BCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC),
            onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFFEFB8C8),
            onTertiary = Color(0xFF492532),
            tertiaryContainer = Color(0xFF633B48),
            onTertiaryContainer = Color(0xFFFFD8E4),
        )
        ColorPalette.Green -> darkColorScheme(
            primary = Color(0xFF8BDDA5),
            onPrimary = Color(0xFF00391C),
            primaryContainer = Color(0xFF00522A),
            onPrimaryContainer = Color(0xFFA6F2BF),
            secondary = Color(0xFFB3CCB8),
            onSecondary = Color(0xFF1F3526),
            secondaryContainer = Color(0xFF354B3B),
            onSecondaryContainer = Color(0xFFCFE9D5),
            tertiary = Color(0xFF5DDBB3),
            onTertiary = Color(0xFF003828),
            tertiaryContainer = Color(0xFF00513C),
            onTertiaryContainer = Color(0xFF7EF8CF),
        )
        ColorPalette.Orange -> darkColorScheme(
            primary = Color(0xFFFFB68B),
            onPrimary = Color(0xFF542100),
            primaryContainer = Color(0xFF773200),
            onPrimaryContainer = Color(0xFFFFDBC8),
            secondary = Color(0xFFE6BEAA),
            onSecondary = Color(0xFF442A1C),
            secondaryContainer = Color(0xFF5D4030),
            onSecondaryContainer = Color(0xFFFFDBC8),
            tertiary = Color(0xFFC6CE61),
            onTertiary = Color(0xFF303200),
            tertiaryContainer = Color(0xFF454900),
            onTertiaryContainer = Color(0xFFE3EA7B),
        )
    }
}

private class ThemePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_reader_prefs", Context.MODE_PRIVATE)

    fun loadThemeMode(): ThemeMode {
        val v = prefs.getString("theme_mode", ThemeMode.System.name) ?: ThemeMode.System.name
        return runCatching { ThemeMode.valueOf(v) }.getOrDefault(ThemeMode.System)
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun loadPalette(): ColorPalette {
        val v = prefs.getString("color_palette", ColorPalette.Dynamic.name) ?: ColorPalette.Dynamic.name
        return runCatching { ColorPalette.valueOf(v) }.getOrDefault(ColorPalette.Dynamic)
    }

    fun savePalette(palette: ColorPalette) {
        prefs.edit().putString("color_palette", palette.name).apply()
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
    detectionEvidence: List<ParseEvidence>,
    raw: String,
    rawParts: List<RawPart>,
    tabIndex: Int,
    onTabIndexChange: (Int) -> Unit,
    onPickImage: () -> Unit,
    onCopy: suspend (String, String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    palette: ColorPalette,
    onPaletteChange: (ColorPalette) -> Unit,
    dynamicAvailable: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var rawViewMode by rememberSaveable { mutableStateOf(RawViewMode.Chunked) }

    var positiveMode by remember { mutableStateOf(ViewMode.Normal) }
    var negativeMode by remember { mutableStateOf(ViewMode.Normal) }
    var paramsMode by remember { mutableStateOf(ViewMode.Simple) }
    var showPaletteDialog by remember { mutableStateOf(false) }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }

    if (showPaletteDialog) {
        PalettePickerDialog(
            current = palette,
            isDark = isDark,
            dynamicAvailable = dynamicAvailable,
            onSelect = {
                onPaletteChange(it)
                showPaletteDialog = false
            },
            onDismiss = { showPaletteDialog = false },
        )
    }

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
                    IconButton(onClick = { showPaletteDialog = true }) {
                        Icon(Icons.Filled.Palette, contentDescription = "配色")
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
            val combinedRaw = remember(raw, rawParts) { buildCombinedRaw(raw, rawParts) }

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

                        val canCopyAll = combinedRaw.isNotBlank()
                        Button(
                            onClick = {
                                if (canCopyAll) {
                                    scope.launch { onCopy(combinedRaw, "全部") }
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
                        val aspectRatio = remember(imageInfo) {
                            val w = imageInfo?.width
                            val h = imageInfo?.height
                            if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h.toFloat() else null
                        }
                        Image(
                            bitmap = thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (aspectRatio != null) {
                                        Modifier.aspectRatio(aspectRatio)
                                    } else {
                                        Modifier
                                    },
                                )
                                .heightIn(min = 140.dp, max = 360.dp)
                                .clip(MaterialTheme.shapes.large),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
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
                        if (detectionEvidence.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                detectionEvidence.forEach { step ->
                                    Text(
                                        text = "${step.stage}: ${step.detail}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
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

                    if (error.isNullOrBlank() && positive.isBlank() && negative.isBlank() && combinedRaw.isNotBlank()) {
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
                else -> combinedRaw
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
                                raw = combinedRaw,
                                rawParts = rawParts,
                                viewMode = rawViewMode,
                                onViewModeChange = { rawViewMode = it },
                                onCopy = onCopy,
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
                    if (combinedRaw.isNotBlank()) {
                        scope.launch { onCopy(combinedRaw, "全部") }
                    }
                },
                enabled = combinedRaw.isNotBlank(),
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
    rawParts: List<RawPart>,
    viewMode: RawViewMode,
    onViewModeChange: (RawViewMode) -> Unit,
    onCopy: suspend (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stats = remember(raw) {
        val chars = raw.length
        val lines = raw.count { it == '\n' } + 1
        chars to lines
    }
    val parts = remember(raw, rawParts) {
        if (rawParts.isNotEmpty()) rawParts else listOf(RawPart("Raw", raw))
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
        RawViewMode.Compact -> {
            if (parts.size == 1) {
                PromptTextBox(title = parts.first().title, text = parts.first().text, tall = true, maxDisplayChars = 50_000)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.forEach { part ->
                        PromptTextBox(title = part.title, text = part.text, tall = true, maxDisplayChars = 50_000)
                    }
                }
            }
        }

        RawViewMode.Chunked -> {
            if (parts.size == 1) {
                ChunkedTextViewer(text = parts.first().text, heightDp = 320)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.forEach { part ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = part.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { scope.launch { onCopy(part.text, part.title) } },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                            }
                        }
                        ChunkedTextViewer(text = part.text, heightDp = 220)
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ChunkedTextViewer(text: String, heightDp: Int) {
    val chunkSize = 2000
    val chunkCount = remember(text) { if (text.isEmpty()) 0 else (text.length + chunkSize - 1) / chunkSize }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        shape = MaterialTheme.shapes.large,
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
        shape = MaterialTheme.shapes.extraLarge,
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

    tokens.forEach { token ->
        ListItem(
            headlineContent = { Text(token) },
        )
    }
}

@androidx.compose.runtime.Composable
private fun PalettePickerDialog(
    current: ColorPalette,
    isDark: Boolean,
    dynamicAvailable: Boolean,
    onSelect: (ColorPalette) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择配色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ColorPalette.entries.forEach { palette ->
                    val enabled = palette != ColorPalette.Dynamic || dynamicAvailable
                    val scheme = if (isDark) palette.darkScheme() else palette.lightScheme()
                    val selected = palette == current
                    val bg = if (selected) scheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent
                    val alpha = if (enabled) 1f else 0.45f

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(bg)
                            .clickable(enabled = enabled) { onSelect(palette) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = enabled,
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = palette.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                            )
                            if (palette == ColorPalette.Dynamic && !dynamicAvailable) {
                                Text(
                                    text = "Android 12+ 才支持动态配色",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorSwatch(color = scheme.primary, alpha = alpha)
                            ColorSwatch(color = scheme.secondary, alpha = alpha)
                            ColorSwatch(color = scheme.tertiary, alpha = alpha)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@androidx.compose.runtime.Composable
private fun ColorSwatch(color: Color, alpha: Float) {
    Surface(
        modifier = Modifier.size(16.dp),
        shape = CircleShape,
        color = color.copy(alpha = alpha),
        content = {},
    )
}
