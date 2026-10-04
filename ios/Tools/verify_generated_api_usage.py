#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_generated_api_usage.py — 核对 iOS 侧对 UniFFI 生成绑定的调用是否都存在。

## 为什么需要
这是**本地唯一能闭环验证「会不会编译不过」的风险面**：`ios/Generated/LianyuAgent.swift`
是固定的 API 表面，而我的业务代码要调用它。任何一处调用了不存在的方法/构造函数，
在没有 Mac 的情况下都发现不了，只会在真机上变成编译错误。

## 做法
1. 从生成绑定里抽出关键类型的公开成员（构造器 / 方法 / 属性）
2. 在业务代码里找出对这些类型的调用
3. 报告「调用了但未声明」的成员

已知限制（宁可漏报也不要误报）：
- 只检查本脚本列举的类型；GRDB / SwiftUI / 系统框架不查
- 用正则抽取，遇到条件编译或复杂泛型可能漏抽成员 —— 那种情况会表现为
  「成员未声明」的误报，此时应把该成员加进白名单而不是删检查
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATED = REPO_ROOT / "ios/Generated/LianyuAgent.swift"
BUSINESS_DIRS = [
    REPO_ROOT / "ios/YuNian",      # Agent / App / Data / Platform
    # ⚠️ 第 69 轮：**把测试目录也纳入核对**。
    # 原来只核生产代码的 104 处调用，但测试**同样**在调用生成绑定
    # （构造 AgentGlobalConfig、调用 AgentRuntime 等）。
    # 一旦测试侧用了不存在的成员/参数名，就是编译错误 —— 而这一侧从没被核过。
    # 这与第 68 轮 verify_imports 漏掉整个测试目录是同一类盲区：
    # 关卡的扫描范围停在生产代码，测试代码成了第二个"没人验"的世界。
    REPO_ROOT / "ios/YuNianTests",
]

# 要核对的**生成绑定**类型（`ios/Generated/LianyuAgent.swift` 里的 open class）。
# ⚠️ 只放生成代码里的类型 —— 不要把业务自己的类型放进来（例如 AgentHostThreading
# 是我方定义的 enum，放进来会产生「未声明」的误报）。
TARGET_TYPES = [
    "AgentRuntime",
    "AgentGlobalConfig",
    "PromptOrchestrator",
    "MemorySelector",
    "SkillSelector",
]

# 结构体单独处理（生成侧是 `public struct X { public var y: ... }`，不是 open class）
#
# ⚠️ 第 105 轮发现的盲区：这个列表原先只有 AgentTurnRequest / AgentTurnResult，
# 而后续新写的代码用了 ApiProbeConfig / HttpHeader / ProbeMessage / ToolDefinition ——
# 于是对它们的构造**参数名**完全没校验。变异测试证明：把
# `provider: config.provider` 改成 `providerXXX: ...` 照样通过。
# 那正是「本地唯一能闭环验证编译风险」最该抓住的一类错。
# **新增用到生成结构体时，必须同步加进来。**
TARGET_STRUCTS = [
    "AgentTurnRequest",
    "AgentTurnResult",
    "ApiProbeConfig",
    "HttpHeader",
    "ProbeMessage",
    "ToolDefinition",
    "AgentEvent",
]


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def strip_doc_comments(src: str) -> str:
    """去掉 `///` 与 `/** */` 注释 —— 注释里出现 `Type.member` 不该算调用。

    第 27 轮的教训：不剥注释时，文档里提一句 `AgentGlobalConfig.db_path`
    （Rust 字段名）就会被当成「调用了不存在的成员」而误报。
    """
    out = []
    for line in src.split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("///") or stripped.startswith("//"):
            continue
        out.append(line)
    text = "\n".join(out)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return text


def _type_block(src: str, type_name: str) -> str:
    """取一个类型的声明体（兼容 `open class X {` 与 `public struct X {`）。"""
    m = re.search(rf"(?:open class|public struct|public final class) {re.escape(type_name)}\b", src)
    if not m:
        return ""
    try:
        i = src.index("{", m.start())
    except ValueError:
        return ""
    depth = 0
    end = i
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                end = j
                break
    return src[i + 1:end]


