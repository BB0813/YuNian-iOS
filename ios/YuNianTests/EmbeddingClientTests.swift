import XCTest

/// `EmbeddingClient` 的纯逻辑规格（端点 / 模型表 / 响应解析）。
///
/// 权威来源：`core/network/.../EmbeddingService.kt`。
/// 网络那一半（真的打一次 `/embeddings`）只能在真机 + 一条真实向量渠道上验。
@testable import YuNian
final class EmbeddingClientTests: XCTestCase {

    // MARK: - 端点

    func testEndpointAppendsPath() {
        XCTAssertEqual(
            EmbeddingClient.endpoint(baseUrl: "https://api.openai.com/v1", provider: "OPENAI")?.absoluteString,
            "https://api.openai.com/v1/embeddings"
        )
    }

    /// 尾斜杠要吃掉（预设里的 baseUrl 大多带 `/`）。
    func testEndpointTrimsTrailingSlash() {
        XCTAssertEqual(
            EmbeddingClient.endpoint(baseUrl: "https://api.siliconflow.cn/v1/", provider: "SILICONFLOW")?.absoluteString,
            "https://api.siliconflow.cn/v1/embeddings"
        )
    }

    /// Gemini 的 OpenAI 兼容入口多一层 `/openai`（EmbeddingService.kt:127）。
    func testEndpointGeminiUsesOpenAIPrefix() {
        XCTAssertEqual(
            EmbeddingClient.endpoint(
                baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
                provider: "GEMINI"
            )?.absoluteString,
            "https://generativelanguage.googleapis.com/v1beta/openai/openai/embeddings"
        )
    }

    func testEndpointEmptyBaseUrlIsNil() {
        XCTAssertNil(EmbeddingClient.endpoint(baseUrl: "", provider: "OPENAI"))
        XCTAssertNil(EmbeddingClient.endpoint(baseUrl: "   ", provider: "OPENAI"))
    }

    // MARK: - provider 白名单与推荐模型

    func testCapabilityWhitelistMatchesAndroid() {
        for provider in ["OPENAI", "OPENROUTER", "SILICONFLOW", "DASHSCOPE",
                         "ZHIPU", "GEMINI", "CUSTOM"] {
            XCTAssertTrue(EmbeddingClient.isSupported(provider: provider), provider)
        }
        for provider in ["ANTHROPIC", "KIMI", "DEEPSEEK", "XIAOMI", "GROQ", "PARTNER"] {
            XCTAssertFalse(EmbeddingClient.isSupported(provider: provider), provider)
        }
    }

