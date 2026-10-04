#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_seeds.py — 从 Android 侧源码生成 iOS 的初始数据种子。

## 为什么是生成的，而不是手写的
初始数据（供应商预设、默认伴侣人设）在 Android 侧是 Kotlin 常量。
手抄有两个风险：①中文长文本抄错字；②Android 侧改了内容而 iOS 没跟上。
生成器把「Android 源码」当唯一真源，改了就重新生成，并由 `--check` 把关。

## 产出
  ios/YuNian/Data/Seed/YuNianSeed.swift
    - `apiProviderPresets`：对应 AppDatabase.seedApiProviderPresets()（**建库时**播种）
    - `defaultCompanionGirlfriend` / `defaultCompanionBoyfriend`：
      对应 RolePresets + RoleProfile.createCompanion() + DefaultCompanionSeeder 的常量

## 数据来源（逐项对应）
  - ApiProvider 枚举          → core/database/.../model/ApiConfig.kt:68-84
  - 预设列表与 sortOrder      → core/database/.../AppDatabase.kt:1775-1812
  - 服务端基址常量            → core/common/.../SuFlowApi.kt
  - 人设内容                  → core/database/.../RolePresets.kt
  - createCompanion 映射      → core/database/.../model/RoleProfile.kt
  - 默认标签 / 头像           → core/database/.../DefaultCompanionSeeder.kt

## 未覆盖（已知缺口，见 ios/README.md）
  SecurityDataSeeder（关键词表 + 安全自检题库，473 行 XOR 混淆数据）尚未迁移。
  其解码方式已探明，迁移方案见 README 的「已知缺口」。

用法：
    python ios/Tools/generate_seeds.py
    python ios/Tools/generate_seeds.py --check
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_FILE = REPO_ROOT / "ios/YuNian/Data/Seed/YuNianSeed.swift"

API_CONFIG_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/model/ApiConfig.kt"
APP_DATABASE_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/AppDatabase.kt"
SUFLOW_API_KT = REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common/SuFlowApi.kt"
ROLE_PRESETS_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/RolePresets.kt"
DEFAULT_SEEDER_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/DefaultCompanionSeeder.kt"


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到数据来源文件：{p}")
    return p.read_text(encoding="utf-8")


