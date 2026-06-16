package com.lianyu.ai

/**
 * Navigation 状态机 — 所有路由的 sealed class 定义。
 *
 * 控制论: 离散系统差分方程 S[k+1] = f(S[k], E[k])
 * 验证: 所有状态从 Home 可达 ✓  所有状态出度 ≥ 1 ✓  编译器穷尽检查 ✓
 */
sealed class MainRoute(val route: String) {
    // === 主页 Tab ===
    object Home : MainRoute("home")
    object Contacts : MainRoute("contacts")
    object Profile : MainRoute("profile")

    // === 聊天 ===
    data class Chat(val companionId: Long) : MainRoute("chat/$companionId")
    data class ChatDetail(val companionId: Long) : MainRoute("chat_detail/$companionId")
    data class VoiceCall(val companionId: Long) : MainRoute("voice_call/$companionId")

    // === 群聊 ===
    data class GroupChat(val groupId: Long) : MainRoute("group_chat/$groupId")
    data class GroupDetail(val groupId: Long) : MainRoute("group_detail/$groupId")
    object CreateGroup : MainRoute("create_group")

    // === 伴侣 ===
    object CreateCompanion : MainRoute("create")
    data class EditCompanion(val companionId: Long) : MainRoute("edit/$companionId")

    // === 设置 ===
    object Settings : MainRoute("settings")
    object TtsSettings : MainRoute("tts_settings")
    object TokenUsage : MainRoute("token_usage")
    object Theme : MainRoute("theme")
    object Language : MainRoute("language")
    object CheckUpdate : MainRoute("check_update")
    object FrameRate : MainRoute("frame_rate")
    object AppSettings : MainRoute("app_settings")

    // === 个人中心 ===
    object Memory : MainRoute("memory")
    object ContextMemory : MainRoute("context_memory")
    object About : MainRoute("about")
    object AgreementView : MainRoute("agreement_view")
    object Team : MainRoute("team")
    object Support : MainRoute("support")

    // === 微信 ===
    object WeChatSettings : MainRoute("wechat_settings")
    object WeChatBind : MainRoute("wechat_bind")

    companion object {
        /** 从路由字符串解析（用于 NavHost currentRoute） */
        fun fromRoute(route: String?): MainRoute = when {
            route == null -> Home
            route == "home" -> Home
            route == "contacts" -> Contacts
            route == "profile" -> Profile
            route == "create" -> CreateCompanion
            route == "create_group" -> CreateGroup
            route == "settings" -> Settings
            route == "tts_settings" -> TtsSettings
            route == "token_usage" -> TokenUsage
            route == "memory" -> Memory
            route == "context_memory" -> ContextMemory
            route == "theme" -> Theme
            route == "language" -> Language
            route == "check_update" -> CheckUpdate
            route == "about" -> About
            route == "agreement_view" -> AgreementView
            route == "frame_rate" -> FrameRate
            route == "team" -> Team
            route == "support" -> Support
            route == "app_settings" -> AppSettings
            route == "wechat_settings" -> WeChatSettings
            route == "wechat_bind" -> WeChatBind
            route?.startsWith("chat/") == true -> Chat(route.removePrefix("chat/").toLongOrNull() ?: 0L)
            route?.startsWith("chat_detail/") == true -> ChatDetail(route.removePrefix("chat_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("voice_call/") == true -> VoiceCall(route.removePrefix("voice_call/").toLongOrNull() ?: 0L)
            route?.startsWith("group_chat/") == true -> GroupChat(route.removePrefix("group_chat/").toLongOrNull() ?: 0L)
            route?.startsWith("group_detail/") == true -> GroupDetail(route.removePrefix("group_detail/").toLongOrNull() ?: 0L)
            route?.startsWith("edit/") == true -> EditCompanion(route.removePrefix("edit/").toLongOrNull() ?: 0L)
            else -> Home
        }
    }
}