    func testRecommendedModelsMatchAndroid() {
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "OPENAI"), "text-embedding-3-small")
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "OPENROUTER"), "openai/text-embedding-3-small")
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "SILICONFLOW"), "BAAI/bge-m3")
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "DASHSCOPE"), "text-embedding-v3")
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "ZHIPU"), "embedding-3")
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "GEMINI"), "text-embedding-004")
        // 没有推荐表的 provider 回落默认模型
        XCTAssertEqual(EmbeddingClient.recommendedModel(provider: "CUSTOM"),
                       EmbeddingClient.defaultModel)
    }

    // MARK: - 响应解析

    func testParseEmbedding() throws {
        let body = Data(#"{"data":[{"embedding":[0.5,-1.25,3]}]}"#.utf8)
        // ⚠️ 用 `XCTUnwrap` 而不是 `floats?[0] ?? 0`：后者把"解析返回 nil"
        // 变成"拿到 0"，断言还能勉强成立一半（0 != 0.5 会失败，但
        // `?? 0` 与 accuracy 重载的组合正是 reviewer 提醒过的
        // "本仓没有先例、编译器行为要靠推断"的写法）。unwrap 一步到底。
        let floats = try XCTUnwrap(EmbeddingClient.parseEmbedding(body))
        XCTAssertEqual(floats.count, 3)
        XCTAssertEqual(floats[0], 0.5, accuracy: 0.0001)
        XCTAssertEqual(floats[1], -1.25, accuracy: 0.0001)
        XCTAssertEqual(floats[2], 3, accuracy: 0.0001)
    }

    /// 字符串形式的数字也接受（部分网关这么返回）。
    func testParseEmbeddingAcceptsNumericStrings() throws {
        let body = Data(#"{"data":[{"embedding":["1.5","2.5"]}]}"#.utf8)
        let floats = try XCTUnwrap(EmbeddingClient.parseEmbedding(body))
        XCTAssertEqual(floats.count, 2)
        XCTAssertEqual(floats[0], 1.5, accuracy: 0.0001)
    }

    /// 缺字段 / 空数组 / 非数字元素一律 nil —— 宁可退回关键词召回，
    /// 也不要把一个"长度不对的向量"交给余弦相似度（那会得到看似有效的乱序分数）。
    func testParseEmbeddingRejectsMalformedBodies() {
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data(#"{"data":[]}"#.utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data(#"{"data":[{"embedding":[]}]}"#.utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data(#"{"data":[{"embedding":[1,"x"]}]}"#.utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data(#"{"data":[{}]}"#.utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data(#"{"error":"nope"}"#.utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data("not json".utf8)))
        XCTAssertNil(EmbeddingClient.parseEmbedding(Data()))
    }

    // MARK: - 空文本不发起请求

    func testEmbedSyncRejectsEmptyText() {
        let request = EmbeddingClient.Request(
            baseUrl: "https://api.openai.com/v1",
            apiKey: "sk-test",
            model: "text-embedding-3-small",
            provider: "OPENAI",
            text: "   "
        )
        XCTAssertThrowsError(try EmbeddingClient().embedSync(request)) { error in
            XCTAssertEqual(error as? EmbeddingClient.EmbeddingError, EmbeddingClient.EmbeddingError.emptyText)
        }
    }

    func testEmbedSyncRejectsMissingKey() {
        let request = EmbeddingClient.Request(
            baseUrl: "https://api.openai.com/v1",
            apiKey: "  ",
            model: "text-embedding-3-small",
            provider: "OPENAI",
            text: "你好"
        )
        XCTAssertThrowsError(try EmbeddingClient().embedSync(request)) { error in
            XCTAssertEqual(error as? EmbeddingClient.EmbeddingError, EmbeddingClient.EmbeddingError.missingKey)
        }
    }

    func testEmbedSyncRejectsEmptyBaseUrl() {
        let request = EmbeddingClient.Request(
            baseUrl: "",
            apiKey: "sk-test",
            model: "text-embedding-3-small",
            provider: "OPENAI",
            text: "你好"
        )
        XCTAssertThrowsError(try EmbeddingClient().embedSync(request)) { error in
            XCTAssertEqual(error as? EmbeddingClient.EmbeddingError, EmbeddingClient.EmbeddingError.badBaseUrl)
        }
    }

    // MARK: - 查询缓存

    func testQueryCacheStoresAndEvicts() throws {
        let cache = EmbeddingQueryCache(capacity: 2)
        cache.insert([1, 2], for: "a")
        cache.insert([3], for: "b")
        // 用 `XCTUnwrap` 而不是直接比 `[Float]?` 与字面量：把"Optional 上下文里
        // 的数组字面量推断"这件事从断言里去掉，同时 nil 会明确失败而不是被吞掉。
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "a")), [1, 2])

        cache.insert([4], for: "c")   // 超出容量 → 丢最旧的 a
        XCTAssertNil(cache.value(for: "a"))
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "b")), [3])
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "c")), [4])
    }

    /// 同一个键重复插入只更新值，不把容量撑爆。
    func testQueryCacheUpdateDoesNotGrow() throws {
        let cache = EmbeddingQueryCache(capacity: 2)
        cache.insert([1], for: "a")
        cache.insert([2], for: "a")
        cache.insert([3], for: "b")
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "a")), [2])
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "b")), [3])
    }

    func testQueryCacheCapacityFloor() throws {
        // 容量至少为 1（传 0 不该让缓存变成"永远空"）
        let cache = EmbeddingQueryCache(capacity: 0)
        cache.insert([9], for: "x")
        XCTAssertEqual(try XCTUnwrap(cache.value(for: "x")), [9])
    }

    // MARK: - 失败退避（`embedText` 是同步阻塞的，这条挡的是"每回合都卡超时"）

    func testBackoffSkipsDuringCooldown() {
        let backoff = EmbeddingFailureBackoff()
        XCTAssertFalse(backoff.shouldSkip(now: 1_000), "没失败过就不该跳过")

        backoff.recordFailure(now: 1_000)
        XCTAssertTrue(backoff.shouldSkip(now: 1_000))
        XCTAssertTrue(backoff.shouldSkip(now: 1_000 + EmbeddingFailureBackoff.cooldown - 1))

        // 静默期一过就允许再试一次
        XCTAssertFalse(backoff.shouldSkip(now: 1_000 + EmbeddingFailureBackoff.cooldown))
    }

    func testBackoffSuccessClearsFailure() {
        let backoff = EmbeddingFailureBackoff()
        backoff.recordFailure(now: 1_000)
        XCTAssertTrue(backoff.shouldSkip(now: 1_000))

        backoff.recordSuccess()
        XCTAssertFalse(backoff.shouldSkip(now: 1_000))
    }
}
