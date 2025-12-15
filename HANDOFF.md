# Prompt Viewer for Android — 交接文档（给下一个 AI）

更新时间：2025-12-16  
仓库：`https://github.com/homura251/prompt-viewer-for-android.git`（`master`）  
最近提交：`c24294d`（Parameters simple/normal toggle and JSON settings formatting）

## 0. 项目目标与范围

目标：在 Android 手机上读取图片内嵌的 Stable Diffusion / ComfyUI / SwarmUI / Fooocus / NovelAI 等元数据，展示并可一键复制：
- 正向提示词（Positive）
- 反向提示词（Negative）
- 参数（Settings/Params）
- Raw（原始元数据/JSON/文本）

当前实现重点：先交付“可安装可用的 APK”，再逐步向 Windows 版（Python GUI）功能对齐。

## 1. 仓库结构

- `prompt-reader-android/`
  - Android App（Kotlin + Jetpack Compose Material3）
  - 当前主力实现：解析逻辑、UI 展示、复制。
- `prompt-reader-kotlin/`
  - Kotlin 多模块骨架（`core`/`cli`），用于未来抽离共享解析核心（目前 Android 仍以内置 parser 为主）。
- `comfyui-workflow-prompt-viewer/`
  - Python 参考实现（用于对齐解析策略/格式支持/fixtures 参考）。
- `README.md`
  - 基本构建说明。

> 注意：仓库根目录可能存在本地样例图片（例如 `IMG_20251214_224931_583.png`），不建议提交私人图片；可用 `.gitignore` 忽略。

## 2. 构建与运行（Windows/Android Studio）

### 2.1 前置环境
- Android SDK（建议 Android Studio 安装）
- JDK 17

Android 工程配置（关键）：
- `compileSdk = 35` / `targetSdk = 35` / `minSdk = 26`
- AGP `8.7.3`
- Kotlin `2.0.21` + `org.jetbrains.kotlin.plugin.compose`

### 2.2 local.properties
如果 CI/本机没有自动识别 SDK，需要在 `prompt-reader-android/local.properties` 配置：
- `sdk.dir=...`（Android SDK 路径）

### 2.3 构建命令
在 `prompt-reader-android/` 下：
```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```
APK 输出：
- `prompt-reader-android/app/build/outputs/apk/debug/app-debug.apk`

## 3. 关键功能与当前 UX

入口 Activity：`prompt-reader-android/app/src/main/java/com/promptreader/android/MainActivity.kt`
- 选择图片（SAF OpenDocument）
- 显示缩略图 + 文件信息卡（文件名、MIME、尺寸、体积）
- Tab：`正向 / 反向 / 参数 / Raw`
- 复制：单页复制 + “复制全部（Raw）”

### 3.1 “灯泡”模式（Simple/Normal）
为了对齐桌面端“simple/normal”概念，移动端加入灯泡切换：
- 正向/反向：
  - `Normal`：整段文本
  - `Simple`：按逗号拆成 tag 列表（用于更易读、快速复制单条）
- 参数：
  - `Simple`（默认）：结构化 key/value（只显示核心字段，不展示 prompt/uc）
  - `Normal`：pretty JSON（同样去掉 prompt/uc，避免重复；用于排查/对齐）

动机：NovelAI/Fooocus 的元数据是 JSON，不能用 A1111 那种“逗号+冒号”的字符串拆分，否则会误拆成一大串。

## 4. 解析架构（Android 侧）

解析入口：`prompt-reader-android/app/src/main/java/com/promptreader/android/parser/PromptReader.kt`

### 4.1 文件类型探测
- 读前 16 bytes 判断：PNG / JPEG / WEBP

### 4.2 PNG：text chunk 读取
- 读取：`prompt-reader-android/app/src/main/java/com/promptreader/android/png/PngTextChunkReader.kt`
- 支持 chunk：`tEXt` / `iTXt` / `zTXt`
- 输出 keyword -> text map

### 4.3 PNG 解析分发（重要：顺序影响识别）
`PromptReader.parsePng()` 当前顺序（必须理解）：
1) SwarmUI：`parameters` 里包含 `sui_image_params`
2) NovelAI legacy：`Software == "NovelAI"` 且存在 `Description` + `Comment`
3) Fooocus：`Comment` 为 JSON，且满足特征字段（`negative_prompt` 等）
4) ComfyUI：存在 `prompt`（JSON）
5) A1111：存在 `parameters`
6) NovelAI stealth：位图 alpha LSB 解码

此前真实样本 bug：NovelAI legacy 的 `Comment` 也是 JSON，曾被“任何 JSON Comment 都按 Fooocus 解析”误判，导致 negative 丢失。现已修复：NovelAI legacy 必须优先于 Fooocus，并且 Fooocus 需要特征字段判断。

### 4.4 JPEG/WEBP：EXIF 读取
- 使用 `androidx.exifinterface:exifinterface`
- 读取字段：
  - `TAG_USER_COMMENT`
  - `TAG_SOFTWARE`
  - `TAG_IMAGE_DESCRIPTION`
