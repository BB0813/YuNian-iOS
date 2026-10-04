#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_m0_prereqs.py — 核对 M0（macOS 实跑）的前提条件是否自洽。

## 为什么需要
第 100 轮自查 `ios/M0-RUNBOOK.md` 时发现：这份文档是「交给有 Mac 的人」的入口，
但它引用的东西（project.yml 的路径、构建脚本的产物、runbook 的步骤）
从来没有被机器核对过。一旦 `project.yml` 引用了不存在的目录，
实跑的人会在 `xcodegen generate` 这一步拿到一个难以归因的错误。

本脚本核对三件事：
1. `project.yml` 引用的路径存在（除**由构建脚本生成**的产物）
2. 构建脚本的产物路径与 `project.yml` 的期望一致
3. runbook 里的关键命令引用的文件存在
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IOS = REPO_ROOT / "ios"
PROJECT = IOS / "project.yml"
BUILD_SCRIPT = REPO_ROOT / "scripts/build_agent_ios.sh"
RUNBOOK = IOS / "M0-RUNBOOK.md"

# 这些路径**不在仓库里**，由 scripts/build_agent_ios.sh 在 macOS 上生成。
# 它们必须被 project.yml 引用（否则链接不到），所以按「应当缺失」验证。
GENERATED_BY_SCRIPT = {
    "Frameworks/lianyu_agent.xcframework",
    "Generated/LianyuAgent.swift",
    "Generated/lianyu_agentFFI.h",
    "Generated/lianyu_agentFFI.modulemap",
}


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def main() -> int:
    problems: list[str] = []

    yml = read(PROJECT)
    # ① project.yml 引用的路径
    refs = re.findall(r"(?:path|sources)\s*:\s*([\w./-]+)", yml)
    refs += re.findall(r"^\s*-\s*path:\s*([\w./-]+)", yml, re.M)
    for r in sorted(set(refs)):
        if not re.search(r"\.(swift|plist|json|json|strings|xcassets|png|h|modulemap)$", r):
            continue
        p = IOS / r
        if p.exists():
            continue
        if r in GENERATED_BY_SCRIPT:
            continue      # 由脚本生成，仓库里本来就没有
        problems.append(f"project.yml 引用了不存在的路径：{r}")

    # ② 构建脚本产物路径 ↔ project.yml 期望
    script = read(BUILD_SCRIPT)
    m = re.search(r'OUT_XCFRAMEWORK="([^"]+)"', script)
    if m:
        out = m.group(1)
        if "ios/Frameworks/lianyu_agent.xcframework" not in out.replace("$REPO_ROOT/", ""):
            problems.append(f"构建脚本产物路径异常：{out}")
        if "Frameworks/lianyu_agent.xcframework" not in yml:
            problems.append("project.yml 未引用构建脚本产出的 xcframework")
    else:
        problems.append("构建脚本里找不到 OUT_XCFRAMEWORK 定义")

    m = re.search(r'OUT_BINDINGS="([^"]+)"', script)
    if not m:
        problems.append("构建脚本里找不到 OUT_BINDINGS 定义")

    # ③ runbook 里的关键引用
    book = read(RUNBOOK)
    for token in (
        "scripts/build_agent_ios.sh", "xcodegen generate",
        "CODE_SIGNING_ALLOWED=NO", "YuNian.xcodeproj",
    ):
        if token not in book:
            problems.append(f"runbook 缺少关键引用：{token}")

    # ④ runbook 提到的每个 iOS 目录存在
    for d in ("YuNian", "YuNianTests", "project.yml"):
        if not (IOS / d).exists():
            problems.append(f"iOS 目录缺失：{d}")

    # ⑤ 构建脚本的 Rust 侧前提（第 101 轮补）
    #    build_agent_ios.sh 依赖这些 Cargo 配置；缺任何一项，
    #    M0 会在 --bindings-only 那一步失败，而错误信息离根因很远。
    cargo = read(REPO_ROOT / "agent-native/Cargo.toml")
    if 'name = "uniffi-bindgen"' not in cargo:
        problems.append("Cargo.toml 缺少 uniffi-bindgen bin（构建脚本依赖它生成绑定）")
    if 'required-features = ["cli-bin"]' not in cargo:
        problems.append("Cargo.toml 的 uniffi-bindgen bin 未声明 cli-bin required-features")
    if 'cli-bin = ["uniffi/cli"]' not in cargo:
        problems.append("Cargo.toml 缺少 cli-bin feature")
    if "staticlib" not in cargo or "cdylib" not in cargo:
        problems.append("Cargo.toml 的 crate-type 缺 staticlib/cdylib（iOS 链接需要）")
    # strip = "symbols" 或 true 会移除 .symtab → bindgen 的 library 模式抽不到
    # UNIFFI_META_* 符号 → 绑定生成失败（构建脚本 L78 也检查了这一点）
    if re.search(r"^\s*strip\s*=\s*(true|\"symbols\")", cargo, re.M):
        problems.append('Cargo.toml 的 strip 不能是 true/"symbols" —— 会移除 .symtab 导致绑定生成失败')

    uniffi = read(REPO_ROOT / "agent-native/uniffi.toml")
    for key in ("[bindings.swift]", 'module_name = "LianyuAgent"',
                'ffi_module_name = "lianyu_agentFFI"', 'cdylib_name = "lianyu_agent"'):
        if key not in uniffi:
            problems.append(f"uniffi.toml 缺少 {key}")

    # ⑦ Asset Catalog 完整性（第 104 轮补）
    #    imageset 的 Contents.json 引用了不存在的图片文件时，
    #    actool 会在 xcodebuild 阶段失败，且错误信息难以归因到根因。
    assets = IOS / "YuNian/Resources/Assets.xcassets"
    if assets.exists():
        for ij in sorted(assets.rglob("*.imageset/Contents.json")):
            try:
                import json as _json
                j = _json.loads(ij.read_text(encoding="utf-8"))
            except Exception as e:
                problems.append(f"imageset JSON 解析失败 {ij.parent.name}: {e}")
                continue
            for im in j.get("images", []):
                fn = im.get("filename")
                if fn and not (ij.parent / fn).exists():
                    problems.append(f"imageset {ij.parent.name} 引用了不存在的文件 {fn}")
        top = assets / "Contents.json"
        if top.exists():
            try:
                import json as _json
                _json.loads(top.read_text(encoding="utf-8"))
            except Exception as e:
                problems.append(f"Assets.xcassets/Contents.json 解析失败: {e}")
        else:
            problems.append("缺少 Assets.xcassets/Contents.json")
    else:
        problems.append("缺少 YuNian/Resources/Assets.xcassets")
    #    缺 UILaunchScreen 会让 iPad 信箱化运行（project.yml 未声明设备族，
    #    XcodeGen 默认 iPhone + iPad）；缺 CFBundle* 会让 xcodebuild 阶段失败。
    # ⑥ Info.plist 必要键（第 103 轮补）
    #    缺 UILaunchScreen 会让 iPad 信箱化运行（project.yml 未声明设备族，
    #    XcodeGen 默认 iPhone + iPad）；缺 CFBundle* 会让 xcodebuild 阶段失败。
    plist = IOS / "YuNian/Resources/Info.plist"
    if plist.exists():
        try:
            import plistlib
            d = plistlib.loads(plist.read_bytes())
        except Exception as e:
            problems.append(f"Info.plist 解析失败：{e}")
        else:
            for k in ("CFBundleDisplayName", "CFBundleExecutable", "CFBundleIdentifier",
                      "CFBundleShortVersionString", "CFBundleVersion", "UILaunchScreen",
                      "UISupportedInterfaceOrientations"):
                if k not in d:
                    problems.append(f"Info.plist 缺少 {k}")
    else:
        problems.append("找不到 YuNian/Resources/Info.plist")

    if problems:
        print(f"[FAIL] M0 前提条件不自洽，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        return 1

    print(f"M0 前提条件自洽（project.yml {len(set(refs))} 个引用、构建脚本产物、runbook 命令）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