# ── Kotlin 字符串字面量解码 ──────────────────────────────────────────────
def decode_kotlin_string(lit: str) -> str:
    """解 Kotlin 双引号字符串字面量的转义（\\n \\t \\" \\\\ \\uXXXX）。"""
    out = []
    i = 0
    while i < len(lit):
        c = lit[i]
        if c == "\\" and i + 1 < len(lit):
            n = lit[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$"}
            if n in mapping:
                out.append(mapping[n])
                i += 2
                continue
            if n == "u" and i + 5 < len(lit):
                out.append(chr(int(lit[i + 2 : i + 6], 16)))
                i += 6
                continue
        out.append(c)
        i += 1
    return "".join(out)


def trim_indent(block: str) -> str:
    """复刻 Kotlin 的 trimIndent()：去掉公共缩进与前后的空行。"""
    lines = block.split("\n")
    # 去掉首尾空行
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    common = min(indents) if indents else 0
    return "\n".join(l[common:] if len(l) >= common else "" for l in lines)


# ── ApiProvider 枚举 ────────────────────────────────────────────────────
def parse_api_providers() -> dict[str, dict[str, str]]:
    src = read(API_CONFIG_KT)
    m = re.search(r"enum class ApiProvider\([^)]*\)\s*\{(.*?)\n\}", src, re.S)
    if not m:
        sys.exit("无法解析 ApiProvider 枚举")
    providers: dict[str, dict[str, str]] = {}
    for line in m.group(1).split("\n"):
        line = line.strip().rstrip(",")
        if not line or line.startswith("//"):
            continue
        pm = re.match(r'^(\w+)\("([^"]*)",\s*([^,]+),\s*"([^"]*)"\)$', line)
        if not pm:
            continue
        name, display, base, model = pm.groups()
        providers[name] = {
            "displayName": display,
            "baseUrlExpr": base.strip(),
            "model": model,
        }
    return providers


def resolve_base_url(expr: str) -> str:
    """把 `SuFlowApi.CHAT_BASE_URL` 这类常量引用解析成字面量。"""
    literal = re.match(r'^"([^"]*)"$', expr)
    if literal:
        return literal.group(1)
    ref = re.match(r"^SuFlowApi\.(\w+)$", expr)
    if ref:
        src = read(SUFLOW_API_KT)
        cm = re.search(rf'const val {ref.group(1)}\s*=\s*"([^"]*)"', src)
        if cm:
            return cm.group(1)
        sys.exit(f"无法在 SuFlowApi.kt 中解析常量 {ref.group(1)}")
    sys.exit(f"无法解析 baseUrl 表达式：{expr}")


def parse_preset_plan() -> list[tuple[str, int]]:
    """解析 AppDatabase.seedApiProviderPresets 的预设列表与 sortOrder。"""
    src = read(APP_DATABASE_KT)
    m = re.search(
        r"private fun seedApiProviderPresets.*?val presets = listOf\((.*?)\n\s*\)",
        src,
        re.S,
    )
    if not m:
        sys.exit("无法解析 seedApiProviderPresets 的 presets 列表")
    plan: list[tuple[str, int]] = []
    for line in m.group(1).split("\n"):
        pm = re.search(r"ApiProvider\.(\w+)\s+to\s+(\d+)", line)
        if pm:
            plan.append((pm.group(1), int(pm.group(2))))
    if not plan:
        sys.exit("presets 列表解析结果为空")
    return plan


# ── RolePresets ─────────────────────────────────────────────────────────
def parse_role_preset(role_key: str) -> dict[str, object]:
    src = read(ROLE_PRESETS_KT)
    m = re.search(
        rf"val {role_key}: RoleProfile = RoleProfile\((.*?)\n    \)\n",
        src,
        re.S,
    )
    if not m:
        sys.exit(f"无法解析 RolePresets.{role_key}")
    body = m.group(1)

    def field(name: str) -> str | None:
        # 支持单行字符串，以及相邻字符串用 + 拼接的多行写法
        fm = re.search(
            rf"\b{name}\s*=\s*((?:\"(?:\\.|[^\"\\])*\"\s*\+?\s*)+)",
            body,
            re.S,
        )
        if not fm:
            return None
        parts = re.findall(r'"((?:\\.|[^"\\])*)"', fm.group(1))
        return decode_kotlin_string("".join(parts))

    def multiline(name: str) -> str | None:
        mm = re.search(rf'\b{name}\s*=\s*"""(.*?)"""', body, re.S)
        if not mm:
            return None
        return trim_indent(mm.group(1))

    age_m = re.search(r"\bage\s*=\s*(\d+)", body)
    return {
        "name": field("name") or "",
        "age": int(age_m.group(1)) if age_m else None,
        "avatarUrl": field("avatarUrl"),
        "personality": field("personality") or "",
        "backstory": field("backstory"),
        "speakingStyle": field("speakingStyle"),
        "rawPrompt": field("rawPrompt"),
        "systemPrompt": multiline("systemPrompt"),
        "tags": field("tags"),
    }


def resolved_system_prompt(p: dict[str, object]) -> str:
    """复刻 RoleProfile.resolvedSystemPrompt()：有显式 systemPrompt 就用它（trim）。"""
    sp = p.get("systemPrompt")
    if isinstance(sp, str) and sp.strip():
        return sp.strip()
    # 回退分支（RolePresets 目前都提供了显式 systemPrompt）
    lines = [f"名字：{p['name']}"]
    if p.get("age"):
        lines.append(f"年龄：{p['age']}岁")
    lines.append(f"人设：{p['personality']}")
    for label, key in (("说话风格", "speakingStyle"), ("背景", "backstory")):
        v = p.get(key)
        if isinstance(v, str) and v.strip():
            lines.append(f"{label}：{v.strip()}")
    rp = p.get("rawPrompt")
    personality = str(p.get("personality") or "")
    if isinstance(rp, str) and rp.strip() and rp != personality and rp not in personality:
        lines.append(f"补充设定：{rp.strip()}")
    return "\n".join(lines)


def parse_default_seeder_constants() -> dict[str, str]:
    src = read(DEFAULT_SEEDER_KT)
    out = {}
    for key in ("defaultExperienceCompanionTag", "DEFAULT_COMPANION_TAGS",
                "DEFAULT_GIRLFRIEND_AVATAR_URL", "DEFAULT_BOYFRIEND_AVATAR_URL",
                "LEGACY_TAG"):
        m = re.search(rf'const val {key}\s*(?::\s*String\s*)?=\s*"([^"]*)"', src)
        if m:
            out[key] = m.group(1)
    # DEFAULT_COMPANION_TAGS 含字符串模板 $defaultExperienceCompanionTag，需展开
    if "DEFAULT_COMPANION_TAGS" in out and "defaultExperienceCompanionTag" in out:
        out["DEFAULT_COMPANION_TAGS"] = out["DEFAULT_COMPANION_TAGS"].replace(
            "$defaultExperienceCompanionTag", out["defaultExperienceCompanionTag"]
        )
    return out


# ── 渲染 Swift ──────────────────────────────────────────────────────────
def swift_str(s: str | None) -> str:
    if s is None:
        return "nil"
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t") + '"'


def swift_multiline(s: str, indent: str = "        ") -> str:
    """把多行文本渲染成 Swift 多行字符串字面量。"""
    body = s.replace("\\", "\\\\").replace('"""', '\\"\\"\\"').replace("\n", "\n" + indent)
    return '"""\n' + indent + body + '\n' + indent[:-4] + '"""'


def render(providers: dict, plan: list, presets: dict, seeder: dict) -> str:
    L: list[str] = []
    A = L.append

    A("// ⚠️ 本文件由 ios/Tools/generate_seeds.py 自动生成，请勿手改。")
    A("//")
    A("// 数据来源（Android 侧为唯一真源）：")
    A("//   ApiProvider 枚举        core/database/.../model/ApiConfig.kt")
    A("//   预设列表与 sortOrder    core/database/.../AppDatabase.kt :: seedApiProviderPresets")
    A("//   人设内容                core/database/.../RolePresets.kt")
    A("//   createCompanion 映射    core/database/.../model/RoleProfile.kt")
    A("//   默认标签 / 头像         core/database/.../DefaultCompanionSeeder.kt")
    A("//")
    A("// 重新生成：python ios/Tools/generate_seeds.py")
    A("")
    A("import Foundation")
    A("")
    A("/// iOS 侧的初始数据种子。")
    A("enum YuNianSeed {")
    A("")
    A("    // MARK: - API 供应商预设")
    A("    //")
    A("    // ⚠️ 这些行属于**建库**的一部分 —— Android 侧在 Room 的 `onCreate` 回调里播种")
    A("    //   （`AppDatabase.seedApiProviderPresets`）。iOS 若不在建 v45 基线时一并写入，")
    A("    //   `api_provider_presets` 会是空表，供应商列表界面无内容。")
    A("    //")
    A("    // 注意：预设共 " + str(len(plan)) + " 条，**不含 PARTNER**（内置云通道单独管理）。")
    A("")
    A("    struct ApiProviderPreset: Sendable, Equatable {")
    A("        let provider: String")
    A("        let displayName: String")
    A("        let baseUrl: String")
    A("        let model: String")
    A("        let formatHint: String")
    A("        let sortOrder: Int")
    A("    }")
    A("")
    A("    /// 与 AppDatabase.seedApiProviderPresets 逐条对应。")
    A("    static let apiProviderPresets: [ApiProviderPreset] = [")
    for name, order in plan:
        p = providers.get(name)
        if not p:
            sys.exit(f"预设引用了未知 provider：{name}")
        base = resolve_base_url(p["baseUrlExpr"])
        hint = "anthropic" if name == "ANTHROPIC" else "openai"
        A(f"        .init(provider: {swift_str(name)},")
        A(f"              displayName: {swift_str(p['displayName'])},")
        A(f"              baseUrl: {swift_str(base)},")
        A(f"              model: {swift_str(p['model'])},")
        A(f"              formatHint: {swift_str(hint)},")
        A(f"              sortOrder: {order}),")
    A("    ]")
    A("")
    A("    // MARK: - 默认伴侣（首启播种）")
    A("    //")
    A("    // 对应 DefaultCompanionSeeder.createDefaultTestCompanion()：")
    A("    //   RolePresets.<role>.createCompanion() 再覆盖 tags 与 avatarUrl。")
    A("    // 注意 RoleProfile 的 bodyType / profession / personalityTags **不落库** ——")
    A("    //   `companions` 表没有这三列（见 schema v45）。")
    A("")
    A("    struct DefaultCompanion: Sendable, Equatable {")
    A("        let name: String")
    A("        let age: Int?")
    A("        let avatarUrl: String")
    A("        let personality: String")
    A("        let backstory: String?")
    A("        let speakingStyle: String?")
    A("        let rawPrompt: String")
    A("        let systemPrompt: String")
    A("        let tags: String")
    A("    }")
    A("")

    for role_key, const_name in (("girlfriend", "defaultCompanionGirlfriend"),
                                 ("boyfriend", "defaultCompanionBoyfriend")):
        p = presets[role_key]
        avatar = p.get("avatarUrl") or ""
        raw = p.get("rawPrompt") or p.get("personality") or ""
        A(f"    /// RolePresets.{role_key}")
        A(f"    static let {const_name} = DefaultCompanion(")
        A(f"        name: {swift_str(p['name'])},")
        A(f"        age: {p['age'] if p['age'] is not None else 'nil'},")
        A(f"        avatarUrl: {swift_str(avatar)},")
        A(f"        personality: {swift_str(p['personality'])},")
        A(f"        backstory: {swift_str(p.get('backstory'))},")
        A(f"        speakingStyle: {swift_str(p.get('speakingStyle'))},")
        A(f"        rawPrompt: {swift_str(raw)},")
        A(f"        systemPrompt: {swift_multiline(resolved_system_prompt(p), '            ')},")
        A(f"        tags: {swift_str(seeder.get('DEFAULT_COMPANION_TAGS'))}")
        A("    )")
        A("")

    A("    // MARK: - 播种标记（对应 Android 的 SharedPreferences）")
    A("    //")
    A("    // Android 侧 DefaultCompanionSeeder 用 SharedPreferences 的 deleted_by_user 标志，")
    A("    // 保证「用户手动删掉默认伴侣后不再自动重建」。iOS 用 UserDefaults 存同一语义。")
    A("    // 该数据非机密，放 Keychain 反而不合适（会占 iCloud 钥匙串条目）。")
    A("")
    A(f"    static let defaultExperienceCompanionTag = {swift_str(seeder.get('defaultExperienceCompanionTag'))}")
    A(f"    static let legacyDefaultCompanionTag = {swift_str(seeder.get('LEGACY_TAG'))}")
    A("    static let deletedByUserDefaultsKey = \"yunian.defaultCompanion.deletedByUser\"")
    A("}")
    A("")
    return "\n".join(L)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    providers = parse_api_providers()
    plan = parse_preset_plan()
    presets = {
        "girlfriend": parse_role_preset("girlfriend"),
        "boyfriend": parse_role_preset("boyfriend"),
    }
    seeder = parse_default_seeder_constants()

    # 自检：解析结果必须合理，否则宁可失败也不要产出半个种子
    errors = []
    if len(providers) != 14:
        errors.append(f"ApiProvider 应有 14 项，解析到 {len(providers)}")
    if len(plan) != 13:
        errors.append(f"预设应有 13 条，解析到 {len(plan)}")
    if any(n == "PARTNER" for n, _ in plan):
        errors.append("预设不应包含 PARTNER")
    for key in ("girlfriend", "boyfriend"):
        p = presets[key]
        if not p["name"] or not p["personality"] or not resolved_system_prompt(p):
            errors.append(f"RolePresets.{key} 解析不完整：{p}")
    if "default-experience-companion" not in (seeder.get("DEFAULT_COMPANION_TAGS") or ""):
        errors.append("DEFAULT_COMPANION_TAGS 未正确展开")
    if errors:
        for e in errors:
            print("  [FAIL]", e)
        return 1

    out = render(providers, plan, presets, seeder)

    if args.check:
        if not OUT_FILE.exists():
            sys.exit(f"--check 失败：{OUT_FILE} 不存在")
        if OUT_FILE.read_text(encoding="utf-8") != out:
            sys.exit("--check 失败：生成结果与磁盘文件不一致，请重新运行生成器")
        print(f"--check 通过：{OUT_FILE.relative_to(REPO_ROOT)}")
        return 0

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(out, encoding="utf-8")
    print(f"已生成 {OUT_FILE.relative_to(REPO_ROOT)}")
    print(f"  供应商预设 {len(plan)} 条（ApiProvider 共 {len(providers)} 项）")
    print(f"  默认伴侣：{presets['girlfriend']['name']} / {presets['boyfriend']['name']}")
    print(f"  默认标签：{seeder.get('DEFAULT_COMPANION_TAGS')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
