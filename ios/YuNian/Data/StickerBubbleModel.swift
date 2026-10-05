import Foundation
import GRDB
import UIKit

/// 表情气泡数据 —— 由 `[stickerId]` 消息解析而来。
///
/// ## 为什么是 struct 而不是在 View 里直接读库
/// SwiftUI 的 body 会在每次刷新时重算；把 DB 查询做成惰性的、
/// 一次性消费的模型，可以避免"每次重绘都去读一次 SQLite"。
struct StickerBubbleModel {

    let entryId: Int64
    let fileName: String
    let description: String?

    /// 图片。**惰性读取**：构造时不解码，首次访问才读文件。
    ///
    /// 读不到文件时为 nil，由调用方显示占位 —— 消息仍可见，
    /// 与 Android"资源缺失时显示描述"的处理一致。
    var image: UIImage? {
        let url = try? AppPaths.stickersDirectory().appendingPathComponent(fileName)
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }

    /// 气泡下方的次要文字。
    var label: String? {
        if let description, !description.isEmpty { return description }
        return fileName.isEmpty ? nil : fileName
    }

    /// 从库里取一条表情条目。
    ///
    /// 取不到（例如备份导入后 id 变了、或表情被删）时用空 fileName，
    /// 让界面显示占位而不是崩。
    init?(entryId: Int64, database: YuNianDatabase?) {
        guard let database else { return nil }

        let row: Row? = try? database.pool.read { db in
            try Row.fetchOne(
                db,
                sql: "SELECT fileName, description FROM sticker_entries WHERE id = ? LIMIT 1",
                arguments: [entryId])
        }
        guard let row else { return nil }

        self.entryId = entryId
        self.fileName = row["fileName"] ?? ""
        self.description = row["description"]
    }
}
