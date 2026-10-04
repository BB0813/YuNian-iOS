#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
find_dead_swift.py — 找出 iOS 侧「声明了但从未被引用」的类型 / 函数 / 属性。

## 为什么需要
项目里有 55 个 Swift 文件、21,650 行，其中相当一部分是这几十轮增量写的。
在**从未编译过**的前提下，一个声明了却没人引用的成员是双重负担：
  1. 它可能引用了错误的东西（类型名拼错等），而没人调用就没人发现
  2. 它让读代码的人误以为某能力已实现

本轮动因：我发现 `ApiProbeService.ProbeResult` 定义后从未使用。

## 判据（保守，宁漏不错）
- 只扫 `ios/YuNian/**`（不含 Tests，测试引用算使用）
- 成员名在整个 YuNian 源码里出现 **0 次**（除声明处）才算死
- 协议方法、`@objc`、`@Published`、`init`、`deinit`、运算符、重写一律跳过
- 名下有 `// TODO` / `TODO:` 的跳过（有意预留）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "ios/YuNian"

SKIP_PREFIX = {"@objc", "@IBAction", "@Published", "@MainActor", "@escaping", "@Sendable", "@discardableResult", "@ViewBuilder"}
SKIP_NAMES = {"init", "deinit", "body", "makeContent", "hash", "==", "!=", "<", ">", "+", "-", "*", "/", "%"}

# 框架按协议**反射调用**的方法 —— 本仓源码里看不到调用点，
# 但它们是活的。第 147 轮加：不加时三态报告里
# `makeUIViewController` / `documentPicker` / `onTextDelta` 等都被误判为未引用，
# 稀释了报告（36 项里约 10 项是这类）。
FRAMEWORK_CALLED = {
    # UIViewControllerRepresentable
    "makeUIViewController", "updateUIViewController", "makeCoordinator",
    # UIDocumentPickerDelegate
    "documentPicker",
    # UniFFI 外来 trait（Rust 调 iOS）
    "onTextDelta", "onReasoningDelta", "onDone", "onError",
    "signHeaders", "executeTool", "toolSources", "onRound",
    "getMemoryContent", "setLastConsolidatedAt", "embedText",
    # SwiftUI / App 生命周期
    "windowGroup", "scenePhase",
    # Combine ObservableObject
    "objectWillChange",
}


def files() -> list[Path]:
    return sorted(p for p in SRC.rglob("*.swift"))


def main() -> int:
    fs = files()
    if not fs:
        print("没有找到 Swift 源文件")
        return 1
    texts = {p: p.read_text(encoding="utf-8", errors="ignore") for p in fs}
    # 两个语料库分开：生产代码 vs 测试。
    # 只把测试算作引用会掩盖「功能已测但没接 UI」—— 第 120 轮的 BackupImporter 即是。
    app_corpus = "\n".join(texts.values())
    tests_dir = REPO_ROOT / "ios/YuNianTests"
    tests_corpus = ""
    if tests_dir.exists():
        tests_corpus = "\n".join(
            p.read_text(encoding="utf-8", errors="ignore")
            for p in sorted(tests_dir.rglob("*.swift"))
        )
    corpus = app_corpus   # 保留旧名以最小化改动面

    dead: list[tuple[str, str, str]] = []
    test_only: list[tuple[str, str, str]] = []

    dead: list[tuple[str, str, str]] = []   # (file, member, kind)

    for p, text in texts.items():
        rel = str(p.relative_to(REPO_ROOT))
        # 顶层 / 嵌套的 func / var / let / struct / class / enum
        for m in re.finditer(
            r"^\s*(?:(?:private|public|internal|fileprivate|static|final|open)\s+)*"
            r"(func|var|let|struct|class|enum|protocol)\s+([A-Za-z_]\w*)",
            text, re.M,
        ):
            kind, name = m.group(1), m.group(2)
            if name in SKIP_NAMES or kind == "protocol":
                continue
            if name in FRAMEWORK_CALLED:
                continue          # 框架按协议调用，源码里看不到调用点
            line_start = text.rfind("\n", 0, m.start()) + 1
            line = text[line_start:text.find("\n", m.start())]
            if any(k in line for k in SKIP_PREFIX):
                continue
            if "TODO" in line:
                continue
            # 该名字在**生产代码**里出现几次（除声明那次）
            # ⚠️ 第 120 轮：此前只用一个合并语料库（生产 + 测试），于是
            # 「只被测试引用」的功能（BackupImporter / BackupCrypto）看起来是活的。
            # 但那种状态的真实含义是：**已实现、已单测、用户却触达不到**。
            in_app = len(re.findall(rf"\b{re.escape(name)}\b", app_corpus))
            if in_app > 1:
                continue
            in_tests = len(re.findall(rf"\b{re.escape(name)}\b", tests_corpus))
            if in_tests > 0:
                test_only.append((rel, name, kind))
            else:
                dead.append((rel, name, kind))

    if not dead and not test_only:
        print(f"未发现死代码或未接线功能（扫描 {len(fs)} 个文件）")
        return 0

    if dead:
        print(f"完全未引用 {len(dead)} 处：")
        for rel, name, kind in dead:
            print(f"  ?? {kind} {rel}: {name}")
    if test_only:
        print(f"\n仅被测试引用、**无 UI 入口** {len(test_only)} 处"
              f"（已实现已测，但用户触达不到）：")
        for rel, name, kind in test_only:
            print(f"  ?? {kind} {rel}: {name}")
    print("\n处理：")
    print("  · 完全未引用 → 确认无用则删，或加 TODO 说明预留")
    print("  · 仅测试引用 → 需要 UI 入口才能交付")
    # report-only
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
