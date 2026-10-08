#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_literals.py — 交叉核对 Swift 里的字面量契约与 Android 源码。

## 为什么需要它
移植时最容易出的错不是逻辑，而是**字面量**：某个列存的是枚举名还是业务字面量？
大小写是什么？这类错误**不会报错**，只会让查询静默返回空结果。

本脚本由一次真实事故催生：`MessageRepository.ConversationType` 最初被写成
`"COMPANION"` / `"GROUP"`，而 Android 实际存的是 `"chat"` / `"group"`。
更糟的是 SQLite 验证脚本里也硬编码了同样的错值 —— 插入与查询自洽，
64 项断言全过，却与 Android 完全不同。**自洽不等于忠实。**

## 契约的唯一来源
所有「Android 侧的合法取值」都由 `contracts.py` 从源码推导。
**本脚本与任何其它脚本都不得再硬编码这些字符串** ——
重复硬编码正是事故的根因。

用法：
    python ios/Tools/verify_literals.py
    python ios/Tools/verify_literals.py --contracts   # 只打印推导出的契约
退出码 0 = 全部一致。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import contracts as C  # noqa: E402

REPO_ROOT = C.REPO_ROOT
SWIFT_DIR = REPO_ROOT / "ios/YuNian"

# ── 覆盖面下限（自证用）────────────────────────────────────────────
# 第 47 轮的教训：给本脚本加新节时误插了一个提前 `return`，导致第 8/9/10 节
# （约 26 项断言）静默变成死代码，而所有关卡仍全绿 —— 因为报告的数字
# 就是从被阉割后的代码里算出来的。
#
# 因此这里设一个下限：实际断言数低于它说明覆盖被阉割，**必须失败**。
# 新增断言时请同步抬高这个数字（它是护栏，不是天花板）。
MIN_CHECKS = 90
MESSAGE_REPO = SWIFT_DIR / "Data/Repositories/MessageRepository.swift"
AGENT_STORES = SWIFT_DIR / "Agent/AgentStores.swift"
SEED_SWIFT = SWIFT_DIR / "Data/Seed/YuNianSeed.swift"


class Report:
    def __init__(self) -> None:
        self.passed = 0
        self.failed: list[str] = []

    def check(self, name: str, cond: bool, detail: str = "") -> None:
        if cond:
            self.passed += 1
            print(f"  [ok]   {name}")
        else:
            self.failed.append(f"{name} {detail}")
            print(f"  [FAIL] {name} {detail}")


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到文件：{p}")
    return p.read_text(encoding="utf-8")


def swift_enum_values(src: str, type_name: str) -> set[str]:
    """取 Swift 枚举的 rawValue（`case x = "..."` 形式）。"""
    m = re.search(rf"enum {type_name}: String \{{(.*?)\n    \}}", src, re.S)
    if not m:
        return set()
    return set(re.findall(r'case \w+ = "([^"]+)"', m.group(1)))


