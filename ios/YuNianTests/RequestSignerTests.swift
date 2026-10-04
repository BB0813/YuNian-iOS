import XCTest
@testable import YuNian

/// `RequestSigner` 的纯逻辑部分测试（不涉及真实签名）。
///
/// 签名 payload 的任何一处不同都会让服务端拒签（fail-closed），
/// 因此这里把**能离线验证的部分**尽可能钉死：path 的编码形态、clientId 的推导。
/// 实际签名（ECDSA + SPKI keyId）只能在真机/macOS 上端到端验证。
final class RequestSignerTests: XCTestCase {

    // MARK: - path 的编码形态

    /// 纯 ASCII 路径：编码前后相同。
    func testPlainPathUnchanged() {
        XCTAssertEqual(RequestSigner.path(from: URL(string: "https://a.com/v1/chat/completions")!),
                       "/v1/chat/completions")
    }

    /// ⚠️ 关键：必须用**百分号编码后**的路径（对应 OkHttp 的 `encodedPath`）。
    /// 若用 `url.path`（解码后），这类路径就与服务端不一致。
    func testPathKeepsPercentEncoding() {
        let url = URL(string: "https://a.com/v1/a%20b/c%2Fd")!
        XCTAssertEqual(RequestSigner.path(from: url), "/v1/a%20b/c%2Fd",
                       "必须保留 %20 / %2F，不能解码")
    }

    func testQueryIsAppendedWithPercentEncoding() {
        let url = URL(string: "https://a.com/v1/x?q=a%20b&n=1")!
        XCTAssertEqual(RequestSigner.path(from: url), "/v1/x?q=a%20b&n=1")
    }

    func testEmptyPathBecomesRoot() {
        XCTAssertEqual(RequestSigner.path(from: URL(string: "https://a.com")!), "/")
    }

    func testQueryWithoutEquals() {
        XCTAssertEqual(RequestSigner.path(from: URL(string: "https://a.com/p?flag")!), "/p?flag")
    }

    // MARK: - clientId 推导

    func testExplicitHeaderWins() {
        XCTAssertEqual(
            RequestSigner.clientId(clientIdHeader: "explicit", authorizationHeader: "Bearer a:b"),
            "explicit"
        )
    }

    func testBlankExplicitHeaderIsIgnored() {
        XCTAssertEqual(RequestSigner.clientId(clientIdHeader: "   ", authorizationHeader: nil), "")
    }

    func testBearerTokenTakesPartBeforeColon() {
        XCTAssertEqual(RequestSigner.clientId(clientIdHeader: nil, authorizationHeader: "Bearer abc:def"),
                       "abc")
    }

    func testBearerTokenWithoutColonIsWholeToken() {
        XCTAssertEqual(RequestSigner.clientId(clientIdHeader: nil, authorizationHeader: "Bearer abc"),
                       "abc")
    }

    func testNonBearerIsEmpty() {
        XCTAssertEqual(RequestSigner.clientId(clientIdHeader: nil, authorizationHeader: "Basic abc"), "")
        XCTAssertEqual(RequestSigner.clientId(clientIdHeader: nil, authorizationHeader: nil), "")
    }

    /// ⚠️ **大小写陷阱**：前缀判断忽略大小写，但剥离 `removePrefix("Bearer ")` 区分大小写。
    /// 于是 `bearer abc` 时 Kotlin 得到的是整串 `bearer abc`，而不是 `abc`。
    /// 跨端一致性优先于「看起来更对」，故如实复刻。
    func testLowercaseBearerPrefixIsNotStripped() {
        XCTAssertEqual(
            RequestSigner.clientId(clientIdHeader: nil, authorizationHeader: "bearer abc:def"),
            "bearer abc",
            "小写前缀判断通过但剥离失败，整串成为 clientId（与 Kotlin 一致）"
        )
    }

    // MARK: - nonce

    func testNonceIs24LowercaseHex() {
        for _ in 0..<32 {
            let nonce = RequestSigner.randomNonce()
            XCTAssertEqual(nonce.count, 24)
            XCTAssertTrue(nonce.allSatisfy { $0.isHexDigit && !$0.isUppercase })
            XCTAssertNotNil(nonce.range(of: "^[0-9a-f]{24}$", options: .regularExpression))
        }
    }

    /// 空 body 的哈希必须是 SHA-256 的空输入值（e3b0c442...）。
    func testEmptyBodyHashConstant() {
        XCTAssertEqual(
            RequestSigner.emptyBodySHA256,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
    }

    /// 常量与 Android 的 `appId` 一致（`X-LianYu-Client` 头）。
    func testClientIdentifierMatchesAndroid() {
        XCTAssertEqual(RequestSigner.clientIdentifier, "lianyu-1.5.1")
        XCTAssertEqual(RequestSigner.signatureVersion, "v1")
        XCTAssertEqual(RequestSigner.handshakeSegment, "handshake")
    }
}
