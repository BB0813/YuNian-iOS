import Foundation

/// 备份 / 恢复的**结果展示模型**（纯逻辑，可单测）。
///
/// ## 为什么把它从视图里抽出来
/// 1. **这里有一处真 bug**：原来导入结束时写的是
///    `status = .ok(outcome.summary)` —— **从不看 `outcome.ok`**。
///    于是"解密失败：密码错误"会带着**绿色对勾**显示出来，用户以为导成功了。
///    抽成纯函数之后，这条判断就能被测试锁住（`BackupReportTests`）。
/// 2. 分节计数来自 `[String: Int]` **字典** —— 不排序会让同一份数据每次渲染顺序都不同。
///    排序规则（先按固定的 `sectionKeys`，再按名字排未知分区）也一并在这里定死。
enum BackupReport {

    struct Line: Equatable, Identifiable {
        var label: String
        var value: String
        var id: String { label }
    }

    struct Report: Equatable {
        var ok: Bool
        /// 一句话结论（标题）。
        var title: String
        /// 明细行（可为空）。
        var lines: [Line]
        /// 下一步建议 / 提醒（可为空）。
        var hint: String?
    }

    // MARK: - 导出

    static func export(_ outcome: BackupExporter.Outcome) -> Report {
        Report(
            ok: true,
            title: "备份已生成：\(outcome.totalRecords) 条记录",
            lines: [Line(label: "分区", value: "\(outcome.sectionCounts.count) 个"),
                    Line(label: "明文体积", value: bytes(outcome.plaintextBytes)),
                    Line(label: "加密后", value: bytes(outcome.containerBytes))]
                + sectionLines(outcome.sectionCounts),
            hint: "口令**不保存在本机** —— 请把它和这个文件分开保管，忘记口令将无法恢复。"
        )
    }

    // MARK: - 导入

    /// 导入结果（**成功与失败都要走这里** —— 失败绝不能再显示成成功）。
    static func imported(_ outcome: BackupImportService.Outcome) -> Report {
        guard outcome.ok else {
            return Report(
                ok: false,
                title: outcome.message.isEmpty ? "导入未完成" : "导入未完成：\(outcome.message)",
                lines: [],
                hint: "原有数据没有被清空。确认口令正确、文件完整后可以直接重试。"
            )
        }
        let detail = outcome.detail
        var lines: [Line] = []
        if let detail {
            lines = [
                Line(label: "伴侣", value: "新增 \(detail.companionsCreated) · 匹配 \(detail.companionsMatched)"),
                Line(label: "群组", value: "新增 \(detail.groupsCreated)"),
                Line(label: "聊天消息", value: "新增 \(detail.chatMessagesInserted)"),
                Line(label: "群消息", value: "新增 \(detail.groupMessagesInserted)"),
                Line(label: "记忆", value: "新增 \(detail.memoriesInserted)"),
                Line(label: "统一记忆", value: "新增 \(detail.unifiedMemoriesInserted)"),
                Line(label: "临时记忆", value: "新增 \(detail.tempMemoriesInserted)"),
                Line(label: "用量记录", value: "新增 \(detail.tokenUsagesInserted)"),
                Line(label: "日记", value: "新增 \(detail.diariesInserted)"),
            ]
        }
        return Report(
            ok: true,
            title: "导入完成",
            lines: lines,
            hint: "导入是**增量**的：现有数据没有被清空，同名条目以导入内容为准。"
        )
    }

    /// 抛异常那条路（读文件失败等）也走同一套展示。
    static func failure(_ message: String) -> Report {
        Report(ok: false, title: message, lines: [], hint: nil)
    }

    // MARK: - 明细

    /// 分节计数 → 明细行。
    ///
    /// 顺序：先按 `BackupExporter.sectionKeys`（伴侣 → 群组 → 消息 → … 与数据重要性一致），
    /// 未知分区名再按字典序补在后面 —— 不排序会让每次渲染顺序都不同。
    static func sectionLines(_ counts: [String: Int]) -> [Line] {
        let known = BackupExporter.sectionKeys.filter { counts[$0] != nil }
        let unknown = counts.keys.filter { !BackupExporter.sectionKeys.contains($0) }.sorted()
        return (known + unknown).map { key in
            Line(label: sectionTitle(key), value: "\(counts[key] ?? 0) 条")
        }
    }

    /// 分区名的中文（未知分区回退成原名 —— 后端加了新分区也不会显示空白）。
    static func sectionTitle(_ key: String) -> String {
        switch key {
        case "companions": return "伴侣"
        case "chatGroups": return "群组"
        case "chatMessages": return "聊天消息"
        case "groupMessages": return "群消息"
        case "memoryEntries": return "记忆"
        case "tempMemories": return "临时记忆"
        case "tokenUsages": return "用量记录"
        case "unifiedMemories": return "统一记忆"
        case "diaries": return "日记"
        default: return key
        }
    }

    /// 人类可读体积（1024 进制；保留一位小数）。
    static func bytes(_ count: Int) -> String {
        if count < 1024 { return "\(count) B" }
        let kb = Double(count) / 1024
        if kb < 1024 { return String(format: "%.1f KB", kb) }
        let mb = kb / 1024
        if mb < 1024 { return String(format: "%.1f MB", mb) }
        return String(format: "%.1f GB", mb / 1024)
    }
}
