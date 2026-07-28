package com.lianyu.ai.wechat

import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatMediaRef
import com.lianyu.ai.domain.wechat.WeChatOutboundRequest
import com.lianyu.ai.wechat.ilink.IlinkAccount
import com.lianyu.ai.wechat.ilink.IlinkSessionStore
import com.lianyu.ai.wechat.outbox.WeChatOutboxCoordinator
import com.lianyu.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WeChatOutboxCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val coordinator = WeChatOutboxCoordinator(
        dao = FakeOutboxDao(),
        transport = object : WeChatTransportPort {
            override suspend fun sendText(toUserId: String, text: String, contextToken: String?) =
                Result.success(Unit)

            override suspend fun sendImage(
                toUserId: String,
                imageBytes: ByteArray,
                fileName: String,
                description: String?,
                contextToken: String?,
            ) = Result.success(Unit)
        },
    )

    @Test
    fun buildEntities_simpleTextSegments() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 9L, text = "你好。世界！"),
            wechatUserId = "u1",
            rootId = "r1",
            createdAtMs = 1000L,
        )
        assertEquals(2, entities.size)
        assertEquals("r1#0", entities[0].id)
        assertEquals("r1#1", entities[1].id)
        assertEquals(WeChatContentKind.TEXT.wireType, entities[0].kind)
        assertEquals(0, entities[0].segmentIndex)
        assertEquals(2, entities[0].segmentCount)
        assertEquals(9L, entities[0].companionId)
        assertEquals("PENDING", entities[0].status)
    }

    @Test
    fun buildEntities_imageSingle() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(
                companionId = 1L,
                media = WeChatMediaRef(kind = WeChatContentKind.IMAGE, localPath = "/a.png"),
            ),
            wechatUserId = "u2",
            rootId = "img",
            createdAtMs = 1L,
        )
        assertEquals(1, entities.size)
        assertEquals(WeChatContentKind.IMAGE.wireType, entities.single().kind)
        assertEquals("/a.png", entities.single().mediaLocalPath)
    }

    @Test
    fun buildEntities_blankEmpty() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "  "),
            wechatUserId = "u3",
            rootId = "e",
            createdAtMs = 1L,
        )
        assertTrue(entities.isEmpty())
    }

    @Test
    fun drain_resolvesLatestContextTokenFromSessionStore() = runBlocking {
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello", contextToken = "stale-token"),
            wechatUserId = "user-1",
            rootId = "secure",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        var sentToken: String? = null
        val secureCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sentToken = contextToken
                    return Result.success(Unit)
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            sessionStore = FakeSessionStore("latest-token"),
            segmentGapMs = 0L,
        )

        assertEquals(1, secureCoordinator.drain())
        assertEquals("latest-token", sentToken)
        assertEquals("SENT", dao.lastStatus)
    }

    @Test
    fun drain_sendsEveryTextSegmentInOrder() = runBlocking {
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "第一句。第二句！第三句？"),
            wechatUserId = "user-1",
            rootId = "multi",
            createdAtMs = 1L,
        )
        val sentTexts = mutableListOf<String>()
        val multiSegmentCoordinator = WeChatOutboxCoordinator(
            dao = FakeOutboxDao(ready),
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sentTexts += text
                    return Result.success(Unit)
                }

                override suspend fun sendTextSegments(
                    toUserId: String,
                    segments: List<String>,
                    contextToken: String?,
                ): Result<Unit> {
                    error("batch transport must not be used")
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            segmentGapMs = 0L,
        )

        assertEquals(3, multiSegmentCoordinator.drain(limit = 1))
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), sentTexts)
    }

    @Test
    fun cleanupManagedCache_deletesOnlyExpiredUnreferencedFiles() {
        val cacheDir = temporaryFolder.newFolder("stickers")
        val referenced = File(cacheDir, "referenced.png").apply { writeText("keep") }
        val expired = File(cacheDir, "expired.png").apply { writeText("delete") }
        val recent = File(cacheDir, "recent.png").apply { writeText("keep") }
        referenced.setLastModified(100L)
        expired.setLastModified(100L)
        recent.setLastModified(2_000L)
        val cacheCoordinator = WeChatOutboxCoordinator(
            dao = FakeOutboxDao(),
            transport = SuccessfulTransport,
            managedMediaCacheDir = cacheDir,
        )

        val deleted = cacheCoordinator.cleanupManagedCache(
            referencedPaths = setOf(referenced.absolutePath),
            cutoffMs = 1_000L,
        )

        assertEquals(1, deleted)
        assertTrue(referenced.exists())
        assertTrue(recent.exists())
        assertTrue(!expired.exists())
    }
}

private object SuccessfulTransport : WeChatTransportPort {
    override suspend fun sendText(toUserId: String, text: String, contextToken: String?) =
        Result.success(Unit)

    override suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String?,
        contextToken: String?,
    ) = Result.success(Unit)
}

/** 仅满足构造；本测不调用 DAO。 */
private class FakeOutboxDao(
    private val ready: List<com.lianyu.ai.database.model.WeChatOutboxEntity> = emptyList(),
) : com.lianyu.ai.database.dao.WeChatOutboxDao {
    var lastStatus: String? = null

    override suspend fun insertAll(items: List<com.lianyu.ai.database.model.WeChatOutboxEntity>) = Unit
    override suspend fun insert(item: com.lianyu.ai.database.model.WeChatOutboxEntity) = Unit
    override suspend fun listReady(nowMs: Long, limit: Int) = ready.take(limit)
    override suspend fun listOpenByRootId(rootId: String) = ready.filter { it.rootId == rootId }
    override suspend fun updateAttempt(
        id: String,
        status: String,
        retryCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
        updatedAtMs: Long,
    ) = Unit
    override suspend fun updateStatus(id: String, status: String, lastError: String?, updatedAtMs: Long) {
        lastStatus = status
    }
    override suspend fun countOpen(): Int = 0
    override suspend fun countByStatus(status: String): Int = 0
    override suspend fun listRecentFailed(limit: Int) =
        emptyList<com.lianyu.ai.database.model.WeChatOutboxEntity>()
    override suspend fun deleteSentBefore(cutoffMs: Long): Int = 0
    override suspend fun deleteDeadBefore(maxRetry: Int, cutoffMs: Long): Int = 0
    override suspend fun listMediaLocalPaths(): List<String> = emptyList()
}

private class FakeSessionStore(private val contextToken: String) : IlinkSessionStore {
    override suspend fun getSessionAccount() = IlinkAccount("bot", "bot-id", "user-id", accountId = "account-1")
    override suspend fun saveSessionAccount(account: IlinkAccount) = Unit
    override suspend fun clearSessionAccount() = Unit
    override suspend fun getCursor() = ""
    override suspend fun saveCursor(cursor: String) = Unit
    override suspend fun getContextToken(accountId: String, userId: String) = contextToken
    override suspend fun saveContextToken(accountId: String, userId: String, token: String) = Unit
    override suspend fun getContextTokens(accountId: String) = emptyMap<String, String>()
}
