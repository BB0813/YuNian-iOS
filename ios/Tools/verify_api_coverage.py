#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_api_coverage.py — 机械地找出「Rust 暴露了、但 iOS 从未调用」的 API。

## 为什么需要
`AgentRuntime::set_worldbook` 与 `registerGlobalTools` 这两个缺口，
是我在第 79、92 轮**人工对比** Android 启动/回合路径时撞出来的。
人工对比的问题是：撞到什么算什么，没撞到的就一直是缺口。

本脚本把这件事机械化：从 `ios/Generated/LianyuAgent.swift` 抽出全部公开 API，
再看它们在 `ios/YuNian/**` 里是否被调用，输出「零调用」清单。
新增未接线的 API 会立刻出现在报告里。

## 定位
report-only（不是 gate）。理由见文件末尾的「为何不设门禁」。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BINDINGS = REPO_ROOT / "ios/Generated/LianyuAgent.swift"
SOURCE_DIRS = [
    REPO_ROOT / "ios/YuNian",
    REPO_ROOT / "ios/YuNianTests",
]

# app → Rust 方向：这些类的方法**由 iOS 调用**，零调用才是潜在缺口。
# （另有 11 个类是「Rust 调 iOS」方向 —— 我在 Swift 里实现其 trait，
#   方法由 Rust 回调，iOS 源码里当然看不到调用点，不参与本检查。）
APP_TO_RUST_CLASSES = {
    "AgentRuntime", "ApiProbe", "DefaultTurnStateMachine",
}
RUST_TO_APP_CLASSES = {
    "MemorySelector", "SkillSelector", "PromptOrchestrator",
    "StickerPreferenceEngine", "TurnStateControllerImpl", "ToolHostImpl",
    "StreamSinkImpl", "MemoryStoreImpl", "SkillStoreImpl",
    "StickerPreferenceStoreImpl", "RequestSignatureProviderImpl",
}

# iOS 侧**有意**不调用的 API。加进来时必须写理由 ——
# 这条清单本身就是「我们做过判断」的记录，不是「我们没注意」的垃圾桶。
KNOWN_UNUSED = {
    # 多 Agent 委派：定义未接、执行端（AgentToolHost 特判分支）也未接。
    # 将来必须定义 + 执行一起做；只做定义会让模型调到必然失败的工具。
    "registerGlobalTools": "多 Agent 委派定义未移植（需与执行端一起做，见 docs/turn-path-parity.md 第 92 轮）",
    "registerSessionTools": "同上（会话级委派工具）",
    "unregisterGlobalTool": "同上（注销委派工具）",
    # Rust 侧测试专用注入：iOS 无 mock transport 需求。
    "setMockTransport": "Rust 测试专用（mock 响应序列），iOS 不需要",
    # 工具确认属非流式路径，iOS 走流式 runTurnStream，结构上不会触发。
    "approveTool": "工具确认属非流式 runTurn 路径；iOS 走流式，无 confirm_request 事件（第 83 轮核实）",
    "rejectTool": "同上",
    # 同步阻塞版回合调用：iOS 只用流式版。
    "runTurn": "iOS 用 runTurnStream；runTurn 是非流式版",
    # 带凭证注入的变体：iOS 的凭证经 updateCredentials 下发，不用 per-call 变体。
    "runTurnWithCredentials": "iOS 凭证经 updateCredentials 全局下发，不走 per-call 变体",
    "runTurnStreamWithCredentials": "同上（流式变体）",
    # TurnStateController 变体：iOS 用默认 DefaultTurnStateMachine，未注入自定义控制器。
    "runTurnWithController": "iOS 未注入自定义 TurnStateController（用默认 DefaultTurnStateMachine）",
    # 只读快照：诊断用，未接（见 docs/turn-path-parity.md 第 91 轮的同类判断）
    "globalToolDefinitions": "只读快照（诊断用途），未接 —— 同派发日志一类",
    "corePluginSnapshot": "只读快照（诊断用途），未接",
}


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def public_api(bindings: str) -> dict[str, set[str]]:
    """
    抽出每个 public class 的公开方法 / 属性名。

    只认 UniFFI 生成的规整形态：
        open func name(...) / open var name: Type
    忽略 init / deinit / 静态 helper / FfiConverter* / 协议扩展。
    """
    out: dict[str, set[str]] = defaultdict(set)
    # UniFFI 生成的类声明形态是 `open class X: XProtocol, ... {`，
    # 不是 `public class X`（我第一版只认 public，抽到 0 个）。
    class_re = re.compile(r"^(?:public (?:final )?|open )class (\w+)", re.M)
    matches = list(class_re.finditer(bindings))
    for i, m in enumerate(matches):
        name = m.group(1)
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(bindings)
        body = bindings[start:end]
        # ⚠️ 类体边界：到**下一个列 0 的顶层类型/扩展声明**为止。
        # 两个教训：
        #  · 不能用「第一个列 0 的 }」—— 截得过早，只剩 7 个 API
        #  · 边界集合**不能含 `func`**—— UniFFI 的类成员（`open func ...`）
        #    本身顶格写在列 0，含 func 会把第一个成员当成类尾（→ 0 个 API）
        #  也不能不截断 —— 最后一个类会吃到 EOF，把后面 struct 的字段算成它的成员
        top_decl = re.search(
            r"^(?:public |open |internal |fileprivate |private )?(?:final )?"
            r"(?:struct|enum|protocol|class|typealias|extension)\b",
            body, re.M,
        )
        if top_decl:
            body = body[: top_decl.start()]
        for fm in re.finditer(
            r"^\s*(?:open|public)\s+(?:func|var)\s+(\w+)", body, re.M
        ):
            member = fm.group(1)
            if member in {"init", "deinit"}:
                continue
            # UniFFI 管道符号不是应用层 API：看不到调用点是正常的
            if member.startswith(("FfiConverter", "uniffi")):
                continue
            if member.endswith(("_lift", "_lower")):
                continue
            out[name].add(member)
    return dict(out)


