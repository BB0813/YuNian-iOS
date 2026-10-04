#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_swift_conformance.py — 断言 Swift 侧的 foreign trait 实现与 UniFFI 生成物**签名完全一致**。

## 为什么需要它
UniFFI 的 `with_foreign` trait 在 Swift 侧生成 `protocol`，宿主必须精确实现其方法签名。
签名不匹配是**编译期错误**，听起来安全 —— 但真正的风险在于：
`ios/Generated/` 是生成物、不入库，本地开发时可能长期不同步。
一旦 Rust 侧改了 trait（加方法/改参数名），Swift 实现不会自动被提醒，
直到有人重新生成绑定才发现。这个脚本把这个时间点提前到 CI。

## 前置
需要先生成绑定：
    ./scripts/build_agent_ios.sh --sim-only        # macOS
    # 或在任意平台（含 Windows）用 host dylib：
    cargo build --lib
    cargo run --features cli-bin --bin uniffi-bindgen -- generate \
      --library target/debug/<dylib> --language swift --out-dir ../ios/Generated

**注意**：绑定生成**不需要 macOS** —— `--library` 模式解析的是 dylib 里的
`UNIFFI_META_*` 符号，Windows 的 DLL 同样可用。只有**编译** Swift 才需要 macOS。

## 用法
    python ios/Tools/verify_swift_conformance.py
退出码 0 = 全部一致。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATED = REPO_ROOT / "ios/Generated/LianyuAgent.swift"
SWIFT_DIR = REPO_ROOT / "ios/YuNian"

# 宿主必须实现的 7 个 with_foreign trait
TRAITS = [
    "StreamSink",
    "ToolHost",
    "RequestSignatureProvider",
    "TurnStateController",
    "MemoryStore",
    "SkillStore",
    "StickerPreferenceStore",
]

# 当前 iOS 侧有意未实现的 trait（Rust 侧均有默认实现或本阶段不需要）
INTENTIONALLY_UNIMPLEMENTED = {
    # Rust 内置 DefaultTurnStateMachine 已提供等价行为；
    # 只有气泡连发收敛、群聊轮询节奏这类业务场景才需要宿主自定义。
    "TurnStateController",
}


def normalize(sig: str) -> str:
    """归一化签名：去多余空白，统一 `->` 两侧。"""
    sig = " ".join(sig.split())
    sig = re.sub(r"\s*->\s*", " -> ", sig)
    # 去掉 Swift 的参数默认值（协议里不会有，但实现里可能有）
    sig = re.sub(r"\s*=\s*[^,)]+", "", sig)
    return sig.strip()


def parse_generated_protocols(src: str) -> dict[str, set[str]]:
    """从生成物里取每个 trait 的方法签名。"""
    result: dict[str, set[str]] = {}
    for trait in TRAITS:
        m = re.search(
            rf"public protocol {trait}\s*:[^{{]*\{{(.*?)\n\}}", src, re.S
        )
        if not m:
            print(f"!! 生成物中找不到 protocol {trait}")
            continue
        body = m.group(1)
        sigs = set()
        for name, params, ret in re.findall(
            r"func\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*([^\n{]+))?", body
        ):
            sig = f"func {name}({normalize(params)})"
            if ret:
                sig += " -> " + normalize(ret)
            sigs.add(sig)
        result[trait] = sigs
    return result


def parse_swift_sources() -> dict[str, tuple[str, set[str]]]:
    """扫描 ios/YuNian 下所有 Swift，返回 {trait: (声明类型名, 方法签名集合)}。"""
    found: dict[str, tuple[str, set[str]]] = {}
    for path in sorted(SWIFT_DIR.rglob("*.swift")):
        src = path.read_text(encoding="utf-8")
        for trait in TRAITS:
            # 找 `class X: ... Trait ... {` / `extension X: Trait {`
            for m in re.finditer(
                rf"(?:class|struct|extension|enum)\s+(\w+)\s*:\s*([^{{]*\b{trait}\b[^{{]*)\{{",
                src,
            ):
                type_name = m.group(1)
                # 从 `{` 起做花括号配平，取出该类型的完整 body
                start = m.end() - 1
                depth, i = 0, start
                while i < len(src):
                    if src[i] == "{":
                        depth += 1
                    elif src[i] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    i += 1
                body = src[start + 1 : i]

                sigs = set()
                for name, params, ret in re.findall(
                    r"\bfunc\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*([^\n{]+))?", body
                ):
                    sig = f"func {name}({normalize(params)})"
                    if ret:
                        sig += " -> " + normalize(ret)
                    sigs.add(sig)
                # 同一 trait 可能在多个 extension 里分别实现（AgentStores 就是）
                if trait in found:
                    prev_type, prev = found[trait]
                    found[trait] = (prev_type, prev | sigs)
                else:
                    found[trait] = (type_name, sigs)
    return found


def main() -> int:
    verbose = "--verbose" in sys.argv or "-v" in sys.argv

    if not GENERATED.exists():
        print(f"跳过：绑定尚未生成（{GENERATED.relative_to(REPO_ROOT)} 不存在）")
        print("  生成方式见本脚本头部注释 —— 任意平台均可。")
        return 0

    generated = parse_generated_protocols(GENERATED.read_text(encoding="utf-8"))
    implemented = parse_swift_sources()

    failures: list[str] = []
    checked = 0

    for trait in TRAITS:
        expected = generated.get(trait)
        if expected is None:
            failures.append(f"{trait}: 生成物里没有该 protocol")
            continue

        if trait in INTENTIONALLY_UNIMPLEMENTED and trait not in implemented:
            print(f"  [skip] {trait}: 有意不实现（见脚本内说明）")
            continue

        if trait not in implemented:
            failures.append(f"{trait}: 宿主侧找不到任何实现")
            continue

        type_name, actual = implemented[trait]
        missing = expected - actual
        extra = actual - expected
        checked += 1

        if missing:
            failures.append(
                f"{trait}（{type_name}）缺少方法：\n      " + "\n      ".join(sorted(missing))
            )
        if extra and verbose:
            # 多出来的通常是同类型的私有辅助方法（closeAll / memoryMeta 等），
            # 属正常，默认不打印以免 CI 输出被噪声淹没。
            print(f"  [note] {trait}（{type_name}）另有 {len(extra)} 个非协议方法：")
            for e in sorted(extra):
                print(f"           {e}")
        if not missing:
            print(f"  [ok]   {trait}（{type_name}）{len(expected)} 个方法全部匹配")

    print()
    print(f"已核对 {checked} 个 trait，失败 {len(failures)} 项")
    for f in failures:
        print("  -", f)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
