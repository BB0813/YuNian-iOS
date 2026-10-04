import Foundation

/// 请求签名 —— 与 Android 侧 `RequestSecurityInterceptor` / `AgentRequestSigner`
/// **逐字节等价**。这是全项目跨端一致性要求最高的部分：payload 任何一处不同，
/// 服务端都直接拒签（fail-closed）。
///
/// ## payload 规范（8 段，`\n` 分隔，无末尾换行）
/// ```
/// v1\n{METHOD}\n{PATH}\n{BODY_SHA256}\n{TS}\n{NONCE}\n{CLIENT_ID}\n{DEVICE_ID}
/// ```
///
/// ## 第二种格式（4 段，必须同时实现）
/// `/api/auth/handshake` 用的是：
/// ```
/// v1\nhandshake\n{CHALLENGE}\n{DEVICE_ID}
/// ```
/// 第三段是 challenge，不是 body hash。见 `signHandshake(challenge:)`。
///
/// ## ⚠️ 三处已知的跨端不一致（必须先与服务端对齐，见 §9 的 V9）
/// 1. `PATH`：Kotlin 侧取 `url.encodedPath`，PARTNER 的 base 是
///    `https://suflow.cloud/v1` → `/v1/chat/completions`；而 Rust 侧
///    `native_gateway.rs` **硬编码** `"/chat/completions"`。同一后端产生不同 payload。
/// 2. `CLIENT_ID`：Kotlin 从 header / Bearer token 推导；Rust 从凭证 JSON 的
///    `client_id` 字段读。二者可能取到不同值。
/// 3. `X-LianYu-Pub`（Base64 SPKI 公钥）只在更新通道发送，主 API 链路不发；
///    若主 API 按「用请求附带公钥验签」实现，则主链路必然 401。
///
/// 本实现的 `path` 由调用方传入 —— 请按 V9 的结论决定传哪一种。
enum RequestSigner {

    // ⚠️ 第 122 轮删除 `clientIdentifier = "lianyu-1.5.1"`：
    // 它对应 Android `RequestSecurityInterceptor:167` 的 `X-LianYu-Client` 头，
    // 但那个头只存在于 **Kotlin 的 AiService 路径**。iOS（以及 Android 的 Rust 路径）
    // 走 Rust `native_gateway.rs`，该文件处理 X-LianYu-Session / -Client-Id 与 7 个签名头，
    // **完全没有 X-LianYu-Client**。故本常量在 iOS 上无人使用，是早期
    // 「iOS 也会有网络拦截层」假设的残留，删之。
    static let signatureVersion = "v1"
    static let handshakeSegment = "handshake"

    /// 空 body 的 SHA-256（与 Kotlin 的 `sha256Hex(ByteArray(0))` 一致）。
    static let emptyBodySHA256 = DeviceIdentity.sha256Hex(Data())

    struct Headers {
        let values: [String: String]

        /// 参与签名的 7 个头（顺序无关，服务端按名取值）。
        var signedHeaderNames: [String] { Array(values.keys).sorted() }
    }

