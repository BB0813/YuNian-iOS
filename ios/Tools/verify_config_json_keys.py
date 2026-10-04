#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_config_json_keys.py — Rust 读取的 settings/credentials JSON 键，iOS 必须写得进。

## 为什么需要
第 135 轮按"硬编码期望 vs 权威源"模式继续清点，发现这一类此前无人覆盖：

Rust 从 `AgentGlobalConfig.settings_json` / `credentials_json` 里**按键取值**：
    native_gateway.rs:343   settings().get("role")
    native_gateway.rs:357   credentials().get("api_key")
    native_gateway.rs:363   credentials().get("extra_api_keys")
    native_gateway.rs:953   credentials().get("client_id")

iOS 侧由 `AgentSettings` / `AgentCredentials` 拼这两个 JSON。
**若 Rust 读了某个键而 iOS 没写，Rust 落默认值** —— 后果是
"API key 明明配了却报没有可用 key""role 不是用户选的"这类，
且症状离根因很远（第 40 轮的 `api_configs` 行问题正是同族）。

本关卡把两边对齐：Rust 读的键集合 ⊆ iOS 写的键集合。

## 判据
`[FAIL] Rust 读了但 iOS 没写` —— 这是真缺口（必须补 iOS 侧）。
反向（iOS 写了 Rust 不读）只提示，不失败：可能是给别的消费者用的。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RUST = REPO_ROOT / "agent-native/src/native_gateway.rs"
SWIFT = REPO_ROOT / "ios/YuNian/Agent/AgentDTOs.swift"


def rust_read_keys() -> tuple[set[str], set[str]]:
    """
    返回 (配置读取键, 空集)。

    ⚠️ 第 135 轮两次试错后的解法 —— **函数级**：
      · 精确到 `settings()/credentials()` 链式 → 漏掉经局部变量的 `cred.get(...)`
      · 全文件 `.get(...)` → 把 HTTP 响应解析（choices/content/delta）也算进来，23 个假阳性
    正确做法：按 `fn` 切分，只收集**函数体内出现过 settings() 或 credentials()**
    的那些函数里的 `.get("k")`。这样既覆盖局部变量写法，又排除响应解析。
    """
    src = RUST.read_text(encoding="utf-8")
    keys: set[str] = set()

    # 按顶层 fn 切分（粗略：`fn name(` 到下一个 `fn ` 或 `}` 同级）
    starts = [m.start() for m in re.finditer(r"\n    (?:pub(?:\(crate\))?\s+)?fn\s+\w+", src)]
    starts.append(len(src))
    for i in range(len(starts) - 1):
        body = src[starts[i]:starts[i + 1]]
        if "settings()" not in body and "credentials()" not in body:
            continue
        for m in re.finditer(r"\.get\(\s*\"(\w+)\"", body):
            keys.add(m.group(1))
    return keys, set()


def swift_written_keys() -> tuple[set[str], set[str]]:
    """
    从 AgentDTOs.swift 抽两个 JSON 的写入键。

    ⚠️ 第 135 轮：两侧都有显式 `enum CodingKeys: String, CodingKey`，
    形如 `case clientId = "client_id"` —— **JSON 键是映射后的值，不是属性名**。
    我第一版按属性名抽，于是 `clientId` 与 Rust 的 `client_id` 对不上，
    误报「Rust 读了 iOS 没写」，而实际 iOS 写得对。
    这是本项目新检查器第六次首跑误报（110/114/116/129/133/135）。
    """
    src = SWIFT.read_text(encoding="utf-8")
    settings_keys: set[str] = set()
    credentials_keys: set[str] = set()

    for name, sink in (("AgentSettings", settings_keys),
                       ("AgentCredentials", credentials_keys)):
        m = re.search(rf"struct {name}[^{{]*\{{(.*?)\n\}}", src, re.S)
        if not m:
            continue
        body = m.group(1)

        # ① 优先用 CodingKeys 的显式映射
        km = re.search(r"enum CodingKeys[^{]*\{(.*?)\n    \}", body, re.S)
        if km:
            for cm in re.finditer(r"case\s+(\w+)\s*=\s*\"([^\"]+)\"", km.group(1)):
                sink.add(cm.group(2))
            # 未显式映射的 case：`case session` → 键名就是 case 名
            for cm in re.finditer(r"case\s+(\w+)\s*$", km.group(1), re.M):
                sink.add(cm.group(1))
            continue

        # ② 兜底：无 CodingKeys 时用属性名
        for pm in re.finditer(r"^\s*(?:public\s+|private\s+)?var\s+(\w+)\s*:", body, re.M):
            sink.add(pm.group(1))
    return settings_keys, credentials_keys


def main() -> int:
    r_sets, r_creds = rust_read_keys()
    s_sets, s_creds = swift_written_keys()

    if not r_sets and not r_creds:
        print("[SKIP] Rust 侧未抽到 settings/credentials 读取点")
        return 0
    if not s_sets and not s_creds:
        print("[SKIP] Swift 侧未抽到写入键（AgentDTOs 结构可能已变）")
        return 0

    problems: list[str] = []
    # Rust 读的键并集（含从 SQLite 行里 get 的，一并纳入粗判据）
    missing = (r_sets | r_creds) - (s_sets | s_creds)
    for k in sorted(missing):
        problems.append(f"Rust 读取的键 `{k}` 在 iOS 的 settings/credentials 里不存在")

    extra = (s_sets | s_creds) - (r_sets | r_creds)

    if problems:
        print(f"[FAIL] 配置 JSON 键缺口 {len(problems)} 处（Rust 读了 iOS 没写）：")
        for p in problems:
            print("  -", p)
        print("\n后果：Rust 落默认值 —— 症状离根因很远（如「key 配了却报没有」）。")
        return 1

    print(f"配置 JSON 键对齐：iOS 写 {len(s_sets | s_creds)} 个，Rust 读的都在其中")
    print(f"  Rust 读取: {sorted(r_sets | r_creds)}")
    if extra:
        print(f"  iOS 多写（不失败，仅提示）: {sorted(extra)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
