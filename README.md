# Prompt Reader for Android

一个用于在 Android 手机上读取图片里的 SD 提示词 / 参数的工具。

## 当前能力（Debug 版 APK）
- 选择图片 → 解析元数据 → 展示 Positive / Negative / Settings / Raw
- 支持：PNG chunk（A1111/ComfyUI）、EXIF UserComment（常见 A1111/SwarmUI/Fooocus 变体）、NovelAI stealth（alpha LSB + gzip JSON）

## 构建
### 前置
- Android SDK（建议通过 Android Studio 安装）
- JDK 17（Gradle Toolchain 会尝试自动处理）

### 命令
在 `prompt-reader-android/` 目录下运行：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

APK 输出：
- `prompt-reader-android/app/build/outputs/apk/debug/app-debug.apk`

## 目录
- `prompt-reader-android/`：Android App（Compose）
- `prompt-reader-kotlin/`：Kotlin 解析核心（后续可与 Android 共享）
- `comfyui-workflow-prompt-viewer/`：Python 参考实现与解析逻辑来源（用于对齐与 fixtures）
