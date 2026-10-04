import Foundation

/// 头像 URL 归一化。
///
/// ## 背景
/// `RolePresets` 里的默认头像用的是 **Android 资源 URI**：
/// ```
/// android.resource://com.yunian.ai/drawable/avatar_xiaoyu
/// ```
/// 这个字符串会原样存进 `companions.avatarUrl`（因为 schema 是两端共用的）。
/// iOS 直接拿它去加载会失败，因此需要在这里翻译成 iOS 侧的资源名。
///
/// ## 为什么把 Android URI 原样存库，而不是在播种时改写成 iOS 形式
/// 两端共用同一套 schema 与同一份种子数据。若 iOS 在播种时把 URL 改写成自己格式，
/// 一旦用户从 iOS 换回 Android（或做数据迁移），角色头像就会在 Android 上失效。
/// 因此**保持原样、在渲染层翻译**是更稳的做法 —— 这也和数据迁移的总体策略一致
/// （见文档 §4.4：跨端迁移走逻辑导出，不篡改既有字段语义）。
enum AvatarResolver {

    /// 是否为 Android 资源 URI。
    static func isAndroidResourceURI(_ url: String) -> Bool {
        url.hasPrefix("android.resource://")
    }

    /// 从 Android 资源 URI 中抽出资源名。
    /// `android.resource://com.yunian.ai/drawable/avatar_xiaoyu` → `avatar_xiaoyu`
    static func androidResourceName(from url: String) -> String? {
        guard isAndroidResourceURI(url) else { return nil }
        // 取最后一个路径段；忽略可能的类型段（drawable / mipmap / raw）
        let segments = url.split(separator: "/").map(String.init)
        guard let last = segments.last, !last.isEmpty else { return nil }
        // 去掉可能的扩展名
        return last.split(separator: ".").first.map(String.init)
    }

    /// 解析成 iOS 资源目录（Assets.xcassets）里的名字；无法解析时返回 nil（调用方用占位图）。
    ///
    /// 当前已搬运的资源：`avatar_xiaoyu`（WebP，来自 Android
    /// `app/src/main/res/drawable/avatar_xiaoyu.webp`，两端共用同一素材）。
    ///
    /// ⚠️ **已知缺口**：`avatar_aze`（男友默认头像）在 Android 侧是**矢量 drawable XML**
    /// （`app/src/main/res/drawable/avatar_aze.xml`），iOS 无法直接使用。
    /// 本机没有 SVG 光栅化工具，**无法验证转换结果的渲染效果**，
    /// 因此不生成未经验证的产物 —— 该资源当前返回 nil，UI 会退化为占位图。
    /// 处理方式（三选一，需设计侧决定）：
    ///   1. 在 macOS 上用 Xcode 把矢量 drawable 导出为 PDF/SVG 放进资源目录；
    ///   2. 请设计提供该头像的位图或原生 SVG；
    ///   3. 换用不依赖该素材的默认头像方案。
    static let bundledAssetNames: Set<String> = ["avatar_xiaoyu"]

    static func assetName(for avatarUrl: String?) -> String? {
        guard let avatarUrl, !avatarUrl.isEmpty else { return nil }

        if isAndroidResourceURI(avatarUrl) {
            guard let name = androidResourceName(from: avatarUrl),
                  bundledAssetNames.contains(name) else {
                return nil
            }
            return name
        }

        // 未来若支持 iOS 侧写入 `asset://name`，在这里解析
        if avatarUrl.hasPrefix("asset://") {
            let name = String(avatarUrl.dropFirst("asset://".count))
            return name.isEmpty ? nil : name
        }

        // 其余按远程 URL 处理（由调用方的图片库加载）
        return nil
    }

    /// 是否是可交给图片库加载的远程 URL（http/https）。
    static func remoteURL(for avatarUrl: String?) -> URL? {
        guard let avatarUrl,
              !isAndroidResourceURI(avatarUrl),
              !avatarUrl.hasPrefix("asset://"),
              let url = URL(string: avatarUrl),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return nil
        }
        return url
    }

    /// 解析为 iOS 侧资产名（**渲染层调用这个**）。
    ///
    /// - Parameter url: `companions.avatarUrl` 原始值（可能是 Android 资源 URI）
    /// - Returns: Asset Catalog 里的 imageset 名；无法解析时返回 nil（调用方应给占位图）
    ///
    /// 第 144 轮之前本枚举整体没有调用方 —— 默认伴侣的 `avatarUrl` 是
    /// `android.resource://...`，不翻译就永远加载不出头像。
    static func assetName(for url: String?) -> String? {
        guard let url else { return nil }
        return androidResourceName(from: url)
    }
}
