package com.lianyu.ai.security

/**
 * SecurityConstants — 统一安全常量分级管理
 *
 * ## 安全分级体系
 *
 * ### 等级 1: 绝密级 (TOP SECRET)
 * - API密钥、签名密钥、加密密钥、证书哈希
 * - **规则**: 绝不能硬编码在 Kotlin/Java 源码中
 * - **存储**: Native .so (.rodata 加密段) 或 Android Keystore/TEE
 * - **访问**: 仅通过 JNI native 方法获取，每次调用后立即清零
 * - **示例**: AES-256 密钥、WB-AES 主密钥、APK 签名证书 SHA-256、Partner API Key
 *
 * ### 等级 2: 中高级 (HIGH)
 * - 第三方 App ID、API Base URL、OAuth 回调地址
 * - **规则**: 不硬编码明文，采用多层防护
 * - **存储**: SecureStrings (native 加密字符串表) → 运行时解密 → 用完清空
 * - **防护**: 字符串加密 + 代码混淆 + NDK 防护
 * - **示例**: api.openai.com、api.deepseek.com、GitHub Repo 信息
 *
 * ### 等级 3: 中级 (MEDIUM)
 * - 调试/日志常量、内网地址、开发环境配置
 * - **规则**: 确保在生产 (Release) 版本中彻底移除
 * - **防护**: BuildConfig.DEBUG 守卫 + ProGuard -assumenosideeffects 剥离
 * - **示例**: DebugLogReporter URL、System.err.println 调试输出
 *
 * ## 安全审计
 *
 * 每个常量的安全级别必须在代码注释中标注，格式：
 * ```kotlin
 * /** @SecurityLevel TOP_SECRET — 存储于 native .rodata 加密段 */
 * ```
 *
 * ## 禁止事项
 * - ❌ 禁止在 Kotlin/Java 源码中以任何形式硬编码 TOP_SECRET 级别常量
 * - ❌ 禁止在注释中写出完整密钥值
 * - ❌ 禁止使用 val/const val 定义 TOP_SECRET 级别常量
 * - ❌ 禁止在日志中打印解密后的密钥内容
 */
object SecurityConstants {

    /**
     * 安全级别枚举
     */
    enum class Level(val label: String) {
        /** 绝密: 密钥类，必须在 native/Keystore 中 */
        TOP_SECRET("绝密"),
        /** 高级: URL/ID 类，必须经 native 加密字符串表 */
        HIGH("高级"),
        /** 中级: 调试类，Release 必须剥离 */
        MEDIUM("中级")
    }

    /**
     * 验证常量是否符合其声明的安全级别
     * 在 CI/自动化测试中调用，确保无降级泄露
     */
    fun validateLevel(constantName: String, declaredLevel: Level, actualSource: String) {
        when (declaredLevel) {
            Level.TOP_SECRET -> {
                require(actualSource == "NATIVE") {
                    "SECURITY VIOLATION: $constantName is TOP_SECRET but sourced from $actualSource (must be NATIVE)"
                }
            }
            Level.HIGH -> {
                require(actualSource in listOf("NATIVE", "SECURE_STRINGS")) {
                    "SECURITY VIOLATION: $constantName is HIGH but sourced from $actualSource (must be NATIVE or SECURE_STRINGS)"
                }
            }
            Level.MEDIUM -> {
                // 允许 Kotlin 常量，但必须 BuildConfig.DEBUG 守卫
            }
        }
    }
}