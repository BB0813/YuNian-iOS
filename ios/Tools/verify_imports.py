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


def _main_imports() -> int:
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
            else:
                # ⚠️ 第 172 轮：补"模块限定形式"的检查。
                # 原来的循环只匹配裸符号名，于是 `GRDB.Row.fetchAll(...)`
                # 这种写法**完全漏过** —— CI（d7d0f06）报
                # "cannot find 'GRDB' in scope"，而本地关卡一声不响。
                #
                # 这是同一个坑第二次：第 139 轮 MessageSearchView 漏
                # import GRDB（那次是用裸符号，关卡抓到了）；
                # 这次我用限定名避开类型名冲突，反而绕过了关卡。
                # 修法：`<Module>.<任意标识符>` 形态也视为用到该模块。
                if re.search(rf"\b{module}\s*\.\s*[A-Za-z_]", text):
                    problems.append(
                        f"{p.relative_to(REPO_ROOT)}: 用到 {module}.X 限定形式但未 import {module}"
                    )
                    break

    if problems:
        print(f"[FAIL] 缺失 import {len(problems)} 处：")
        for q in problems:
            print("  -", q)
        return 1

    print(f"import 覆盖核对通过（{len(files)} 个文件 / {len(MODULE_BY_SYMBOL)} 个符号映射）")
    return 0


def check_testable_import() -> int:
    """所有测试文件必须有 `@testable import YuNian`（第 140 轮新增）。

    起因：我新写 YuNianLiquidTabsTests 时只写了 `import XCTest`，
    结果测试 target 看不到 App target 的类型，CI 报 10 处
    "cannot find 'YuNianTheme' in scope"。
    仓内其它 14 个测试文件都有这一行，我漏了 —— 而这种遗漏
    **编译期才发现**，代价是一整轮 CI。

    这条规则把「写新测试文件必带 @testable import」固化成关卡。
    """
    tests = REPO_ROOT / "ios/YuNianTests"
    if not tests.exists():
        return 0
    bad = []
    for f in sorted(tests.glob("*.swift")):
        # ⚠️ 第 140 轮：必须**按行**判断，不能子串搜索整个文件。
        # 我第一版用 `"@testable import YuNian" not in text`，
        # 结果变异测试一跑就发现是**假阴性**：
        # 文件顶部的说明注释里逐字提到了这个字符串，
        # 于是真把 import 删掉，关卡照样放行。
        # （同一坑第 50/72/121 轮踩过三次：注释会骗过子串匹配。）
        has = any(
            ln.lstrip().startswith("@testable import YuNian")
            for ln in f.read_text(encoding="utf-8", errors="ignore").splitlines()
        )
        if not has:
            bad.append(f.name)
    if bad:
        print("[FAIL] 测试文件缺少 `@testable import YuNian`：")
        for n in bad:
            print(f"  - {n}")
        print("       （测试 target 需经它才能看到 App target 的类型）")
        return 1
    print(f"[ok] {len(list(tests.glob('*.swift')))} 个测试文件都有 @testable import YuNian")
    return 0


def _balanced_call(text: str, open_paren: int) -> str:
    """从 `(` 的位置起取到配平的 `)`（含），用于粗看一次调用的实参。"""
    depth = 0
    for i in range(open_paren, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren:i + 1]
    return text[open_paren:]


def check_insert_arguments() -> int:
    """测试里调 `MessageRepository.insert(...)` 必须带 `searchContent:`（第 227 轮）。

    起因与上面那条一样，但更具体：`searchContent` 在签名里是**必填**
    （`searchContent: String`，FTS 正文，刻意不给默认值 —— 逼调用方想清楚
    "这条消息该被什么关键词搜到"）。我写 `NotificationSettingsTests` 时漏了它，
    **本地构建只编 App、不编测试目标**，于是一路绿灯，CI 才报编译错 —— 又是一整轮。

    这条关卡只做一件窄事，但正好堵住这个反复出现的坑：
    逐个 `insert(` 调用取出配平括号内的实参文本，看有没有 `searchContent:`。

    自我保护：**先去真实签名里确认该参数仍是必填** ——
    哪天有人把它改成 `searchContent: String = ""`，这条检查就该自动失效，
    而不是继续逼着测试写一个已经可省的参数。
    """
    repo_file = REPO_ROOT / "ios/YuNian/Data/Repositories/MessageRepository.swift"
    if not repo_file.exists():
        return 0
    signature = repo_file.read_text(encoding="utf-8", errors="ignore")
    if not re.search(r"searchContent:\s*String\s*,", signature):
        print("[ok] searchContent 已不是必填参数，跳过该检查")
        return 0

    tests = REPO_ROOT / "ios/YuNianTests"
    if not tests.exists():
        return 0

    bad = []
    checked = 0
    for f in sorted(tests.glob("*.swift")):
        text = f.read_text(encoding="utf-8", errors="ignore")
        # ⚠️ 只查**绑定了 MessageRepository 的那个变量**。
        # 第一版我匹配所有 `.insert(` —— 结果把字典/集合的 `insert` 也算了进来
        # （`EmbeddingClientTests` / `ImageGenClientTests` 各报了几处假阳性）。
        # 关卡报假阳性比漏报更糟：会训练人忽略它。
        names = set(re.findall(r"(\w+)\s*=\s*MessageRepository\(", text))
        for name in names:
            for m in re.finditer(rf"\b{re.escape(name)}\.insert\(", text):
                checked += 1
                call = _balanced_call(text, m.end() - 1)
                if "searchContent:" not in call:
                    line = text[:m.start()].count("\n") + 1
                    bad.append(f"{f.name}:{line}")
    if bad:
        print("[FAIL] 测试里的 insert 调用缺少必填参数 `searchContent:`：")
        for q in bad:
            print("  -", q)
        print("       （该参数无默认值，漏了会在测试 target 编译期才报错）")
        return 1
    print(f"[ok] 测试里 {checked} 处 MessageRepository.insert 都带了 searchContent")
    return 0