    /// 为一次 HTTP 请求生成签名头。
    ///
    /// - Parameters:
    ///   - method: HTTP 方法，内部会转大写
    ///   - path: 已编码的路径，含 query（如 `/v1/chat/completions`）；**权威取值待 V9 确认**
    ///   - body: 请求体原始字节；无 body 传 nil
    ///   - clientId: 取自 `X-LianYu-Client-Id` 头或 `Authorization` Bearer 前缀；无则空串
    ///   - timestamp: Unix 秒；默认当前时间
    ///   - nonce: 12 字节随机的小写 hex；默认随机生成
    static func sign(
        method: String,
        path: String,
        body: Data?,
        clientId: String,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970),
        nonce: String = randomNonce()
    ) throws -> Headers {
        let bodyHash = body.map { DeviceIdentity.sha256Hex($0) } ?? emptyBodySHA256
        let payload = [
            signatureVersion,
            method.uppercased(),
            path,
            bodyHash,
            String(timestamp),
            nonce,
            clientId,
            DeviceIdentity.deviceId,
        ].joined(separator: "\n")

        let signed = try SecureEnclaveSigner.sign(Data(payload.utf8))

        return Headers(values: [
            "X-LianYu-Sig-Version": signatureVersion,
            "X-LianYu-Ts": String(timestamp),
            "X-LianYu-Nonce": nonce,
            "X-LianYu-Body-SHA256": bodyHash,
            "X-LianYu-Device-Id": signed.deviceId,
            "X-LianYu-Key-Id": signed.keyId,
            "X-LianYu-Sig": signed.signature,
        ])
    }

    /// 握手用的 4 段格式（`/api/auth/handshake`）。
    /// 对应 `core/common/.../RemoteKeyProvider.kt` 的
    /// `"v1\nhandshake\n$challenge\n$deviceId"`。
    static func signHandshake(challenge: String) throws -> Headers {
        let payload = [
            signatureVersion,
            handshakeSegment,
            challenge,
            DeviceIdentity.deviceId,
        ].joined(separator: "\n")

        let signed = try SecureEnclaveSigner.sign(Data(payload.utf8))

        return Headers(values: [
            "X-LianYu-Sig-Version": signatureVersion,
            "X-LianYu-Device-Id": signed.deviceId,
            "X-LianYu-Key-Id": signed.keyId,
            "X-LianYu-Sig": signed.signature,
        ])
    }

    // ⚠️ 第 140 轮删除 `publicKeyHeader()`（曾产出 `X-LianYu-Pub`）：
    // Android 侧只在 `AppUpdateManager.kt:209`（**应用更新通道**）注入该头，
    // AI 请求路径不用 —— Rust 的 native_gateway.rs 也只发 7 个签名头，
    // 不含 X-LianYu-Pub（第 123 轮核实）。iOS 侧没有更新检查功能，
    // 故该函数自写下从未被调用。残留来源同 clientIdentifier：
    // 早期假设「iOS 也会有更新通道」。
    //
    // V9 ③ 的结论：**AI 请求路径两端都不需要 X-LianYu-Pub**，
    // 因此这一项对 iOS 不构成风险；将来若做应用内更新，再按需加回。

    // MARK: - 从请求推导 clientId

    /// 复刻 Kotlin 侧 `RequestSecurityInterceptor.extractYuNianClientId`：
    /// ```kotlin
    /// request.header("X-LianYu-Client-Id")?.takeIf { it.isNotBlank() }?.let { return it }
    /// val authorization = request.header("Authorization") ?: return ""
    /// if (!authorization.startsWith("Bearer ", ignoreCase = true)) return ""
    /// return authorization.removePrefix("Bearer ").substringBefore(':')
    /// ```
    ///
    /// ## ⚠️ 一处必须如实复刻的大小写陷阱
    /// 前缀**判断**是忽略大小写的（`ignoreCase = true`），
    /// 但**剥离**用的 `removePrefix("Bearer ")` 是**区分大小写**的。
    /// 于是 `Authorization: bearer abc` 时：
    ///   - 判断通过
    ///   - 剥离失败（`removePrefix` 没匹配到），`substringBefore(':')` 得到整串 `bearer abc`
    /// 即 Kotlin 会把 `bearer abc` 整体当作 clientId。
    ///
    /// 我原先写成「判断通过后 dropFirst(7)」，那会得到 `abc` —— 与服务端签出的 payload 不同。
    /// 跨端一致性优先于「看起来更对」，故如实复刻。
    static func clientId(clientIdHeader: String?, authorizationHeader: String?) -> String {
        if let explicit = clientIdHeader, !explicit.trimmingCharacters(in: .whitespaces).isEmpty {
            return explicit
        }
        guard let authorization = authorizationHeader,
              authorization.range(of: "Bearer ", options: [.caseInsensitive, .anchored]) != nil
        else { return "" }

        // 对应 `removePrefix("Bearer ")`：**区分大小写**，不匹配则原样保留
        let body = authorization.hasPrefix("Bearer ")
            ? String(authorization.dropFirst("Bearer ".count))
            : authorization
        // 对应 `substringBefore(':')`：无冒号时返回整串
        return body.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
    }

    /// 12 字节密码学随机 → 24 字符小写 hex（与 Kotlin 的 `SecureRandom` + hex 一致）。
    ///
    /// 服务端校验 `^[0-9a-f]{16,64}$`（`_server_update/server.mjs`），24 字符满足。
    static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 12)
        for i in bytes.indices {
            bytes[i] = UInt8.random(in: .min ... .max)
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// 由 URL 组件拼出签名用的 path。
    ///
    /// ## ⚠️ 必须用**百分号编码后**的路径
    /// Android 用的是 OkHttp 的 `url.encodedPath`（**保留 `%XX`**）+ `?encodedQuery`。
    /// 我原先用 Swift 的 `url.path`，那是**已解码**的路径 ——
    /// 对 `/v1/chat/completions` 这类纯 ASCII 路径恰好相同，
    /// 但路径含空格/非 ASCII/`%2F` 时会与服务端签名不一致（表现为 401 且极难定位）。
    /// 因此这里用 `percentEncodedPath`。
    /// 查询串同理：`url.query` 是解码后的，`percentEncodedQuery` 才是编码后的。
    static func path(from url: URL) -> String {
        let encodedPath = url.percentEncodedPath
        var path = encodedPath.isEmpty ? "/" : encodedPath
        if let query = url.percentEncodedQuery, !query.isEmpty {
            path += "?" + query
        }
        return path
    }
}
