package com.lianyu.ai.feature.wechat.data

import android.content.Context
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatContentPart
import com.lianyu.ai.domain.wechat.WeChatDialoguePort
import com.lianyu.ai.domain.wechat.WeChatDialogueRequest
import com.lianyu.ai.domain.wechat.WeChatDialogueResult
import com.lianyu.ai.domain.wechat.WeChatInboundMessage
import com.lianyu.ai.domain.wechat.WeChatMediaRef
import com.lianyu.ai.feature.wechat.WeChatDebugLog
import com.lianyu.ai.feature.wechat.data.model.M0
import com.lianyu.ai.feature.wechat.data.model.M1
import com.lianyu.ai.feature.wechat.data.model.M1Type
import com.lianyu.ai.feature.wechat.data.model.M2
import com.lianyu.ai.feature.wechat.service.WeChatServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

internal fun normalizeOutboundText(text: String): String = text.trim()
    .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
    .replace(Regex(" *\\n *"), "\n")
    .replace(Regex("\\n{3,}"), "\n\n")

internal fun extractInboundText(message: M0): String? = message.itemList.orEmpty()
    .mapNotNull { item -> item.textItem?.text?.takeIf { it.isNotBlank() } }
    .joinToString("\n")
    .takeIf { it.isNotBlank() }

/**
 * S3/S7：微信通道编排（映射 / CDN / Outbox / 表情物化入队）。
 *
 * AI 生成、安全过滤、落库、记忆提取已迁至 [WeChatDialoguePort]（app 绑定）。
 * 表情经 [WeChatStickerMaterializer] 落盘后入 Outbox，不再字节直发。
 */