- 分发（当前）：
  - SwarmUI：userComment 包含 `sui_image_params`
  - Fooocus：userComment JSON 且含 `negative_prompt`
  - A1111：userComment 走 A1111 文本解析
  - NovelAI stealth：尝试 LSB（WEBP 也可能存在）
  - NovelAI legacy：`Software == NovelAI` 且 `ImageDescription` 存在

## 5. 各格式 Parser（现状）

数据结构：
- 结果：`PromptParseResult`（`tool/positive/negative/setting/raw` + `settingEntries/settingDetail`）
  - 文件：`prompt-reader-android/app/src/main/java/com/promptreader/android/parser/PromptParseResult.kt`

### 5.1 A1111
- 文件：`.../parser/A1111Parser.kt`
- 解析：基于 `Negative prompt:` 与 `Steps:` 分段

### 5.2 ComfyUI（最小移植）
- 文件：`.../parser/ComfyUiParser.kt`
- 策略：
  - 找 end nodes（SaveImage / KSampler variants）
  - 向上游 traverse，找 `CLIPTextEncode` 的 text
  - 试图在 flow 里汇总 steps/sampler/cfg/seed/model 等
- 说明：该实现是“可用但不完全 clone”，后续需要更多样本对齐。

### 5.3 SwarmUI
- 文件：`.../parser/SwarmUiParser.kt`
- 解析：读取 `sui_image_params` JSON
- 当前 settings 仍是简单字符串（未结构化 entries/detail），可按 NovelAI/Fooocus 的方式补齐。

### 5.4 Fooocus
- 文件：`.../parser/FooocusParser.kt`
- 解析：JSON；取 `prompt`/`negative_prompt`
- 参数：
  - `settingEntries`：优先从常见字段提取（steps/sampler/seed/size/cfg 等）
  - `settingDetail`：pretty JSON（去掉 prompt/negative_prompt）

### 5.5 NovelAI（legacy/stealth）
- 文件：`.../parser/NovelAiParser.kt`
- legacy：
  - `Description` 为 positive
  - `Comment` JSON 的 `uc` 为 negative
  - `settingEntries`：steps/sampler/scale/seed/size/cfg_rescale/noise_schedule/strength 等
  - `settingDetail`：pretty JSON（去掉 prompt/uc）
- stealth：
  - 解码：`.../novelai/NovelAiStealthDecoder.kt`
  - 机制：alpha LSB 中读取 magic `stealth_pngcomp` + bitLen + gzip JSON

## 6. 已知问题 / 技术债

1) “Windows 版功能一个不少”尚未完成
- 当前 Android 仅做：读取/展示/复制
- 未做：编辑/写回元数据/批量导出/文件管理等

2) Settings 结构化覆盖不完全
- NovelAI/Fooocus 已有 `settingEntries` + `settingDetail`
- SwarmUI/ComfyUI/A1111 已补齐 `settingEntries` + `settingDetail`（并保持 Simple/Normal 语义一致）
- 建议统一：所有 parser 输出结构化 entries + detail

3) 识别规则仍需样本驱动
- 特别是 Fooocus/NovelAI/SwarmUI 的“Comment/UserComment JSON”变体多
- 需要更多真实图片样本（或 Python fixtures）来回归

4) 性能与内存
- stealth 解码需要完整 decode bitmap，超大图可能内存压力
- 可优化：先 decode bounds + 限制 inSampleSize 或只取 alpha 通道（需要更复杂实现）

5) UI 细节
- token list（simple）目前按逗号拆分，复杂权重/括号规则未做精确 tokenize
- 复制/分享/导出等交互可扩展

## 7. 推荐下一步（按优先级）

P0（对齐与稳定性）
- 已为 SwarmUI/ComfyUI/A1111 补齐：`settingEntries` + `settingDetail`
- 已新增解析 fixtures + 单元测试（`prompt-reader-android/app/src/test/...`）；后续可再对齐 Python goldens 做 diff

P1（交付体验）
- 增加 GitHub Release（签名/版本号）或 CI（GitHub Actions）自动产出 APK
- 增加错误提示：显示识别路径（用了哪个 parser/哪个字段命中），便于用户报错

P2（功能扩展）
- 批量选择/批量导出 JSON
- 编辑/写回（风险高：不同格式写回策略不同，避免污染 parameters/workflow）

## 8. 开发注意事项（坑位）

- 解析顺序很关键：NovelAI legacy 必须在 Fooocus 之前，否则会被误判。
- “参数字符串拆分”不能用于 JSON：必须区分 A1111 风格文本 vs JSON。
- 不要提交真实样图到公开仓库：默认用 `.gitignore` 忽略本地图片。

---

如需继续对齐 Windows 版（Python GUI）的 “normal/simple” 语义：
- 建议先定义统一数据模型（Prompt + Negative + SettingsEntries + RawJson + SourceTool + DetectionEvidence），再让各 parser 填充；UI 只消费统一模型。