def called_symbols() -> str:
    """把 iOS 源码全部拼起来，供成员访问匹配。"""
    chunks = []
    for d in SOURCE_DIRS:
        if not d.exists():
            continue
        for p in sorted(d.rglob("*.swift")):
            chunks.append(p.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(chunks)


def view_symbols() -> str:
    """
    视图层（`ios/YuNian/Views/**` + AppEnvironment）的源码。

    用途：判断一个 API 是否**有 UI 入口**。
    第 97 轮记录的盲区是：`queryBalance` 只在 `ApiProbeService` 里被引用过一次，
    旧检查器据此认为「已调用」，但用户根本触达不到。
    因此除「是否被引用」外，再问一句「是否被视图层引用」。
    """
    chunks = []
    for p in sorted((REPO_ROOT / "ios/YuNian/Views").rglob("*.swift")):
        chunks.append(p.read_text(encoding="utf-8", errors="ignore"))
    env = REPO_ROOT / "ios/YuNian/App/AppEnvironment.swift"
    if env.exists():
        chunks.append(env.read_text(encoding="utf-8", errors="ignore"))
    return "\n".join(chunks)


def main() -> int:
    bindings = read(BINDINGS)
    api = public_api(bindings)
    sources = called_symbols()
    views = view_symbols()

    # 未被调用的 API
    rows: list[tuple[str, str, str]] = []   # (class, member, verdict_note)
    no_ui: list[tuple[str, str]] = []      # 被引用但无 UI 入口
    skipped_reverse = 0
    for cls in sorted(api):
        if cls in RUST_TO_APP_CLASSES:
            # Rust 调 iOS 方向：iOS 源码无调用点是**正常的**
            skipped_reverse += len(api[cls])
            continue
        for member in sorted(api[cls]):
            # 成员访问形态：`.member(` 或 `.member`（属性）
            if re.search(rf"\.{re.escape(member)}\b", sources):
                pass
            elif re.search(rf"\b{re.escape(member)}\s*\(", sources):
                pass
            else:
                note = KNOWN_UNUSED.get(member, "")
                rows.append((cls, member, note))
                continue
            # 被引用了 —— 再问一句：视图层有没有引用它？
            # （第 97 轮：旧版到此为止，把「服务层引用」当成「功能可用」）
            if not re.search(rf"\.{re.escape(member)}\b", views):
                no_ui.append((cls, member))

    total = sum(len(v) for v in api.values())
    used = total - len(rows) - skipped_reverse
    print(f"生成绑定公开 API {total} 个（{len(api)} 个类）")
    print(f"  其中 Rust→iOS 方向 {skipped_reverse} 个（由 Rust 回调，不参与本检查）")
    print(f"  app→Rust 方向 {used + len(rows)} 个：被引用 {used}，零引用 {len(rows)}")

    unknown = [r for r in rows if not r[2]]
    if not rows and not no_ui:
        print("全部 app→Rust API 均有引用，且均有 UI 入口")
        return 0

    print("\n零引用清单：")
    for cls, member, note in rows:
        mark = "  " if note else "??"
        suffix = f"  ← {note}" if note else "  ← **未评估的新缺口**"
        print(f"  {mark} {cls}.{member}{suffix}")

    if no_ui:
        print(f"\n被引用但**无 UI 入口**（{len(no_ui)} 个，用户触达不到）：")
        for cls, member in no_ui:
            print(f"  ?? {cls}.{member}  ← 只有服务层引用，无 View/AppEnvironment 引用")

    if unknown:
        print(f"\n其中 {len(unknown)} 个零引用项没有评估记录：")
        for cls, member, _ in unknown:
            print(f"  - {cls}.{member}")
        print("\n处理方式（二选一）：")
        print("  1. 确认应当调用 → 接进 iOS（这是真缺口）")
        print("  2. 确认有意不调 → 加进 KNOWN_UNUSED 并写理由")
    # report-only：不因此失败
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# 为何不设门禁
# -------------
# 「零调用」既可能是真缺口（set_worldbook / registerGlobalTools 都是），
# 也可能是合理的不调用（测试专用 API、非流式分支）。
# 自动 gate 无法区分二者，而强行用白名单消除噪音会让它退化成橡皮图章
# （我在 VerifyLiterals/verify_swift_sql 上见过这种退化）。
# 因此它作为**报告**运行：每次核对时人工过一遍新增项，判断后写进 KNOWN_UNUSED。
# 判断记录留在代码里，下一个人能看到「这条为什么不用」。
