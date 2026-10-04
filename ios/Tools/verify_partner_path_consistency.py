#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_partner_path_consistency.py — Rust 请求路径与签名路径必须一致（V9 的 PATH 项）。

## 背景：V9 的三处不一致，本地能闭合的是哪一处
`docs/ios-port-feasibility.md` §7.5 / V9 列出请求签名的三处不一致：
  ① PATH 前缀 —— **本关卡处理其本地可验的一半**
  ② CLIENT_ID 来源 —— 已由 verify_client_id_extraction.py 闭合
  ③ keyId → 公钥映射 / X-LianYu-Pub —— 需服务端结论

## ① 的真实结构（第 139 轮从源码读清）
Rust 侧同时是**请求方**与**签名触发方**：
    native_gateway.rs:926  let url = format!("{}{}", self.base_url(cfg), "/chat/completions");
    native_gateway.rs:929  self.inject_partner_signature(cfg, "POST", "/chat/completions", body, ...)
iOS 侧签名由 Rust 回调触发，`pathPrefix` 为 ""，故签名路径 == Rust 传入路径 == 请求路径。
**所以 iOS 内部自洽 —— 这条不需要服务端确认。**

真正需要服务端的是另一个问题：**服务端期望签 `/chat/completions` 还是 `/v1/chat/completions`**。
（Kotlin 侧签 `url.encodedPath`，PARTNER base 为 `https://suflow.cloud/v1` 时得到
`/v1/chat/completions`。）那取决于服务端如何还原路径，本地无法判定。

## 本关卡负责本地可验的那一半
Rust 的**请求路径字面量**必须与**签名路径字面量**一致。
若 Rust 改成请求 `/v1/chat/completions` 却仍签 `/chat/completions`，
那是一个 Rust 侧不一致的 bug，且会让 iOS 的签名与实际请求不符。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RUST = REPO_ROOT / "agent-native/src/native_gateway.rs"
SWIFT_PROVIDER = REPO_ROOT / "ios/YuNian/Agent/AgentHost.swift"


def rust_paths() -> tuple[str | None, str | None]:
    """返回 (请求路径字面量, 签名路径字面量)。"""
    src = RUST.read_text(encoding="utf-8")

    # 请求：format!("{}{}", self.base_url(cfg), "<PATH>")
    req: str | None = None
    m = re.search(r'format!\("\{\}\{\}",\s*self\.base_url\(cfg\),\s*"([^"]+)"\)', src)
    if m:
        req = m.group(1)
    if req is None:
        m = re.search(r'let url = format!\("\{base_url\}([^"]+)"\)', src)
        if m:
            req = m.group(1)

    # 签名：inject_partner_signature(cfg, "POST", "<PATH>", body, ...)
    sign: str | None = None
    m = re.search(r'inject_partner_signature\([^)]*?,\s*"([^"]+)"\s*,\s*body', src)
    if m:
        sign = m.group(1)
    return req, sign


def swift_prefix() -> str | None:
    """iOS 侧 partnerPathPrefix（应为空串，表示不附加前缀）。"""
    src = SWIFT_PROVIDER.read_text(encoding="utf-8")
    m = re.search(r'partnerPathPrefix\s*=\s*"([^"]*)"', src)
    return m.group(1) if m else None


def main() -> int:
    req, sign = rust_paths()
    prefix = swift_prefix()

    problems: list[str] = []
    if req is None:
        problems.append("Rust 侧未抽到请求路径字面量（format! 形态可能已变）")
    if sign is None:
        problems.append("Rust 侧未抽到签名路径字面量（inject_partner_signature 形态可能已变）")
    if prefix is None:
        problems.append("iOS 侧未抽到 partnerPathPrefix")
    if problems:
        print("[FAIL] 抽取失败：")
        for p in problems:
            print("  -", p)
        return 1

    # ① Rust 自身必须一致
    if req != sign:
        problems.append(
            f"Rust 请求路径 {req!r} 与签名路径 {sign!r} 不一致 —— "
            f"这会让 iOS 的签名与实际请求不符（Rust 侧 bug）")

    # ② iOS 不得附加额外前缀（否则签名路径 ≠ 请求路径）
    if prefix != "":
        problems.append(
            f"iOS 侧 partnerPathPrefix = {prefix!r}（非空）—— "
            f"签名路径会变成 {prefix + (sign or '')!r}，与 Rust 实际请求的 {req!r} 不符")

    if problems:
        print(f"[FAIL] PARTNER 路径一致性 {len(problems)} 处：")
        for p in problems:
            print("  -", p)
        return 1

    print(f"PARTNER 路径自洽：Rust 请求 = 签名 = {req!r}，iOS 前缀为空")
    print("  注：服务端期望签 '/chat/completions' 还是 '/v1/chat/completions' 仍需 V9 向服务端确认 ——")
    print("      本关卡只保证「iOS 签的路径 == iOS 实际请求的路径」。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