def _code_only(line: str) -> str:
    """去掉行注释与字符串字面量，只留代码。

    ⚠️ 第一版直接 `"try " in line` —— 把**中文注释里提到 try 的那几行**也算进来了，
    于是三处本来正确的测试被误报。关卡报假阳性比漏报更糟：会训练人忽略它。
    字符串字面量同理（断言消息里可能写到 `try`）。
    """
    # 去行注释（粗粒度：不处理 `//` 出现在字符串里的情况，对本仓库够用）
    out = []
    in_string = False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == '"' and (i == 0 or line[i - 1] != "\\"):
            in_string = not in_string
            i += 1
            continue
        if not in_string and ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        if not in_string:
            out.append(ch)
        i += 1
    return "".join(out)


def check_throws_declarations() -> int:
    """测试里用了 `try` 的函数必须声明 `throws`（第 229 轮新增）。

    起因：`ContextLedgerTests` 里多个用例写了 `try XCTUnwrap(...)` 却没标 `throws`，
    CI 报 `errors thrown from here are not handled`。
    **这是同一类盲点第四次咬人**（第 140 轮漏 `@testable import`、
    第 221 轮可选解包层级、第 227 轮漏必填参数，都是"本地不编测试目标 → 只有 CI 拦"）。

    规则很窄：在 `ios/YuNianTests/*.swift` 里，
    一个 `func xxx(...) {` 的函数体若出现 `try `，签名必须带 `throws`。

    自我保护：只查**顶层函数**（按缩进识别，跳过嵌套闭包），
    并跳过已经是 `throws` / `rethrows` 的签名。
    """
    tests = REPO_ROOT / "ios/YuNianTests"
    if not tests.exists():
        return 0

    bad = []
    checked = 0
    for f in sorted(tests.glob("*.swift")):
        lines = f.read_text(encoding="utf-8", errors="ignore").splitlines()
        i = 0
        while i < len(lines):
            # 修饰符（throws / async / -> T）都在 `{` 之前，统一用组 4 捕获。
            # ⚠️ 第一版把 `-> T` 写成可选组、再要求紧跟 `{` —— 于是
            # `func x() throws {` **根本匹配不到**（`throws` 挡在中间），
            # 检查等于空跑（报 0 个）。这种"关卡空转"比误报更危险：看着通过，其实没查。
            m = re.match(r"^(\s+)func (\w+)\(([^)]*)\)([^{]*)\{\s*$", lines[i])
            if not m:
                i += 1
                continue
            indent, name, _params, modifiers = m.group(1), m.group(2), m.group(3), m.group(4)
            signature_throws = "throws" in modifiers or "rethrows" in modifiers
            # 收集函数体（同缩进的 func 之前）
            j = i + 1
            body = []
            while j < len(lines) and not re.match(rf"^{indent}func ", lines[j]):
                body.append(lines[j])
                j += 1
            # 只查 `try XCTUnwrap` —— 这是实际反复咬人的那一个模式
            # （第 221/229 轮）。放宽到 "try " 会因函数体扫描跨段而误报。
            uses_try = any("try XCTUnwrap" in _code_only(ln) for ln in body)
            if uses_try:
                checked += 1
                if not signature_throws:
                    bad.append(f"{f.name}:{i + 1} {name}")
            i = j

    if bad:
        print("[FAIL] 测试里用了 try 但未声明 throws：")
        for q in bad:
            print("  -", q)
        print("       （`try XCTUnwrap` 等要求函数标 throws，否则只在测试 target 编译期报错）")
        return 1
    print(f"[ok] {checked} 个用 try 的测试函数都声明了 throws")
    return 0


def main() -> int:
    rc = check_testable_import()
    if rc:
        return rc
    rc = check_insert_arguments()
    if rc:
        return rc
    rc = check_throws_declarations()
    if rc:
        return rc
    return _main_imports()


if __name__ == "__main__":
    raise SystemExit(main())
