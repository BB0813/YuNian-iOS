# -*- coding: utf-8 -*-
"""为 iOS 生成占位 AppIcon（CI 需要，真实图标由设计后续替换）。

CI 报：

    None of the input catalogs contained a matching stickers icon set
    or app icon set named "AppIcon".

Assets.xcassets 里只有 avatar_xiaoyu.imageset，没有 AppIcon.appiconset。
iOS 的 app icon 是必需的（上架更要，模拟器构建也要求存在）。

本脚本生成一个**单尺寸 1024x1024 纯色**占位图标。
它是占位，不是设计产物 —— 见其中的说明注释。
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SET = ROOT / "ios/YuNian/Resources/Assets.xcassets/AppIcon.appiconset"


def make_png(path: Path, size: int = 1024, rgb=(98, 108, 255)) -> None:
    """写一个最小合法 PNG（纯色，无第三方依赖）。"""
    import struct
    import zlib

    w = h = size
    raw = bytearray()
    row = bytes(rgb) * w
    for _ in range(h):
        raw.append(0)          # filter type 0
        raw.extend(row)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


def main() -> int:
    SET.mkdir(parents=True, exist_ok=True)
    make_png(SET / "icon_1024.png")
    contents = {
        "images": [
            {"filename": "icon_1024.png", "idiom": "universal",
             "platform": "ios", "size": "1024x1024"}
        ],
        "info": {"author": "xcode", "version": 1},
    }
    (SET / "Contents.json").write_text(
        json.dumps(contents, indent=2, ensure_ascii=False), encoding="utf-8")
    png = SET / "icon_1024.png"
    print(f"已生成占位 AppIcon：{png.relative_to(ROOT)}  {png.stat().st_size} bytes")
    # ⚠️ 第 76 轮：这里原来用了 ⚠️ emoji，Windows 控制台按 GBK 编码时
    # UnicodeEncodeError，导致脚本以非零退出。真倒霉，生成本身是成功的。
    print("注意：这是 1024x1024 纯色占位，不是设计产物。上架前必须替换为真实图标。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
