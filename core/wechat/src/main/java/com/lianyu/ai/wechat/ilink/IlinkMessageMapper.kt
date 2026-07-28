package com.lianyu.ai.wechat.ilink

import com.github.wechat.ilink.sdk.core.model.CDNMedia as SdkCdnMedia
import com.github.wechat.ilink.sdk.core.model.MessageItem as SdkMessageItem
import com.github.wechat.ilink.sdk.core.model.WeixinMessage as SdkWeixinMessage
import com.lianyu.ai.wechat.wire.WireCdnMedia
import com.lianyu.ai.wechat.wire.WireFileItem
import com.lianyu.ai.wechat.wire.WireImageItem
import com.lianyu.ai.wechat.wire.WireMessageItem
import com.lianyu.ai.wechat.wire.WireTextItem
import com.lianyu.ai.wechat.wire.WireVideoItem
import com.lianyu.ai.wechat.wire.WireVoiceItem
import com.lianyu.ai.wechat.wire.WireWeChatMessage

object IlinkMessageMapper {
    fun toWireMessage(message: SdkWeixinMessage): WireWeChatMessage = WireWeChatMessage(
        seq = message.message_id,
        messageId = message.message_id,
        fromUserId = message.from_user_id,
        toUserId = message.to_user_id,
        createTimeMs = message.create_time_ms,
        messageType = message.message_type,
        itemList = message.item_list?.map { it.toWireItem() },
        contextToken = message.context_token,
    )

    private fun SdkMessageItem.toWireItem(): WireMessageItem = WireMessageItem(
        type = type,
        textItem = text_item?.let { WireTextItem(text = it.text.orEmpty()) },
        imageItem = image_item?.let { WireImageItem(cdnImg = it.media?.toWireMedia()) },
        voiceItem = voice_item?.let { WireVoiceItem(cdnVoice = it.media?.toWireMedia()) },
        fileItem = file_item?.let {
            WireFileItem(
                cdnFile = it.media?.toWireMedia(),
                fileName = it.file_name,
            )
        },
        videoItem = video_item?.let {
            WireVideoItem(
                cdnVideo = it.media?.toWireMedia(),
                cdnThumb = it.thumb_media?.toWireMedia(),
            )
        },
    )

    private fun SdkCdnMedia.toWireMedia(): WireCdnMedia = WireCdnMedia(
        encryptQueryParam = encrypt_query_param,
        aesKey = aes_key,
    )
}