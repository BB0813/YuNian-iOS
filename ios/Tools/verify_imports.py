#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_imports.py — 核对每个 Swift 文件是否 import 了它用到的框架类型。

## 为什么需要
第 113 轮清点「尚未覆盖的编译风险面」时发现这一类完全没人管：
冒烟检查只做括号配平与三引号，生成绑定核对只查调用与构造参数，
都不管 `import`。而这类错误的症状是 `Cannot find type 'Logger' in scope`
——**报错点在首次编译，且一个文件漏 import 会连带几十个误报**，
让人以为有大问题，实际只是少一行。

## 覆盖的映射（按本仓实际用到的）
模块里出现的类型 → 必须 import 的模块。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "ios/YuNian"

# 类型/符号 → 必需模块
MODULE_BY_SYMBOL = {
    # Foundation（大量文件靠它，但 SwiftUI/GRDB 会传递导入，故只标强特征）
    "JSONSerialization": "Foundation",
    "DateComponentsFormatter": "Foundation",
    "NSRegularExpression": "Foundation",
    "CharacterSet": "Foundation",
    "NSRange": "Foundation",
    # os
    "Logger": "os",
    "OSAllocatedUnfairLock": "os",
    # CryptoKit
    "SymmetricKey": "CryptoKit",
    "AES": "CryptoKit",
    "SHA256": "CryptoKit",
    "Insecure": "CryptoKit",
    # CommonCrypto
    "CCKeyDerivationPBKDF2": "CommonCrypto",
    "CCPBKDFAlgorithm": "CommonCrypto",
    "CCPseudoRandomAlgorithm": "CommonCrypto",
    # SwiftUI
    "View": "SwiftUI",
    "NavigationStack": "SwiftUI",
    "@State": "SwiftUI",
    # Combine（@Published / ObservableObject）
    "ObservableObject": "Combine",
    "@Published": "Combine",
    # GRDB
    "DatabasePool": "GRDB",
    "FetchableRecord": "GRDB",
    "StatementArguments": "GRDB",
    # UserNotifications
    "UNUserNotificationCenter": "UserNotifications",
    "UNMutableNotificationContent": "UserNotifications",
    "UNNotificationRequest": "UserNotifications",
    # UIKit
    "UIPasteboard": "UIKit",
    "UIDevice": "UIKit",
    "UIApplication": "UIKit",
}

# 符号可能是类型名也可能是成员名；只在该符号「作为类型被使用」时才算。
# 用形态而不是精确解析：`Logger(` / `: Logger` / `<Logger>` / `Logger.` 的**类型位置**。
TYPE_POSITION_PATTERNS = [
    r":\s*{sym}\b",
    r"<\s*{sym}\s*>",
    r"\b{sym}\s*\(",
    r"\bas\s+{sym}\b",
    r"\bas\?\s+{sym}\b",
    r"\bas!\s+{sym}\b",
    r"\bis\s+{sym}\b",
    r"\breturn\s+{sym}\b",
    r"\[\s*{sym}\s*\]",
]


def main() -> int:
    files = sorted(SRC.rglob("*.swift"))
    if not files:
        print("[FAIL] 没有找到 Swift 文件")
        return 1

    problems: list[str] = []
    for p in files:
        text = p.read_text(encoding="utf-8", errors="ignore")
        imports = set(re.findall(r"^import\s+(\w+)", text, re.M))
        for sym, module in MODULE_BY_SYMBOL.items():
            if module in imports:
                continue
            # 其他模块可能传递导入（SwiftUI 带入 Combine 的 @Published 等）
            for pat in TYPE_POSITION_PATTERNS:
                if re.search(pat.format(sym=re.escape(sym)), text):
                    problems.append(
                        f"{p.relative_to(REPO_ROOT)}: 用到 {sym} 但未 import {module}"
                    )
                    break

    if problems:
        print(f"[FAIL] 缺失 import {len(problems)} 处：")
        for q in problems:
            print("  -", q)
        return 1

    print(f"import 覆盖核对通过（{len(files)} 个文件 / {len(MODULE_BY_SYMBOL)} 个符号映射）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
