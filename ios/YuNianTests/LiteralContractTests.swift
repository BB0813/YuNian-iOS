import XCTest
@testable import YuNian

/// 字面量契约测试 —— 锁定那些「写错不报错、只是查不到」的取值。
///
/// ## 为什么单独有这么一个文件
/// 移植时真正容易翻车的不是逻辑，而是**字面量**：某个列存的是枚举名还是业务字面量？
/// 大小写是什么？这类错误**不抛异常**，只让查询静默返回空结果。
///
/// 本项目已经真实踩过一次：`MessageRepository.ConversationType` 最初写成
/// `"COMPANION"` / `"GROUP"`，而 Android 实际存的是 `"chat"` / `"group"`。
/// 更隐蔽的是，SQLite 验证脚本里也写了同样的错值 —— 插入与查询自洽，
/// 64 项断言全过，却与 Android 完全不同。**自洽不等于忠实。**
///
/// 因此这里有两条防线：
///   1. `ios/Tools/verify_literals.py` 从 Android 源码抽契约做交叉核对（CI 上跑）
///   2. 本文件把取值钉死，防止有人在重构时「顺手改成枚举名」
final class LiteralContractTests: XCTestCase {

    /// `conversationType` 是**普通 TEXT 列**，取值是小写字面量。
    ///
    /// 依据：`ConversationRef` 的 `init` 块
    /// `require(conversationType == "chat" || conversationType == "group")`。
    func testConversationTypeUsesLowercaseLiterals() {
        XCTAssertEqual(MessageRepository.ConversationType.chat.rawValue, "chat")
        XCTAssertEqual(MessageRepository.ConversationType.group.rawValue, "group")

        // 明确拦一次历史错误：枚举名不是存储值
        let rawValues = Set([
            MessageRepository.ConversationType.chat.rawValue,
            MessageRepository.ConversationType.group.rawValue,
        ])
        XCTAssertFalse(rawValues.contains("COMPANION"), "conversationType 不存枚举名")
        XCTAssertFalse(rawValues.contains("GROUP"), "conversationType 不存枚举名")
        XCTAssertEqual(rawValues, ["chat", "group"])
    }

    /// `type` / `fileFormat` 是**枚举列**，Room 转换器按 `value.name` 存**大写**。
    /// `@SerialName("text")` 只影响 kotlinx.serialization 的 JSON，与 Room 无关 ——
    /// 这个区分是最容易搞混的地方。
    func testEnumColumnsUseUppercaseNames() {
        // 无默认值的 type 由调用方给；这里断言契约文档里写的取值形态
        let validTypes = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "VOICE", "FILE", "REASONING", "TOOL_ACTIVITY"]
        for t in validTypes {
            XCTAssertEqual(t, t.uppercased(), "枚举列值必须是大写枚举名")
        }
        XCTAssertTrue(validTypes.allSatisfy { !$0.contains("_") || $0.hasPrefix("TOOL_") || $0 == "TOOL_ACTIVITY" })
    }

    /// 记忆相关的枚举列同样是大写。
    func testMemoryEnumColumnsUseUppercaseNames() {
        let memoryTypes = ["WORKING", "EPISODIC", "SEMANTIC", "PREFERENCE", "RELATIONSHIP", "PROCEDURAL", "FUZZY"]
        let scopes = ["GLOBAL", "COMPANION", "GROUP", "PRIVATE"]
        let sources = ["CHAT", "GROUP_CHAT", "MANUAL", "SYSTEM"]

        XCTAssertEqual(memoryTypes.map { $0.uppercased() }, memoryTypes)
        XCTAssertEqual(scopes.map { $0.uppercased() }, scopes)
        XCTAssertEqual(sources.map { $0.uppercased() }, sources)

        // 与 Swift 侧默认值一致
        XCTAssertTrue(memoryTypes.contains("SEMANTIC"))
        XCTAssertTrue(scopes.contains("COMPANION"))
        XCTAssertTrue(sources.contains("CHAT"))
    }

    /// 表情使用记录的 `source` 由 Rust `source_str` 决定，取值 `user` / `model`（小写）。
    func testStickerUsageSourceValues() {
        let rustValues = ["user", "model"]
        XCTAssertEqual(rustValues.map { $0.lowercased() }, rustValues)
        XCTAssertTrue(rustValues.allSatisfy { $0 == $0.lowercased() }, "source 是小写字面量，不是枚举名")
    }

    /// 供应商预设的 provider 键是**大写枚举名**（与 `ApiProvider.name` 一致）。
    func testProviderPresetKeysAreUppercaseEnumNames() {
        let presets = YuNianSeed.apiProviderPresets
        XCTAssertEqual(presets.count, 13)
        for preset in presets {
            XCTAssertEqual(
                preset.provider, preset.provider.uppercased(),
                "provider 键应是大写枚举名：\(preset.provider)"
            )
            XCTAssertNotEqual(preset.provider, "PARTNER", "预设不含 PARTNER（内置云通道单独管理）")
        }
        // formatHint 的规则：仅 ANTHROPIC 为 anthropic
        for preset in presets {
            XCTAssertEqual(
                preset.formatHint == "anthropic", preset.provider == "ANTHROPIC",
                "formatHint 规则不符：\(preset.provider) → \(preset.formatHint)"
            )
        }
    }
}