def main() -> int:
    if "--contracts" in sys.argv:
        print("conversationType   :", C.conversation_types())
        print("Room 转换器用 name :", C.room_converter_stores_enum_name())
        for e in ("MessageType", "FileFormat", "MemoryType", "MemoryScope",
                  "MemorySource", "ApiProvider"):
            print(f"{e:19s}:", C.enum_names(e))
        print("sticker source     :", C.sticker_usage_sources())
        print("ViolationLevel     :", C.filter_pattern_levels())
        return 0

    report = Report()

    # ── 1. conversationType ────────────────────────────────────────────
    print("conversationType（普通 TEXT 列，非枚举列）")
    allowed = set(C.conversation_types())
    repo_src = read(MESSAGE_REPO)
    swift_values = swift_enum_values(repo_src, "ConversationType")

    report.check("能从 Android 源码推导出取值约束", bool(allowed), f"得到 {sorted(allowed)}")
    report.check("MessageRepository 中能找到 ConversationType", bool(swift_values))
    report.check(
        "Swift 枚举取值与 ConversationRef 约束一致",
        swift_values == allowed,
        f"Swift={sorted(swift_values)} Android={sorted(allowed)}",
    )
    # 明确拦一次历史错误：枚举名不是存储值
    wrong = swift_values & {"COMPANION", "GROUP"}
    report.check("未误用枚举名（COMPANION/GROUP）", not wrong, f"发现 {sorted(wrong)}")

    # ── 2. 枚举列：Room 按 value.name 存大写 ───────────────────────────
    print()
    print("枚举列（Room 转换器按 value.name 存大写；@SerialName 只影响 JSON）")

    report.check(
        "Converters 正向转换使用 value.name（前提成立）",
        C.room_converter_stores_enum_name(),
        "未找到 `= value.name` 形式的转换器；若改用 @SerialName 则本检查前提失效",
    )

    stores_src = read(AGENT_STORES)
    for enum_name, swift_default in [
        ("MessageType", None),
        ("FileFormat", "TEXT"),
        ("MemoryType", "SEMANTIC"),
        ("MemoryScope", "COMPANION"),
        ("MemorySource", "CHAT"),
    ]:
        names = C.enum_names(enum_name)
        report.check(f"{enum_name} 可解析且全部为大写枚举名",
                     bool(names) and all(n == n.upper() for n in names))
        if swift_default:
            report.check(f"{enum_name} 含 Swift 侧默认值 {swift_default}",
                         swift_default in names, f"枚举={names}")

    # 断言默认值是大写枚举名。
    # 用 `?? "SEMANTIC"` 这样的片段而不是完整表达式 —— 完整表达式容易因
    # 无关改动（例如加上 `.uppercased()`）而误报，那不是契约变化。
    report.check(
        "AgentStores 的 memoryType 默认值为大写枚举名",
        '?? "SEMANTIC"' in stores_src,
        "未找到 `?? \"SEMANTIC\"`；若默认值改为其它枚举名请同步更新本检查",
    )
    report.check(
        "AgentStores 的 scope 默认值为大写枚举名",
        '?? "COMPANION"' in stores_src,
    )
    # 入参侧的大小写归一（对应 Android 的 valueOf(raw.trim().uppercase())）
    report.check(
        "AgentStores 对入参 memory_type/scope 做了大小写归一",
        '"? String)?.uppercased()' in stores_src or 'as? String)?.uppercased()' in stores_src,
    )
    report.check(
        "MessageRepository 的 fileFormat 默认值为大写枚举名",
        'fileFormat: String = "TEXT"' in repo_src,
    )
    report.check(
        "MessageRepository 的 type 为必填（不给默认，避免猜错类型）",
        re.search(r"\btype: String,\s*\n", repo_src) is not None,
    )

    # ── 3. 表情 source（由 Rust 决定）────────────────────────────────
    print()
    print("表情使用记录的 source（由 Rust source_str 决定）")
    rs_values = set(C.sticker_usage_sources())
    report.check("能从 Rust 解析出 source 取值", bool(rs_values), f"得到 {sorted(rs_values)}")
    report.check("source 取值集合为 {user, model}", rs_values == {"user", "model"})
    report.check(
        "Swift 按 source 显式选择计数列（不用「非 user 即 model」）",
        'case "user": countColumn = "userUsageCount"' in stores_src
        and 'case "model": countColumn = "modelUsageCount"' in stores_src,
    )
    report.check("Swift 对未知 source 不做计数累加", "insertUsageLogOnly" in stores_src)

    # ── 4. 供应商预设（provider 键 = 大写枚举名）─────────────────────
    print()
    print("供应商预设（provider 键与 ApiProvider 枚举名一致）")
    seed_src = read(SEED_SWIFT)
    seed_providers = re.findall(r'\.init\(provider: "([^"]+)"', seed_src)
    api_providers = set(C.enum_names("ApiProvider"))
    report.check("生成物中有预设条目", len(seed_providers) == 13, f"实际 {len(seed_providers)}")
    report.check(
        "所有 provider 键都是 ApiProvider 的合法枚举名",
        set(seed_providers) <= api_providers,
        f"非法项={sorted(set(seed_providers) - api_providers)}",
    )
    report.check("预设不含 PARTNER（内置云通道单独管理）", "PARTNER" not in seed_providers)

    # ── 5. SPKI DER 前缀（签名 keyId 的基础，错了会全线 401）────────────
    print()
    print("SecureEnclaveSigner 的 P-256 SPKI DER 前缀")
    signer_src = read(SWIFT_DIR / "Platform/SecureEnclaveSigner.swift")
    m = re.search(r"spkiPrefix:\s*\[UInt8\]\s*=\s*\[(.*?)\]", signer_src, re.S)
    report.check("能找到 spkiPrefix 定义", m is not None)
    if m:
        prefix = bytes(int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", m.group(1)))
        report.check("前缀为 26 字节",
                     len(prefix) == 26, f"实际 {len(prefix)}")
        report.check("前缀以 SEQUENCE(0x30 0x59) 开头",
                     prefix[:2] == b"\x30\x59", f"实际 {prefix[:2].hex()}")

        # 逐层解析 DER，确认结构与长度自洽（不是靠人工读注释）
        try:
            i = 0
            assert prefix[i] == 0x30, "外层应为 SEQUENCE"
            outer_len = prefix[i + 1]
            i += 2
            # 内层 SEQUENCE：两个 OID
            assert prefix[i] == 0x30, "内层应为 SEQUENCE"
            inner_len = prefix[i + 1]
            i += 2
            oid1_len = prefix[i + 1]
            oid1 = prefix[i + 2:i + 2 + oid1_len]
            i += 2 + oid1_len
            oid2_len = prefix[i + 1]
            oid2 = prefix[i + 2:i + 2 + oid2_len]
            i += 2 + oid2_len
            # BIT STRING
            assert prefix[i] == 0x03, "公钥应为 BIT STRING"
            bits_len = prefix[i + 1]
            unused = prefix[i + 2]
            i += 3

            report.check("内层 SEQUENCE 长度自洽", inner_len == (2 + oid1_len) + (2 + oid2_len),
                         f"{inner_len} vs {(2 + oid1_len) + (2 + oid2_len)}")
            report.check("OID1 = 1.2.840.10045.2.1 (ecPublicKey)",
                         oid1 == bytes.fromhex("2A8648CE3D0201"), oid1.hex())
            report.check("OID2 = 1.2.840.10045.3.1.7 (prime256v1)",
                         oid2 == bytes.fromhex("2A8648CE3D030107"), oid2.hex())
            report.check("BIT STRING 的 unused bits 为 0", unused == 0)
            report.check("BIT STRING 长度 = 1 + 65（未压缩点）", bits_len == 66, f"实际 {bits_len}")
            # 外层内容 = 内层整体(2+19=21) + BIT STRING 整体(2+66=68) = 89
            report.check(
                "长度自洽：外层 89 = 内层整体 21 + BIT STRING 整体 68（内层内容 19）",
                outer_len == 89 and inner_len == 19 and inner_len == (2 + oid1_len) + (2 + oid2_len),
                f"outer={outer_len} inner={inner_len}",
            )
            report.check("前缀恰好到裸点之前（26 字节）", i == 26, f"实际 {i}")
        except AssertionError as e:
            report.check(f"SPKI DER 结构解析失败：{e}", False)

    # keyId 的推导必须与 Android 一致：sha256(SPKI).hex[:32]
    report.check("keyId = sha256(SPKI).hex 前 32 位",
                 "sha256Hex(spki).prefix(32)" in signer_src)
    report.check("publicKeyBase64 = Base64(SPKI)",
                 "spkiDER().base64EncodedString()" in signer_src)

    # ── 6. settings_json / credentials_json 的键集合 ────────────────────
    print()
    print("settings_json / credentials_json 键集合（多发键会改变 system prompt）")

    # Android 的 buildSettingsJson 只发 4 个键（role/timezone/working_memory_limit 必有，
    # image_gen_rules 条件发送）。从源码抽，避免手写。
    android_facade = read(
        REPO_ROOT / "core/agent/src/main/kotlin/com/yunian/ai/agent/AgentFacade.kt")
    # buildSettingsJson 是**表达式体**：`fun ...( ... ): String = org.json.JSONObject().apply { ... }.toString()`
    # 注意结尾是 `}.toString()`（apply 块闭合），不是 `).toString()` —— 搞错就永远匹配不上。
    m = re.search(r"fun buildSettingsJson\(.*?\}\.toString\(\)", android_facade, re.S)
    report.check("能找到 Android buildSettingsJson", m is not None)
    if m:
        body = m.group(0)
        android_settings_keys = set(re.findall(r'put\("(\w+)"', body))
        report.check(
            "Android 默认发送的键恰为 4 个",
            android_settings_keys == {"role", "timezone", "working_memory_limit", "image_gen_rules"},
            f"实际 {sorted(android_settings_keys)}（若变了需重新评估与 iOS 的差异）",
        )
        report.check("Android 不发 session_id", "session_id" not in android_settings_keys)
        report.check("Android 不发 owner_name", "owner_name" not in android_settings_keys)

        # Swift 侧：AgentSettings 的 CodingKeys
        dto_src = read(SWIFT_DIR / "Agent/AgentDTOs.swift")
        sw = re.search(r"struct AgentSettings:.*?\n(.*?)\n    init\(", dto_src, re.S)
        report.check("能找到 Swift AgentSettings", sw is not None)
        if sw:
            swift_keys = set(re.findall(r'case \w+ = "(\w+)"', sw.group(1)))
            report.check(
                "Swift settings 键是 Android 的子集（允许多 owner_name）",
                swift_keys - android_settings_keys == {"owner_name"} or swift_keys <= android_settings_keys,
                f"Swift 多出 {sorted(swift_keys - android_settings_keys)}",
            )
            report.check("Swift 不含 session_id", "session_id" not in swift_keys,
                         "session_id 曾误加：Android 无此键，多发会改 system prompt")
            # owner_name 必须被显式标注为「有意的分歧」
            if "owner_name" in swift_keys:
                report.check(
                    "owner_name 被标注为有意的跨端分歧",
                    "有意的" in dto_src and "分歧" in dto_src,
                )

    # credentials：从 Android buildCredentialsJson 抽规则
    report.check("Android credentials 用 isNullOrBlank 过滤空白",
                 "isNullOrBlank" in android_facade)
    report.check("Android credentials 对 session/client_id 做联合判断",
                 "sessionToken.isNullOrBlank() && !clientId.isNullOrBlank()" in android_facade)
    report.check("Android credentials 空结果返回 \"{}\"",
                 'json.length() == 0) "{}"' in android_facade)
    dto_full = read(SWIFT_DIR / "Agent/AgentDTOs.swift")
    report.check("Swift credentials 复刻了 isNullOrBlank 语义",
                 "whitespacesAndNewlines).isEmpty" in dto_full)
    report.check("Swift credentials 复刻了 session/client_id 联合判断",
                 "if let session, let clientId" in dto_full)

    # ── 7. 回合请求参数（maxRounds / tools）────────────────────────────
    print()
    print("AgentTurnRequest 参数（直接决定模型一个回合能做什么）")

    # maxRounds：主对话路径权威值 6（AgentDialogueCoordinator），另有委托 3u、评估自定
    coord_src = read(REPO_ROOT / "core/agent/src/main/kotlin/com/yunian/ai/agent/"
                    "AgentDialogueCoordinator.kt")
    m = re.search(r"AgentTurnRequest\((.*?)\n        \)", coord_src, re.S)
    report.check("能找到 Android 主对话路径的 AgentTurnRequest", m is not None)
    if m:
        body = m.group(1)
        android_max = re.search(r"maxRounds\s*=\s*(\d+)u", body)
        report.check("Android 主对话路径声明了 maxRounds", android_max is not None)
        if android_max:
            want = int(android_max.group(1))
            report.check(f"Android 主对话路径 maxRounds = {want}u", True)
            swift_max = re.search(r"defaultMaxRounds:\s*UInt32\s*=\s*(\d+)", dto_full)
            report.check("Swift 侧声明了 defaultMaxRounds", swift_max is not None)
            if swift_max:
                got = int(swift_max.group(1))
                report.check(
                    f"Swift maxRounds 与 Android 一致（{want}）",
                    got == want,
                    f"Swift={got} Android={want} —— 该值决定模型一回合能调几轮工具",
                )
        # 主对话路径传真实工具列表
        android_has_tools = "tools = (" in body or "tools = listOf" in body
        report.check("Android 主对话路径传真实工具列表", android_has_tools)
        # 结构体单独处理（生成侧是 `public struct X { public var y: ... }`，不是 open class）
    chat_src = read(SWIFT_DIR / "Agent/ChatSession.swift")

    swift_empty_tools = re.search(r"tools:\s*\[\],", chat_src)
    has_gap_note = "已知功能性缺口" in chat_src
    report.check(
        "Swift 不再传空 tools（记忆/技能工具已装配）",
        swift_empty_tools is None,
        "若仍为空：说明工具装配未接上（见 AgentToolCatalog）",
    )
    report.check(
        "领域工具缺口仍被显式标注",
        has_gap_note and "领域工具" in chat_src,
        "memory/skill 工具已补，但领域 ToolRegistry 尚未移植 —— "
        "这个残余缺口必须继续标注，避免后人以为工具已齐全",
    )
    report.check(
        "tools 改为装配结果而非空数组",
        "AgentToolCatalog.definitions" in chat_src,
    )

    # ── 7b. per-session 状态不得在装配期冻结 ────────────────────────
    print()
    print("装配期冻结 per-session 状态（第 46 轮踩过的坑）")

    catalog_src = read(SWIFT_DIR / "Agent/AgentToolCatalog.swift")
    report.check("install 不接收 companionId（per-session 状态）",
                 "companionId: Int64?" not in catalog_src,
                 "load_skill 曾按装配时传入 companionId，导致 boot() 时恒为 nil")
    report.check("load_skill 执行器从 contextJson 现取 companionId",
                 "companionId(fromContextJson:" in catalog_src)
    report.check("load_skill 用调用时取到的 companionId 调 loadContent",
                 "skill.loadContent(skillId: skillId, companionId: companionId)" in catalog_src)
    report.check("AppEnvironment 调用 install 时不传 companionId",
                 "companionId: nil" not in read(SWIFT_DIR / "App/AppEnvironment.swift"))

    # 其它静态状态必须是「计算属性」或「设备级缓存」，不得冻结会话态
    dmaintenance = read(SWIFT_DIR / "Data/DatabaseMaintenance.swift")
    report.check("DatabaseMaintenance.lastRunMilliseconds 是计算属性（每次重读）",
                 "static var lastRunMilliseconds: Int64 {" in dmaintenance)
    devid = read(SWIFT_DIR / "Platform/DeviceIdentity.swift")
    report.check("DeviceIdentity 全部是计算属性（IDFV 会变，不能被缓存）",
                 devid.count("static var ") >= 5 and "static let" not in devid)

    # ── 8. 历史清洗（AiDialogueHistoryPolicy）──────────────────────────
    print()
    print("历史清洗（AiDialogueHistoryPolicy）")

    policy_kt = read(REPO_ROOT / "core/domain/src/main/java/com/yunian/ai/domain/AiDialoguePolicy.kt")
    # Android 的关键规则必须在 Kotlin 源码里存在（契约来源）
    report.check("Android 用零宽空格参与判空", "u200B" in policy_kt)
    report.check("Android 过滤操作性消息", "isOperationalContent" in policy_kt)
    report.check("Android 对相邻同角色做合并", "merged[merged.lastIndex]" in policy_kt)
    report.check("Android 的工具结果规则按角色分流",
                 "[工具调用结果]" in policy_kt and "msg.role == AiMessageRole.USER" in policy_kt)

    policy_swift = read(SWIFT_DIR / "Agent/DialogueHistoryPolicy.swift")
    report.check("Swift 实现了 sanitizeForModel", "func sanitizeForModel" in policy_swift)
    report.check("Swift 判空同样去掉零宽空格",
                 "replacingOccurrences(of: \"\\u{200B}\"" in policy_swift)
    report.check("Swift 有操作性消息表", "exactOperational" in policy_swift
                 and "prefixOperational" in policy_swift)
    report.check("Swift 合并相邻同角色", "last.role == message.role" in policy_swift)
    report.check("Swift 合并排除 TOOL", "last.role != .tool" in policy_swift)
    report.check("Swift 实现了工具结果的角色分流",
                 'content.hasPrefix("[工具调用结果]")' in policy_swift)

    # 最关键的一条：历史必须真的经过清洗
    chat_src = read(SWIFT_DIR / "Agent/ChatSession.swift")
    report.check(
        "ChatSession.historyForRequest 调用了 sanitizeForModel",
        "DialogueHistoryPolicy.sanitizeForModel(raw)" in chat_src,
        "不清洗会把 toast / API 错误提示 / 空消息也喂给模型",
    )

    # ── 9. 编排器必须注入（orchestrator: nil 会让回合没有系统提示）──────
    print()
    print("PromptOrchestrator 注入")

    gen = read(REPO_ROOT / "ios/Generated/LianyuAgent.swift")
    report.check("生成绑定含 PromptOrchestrator 构造器",
                 "class PromptOrchestrator" in gen and "init(memory:" in gen)
    report.check("生成绑定含 MemorySelector / SkillSelector 构造器",
                 "init(store: MemoryStore)" in gen and "init(store: SkillStore)" in gen)

    env_src = read(SWIFT_DIR / "App/AppEnvironment.swift")
    report.check("AgentGlobalConfig 未传 orchestrator: nil",
                 "orchestrator: nil" not in env_src,
                 "nil 会让 agent.rs 1091/1186 两处 if let Some(orchestrator) 都不成立："
                 "用户上下文前缀不注入、system prompt 完全不组装")
    report.check("AgentGlobalConfig 注入了 PromptOrchestrator",
                 "orchestrator: PromptOrchestrator(" in env_src)
    report.check("编排器由 MemorySelector / SkillSelector 组装",
                 "MemorySelector(store: stores)" in env_src
                 and "SkillSelector(store: stores)" in env_src)

    # ── 10. PARTNER 门控 + api_configs 读取 ───────────────────────────
    print()
    print("PARTNER 门控（session/client_id 只在 PARTNER 时下发）")

    # Android 的权威语义
    coord = read(REPO_ROOT / "core/agent/src/main/kotlin/com/yunian/ai/agent/"
                 "AgentDialogueCoordinator.kt")
    report.check("Android 按 isPartner 门控 session",
                 'if (isPartner) partnerSession?.token else null' in coord)
    report.check("Android 的 isPartner 取自 activeApi.provider",
                 'activeApi?.provider == com.yunian.ai.database.model.ApiProvider.PARTNER'
                 in coord or "ApiProvider.PARTNER" in coord)

    dto2 = read(SWIFT_DIR / "Agent/AgentDTOs.swift")
    # ⚠️ 第 186 轮：断言从 `fromKeychain(isPartner: Bool)` 放宽到
    # `fromKeychain(isPartner: Bool`（去掉右括号）。
    # 该函数新增了 `apiKey:` 参数（key 改为按配置解析，由调用方传入），
    # 原来的整串匹配就再也命不中了。
    # **意图没变**：这里要保证的是"PARTNER 门控参数存在于装配入口"，
    # 不是"签名永远只有这一个参数"。
    report.check("Swift fromKeychain 接受 isPartner 参数",
                 "fromKeychain(isPartner: Bool" in dto2)
    report.check("Swift fromKeychain 接受调用方传入的 apiKey（第 186 轮）",
                 "apiKey: String)" in dto2)
    report.check("Swift 非 PARTNER 时不下发 session/client_id",
                 "isPartner ? KeychainStore.string(for: KeychainStore.Key.partnerToken) : nil"
                 in dto2)

    env2 = read(SWIFT_DIR / "App/AppEnvironment.swift")
    # ⚠️ 第 186 轮：接受两种等价写法。
    # `pushCredentials` 现在要先拿 `config` 才能取到它的 id（按配置读 key），
    # 于是顺手用 `config?.provider == "PARTNER"` 判门控，
    # 不再多查一次 `isActiveProviderPARTNER()`。
    # **意图没变**：这里要保证的是"pushCredentials 里存在 PARTNER 门控"，
    # 不是"必须调用某个具体 helper"。两种写法都算通过，
    # 两种都不在才算门控被删。
    _partner_gate = ("isActiveProviderPARTNER()" in env2
                     or 'config?.provider == "PARTNER"' in env2)
    report.check("pushCredentials 计算 isPartner", _partner_gate)
    report.check("boot 里装配了 ApiConfigRepository",
                 "ApiConfigRepository(database: database)" in env2)

    repo2 = read(SWIFT_DIR / "Data/Repositories/ApiConfigRepository.swift")
    report.check("iOS 有 ApiConfigRepository", "func activeConfig()" in repo2)
    # ⚠️ 第 121 轮：这条断言本身**就是 bug 的帮凶**，必须改。
    # 它原本要求 SQL 里出现 `provider = 'PARTNER'`（即照抄 Android 的
    #   `(apiKey IS NOT NULL AND apiKey != '' OR provider='PARTNER')
    #    AND isEnabled = 1`）。但 iOS 刻意把行内 apiKey 写成空串
    #   （不把明文落进未加密 SQLite），那个谓词在 iOS 上**恒不成立**，
    #   导致 activeConfig() 保存后仍返回 nil、UI 恒显示"未配置"。
    #   → 关卡在论证"抄对了"，而正确性前提根本不成立。
    # 现在改为核对**引擎的真实读法**：Rust load_api_config 用
    # `WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1`（native_gateway.rs）。
    # 宿主观测必须与引擎读法一致，否则"UI 说没配、回合却照样跑"。
    #
    # ⚠️ 第 121 轮：断言那段 SQL 的**完整形态**。
    # 我在这上面连错三次，记下来免得再犯：
    #   1) 通用剥注释（/\*.*?\*/）→ 文件里错配的 /* 与远处 */ 把含 SQL 的
    #      整段代码吃掉，变异后关卡照样全绿 = **假阴性**（比误报更坏）。
    #   2) 全文搜 `FROM api_configs … ORDER BY … LIMIT 1` → 文档注释里
    #      逐字引用了同一段 SQL，非贪婪匹配先命中注释，又一次假阴性。
    #   3) 限定 `func activeConfig` 函数体 → 仍可能被嵌套右括号截断，不稳。
    # 现在改成：SQL 必须**整段**等于下面的期望串。
    # 只要有人把 apiKey 谓词加回去，这段串就对不上 —— 一次性判断，
    # 不依赖"找到 WHERE 子句再解析"这种多步正则。
    expected_active_sql = (
        "SELECT id, provider, name, baseUrl, model, formatHint, isEnabled\n"
        "            FROM api_configs\n"
        "            WHERE isEnabled = 1\n"
        "            ORDER BY id DESC LIMIT 1"
    )
    report.check(
        "activeConfig 的 SQL 与 Rust load_api_config 读法逐字一致",
        expected_active_sql in repo2)
    # 单独再钉一次：整个文件里不得出现 Android 那个 apiKey 非空谓词的**代码**形态。
    # 只在 SQL 字符串里才危险；注释里引用它是为了解释为什么改，属正常。
    # 判法：该谓词只允许出现在 // 或 /// 开头的行。
    bad_apikey_predicate = [
        ln for ln in repo2.splitlines()
        if "apiKey IS NOT NULL" in ln and not ln.lstrip().startswith(("//", "*", "/*"))
    ]
    report.check(
        "代码里不得残留 Android 的 apiKey 非空谓词（iOS 行内恒为空串）",
        not bad_apikey_predicate)
    # 顺带钉住 upsertActiveConfig 的空串决策，防止将来"顺手把 key 也存进去"
    report.check("upsertActiveConfig 仍刻意写空 apiKey（不落明文）",
                 "apiKey = ''" in repo2)

    print()
    print(f"通过 {report.passed} 项，失败 {len(report.failed)} 项")

    # 覆盖面自证：低于下限说明有节被提前 return / 条件跳过阉割掉了
    if report.passed < MIN_CHECKS:
        print(f"[FAIL] 断言数 {report.passed} 低于下限 {MIN_CHECKS} —— "
              f"覆盖可能被阉割（检查是否误插提前 return）")
        report.failed.append(f"断言数 {report.passed} < 下限 {MIN_CHECKS}")

    for f in report.failed:
        print("  -", f)
    return 1 if report.failed else 0

if __name__ == "__main__":
    raise SystemExit(main())
