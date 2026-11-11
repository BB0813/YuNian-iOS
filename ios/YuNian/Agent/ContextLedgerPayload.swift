import os
import Foundation

/// 把「这一轮收据」翻译成 Rust 账本函数的入参（第 233 轮，阶段 3）。
///
/// # 为什么要有这一层"搬运"
///
/// 上下文账本里最贵、也最容易被算错的一项是**工具 Schema 成本**：
///
/// - 工具的权威定义只在 Rust 的 `tool_defs`（含内置工具 `emit_bubble` 等）
/// - iOS 只能看到 `globalToolDefinitions()` / `memoryToolDefinitions()` 里那部分
/// - 所以让 iOS 自己估，会得到一个**偏低**的数字
///
/// 而**低估比缺失更糟**：账本存在的第一个用途就是回答"工具是不是成本大头"。
/// 如果账本说"工具只占 3%"，用户会永远不去优化它 —— 而这恰恰是最贵的一块。
///
/// 所以：Rust 在装配现场算好每一项的**真实字节数**，经 `ledger_tools` 事件带出；
/// 这里只做搬运，**不重算**。
extension ContextLedger {

    /// 构造 `ledgerBuildSnapshotJson` 的入参。
    ///
    /// - Returns: nil 表示这一轮没有账本事件（旧路径 / 事件丢失）→ 调用方回落 v1 桥。
    static func inputJSON(receipt: TurnReceipt) -> String? {
        guard let toolsJson = receipt.ledgerToolsJson,
              let toolsData = toolsJson.data(using: .utf8),
              let tools = (try? JSONSerialization.jsonObject(with: toolsData)) as? [String: Any]
        else { return nil }

        // 片段：只给长度，不给正文（账本不需要正文，也避免复制大字符串）。
        // bytes 用 `chars * 3` 近似 —— 中文为主时 UTF-8 约 3 字节/字；
        // 宁可略高也不要低（与 Rust 侧"向上取整"同一个取向）。
        let fragments: [[String: Any]] = receipt.segments.map { seg in
            ["id": seg.id, "source": seg.source, "layer": Int(seg.layer),
             "chars": seg.chars, "bytes": seg.chars * 3]
        }

        // 工具：**上游已算好 bytes**，这里只搬（Rust 侧 `precomputed_bytes` 优先）。
        // 能出现在 `tool_defs` 里的，就是"用户允许 + 能力就绪"的那批。
        let perTool = (tools["perTool"] as? [[String: Any]]) ?? []
        Logger(subsystem: "com.yunian.ai", category: "ledger")
            .notice("payload perTool=\(perTool.count, privacy: .public)")
        let toolInputs: [[String: Any]] = perTool.compactMap { item in
            guard let name = item["name"] as? String, !name.isEmpty,
                  let bytes = item["bytes"] as? Int, bytes > 0 else { return nil }
            return ["name": name, "bytes": bytes,
                    "group": "", "enabled": true, "available": true]
        }

        var payload: [String: Any] = ["fragments": fragments, "tools": toolInputs]

        // 消息窗口（条数 + 体积）
        if let count = tools["windowCount"] as? Int, let bytes = tools["windowBytes"] as? Int,
           count > 0, bytes > 0 {
            payload["messageWindow"] = [
                "label": "\(count) 条", "count": count,
                "chars": bytes / 3, "bytes": bytes,
            ]
        }

        // 世界书：有注入量 → 已送入；没有 → 按"本轮没命中"记。
        //
        // ⚠️ 用 `skipped_no_trigger` 而不是别的：那是**正常状态**，
        // 界面不会警示、也不会给修复入口。若误写成"未绑定"，
        // 会引导用户去绑一本其实绑过的书。
        let wbBytes = tools["worldbookBytes"] as? Int ?? 0
        let wbEntries = tools["worldbookEntries"] as? Int ?? 0
        let evidence: [String: String] = ["entries": "\(wbEntries)"]
        if wbBytes > 0 {
            payload["worldbook"] = [
                "injected": true, "entries": wbEntries,
                "chars": wbBytes / 3, "bytes": wbBytes,
                "evidence": evidence,
            ]
        } else {
            payload["worldbook"] = [
                "injected": false, "entries": wbEntries,
                "reason": "skipped_no_trigger",
                "evidence": evidence,
            ]
        }

        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 用 **Rust 的权威实现**算出 v2 账本 JSON。
    ///
    /// 返回 nil 时调用方回落 v1（宁可显示"未计量"，也不写一份错的账）。
    static func v2JSON(receipt: TurnReceipt) -> String? {
        guard let input = inputJSON(receipt: receipt) else { return nil }
        let out = ledgerBuildSnapshotJson(inputJson: input)
        // 自校验：必须能解回 v2 且没有 error 字段，否则不落库
        guard let data = out.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (obj["v"] as? Int ?? 0) >= 2,
              obj["error"] == nil else { return nil }
        return out
    }

    /// 取**当前可得的最好一份**账本：v2（Rust 权威）→ 否则 v1 桥。
    ///
    /// 界面统一走这一个入口 —— 免得每个调用点各写一遍回落逻辑。
    static func best(receipt: TurnReceipt) -> ContextLedger {
        if let v2 = v2JSON(receipt: receipt),
           let decoded = ContextLedger.decode(fragmentsJson: v2) {
            return decoded
        }
        return ContextLedger.from(receipt: receipt)
    }
}
