#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_tool_contracts.py — 交叉核对 iOS 与 Android 的工具定义（name / description / schema）。

## 为什么需要
工具定义是**模型侧契约**：description 或 JSON Schema 差一点，模型看到的能力边界就不同。
这类差异不报错，只表现为「模型行为不同」。

我在 Swift 测试里断言过逐字一致，但**断言写死在我的测试文件里** ——
如果 Android 改了 description，只有人记得去改测试才发现。
本脚本从 Kotlin 源码抽取权威定义，与 Swift 侧对比，**接进门禁**。

## 覆盖范围
当前只核对我已移植的工具：
  · `device_get_time` / `device_battery_status`
  · `device_get_clipboard` / `device_set_clipboard`
  · `device_open_url` / `device_notify`

外加此前手工核对过、但未自动化的 `load_skill`。
（`builtin_chat_tool_protocol` 的内容由 generate_builtin_skill_seed.py 负责。）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_TOOLS = REPO_ROOT / "feature/skills/src/main/java/com/yunian/ai/feature/skills/tools/DeviceTools.kt"
ANDROID_FACADE = REPO_ROOT / "core/agent/src/main/kotlin/com/yunian/ai/agent/AgentFacade.kt"
SWIFT_DEVICE = REPO_ROOT / "ios/YuNian/Agent/DeviceTools.swift"
SWIFT_CATALOG = REPO_ROOT / "ios/YuNian/Agent/AgentToolCatalog.swift"


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def android_device_tools() -> dict[str, dict[str, str]]:
    """从 DeviceTools.kt 抽取每个 class 的 name / description / schema。"""
    src = read(ANDROID_TOOLS)
    out: dict[str, dict[str, str]] = {}

    # 逐个 class 块
    for cm in re.finditer(r"class\s+(Device\w+Tool)\b[^\n]*\{", src):
        start = cm.end()
        # 找到该 class 的结束（粗略：下一个 "class " 或文件尾）
        nxt = src.find("\nclass ", start)
        end = len(src) if nxt < 0 else nxt
        body = src[start:end]

        name_m = re.search(r'override val name\s*=\s*"([^"]+)"', body)
        desc_m = re.search(r'override val description\s*=\s*"([^"]+)"', body)
        sch_m = re.search(r'override val parametersJsonSchema\s*=\s*"""(.*?)"""', body, re.S)
        if not (name_m and desc_m and sch_m):
            continue
        schema = sch_m.group(1).strip()
        out[name_m.group(1)] = {
            "description": desc_m.group(1),
            "schema": schema,
            "kotlin_class": cm.group(1),
        }
    return out


def android_load_skill() -> dict[str, str]:
    """从 AgentFacade.kt 抽 load_skill 的 name / description / schema。"""
    src = read(ANDROID_FACADE)
    m = re.search(r'fun skillToolDefinitions\(\).*?name\s*=\s*"([^"]+)"(.*?)\n        \),', src, re.S)
    if not m:
        return {}
    body = m.group(2)
    desc = re.search(r'description\s*=\s*"([^"]+)"', body)
    sch = re.search(r'parametersJson\s*=\s*"""(.*?)"""', body, re.S)
    if not (desc and sch):
        return {}
    return {
        "description": desc.group(1),
        "schema": sch.group(1).strip(),
        "kotlin_class": "skillToolDefinitions",
    }


def swift_device_tools() -> dict[str, dict[str, str]]:
    src = read(SWIFT_DEVICE)
    out: dict[str, dict[str, str]] = {}
    for fm in re.finditer(
        r'static func (\w*[Dd]efinition)\(\) -> ToolDefinition \{(.*?)\n    \}', src, re.S
    ):
        body = fm.group(2)
        name_m = re.search(r'name:\s*(?:\w+Name|"([^"]+)")', body)
        # 形如 `name: getTimeName` 或 `name: "xxx"`
        if name_m.group(1):
            name = name_m.group(1)
        else:
            sym = re.search(r"name:\s*(\w+)", body).group(1)
            lm = re.search(rf"static let {sym}\s*=\s*\"([^\"]+)\"", src)
            if not lm:
                continue
            name = lm.group(1)
        desc = re.search(r'description:\s*"((?:[^"\\]|\\.)*)"', body)
        # 原始字符串：#+  + 引号 + 内容 + 引号 + 同样的 #+
        # （我第一版把分隔符写成 ##"" —— 三引号形态，而实际是 #" 单引号）
        sch = re.search(r'parametersJson:\s*(#+)"(.*?)"\1', body, re.S)
        if not (desc and sch):
            continue
        out[name] = {"description": desc.group(1), "schema": sch.group(2).strip()}
    return out


def main() -> int:
    android = android_device_tools()
    android_load = android_load_skill()
    swift = swift_device_tools()

    # load_skill 单独并入 Android 侧集合
    if android_load:
        android["load_skill"] = android_load

    # Swift 侧：load_skill 定义在 AgentToolCatalog.swift（不在 DeviceTools.swift），
    # 我第一版只扫了 DeviceTools.swift，导致它被误报为「缺失」。
    cat = read(SWIFT_CATALOG)
    lm = re.search(r'let loadSkill = ToolDefinition\((.*?)\n            \)', cat, re.S)
    if lm:
        body = lm.group(1)
        d = re.search(r'description:\s*"((?:[^"\\]|\\.)*)"', body)
        s = re.search(r'parametersJson:\s*(#+)"(.*?)"\1', body, re.S)
        if d and s:
            swift["load_skill"] = {"description": d.group(1), "schema": s.group(2).strip()}

    # ⚠️ iOS 侧**刻意不移植**的工具（无等价 API）—— 记录原因，不算「缺失」。
    # 这两项在 ios/README.md 的 M4 清单里标为「需产品定方案」。
    DELIBERATELY_NOT_PORTED = {
        "device_open_app": "iOS 无法按应用名枚举/启动其它 App（只能 URL scheme + 白名单）",
        "device_set_alarm": "iOS 无「跳转系统闹钟界面并预填」的入口（Android 有 ACTION_SET_ALARM）",
    }

    print(f"Android 侧工具定义 {len(android)} 个，Swift 侧 {len(swift)} 个")

    problems: list[str] = []
    checked = 0

    for name, a in android.items():
        if name in DELIBERATELY_NOT_PORTED:
            print(f"  [skip] {name} —— {DELIBERATELY_NOT_PORTED[name]}")
            continue
        if name not in swift:
            problems.append(f"[缺失] Android 有 `{name}`，Swift 侧没有（也不在白名单）")
            continue
        s = swift[name]
        checked += 1
        if s["description"] != a["description"]:
            problems.append(
                f"[description] {name}\n      Android: {a['description']!r}\n      Swift  : {s['description']!r}"
            )
        if s["schema"] != a["schema"]:
            problems.append(
                f"[schema] {name}\n      Android: {a['schema']!r}\n      Swift  : {s['schema']!r}"
            )

    # Swift 多出来的（不应有，除非刻意新增）
    for name in swift:
        if name not in android:
            problems.append(f"[多余] Swift 有 `{name}`，Android 侧没有对应定义")

    print(f"逐字核对 {checked} 个工具定义（description + JSON Schema）")
    if problems:
        print(f"不一致 {len(problems)} 处：")
        for p in problems:
            print("  -", p)
        return 1
    print("全部一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
