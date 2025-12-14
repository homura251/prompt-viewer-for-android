"""Generate JSON fixtures from the reference Python implementation.

Purpose
- You要求 Android 端“全格式支持 + Windows GUI 功能一个不少”。
- 最稳妥的做法是：用本仓库的 Python 解析结果做金标准，批量生成 fixtures，
  Android(Kotlin/Java) 端对同一批图片做解析输出，再逐项 diff。

Usage
  python tools/generate_fixtures.py -i <image_or_folder> -o <out_folder>

Notes
- 输出是“解析结果快照”，不包含原图字节。
- 请尽量用“原始图片文件”作为输入（不要二次转存）。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

from sd_prompt_reader.image_data_reader import ImageDataReader


@dataclass
class Fixture:
    source: str
    status: str
    tool: str
    format: str
    width: int | None
    height: int | None
    is_sdxl: bool
    positive: str
    negative: str
    positive_sdxl: dict
    negative_sdxl: dict
    setting: str
    parameter: dict
    raw: str
    props: str


def _read_one(path: Path) -> Fixture:
    with path.open("rb") as f:
        image_data = ImageDataReader(f, is_txt=path.suffix.lower() == ".txt")

    def _to_int(value):
        try:
            return int(value)
        except Exception:
            return None

    return Fixture(
        source=str(path),
        status=image_data.status.name,
        tool=image_data.tool or "",
        format=image_data.format or "",
        width=_to_int(image_data.width),
        height=_to_int(image_data.height),
        is_sdxl=bool(image_data.is_sdxl),
        positive=image_data.positive or "",
        negative=image_data.negative or "",
        positive_sdxl=image_data.positive_sdxl or {},
        negative_sdxl=image_data.negative_sdxl or {},
        setting=image_data.setting or "",
        parameter=image_data.parameter or {},
        raw=image_data.raw or "",
        props=image_data.props or "",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-i", "--input", required=True, help="Image file or folder")
    parser.add_argument("-o", "--output", required=True, help="Output folder")
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print JSON (bigger files, easier to diff)",
    )
    args = parser.parse_args()

    source = Path(args.input)
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    if source.is_file():
        files = [source]
    else:
        files = [
            p
            for p in source.rglob("*")
            if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".txt"}
        ]

    failures: list[tuple[str, str]] = []

    for path in sorted(files):
        try:
            fixture = _read_one(path)
        except Exception as e:
            failures.append((str(path), f"EXCEPTION: {e}"))
            continue

        # Stable output file name
        safe_name = path.name.replace(" ", "_")
        target = out_dir / f"{safe_name}.fixture.json"

        payload = asdict(fixture)
        with target.open("w", encoding="utf-8") as f:
            json.dump(
                payload,
                f,
                ensure_ascii=False,
                indent=2 if args.pretty else None,
                sort_keys=True,
            )

    if failures:
        report = out_dir / "_failures.json"
        with report.open("w", encoding="utf-8") as f:
            json.dump(failures, f, ensure_ascii=False, indent=2)
        print(f"Done with failures: {len(failures)}. See: {report}")
        return 2

    print(f"Done. Fixtures: {len(files)} -> {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
