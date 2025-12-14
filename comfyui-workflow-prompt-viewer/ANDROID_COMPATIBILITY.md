# Android 兼容/复用方案（面向 comfyui-workflow-prompt-viewer）

> 结论先说：这个仓库的核心是 **Python 桌面工具**（Tk / customtkinter）+ **解析图片元数据与 ComfyUI workflow 的解析器**。
> 如果你的目标是“Android 上功能与 Windows GUI 一个不少 + 全格式支持（含 SwarmUI/Fooocus/NovelAI）”，那么 Android 端要做的不只是读 prompt：
> - **读**：覆盖 PNG/JPEG/WEBP 的多种元数据写法（含 NovelAI stealth LSB）。
> - **写/清除**：像 Windows 版一样导出、清除、编辑并保存回图片（同时尽量不破坏原图/原工作流）。
>
> 现实可行的“复用”路径是：**复用解析规则/算法并做 Kotlin/Java 端口 + 用 Python 生成 fixtures 做金标准对齐**。
> 在 Android 上直接运行现有 Python（尤其 Pillow/piexif）可做，但工程与包体积成本会高很多。

## 1. 代码结构与可复用核心

### 1.1 核心入口与职责划分

- `main.py`：根据是否有 console 选择 GUI 或 CLI。
- `sd_prompt_reader/app.py`：桌面 GUI（Android 不可复用）。
- `sd_prompt_reader/cli.py`：CLI（可作为“功能定义参考”，但 Android 不直接复用）。

### 1.2 真正需要复用/迁移的核心

1) **从图片中提取元数据字段**（格式识别 + 读取）：
- `sd_prompt_reader/image_data_reader.py`：
  - 识别 PNG/JPEG/WEBP 的不同写法（A1111 / ComfyUI / Fooocus / SwarmUI / NovelAI …）
  - 决定调用哪个 parser

2) **把元数据解析成结构化结果**（正/负 prompt、参数、设置）：
- `sd_prompt_reader/format/`：每个文件一种“元数据文本/JSON 语义”
  - `a1111.py`：A1111 `parameters` 文本解析
  - `comfyui.py`：ComfyUI `prompt` JSON 图遍历，抽取 prompt 与参数
  - 其它格式：`easydiffusion.py` / `invokeai.py` / `fooocus.py` / `novelai.py` / `swarmui.py`

3) **少量通用工具**：
- `sd_prompt_reader/utility.py`：`merge_dict/remove_quotes/concat_strings` 之类（可直接按逻辑翻译）

## 2. Android 端“复用”可选路线（按推荐度排序）

### 路线 A（推荐）：Kotlin/Java 端口核心解析逻辑

**思路**：Android 负责两件事：
1) 读取图片元数据（PNG chunk / EXIF / XMP 等）得到原始字符串/JSON
2) 用 Kotlin 复刻 `format/*.py` 的解析逻辑，输出统一数据结构

优点：
- 体积小、性能好、部署简单
- 避免在 Android 上解决 Pillow/CPython/ABI 的打包与兼容

成本：
- 需要把 `image_data_reader.py` + `format/*.py` 迁移成 Kotlin（但逻辑总体可控）

> 重要：你要求 **Windows GUI 功能一个不少**，意味着 Android 端还必须实现 `save_image()` 对应的“写入/清除元数据”能力；这部分在移动端比“读取”更容易踩坑，需要提前选好写入策略（见 2.4）。

#### 2.1 Kotlin 侧建议的模块切分

- `core/model`：
  - `ParsedPrompt(positive:String, negative:String, setting:String, params:Map<String,String>, raw:String, tool:String, isSdxl:Boolean, positiveSdxl:Map<String,String>, negativeSdxl:Map<String,String>)`
- `core/extract`：
  - `MetadataExtractor`：给定图片输入流 -> 提取候选字段（PNG: tEXt/iTXt；JPEG/WebP: ExifInterface UserComment/XMP）
- `core/format`：
  - `A1111Parser`（对标 `sd_prompt_reader/format/a1111.py`）
  - `ComfyUiParser`（对标 `sd_prompt_reader/format/comfyui.py`）
  - 其它格式 parser（按你需要逐步补）
