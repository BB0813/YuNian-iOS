import Foundation

/// 生图协议 —— 对应 Android `core:domain` 的 `ImageGenProtocol`。
///
/// ## 为什么只需要 `systemRules`
/// 第 105 轮补 `settings.imageGenRules` 时发现它没有来源。
/// Kotlin 侧的调用点是 `ChatGenerationManager`:
/// ```kotlin
/// ImageGenTriggerLogic.systemRules(
///     enabled = appSettingsStore.getImageGenEnabled(),
///     hasKeywordTrigger = appSettingsStore.getImageGenKeywords().isNotEmpty(),
/// )
/// ```
/// 而 `ImageGenTriggerLogic.systemRules` 只是转发到 `ImageGenProtocol.systemRules`
/// （`core/domain/.../imagegen/ImageGenProtocol.kt:114`）。
///
/// ⚠️ Kotlin 用 `buildString { append(...) }` **逐段无分隔拼接**。
/// Swift 的多行字符串字面量每行自带换行，直接搬过来会多出 8 个 `\n`。
/// 所以这里用 `+` 显式连接，逐段对应 Kotlin 的每个 `append`。
///
/// ⚠️ Android 侧还有一整套正则（DOUBLE_BRACKET_TAG / BRACKETED_PROMPT /
/// STANDALONE_PROMPT_LINE）用于从模型回复里**提取**画面描述。
/// 那套是「有生图功能后」才需要的，本轮不移植 —— iOS 当前无生图链路，
/// 强行移植一批没有调用方的正则只会制造新的死代码。
/// 这里只做 settings 注入必需的 `systemRules`。
///
/// ⚠️ 两个入参当前都没有 UI，因此**恒取 Kotlin 的默认分支**
/// （`enabled = true`、`hasKeywordTrigger = false`）。
/// 这与 Android 开箱行为一致：Android 的 `getImageGenEnabled()` 默认 true、
/// 关键词列表默认为空。等 iOS 做了生图开关再接真实值。
enum ImageGenProtocol {

    /// 对应 `ImageGenProtocol.systemRules(enabled:hasKeywordTrigger:)`。
    ///
    /// Kotlin 的 `enabled = false` 直接返回空串，对应 iOS 就是**不发这个键**
    /// （`AgentFacade.buildSettingsJson` 用 `isNullOrBlank()` 判空）。
    /// 所以本函数返回空串时，调用方不应设置 `settings.imageGenRules`。
    static func systemRules(enabled: Bool = true, hasKeywordTrigger: Bool = false) -> String {
        if !enabled { return "" }
        let extra = hasKeywordTrigger
            ? "用户也可能直接用「画一张…」「再生成一张」这类说法来要图，此时同样必须输出该标签。"
            : ""
        return "当用户要求你画图/生图/配图，或你主动想发一张画面时，"
            + "你必须在回复正文的最后单独输出一行：[[生图: 画面描述]]。"
            + "不要只说「帮你生成」「这就画」这类话却不输出该标签 —— 那样不会真的出图。"
            + "画面描述只写画面本身（主体、场景、风格、构图、光线），不要写对话口吻的文字。"
            + extra
            + "不需要配图时，绝对不要输出该标签。"
            + "除 [[生图: 画面描述]] 这一种写法外，"
            + "严禁在回复中输出「画面：…」或以圆括号/方括号/书名号包裹的画面描述，"
            + "也不要复述系统注记里出现的画面内容。"
    }

    /// 便捷入口：当前 iOS 无开关 UI，取 Kotlin 默认分支。
    static var defaultPrompt: String { systemRules() }
}
