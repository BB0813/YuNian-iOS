package com.lianyu.ai.security

/**
 * SecureStrings — typed accessor for the encrypted string table stored in liblianyu_security.so.
 *
 * All sensitive strings (API URLs, OAuth identifiers, crypto constants) are stored as
 * AES-256-CBC ciphertext in the native .rodata section. Each call decrypts on the fly,
 * returns a Java String, and immediately zeros the native plaintext buffer.
 *
 * Static analysis of the .so reveals only ciphertext — no readable URLs or keys.
 *
 * String IDs map to enum SecureStringId in native/string-table.cpp.
 */
object SecureStrings {

    /** Direct lookup by string ID for dynamic dispatch (e.g., ApiProvider enum).
     *  Falls back to individual lazy properties for IDs 0–16. */
    fun getByStringId(id: Int): String = when (id) {
        0 -> apiHost
        1 -> apiSyncPath
        2 -> gitHubApiUrl
        3 -> gitHubRepoOwner
        4 -> gitHubRepoName
        5 -> oauthRedirect
        6 -> openaiBaseUrl
        7 -> deepseekBaseUrl
        8 -> dashscopeBaseUrl
        9 -> kimiBaseUrl
        10 -> anthropicBaseUrl
        11 -> geminiBaseUrl
        12 -> securityProviderClass
        13 -> chatCompletionsPath
        14 -> messagesPath
        15 -> chatCompletionsNoSlash
        16 -> messagesNoSlash
        17 -> xiaomiBaseUrl
        18 -> zhipuBaseUrl
        19 -> siliconflowBaseUrl
        20 -> openrouterBaseUrl
        21 -> groqBaseUrl
        22 -> partnerBaseUrl
        else -> ""
    }

    /** API hostname: "api.lianyu.app" */
    val apiHost: String by lazy { NativeBridge.getSecureString(0) }

    /** API sync path: "/v1/sync" */
    val apiSyncPath: String by lazy { NativeBridge.getSecureString(1) }

    /** GitHub API URL: "https://api.github.com" */
    val gitHubApiUrl: String by lazy { NativeBridge.getSecureString(2) }

    /** GitHub repo owner: "linruoxi666" */
    val gitHubRepoOwner: String by lazy { NativeBridge.getSecureString(3) }

    /** GitHub repo name: "LianYu" */
    val gitHubRepoName: String by lazy { NativeBridge.getSecureString(4) }

    /** OAuth redirect URI: "lianyu://oauth/callback" */
    val oauthRedirect: String by lazy { NativeBridge.getSecureString(5) }

    /** OpenAI base URL */
    val openaiBaseUrl: String by lazy { NativeBridge.getSecureString(6) }

    /** DeepSeek base URL */
    val deepseekBaseUrl: String by lazy { NativeBridge.getSecureString(7) }

    /** DashScope base URL */
    val dashscopeBaseUrl: String by lazy { NativeBridge.getSecureString(8) }

    /** Kimi base URL */
    val kimiBaseUrl: String by lazy { NativeBridge.getSecureString(9) }

    /** Anthropic base URL */
    val anthropicBaseUrl: String by lazy { NativeBridge.getSecureString(10) }

    /** Gemini base URL */
    val geminiBaseUrl: String by lazy { NativeBridge.getSecureString(11) }

    /** Security provider class name */
    val securityProviderClass: String by lazy { NativeBridge.getSecureString(12) }

    // --- Endpoint paths (added for DEX string protection) ---

    /** "/chat/completions" */
    val chatCompletionsPath: String by lazy { NativeBridge.getSecureString(13) }

    /** "/messages" */
    val messagesPath: String by lazy { NativeBridge.getSecureString(14) }

    /** "chat/completions" (no leading slash) */
    val chatCompletionsNoSlash: String by lazy { NativeBridge.getSecureString(15) }

    /** "messages" (path segment) */
    val messagesNoSlash: String by lazy { NativeBridge.getSecureString(16) }

    /** Xiaomi MiMo base URL */
    val xiaomiBaseUrl: String by lazy { NativeBridge.getSecureString(17) }

    /** Zhipu base URL */
    val zhipuBaseUrl: String by lazy { NativeBridge.getSecureString(18) }

    /** SiliconFlow base URL */
    val siliconflowBaseUrl: String by lazy { NativeBridge.getSecureString(19) }

    /** OpenRouter base URL */
    val openrouterBaseUrl: String by lazy { NativeBridge.getSecureString(20) }

    /** Groq base URL */
    val groqBaseUrl: String by lazy { NativeBridge.getSecureString(21) }

    /** SuFlowAPI (Partner) base URL */
    val partnerBaseUrl: String by lazy { NativeBridge.getSecureString(22) }
}