- `core/detect`：
  - `ToolDetector`：模仿 `ImageDataReader.read_data()` 的 if/elif 检测顺序

#### 2.2 元数据读取实现建议

- PNG（关键：ComfyUI/A1111）：
  - 需要读取 `tEXt` / `iTXt` / `zTXt` chunk 的键值对
  - 常见键：
    - A1111：`parameters`
    - ComfyUI：`prompt`（JSON 字符串），有时还有 `workflow`
  - 实现方式：
    - 用成熟库（例如 pngj / Apache Commons Imaging）读取文本 chunk
    - 或写一个最小 PNG chunk reader（按 PNG chunk 格式顺序扫描，抓取 tEXt/iTXt）

- JPEG / WebP：
  - 优先用 `androidx.exifinterface.media.ExifInterface` 读取 `TAG_USER_COMMENT`（A1111/EasyDiffusion/SwarmUI 等会用）
  - 注意 UserComment 的编码与前缀（等价于 Python 里 `piexif.helper.UserComment.load` 的处理）
  - WebP 的元数据来源更复杂：可能是 EXIF chunk 或 XMP chunk；建议先按你要支持的图片来源做覆盖

#### 2.3 全格式覆盖点（你要求 SwarmUI/Fooocus/NovelAI 也必须支持）

下面按当前 Python 实现（`sd_prompt_reader/image_data_reader.py`）总结“识别信号 + 需要读到的字段”，Android 端需要逐条实现：

- **ComfyUI（PNG）**
  - 识别：PNG 文本 chunk 存在 `prompt`（JSON 字符串）
  - 可选：`workflow`

- **A1111（PNG/JPEG/WEBP）**
  - 识别：
    - PNG：文本 chunk 的 `parameters`
    - JPEG/WEBP：EXIF `UserComment`
  - 解析：纯文本（见 `sd_prompt_reader/format/a1111.py`）

- **SwarmUI（PNG/JPEG）**
  - 识别：
    - PNG：`parameters` 里包含 `sui_image_params`
    - JPEG：EXIF `UserComment`（UTF-16）里包含 `sui_image_params`
    - 旧版：EXIF tag `0x0110`（Model）里塞了 JSON，且包含 `sui_image_params`
  - 解析：JSON 的 `sui_image_params` 对象（见 `sd_prompt_reader/format/swarmui.py`）

- **Fooocus（PNG/JPEG/WEBP）**
  - 识别：
    - PNG：`Comment`（JSON）
    - JPEG/WEBP：当前 Python 走 `info["comment"]`（实现上来自 EXIF/XMP/容器 comment，Android 需要基于样本确认来源）
  - 解析：JSON（见 `sd_prompt_reader/format/fooocus.py`）

- **NovelAI（两种）**
  - 旧版（PNG/WebP）：
    - 识别：`Software == "NovelAI"` + `Comment` JSON
  - stealth pnginfo（PNG/WebP）：
    - 识别：需要按像素的 LSB 提取 magic `stealth_pngcomp`
    - 解析：从像素 LSB 读长度（32bit big-endian）-> 读 gzip 压缩 JSON -> 解压 JSON（见 `sd_prompt_reader/format/novelai.py`）
  - Android 实现要点：必须把图片解码成 ARGB_8888 并按“alpha 通道最低位”逐 bit 扫描。

#### 2.4 写入/清除/编辑（Windows GUI 必备功能）

Windows 版的“Edit/Save/Clear/Export”在 Python 里主要落在 `ImageDataReader.save_image()`：

- **清除（Clear）**：去掉元数据，保存新文件
- **编辑并保存（Edit/Save）**：把构造出来的 A1111 风格文本写入图片元数据
- **导出（Export）**：写 txt/json 文件

Android 端想做到“功能一个不少”，建议把写入拆成三层策略：

1) **PNG（推荐：文件级写入，不重编码像素）**
   - 直接改写/新增 tEXt/iTXt chunk。
   - 关键兼容点：
     - 如果源 PNG 含 ComfyUI 的 `prompt`/`workflow`，Windows 版会避免添加标准键 `parameters` 导致 ComfyUI 导入简化 workflow；而是写到 `sd_prompt_reader_parameters`。
     - 同时会“保留并原样写回” `prompt`/`workflow`。
   - Android 也应实现同样逻辑，否则会出现“编辑后 ComfyUI workflow 丢节点/丢自定义节点”的问题。

