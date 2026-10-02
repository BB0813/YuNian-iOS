package com.yunian.ai.feature.qqbot.data

/** QQ 主动消息错误到可操作说明的纯函数映射。 */
object QQBotProactiveErrorMapper {
    private const val ACTIVE_GROUP_PERMISSION_CODE = "40034105"

    /**
     * 40034105 是平台资质限制，不是客户端路由、连接或请求体故障：
     * 群聊主动消息需要企业资质，个人资质机器人只能使用被动回复链路。
     */
    fun message(httpCode: Int, detail: String): String {
        val enterpriseQualificationRequired = httpCode == 400 &&
            (detail.contains(ACTIVE_GROUP_PERMISSION_CODE) ||
                detail.contains("主动消息失败, 无权限"))
        return if (enterpriseQualificationRequired) {
            "QQ 群聊主动消息需要企业资质；当前机器人没有该平台权限（40034105）。" +
                "这不是客户端架构、群路由或连接故障；个人资质仍可正常使用群聊被动回复。"
        } else {
            "主动发送失败: $httpCode" + if (detail.isBlank()) "" else " $detail"
        }
    }
}