def _init_labels(body: str) -> set[str]:
    """抽出所有 init 的参数标签（init 可能跨多行）。"""
    labels: set[str] = set()
    for im in re.finditer(r"public (?:convenience )?init\(", body):
        k = im.end() - 1
        depth = 0
        close = k
        for j in range(k, len(body)):
            if body[j] == "(":
                depth += 1
            elif body[j] == ")":
                depth -= 1
                if depth == 0:
                    close = j
                    break
        params = body[k + 1:close]
        for pm in re.finditer(r"(?:^|,)\s*(?:_\s*)?(\w+)\s*:\s*[^,]+", params):
            label = pm.group(1)
            if label:
                labels.add(label)
    return labels


def extract_members(src: str, type_name: str) -> set[str]:
    """抽出一个类型的全部公开成员：func / var / let / init 参数标签。"""
    body = _type_block(src, type_name)
    if not body:
        return set()
    members: set[str] = set()
    members |= set(re.findall(r"\bfunc\s+(\w+)", body))
    members |= set(re.findall(r"\bvar\s+(\w+)\s*:", body))
    members |= set(re.findall(r"\blet\s+(\w+)\s*:", body))
    members |= _init_labels(body)
    return members


def _top_level_labels(args: str) -> list[str]:
    """取参数串里的**顶层**标签。

    ⚠️ 必须先剥掉嵌套的 `(...)`：`AgentGlobalConfig(orchestrator: PromptOrchestrator(
    memory: ..., skill: ...))` 里，`skill:` 是内层的标签，不是 AgentGlobalConfig 的参数。
    不剥嵌套会把内层标签当成外层的，产生假阳性（第 27 轮实测踩到）。
    """
    text = args
    # 反复剥掉最内层的 (...) 直到没有嵌套
    while True:
        new = re.sub(r"\([^()]*\)", "", text)
        if new == text:
            break
        text = new
    return re.findall(r"(?:^|,)\s*(\w+)\s*:", text)


def main() -> int:
    gen = read(GENERATED)
    # 每个类型都用**同一套**抽取（兼容 class 与 struct，含 init 参数标签）
    allowed = {t: extract_members(gen, t) for t in TARGET_TYPES + TARGET_STRUCTS}

    missing: list[str] = []
    checked = 0

    for d in BUSINESS_DIRS:
        if not d.exists():
            continue
        for path in sorted(d.rglob("*.swift")):
            src = strip_doc_comments(read(path))
            rel = path.relative_to(REPO_ROOT)

            for t in TARGET_TYPES:
                for call in re.findall(rf"\b{re.escape(t)}\.(\w+)", src):
                    checked += 1
                    if call == "init":
                        continue
                    if call not in allowed[t]:
                        missing.append(f"{rel}: {t}.{call}")

            # 变量形式：`runtime.foo(` —— 这是最高价值的一类
            for call in re.findall(r"\bruntime\??\.(\w+)", src):
                checked += 1
                if call not in allowed["AgentRuntime"]:
                    missing.append(f"{rel}: runtime.{call}")

            # 构造器的**参数标签**：`TypeName(\n  label: value,\n ...)`
            # 变异测试证明：不查这一项的话，构造器里多写/写错一个字段抓不到，
            # 而那正是会编译失败的错误。
            for t in TARGET_STRUCTS + TARGET_TYPES:
                for cm in re.finditer(rf"\b{re.escape(t)}\s*\(", src):
                    k = cm.end() - 1
                    depth2 = 0
                    close = k
                    ok = False
                    for j in range(k, len(src)):
                        if src[j] == "(":
                            depth2 += 1
                        elif src[j] == ")":
                            depth2 -= 1
                            if depth2 == 0:
                                close = j
                                ok = True
                                break
                    if not ok:
                        continue
                    args = src[k + 1:close]
                    for label in _top_level_labels(args):
                        checked += 1
                        if label not in allowed[t]:
                            missing.append(f"{rel}: {t}(...) 的参数标签 {label} 未声明")

    print(f"检查 {checked} 处对生成绑定的调用/构造参数")
    missing = sorted(set(missing))
    if missing:
        print(f"未声明成员 {len(missing)} 处：")
        for m in missing:
            print("  -", m)
        return 1
    print("全部调用的成员与构造参数都在生成绑定中存在")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