2) **JPEG/WEBP（写 EXIF UserComment）**
   - 目标：写入 `UserComment`（Unicode）以兼容 A1111/EasyDiffusion 读法。
   - 若 `ExifInterface` 在目标 Android 版本/机型上对 WEBP 写入不稳定：
     - 备选：用纯 Java 库（如 commons-imaging）重写 EXIF segment
     - 或保底方案：解码后重新压缩生成新文件（可能改变体积/质量；但 Windows 版本身也可能重写文件）

3) **NovelAI stealth**
   - Windows 版的 Edit/Save 并不是写回 NovelAI stealth，而是以 A1111 风格写入（README 里也提示“编辑后将以 A1111 格式进行写入”）。
   - Android 端建议保持一致：编辑后统一写 A1111 风格（PNG: 文本 chunk；JPEG/WEBP: UserComment）。

### 路线 B：在 Android 上嵌 CPython（Chaquopy 等）直接跑 Python

**思路**：尽量保持 Python 解析器不动，在 Android 里嵌 Python，然后通过 JNI/桥接调用。

风险/成本：
- Pillow 在 Android 的 wheel/编译/ABI 适配是主要障碍
- 包体积会显著变大
- 读写图片元数据的方式在 Android 可能仍需重写（或引入更多依赖）

适用场景：
- 你非常强调“代码字面复用”，且能接受更复杂的构建链路

### 路线 C：把解析器抽到 Rust（或 KMP）做跨平台核心

**思路**：将“解析元数据字符串/JSON -> ParsedPrompt”实现为 Rust crate 或 Kotlin Multiplatform module，桌面/Android 共用。

适用场景：
- 你同时要维护桌面版 + Android，且愿意投入一次性重构

## 3. ComfyUI workflow/prompt 解析要点（Android 需要复刻）

核心逻辑在 `sd_prompt_reader/format/comfyui.py`：

1) 从 PNG 元数据取 `prompt`（通常是 JSON 字符串；有时 Pillow 已解析为 dict）
2) 找“流的末端节点”（`SaveImage` 或 KSampler 类型）
3) 从末端节点向上递归遍历 `_comfy_traverse()`：
   - 遇到 KSampler：解析 `positive`/`negative` 指向的 CLIPTextEncode 节点链，抽取文本
   - 解析 model/seed/steps/cfg/sampler 等参数
   - 兼容 SDXL（Clip G / Clip L / Refiner 的多组 prompt）
4) 组装 `setting` 与 `parameter` 字段

Android 端复刻时，优先保持这套“遍历图”的策略不变（否则会遇到各种自定义节点与复杂 workflow 的边界问题）。

## 4. 实施建议（最小可用路径）

1) 第一阶段：只支持 PNG（A1111 `parameters` + ComfyUI `prompt`）
2) 第二阶段：补 JPEG（ExifInterface UserComment）
3) 第三阶段：补 SwarmUI/Fooocus/NovelAI（含 NovelAI stealth LSB）
4) 第四阶段：把 Edit/Save/Clear 的“写入策略”按 PNG/JPEG/WEBP 全打通

## 6. 验收与对齐（强烈建议，否则很难保证“一个不少”）

建议把 Python 版当作权威参考实现，建立“测试向量/fixtures”：

- 用 Python 批量读取你的样本图片，输出规范化 JSON（tool/status/positive/negative/setting/parameter/raw/is_sdxl 等）
- Android 端读取同一批样本，跑 Kotlin 解析器，输出同结构 JSON
- 两边做 diff，确保全格式一致

仓库内可新增一个脚本来生成 fixtures（见 `tools/generate_fixtures.py`）。

## 5. 对你当前需求的下一步建议

如果你的 Android app 目标就是“读取 ComfyUI workflow 图片里的 prompt”，我建议你先做：
- Kotlin 版 PNG 文本 chunk reader（只要能取到 `prompt`/`workflow`/`parameters`）
- Kotlin 版 `ComfyUiParser`（复刻 `comfyui.py` 的图遍历与 prompt 抽取）

完成这两步后，Android 上就能覆盖 ComfyUI 的主要场景；其它格式可以渐进式补齐。