class WeChatChatBridge(
    private val context: Context,
    private val weChatRepository: WeChatMessageRepository
) {
    private val database = AppDatabase.getDatabase(context)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val companionRepository = CompanionRepository(database.companionDao())
    private val tokenStore = WeChatTokenStore(context)
    private val mappingManager = WeChatUserMappingManager(tokenStore, companionRepository)
    private val dialoguePort: WeChatDialoguePort by lazy {
        ServiceRegistry.get(WeChatDialoguePort::class.java)
            ?: throw IllegalStateException("WeChatDialoguePort not registered in ServiceRegistry")
    }
    private val bridgeJob = SupervisorJob()

    suspend fun handleIncomingMessage(message: M0): String? = withContext(Dispatchers.IO) {
        val wechatUserId = message.fromUserId ?: return@withContext null
        val text = extractInboundText(message) ?: return@withContext null
        if (text.isBlank()) return@withContext null

        val companionId = mappingManager.getOrCreateMapping(wechatUserId)
            ?: throw IllegalStateException("Failed to resolve companion mapping for $wechatUserId")

        val inbound = M0WireAdapter.toInbound(message)
            ?: WeChatInboundMessage(
                dedupeKey = "bridge-text-$wechatUserId-${System.currentTimeMillis()}",
                fromUserId = wechatUserId,
                parts = listOf(
                    WeChatContentPart(
                        kind = WeChatContentKind.TEXT,
                        text = text,
                    )
                ),
            )

        val result = dialoguePort.generateReply(
            WeChatDialogueRequest(
                companionId = companionId,
                wechatUserId = wechatUserId,
                inbound = inbound,
            )
        )
        WeChatDebugLog.log("[Bridge] generateReply done companion=$companionId blocked=${result.blocked} reply_len=${result.replyText.length}")

        deliverDialogueResult(
            companionId = companionId,
            wechatUserId = wechatUserId,
            result = result,
            forceDeliver = result.blocked,
        )
    }

    suspend fun handleTextMessage(wechatUserId: String, text: String): String? = withContext(Dispatchers.IO) {
        val message = M0(
            fromUserId = wechatUserId,
            toUserId = "",
            itemList = listOf(
                M1(type = 1, textItem = M2(text = text))
            )
        )
        handleIncomingMessage(message)
    }

    suspend fun handleImageMessage(wechatUserId: String, message: M0): String? = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("WeChatBridge", "handleImageMessage called for $wechatUserId")

            val companionId = mappingManager.getOrCreateMapping(wechatUserId)
                ?: run {
                    android.util.Log.e("WeChatBridge", "Failed to get/create mapping for $wechatUserId")
                    return@withContext null
                }

            val imageItem = message.itemList?.firstOrNull { it.type == M1Type.IMAGE.value }?.imageItem
            if (imageItem == null) {
                android.util.Log.w("WeChatBridge", "No image item found in message")
                return@withContext null
            }

            android.util.Log.d("WeChatBridge", "Image item found, cdnInfo present=${imageItem.cdnImg != null}")

            val imagePath = downloadImageFromCdn(message, imageItem)
            if (imagePath == null) {
                android.util.Log.e("WeChatBridge", "Failed to download image, sending fallback response")
                val fallbackResponse = "收到您的图片了！不过暂时无法识别图片内容，可能是因为SDK版本限制。您可以描述一下图片内容，我会尽力帮助您~"
                enqueueAndDrainText(companionId, wechatUserId, fallbackResponse)
                return@withContext fallbackResponse
            }

            android.util.Log.d("WeChatBridge", "Image downloaded successfully: $imagePath")

            val baseInbound = M0WireAdapter.toInbound(message)
            val inbound = if (baseInbound != null) {
                baseInbound.copy(
                    parts = baseInbound.parts.map { part ->
                        if (part.kind == WeChatContentKind.IMAGE) {
                            part.copy(
                                media = (part.media ?: WeChatMediaRef(kind = WeChatContentKind.IMAGE))
                                    .copy(localPath = imagePath),
                            )
                        } else {
                            part
                        }
                    }.ifEmpty {
                        listOf(
                            WeChatContentPart(
                                kind = WeChatContentKind.IMAGE,
                                media = WeChatMediaRef(
                                    kind = WeChatContentKind.IMAGE,
                                    localPath = imagePath,
                                ),
                            )
                        )
                    },
                )
            } else {
                WeChatInboundMessage(
                    dedupeKey = "bridge-image-$wechatUserId-${System.currentTimeMillis()}",
                    fromUserId = wechatUserId,
                    parts = listOf(
                        WeChatContentPart(
                            kind = WeChatContentKind.IMAGE,
                            media = WeChatMediaRef(
                                kind = WeChatContentKind.IMAGE,
                                localPath = imagePath,
                            ),
                        )
                    ),
                )
            }

            val result = dialoguePort.generateReply(
                WeChatDialogueRequest(
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    inbound = inbound,
                )
            )

            deliverDialogueResult(
                companionId = companionId,
                wechatUserId = wechatUserId,
                result = result,
                forceDeliver = true,
            )
        } catch (e: Exception) {
            android.util.Log.e("WeChatBridge", "Error in handleImageMessage", e)
            val errorResponse = "图片识别过程中出现错误: ${e.message}. 请稍后重试或发送文字描述。"
            runCatching {
                val companionId = mappingManager.getOrCreateMapping(wechatUserId)
                if (companionId != null) {
                    enqueueAndDrainText(companionId, wechatUserId, errorResponse)
                }
            }
            errorResponse
        }
    }

    /**
     * 将 DialoguePort 结果投递到微信（Outbox + 表情）。
     * 文本路径尊重 forwardEnabled；blocked / 图片路径可 forceDeliver。
     */
    private suspend fun deliverDialogueResult(
        companionId: Long,
        wechatUserId: String,
        result: WeChatDialogueResult,
        forceDeliver: Boolean,
    ): String? {
        val aiResponseText = result.replyText
        val aiMessageId = result.assistantMessageId ?: 0L

        if (aiMessageId > 0 && aiResponseText.isNotBlank()) {
            val processed = runCatching { extractStickerTags(aiResponseText) }
                .getOrDefault(Pair(aiResponseText, emptyList<StickerInfo>()))
            if (processed.first.isNotEmpty() && processed.first != aiResponseText) {
                val messageIds = result.assistantMessageIds.ifEmpty { listOf(aiMessageId) }
                // 气泡架构（用户定稿）：AI 回复整条一条气泡，回写整条内容（不按 SIMPLE 分段）
                val segments = if (messageIds.size <= 1) {
                    listOf(processed.first)
                } else {
                    messageIds.indices.map { index ->
                        val text = processed.first
                        if (index == messageIds.lastIndex) text else ""
                    }
                }
                messageIds.zip(segments).forEach { (messageId, segment) ->
                    if (segment.isNotBlank()) {
                        chatRepository.updateMessageContent(messageId, segment)
                    }
                }
            }
        }

        // 文本路径：输入/输出拦截仅落库，不强制推微信（与 S3 前行为一致）
        // 图片路径 forceDeliver=true：拦截/成功均推微信
        if (result.blocked && !forceDeliver) {
            return aiResponseText.ifBlank { null }
        }

        val forwardEnabled = tokenStore.getForwardEnabled()
        val shouldForward = forceDeliver || forwardEnabled
        WeChatDebugLog.log("[Bridge] deliverDialogueResult forwardEnabled=$forwardEnabled forceDeliver=$forceDeliver shouldForward=$shouldForward text_len=${aiResponseText.length}")
        if (!shouldForward || aiResponseText.isBlank()) {
            WeChatDebugLog.log("[Bridge] deliverDialogueResult SKIPPED (forward=$shouldForward blank=${aiResponseText.isBlank()})")
            return aiResponseText.ifBlank { null }
        }

        val (cleanText, stickers) = runCatching { extractStickerTags(aiResponseText) }
            .getOrDefault(Pair(aiResponseText, emptyList<StickerInfo>()))

        android.util.Log.d(
            "WeChatBridge",
            "Forward: cleanText length=${cleanText.length}, stickers count=${stickers.size}, blocked=${result.blocked}",
        )

        val isTextMeaningful = cleanText.isNotBlank() &&
            cleanText.length > 1 &&
            !cleanText.all { it.isWhitespace() } &&
            cleanText != "\u200B"

        if (stickers.isNotEmpty() && !isTextMeaningful) {
            android.util.Log.d("WeChatBridge", "Only stickers, no meaningful text to send")
        } else if (isTextMeaningful) {
            val finalText = normalizeOutboundText(cleanText)
                .replace(Regex("^[\\[\\]\\s，。！？、]+"), "")
                .replace(Regex("[\\[\\]\\s，。！？、]+$"), "")
                .trim()
            if (finalText.length >= 1) {
                val contextToken = weChatRepository.getContextToken(wechatUserId)
                val rootId = weChatRepository.enqueueTextOutbound(
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    text = finalText,
                    contextToken = contextToken,
                    sourceMessageId = aiMessageId.takeIf { it > 0 },
                )
                val sent = weChatRepository.drainOutbox()
                WeChatDebugLog.log("[Bridge] Text delivered rootId=$rootId drainSent=$sent")
                android.util.Log.d(
                    "WeChatBridge",
                    "Text enqueued rootId=$rootId drainSent=$sent",
                )
            }
        }

        if (stickers.isNotEmpty()) {
            val contextToken = weChatRepository.getContextToken(wechatUserId)
            var stickerEnqueued = false
            stickers.forEachIndexed { index, sticker ->
                runCatching {
                    android.util.Log.d(
                        "WeChatBridge",
                        "Enqueue sticker[$index]: name=${sticker.name}, path=${sticker.path}",
                    )
                    val rootId = weChatRepository.enqueueStickerOutbound(
                        companionId = companionId,
                        wechatUserId = wechatUserId,
                        sticker = sticker,
                        contextToken = contextToken,
                        sourceMessageId = aiMessageId.takeIf { it > 0 },
                    )
                    if (rootId != null) {
                        stickerEnqueued = true
                        android.util.Log.d(
                            "WeChatBridge",
                            "Sticker[$index] enqueued rootId=$rootId",
                        )
                    } else {
                        android.util.Log.w(
                            "WeChatBridge",
                            "Sticker[$index] materialize/enqueue failed: ${sticker.name}",
                        )
                    }
                }.onFailure { e ->
                    android.util.Log.e("WeChatBridge", "Error enqueue sticker[$index]", e)
                }
            }
            if (stickerEnqueued) {
                val sent = weChatRepository.drainOutbox()
                android.util.Log.d("WeChatBridge", "Sticker drainSent=$sent")
            }
        }

        return aiResponseText.ifBlank { null }
    }

    /** S1：文本入 Outbox 并立即 drain 一段。 */
    private suspend fun enqueueAndDrainText(
        companionId: Long,
        wechatUserId: String,
        text: String,
        sourceMessageId: Long? = null,
    ): Int {
        if (text.isBlank()) return 0
        val contextToken = weChatRepository.getContextToken(wechatUserId)
        weChatRepository.enqueueTextOutbound(
            companionId = companionId,
            wechatUserId = wechatUserId,
            text = text,
            contextToken = contextToken,
            sourceMessageId = sourceMessageId,
        )
        return weChatRepository.drainOutbox()
    }

    private suspend fun downloadImageFromCdn(message: M0, imageItem: com.lianyu.ai.feature.wechat.data.model.M3): String? {
        return try {
            val sdkClient = WeChatServiceLocator.sdkClientManager(context)
            val tempFile = File(context.cacheDir, "wechat_img_${System.currentTimeMillis()}.jpg")

            when {
                imageItem.cdnImg != null -> {
                    android.util.Log.d("WeChatBridge", "Attempting to download image via SDK CDN")
                    runCatching {
                        val downloadedBytes = sdkClient.downloadMedia(
                            com.lianyu.ai.wechat.ilink.IlinkCdnMedia(
                                encryptQueryParam = imageItem.cdnImg.encryptQueryParam,
                                aesKey = imageItem.cdnImg.aesKey,
                            ),
                        )
                        if (downloadedBytes != null && downloadedBytes.isNotEmpty()) {
                            tempFile.writeBytes(downloadedBytes)
                            tempFile.absolutePath
                        } else {
                            android.util.Log.w("WeChatBridge", "SDK returned empty bytes for image")
                            null
                        }
                    }.getOrNull()
                }
                else -> {
                    android.util.Log.w("WeChatBridge", "No CDN info available for image")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("WeChatBridge", "Failed to download image from CDN", e)
            null
        }
    }

    fun close() {
        bridgeJob.cancel()
    }

    private fun extractStickerTags(text: String): Pair<String, List<StickerInfo>> {
        val stickerManager = StickerManager.getInstance(context)
        val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
        val stickerRegex = Regex("\\[([^\\[\\]]+?)\\]")
        val fileNamePattern = Regex("^[a-zA-Z0-9_\\-]+\\.(png|jpg|jpeg|gif|webp)$", RegexOption.IGNORE_CASE)
        val matches = stickerRegex.findAll(text).toList()

        val stickers = mutableListOf<StickerInfo>()
        val sentStickerDescs = mutableSetOf<String>()
        var cleanText = text

        val rolePrefixRegex = Regex("(?m)^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*")
        cleanText = rolePrefixRegex.replace(cleanText, "")

        val thinkRegex = Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>")
        cleanText = thinkRegex.replace(cleanText, "")

        val encRegex = Regex("(?m)^enc:\\S+$")
        cleanText = encRegex.replace(cleanText, "")

        for (match in matches) {
            val description = match.groupValues[1].trim()
            if (description in systemTags) continue

            var found = false
            val sticker = stickerManager.findStickerByDescriptionExact(description)
                ?: stickerManager.findStickerByDescription(description)
            if (sticker != null) {
                if (stickers.none { it.name == sticker.name }) {
                    stickers.add(sticker)
                    found = true
                } else {
                    android.util.Log.d("WeChatBridge", "Duplicate sticker skipped: ${sticker.name}")
                }
            }
            sentStickerDescs.add(description)
            sticker?.description?.let { sentStickerDescs.add(it) }
            cleanText = cleanText.replace(match.value, "")
            if (!found && fileNamePattern.matches(description)) {
                android.util.Log.d("WeChatBridge", "Removed unmatched sticker file tag: [$description]")
            }
            if (!found && !fileNamePattern.matches(description)) {
                android.util.Log.w("WeChatBridge", "Removed unmatched sticker tag: [$description]")
            }
        }

        for (desc in sentStickerDescs) {
            if (desc.length >= 2 && cleanText.contains(desc)) {
                cleanText = cleanText.replace(desc, "")
                android.util.Log.d("WeChatBridge", "Removed residual sticker desc from text: $desc")
            }
        }

        cleanText = cleanText.replace("]", "").replace("[", "")
        cleanText = Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE).replace(cleanText, "")

        for (sticker in stickers) {
            val desc = sticker.description
            if (!desc.isNullOrBlank() && desc.length >= 2 && cleanText.contains(desc)) {
                cleanText = cleanText.replace(desc, "")
                android.util.Log.d("WeChatBridge", "Removed sticker desc from text (extra): $desc")
            }
            val name = sticker.name
            if (name.length >= 2 && cleanText.contains(name)) {
                cleanText = cleanText.replace(name, "")
                android.util.Log.d("WeChatBridge", "Removed sticker name from text (extra): $name")
            }
        }

        var result = cleanText.trim()
            .replace(Regex("\\r\\n|\\r|\\n+"), "，")
            .replace(Regex("，{2,}"), "，")
            .trim()
            .trimStart('，', ',', '.', '。', ' ')

        if (stickers.isEmpty()) {
            val allRules = stickerManager.getAllRules()
            if (allRules.isNotEmpty()) {
                val matchedStickers = mutableListOf<Pair<StickerInfo, String>>()
                for (rule in allRules.shuffled()) {
                    val desc = rule.description
                    if (desc.length >= 2 && text.contains(desc)) {
                        val sticker = stickerManager.findStickerByDescription(desc)
                        if (sticker != null) matchedStickers.add(sticker to desc)
                    }
                }
                if (matchedStickers.isNotEmpty()) {
                    val (picked, matchedDesc) = matchedStickers.random()
                    if (stickers.none { it.name == picked.name }) {
                        stickers.add(picked)
                        android.util.Log.d("WeChatBridge", "Matched sticker from text: ${picked.name}")
                    } else {
                        android.util.Log.d("WeChatBridge", "Duplicate sticker skipped: ${picked.name}")
                    }
                    val descToRemove = if (result.contains(matchedDesc)) {
                        matchedDesc
                    } else {
                        (picked.description ?: picked.name)
                    }
                    if (descToRemove.length >= 2) {
                        result = result.replace(descToRemove, "")
                        android.util.Log.d(
                            "WeChatBridge",
                            "Removed sticker desc from text: $descToRemove (matched: $matchedDesc)",
                        )
                    }
                    result = removeLocalRepetition(result)
                }
            }
        }

        result = removeLocalRepetition(result)
        return result to stickers
    }

    private fun removeLocalRepetition(text: String): String {
        if (text.length < 4) return text
        var result = text

        for (len in result.length / 2 downTo 2) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.endsWith(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        for (len in result.length / 2 downTo 4) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.contains(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
            val suffixCleaned = suffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            val beforeSuffixCleaned = beforeSuffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            if (suffixCleaned.length >= 4 && beforeSuffixCleaned.endsWith(suffixCleaned)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        val sentenceDelimiters = Regex("(?<=[。！？.!?])")
        val sentences = result.split(sentenceDelimiters)
        if (sentences.size >= 2) {
            val deduped = mutableListOf<String>()
            for (sentence in sentences) {
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) continue
                val currentClean = trimmed.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
                var isDuplicate = false
                for (prev in deduped) {
                    val prevClean = prev.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
                    if (currentClean == prevClean ||
                        (currentClean.length >= 4 && prevClean.endsWith(currentClean)) ||
                        (prevClean.length >= 4 && currentClean.endsWith(prevClean))
                    ) {
                        isDuplicate = true
                        break
                    }
                }
                if (!isDuplicate) {
                    deduped.add(trimmed)
                }
            }
            val joined = deduped.joinToString("")
            if (joined.length < result.length) {
                result = joined
                return removeLocalRepetition(result)
            }
        }

        return result
    }
}
