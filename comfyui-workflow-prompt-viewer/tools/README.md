# Tools

## generate_fixtures.py

用本仓库的 Python 解析结果生成 fixtures（JSON），用于 Android(Kotlin/Java) 端做对齐测试。

### 运行

```bash
python tools/generate_fixtures.py -i <图片文件或目录> -o <输出目录> --pretty
```

### 输出

- `<filename>.fixture.json`：包含 tool/status/positive/negative/setting/parameter/raw/is_sdxl 等。
- `_failures.json`：如果解析过程中抛异常，会记录在这里。

### 建议用法

1. 收集样本图片（尽量用各工具原始输出）
2. 生成 fixtures
3. Android 端读取同样本，输出同结构 JSON
4. 对两个 JSON 做 diff，逐格式修正直到一致
