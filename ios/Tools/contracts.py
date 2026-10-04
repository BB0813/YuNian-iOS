#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
contracts.py — 从 Android / Rust 源码抽取「字面量契约」，供多个验证脚本共享。

## 为什么需要这个模块
第 8 轮踩过一个真实的坑：`MessageRepository.ConversationType` 被写成
`"COMPANION"` / `"GROUP"`，而 Android 实际存的是 `"chat"` / `"group"`。
**更糟的是 `verify_schema_sql.py` 里也硬编码了同一个错值** —— 插入与查询自洽，
64 项断言全过，却与 Android 完全不同。

失败的根因不是「某处写错」，而是**同一个契约被两处各自硬编码**。
所以契约必须只有一处定义，且那一处**从 Android 源码推导**，而不是手写。

本模块就是那个唯一定义处。任何需要这些取值的脚本都应 import 它，
不要再写字符串字面量。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

CONVERSATION_REF = REPO_ROOT / "core/domain/src/main/java/com/yunian/ai/domain/timeline/TimelineStore.kt"
MODEL_DIR = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/model"
APP_DATABASE_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/AppDatabase.kt"
STICKER_RS = REPO_ROOT / "agent-native/src/sticker_preference.rs"


class ContractError(RuntimeError):
    """无法从源码推导契约 —— 宁可失败也不要退回硬编码。"""


def _read(path: Path) -> str:
    if not path.exists():
        raise ContractError(f"找不到契约来源文件：{path}")
    return path.read_text(encoding="utf-8")


def conversation_types() -> list[str]:
    """
    `messages.conversationType` 的合法取值。

    这是**普通 TEXT 列**（不是枚举列），取值由 `ConversationRef` 的 `init` 块
    `require(conversationType == "chat" || conversationType == "group")` 约束。
    """
    src = _read(CONVERSATION_REF)
    values = sorted(set(re.findall(r'conversationType\s*==\s*"([a-z_]+)"', src)))
    if not values:
        raise ContractError(
            "未能从 ConversationRef 解析出 conversationType 取值约束 —— "
            "若该 require 被移除，请改用其它权威来源，不要硬编码"
        )
    return values


def default_conversation_type() -> str:
    """测试数据默认使用的会话类型。取 chat（单聊是最常见路径）。"""
    values = conversation_types()
    if "chat" in values:
        return "chat"
    return values[0]


def room_converter_stores_enum_name() -> bool:
    """
    Room 的枚举转换器是否按 `value.name` 存字符串。

    这是「枚举列存大写枚举名」这个前提本身；若上游改成 `@SerialName` 的值，
    本模块的 `enum_names()` 语义就要跟着改，因此把它做成可断言的前提。
    """
    src = _read(APP_DATABASE_KT)
    return bool(re.search(r"fun from\w+\(value:\s*\w+\)\s*:\s*\w+\s*=\s*value\.name", src))


def _find_enum_source(enum_name: str) -> Path:
    """
    定位定义了 `enum class <enum_name>` 的文件。

    不假设文件名与枚举名一致 —— 例如 `ApiProvider` 实际定义在 `ApiConfig.kt`，
    `ViolationLevel` 嵌套在 `ContentFilter.kt` 内。按内容搜更稳。
    """
    base = MODEL_DIR if MODEL_DIR.exists() else REPO_ROOT
    for search_root in (base, REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common"):
        if not search_root.exists():
            continue
        for path in search_root.rglob("*.kt"):
            if "build" in path.parts:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            if re.search(rf"\benum class {re.escape(enum_name)}\b", text):
                return path
    raise ContractError(
        f"在源码中找不到 `enum class {enum_name}` —— 契约来源已变化，请更新本模块"
    )


def enum_names(enum_name: str) -> list[str]:
    """
    Kotlin 枚举的条目名 —— 即 Room 存进枚举列的字符串。

    ⚠️ 注意与 `@SerialName("text")` 区分：后者只影响 kotlinx.serialization 的 JSON。
    Room 走 `Converters.fromXxx(value) = value.name`，存的是**大写枚举名**。
    """
    src = _read(_find_enum_source(enum_name))
    # 枚举声明可能带构造参数列表，例如
    #   enum class ApiProvider(val displayName: String, ...) { ... }
    # 因此名字与 `{` 之间要容忍一对括号。
    m = re.search(
        rf"enum class {re.escape(enum_name)}\s*(?:\([^)]*\))?\s*\{{(.*?)\n\s*\}}",
        src,
        re.S,
    )
    if not m:
        raise ContractError(f"无法解析枚举 {enum_name}")
    body = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
    body = re.sub(r"//[^\n]*", "", body)
    body = re.sub(r"@\w+(?:\([^)]*\))?", "", body)   # 剥掉行内注解
    # 条目名后面可能跟构造参数（`OPENAI("OpenAI", ...)`）、逗号/分号，或直接换行
    # （`TEXT,`）。因此只要求「行首是大写名 + 后面是 ( , ; 或行尾」。
    names = re.findall(r"^\s*([A-Z][A-Z_0-9]*)\s*(?:\(|,|;|$)", body, re.M)
    if not names:
        raise ContractError(f"枚举 {enum_name} 未解析到任何条目")
    return names


def sticker_usage_sources() -> list[str]:
    """
    表情使用记录的 `source` 取值 —— 由 Rust `sticker_preference.rs::source_str` 决定，
    宿主回调收到的就是这两个字符串。
    """
    src = _read(STICKER_RS)
    values = sorted(set(re.findall(r'StickerSource::\w+\s*=>\s*"([a-z]+)"', src)))
    if not values:
        raise ContractError("未能从 Rust source_str 解析出 source 取值")
    return values


def provider_preset_keys() -> list[str]:
    """`api_provider_presets.provider` 的取值 —— 来自 `ApiProvider` 枚举名（大写）。"""
    return enum_names("ApiProvider")


def filter_pattern_levels() -> list[str]:
    """过滤词表的等级键 —— 与 `ViolationLevel` 一致（该枚举嵌套在 ContentFilter.kt 内）。"""
    src = _read(REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common/ContentFilter.kt")
    m = re.search(r"enum class ViolationLevel\s*\{(.*?)\n\s*\}", src, re.S)
    if not m:
        raise ContractError("无法解析 ViolationLevel")
    body = re.sub(r"//[^\n]*", "", m.group(1))
    levels = re.findall(r"\b([A-Z][A-Z_0-9]*)\b", body)
    if not levels:
        raise ContractError("ViolationLevel 未解析到条目")
    return levels


if __name__ == "__main__":
    # 便于人工核对：打印所有已推导出的契约
    try:
        print("conversationType      :", conversation_types())
        print("Room 转换器用 name    :", room_converter_stores_enum_name())
        for e in ("MessageType", "FileFormat", "MemoryType", "MemoryScope",
                  "MemorySource", "ApiProvider"):
            print(f"{e:22s}:", enum_names(e)[:8], "…" if len(enum_names(e)) > 8 else "")
        print("sticker source        :", sticker_usage_sources())
        print("ViolationLevel        :", filter_pattern_levels())
    except ContractError as e:
        print(f"[FAIL] {e}", file=sys.stderr)
        raise SystemExit(1)
