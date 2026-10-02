package com.yunian.ai.feature.wechat.service

import com.yunian.ai.common.SecureLog
import com.yunian.ai.feature.wechat.BuildConfig
import com.yunian.ai.feature.wechat.WeChatDebugLog
import com.yunian.ai.wechat.map.WeChatOutboundText

/**
 * 「这一轮没有任何可发送内容」的可观测证据。
 *
 * 背景：桥接链路清洗后为空时原实现直接跳过发送且**不留任何痕迹**，用户看到的是
 * 「AI 回了但微信没收到」。这里不改投递语义（空就是空，不伪造内容、不强行发送），
 * 只把丢弃这件事记下来。
 *
 * 证据分三处，全部复用仓库既有机制，无新增依赖：
 * 1. [SecureLog.critical] —— 仓库里唯一**正式包可见**的日志通道
 *    （SecureLog.i/w/d 都被 isDebug 门控，release 包里什么都不打）。
 *    调用点用 BuildConfig.DEBUG 反向门控：Log.wtf 在可调试构建里会抛异常，
 *    而 debug 包本来就有文件日志（第 2 条），不需要它。
 * 2. [WeChatDebugLog] —— debug 包的文件日志。
 * 3. 进程内计数 + 有界环形缓冲（[dropCount] / [recentDrops]）—— 供健康快照读取。
 *
 * 注意：本类**不**发射 WeChatEvent.SendFailed、**不**触碰设置页 UI——
 * 「要不要给用户一个可见提示」属于产品决策，未在本次改动范围内。
 */
object WeChatOutboundDropLog {

    const val TAG = "WeChatOutboundDrop"

    /** 环形缓冲上限：够定位最近几次丢弃，且不会无限增长。 */
    const val MAX_RECENT_DROPS = 20

    /**
     * 一条证据。
     *
     * 注意字段语义随 [reason] 变化：
     * - 文本被丢弃时，[cleanedLength]/[strippedLength] 是清洗后/裁剪后的字符数；
     * - [REASON_STALE_SENDING_RECOVERED] 时，二者分别是「恢复回待发的行数」与「判死的行数」。
     */
    data class Drop(
        val reason: String,
        val cleanedLength: Int,
        val strippedLength: Int,
        val stickerCount: Int,
        val atMs: Long,
    )

    private val lock = Any()
    private val recent = ArrayDeque<Drop>()
    private var total = 0

    /**
     * 记录一次「清洗后无可发送文本」。
     *
     * @param reason [WeChatOutboundText.DropReason] 的 wireName，区分「清洗后为空」与「裁剪后为空」
     * @param stickerCount 本轮同时入队的表情包数量（>0 表示文本被丢弃但表情包仍会送达）
     */
    fun record(
        reason: String,
        cleanedLength: Int,
        strippedLength: Int,
        stickerCount: Int = 0,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val drop = Drop(
            reason = reason,
            cleanedLength = cleanedLength,
            strippedLength = strippedLength,
            stickerCount = stickerCount,
            atMs = atMs,
        )
        val totalNow: Int
        synchronized(lock) {
            total++
            totalNow = total
            recent.addLast(drop)
            while (recent.size > MAX_RECENT_DROPS) {
                recent.removeFirst()
            }
        }
        val line = "outbound_text_dropped reason=" + reason +
            " cleanedLen=" + cleanedLength +
            " strippedLen=" + strippedLength +
            " stickers=" + stickerCount +
            " total=" + totalNow
        WeChatDebugLog.log("[" + TAG + "] " + line)
        // 正式包唯一可见的通道。可调试构建下 Log.wtf 会抛异常，故反向门控。
        if (!BuildConfig.DEBUG) {
            SecureLog.critical(line)
        }
    }

    /**
     * 记录一次 SENDING 僵尸行恢复（G4）。
     *
     * 与丢弃记录共用同一份证据通道：core:wechat 内部只有 SecureLog.i/w（正式包空操作），
     * 这里补上正式包可见的那一份，同时累加计数供健康快照/诊断读取。
     */
    fun recordStaleSendingRecovered(recovered: Int, dead: Int, atMs: Long = System.currentTimeMillis()) {
        if (recovered <= 0 && dead <= 0) return
        val totalNow: Int
        synchronized(lock) {
            total++
            totalNow = total
            recent.addLast(
                Drop(
                    reason = REASON_STALE_SENDING_RECOVERED,
                    cleanedLength = recovered,
                    strippedLength = dead,
                    stickerCount = 0,
                    atMs = atMs,
                ),
            )
            while (recent.size > MAX_RECENT_DROPS) {
                recent.removeFirst()
            }
        }
        val line = "stale_sending_recovered recovered=" + recovered +
            " dead=" + dead +
            " total=" + totalNow
        WeChatDebugLog.log("[" + TAG + "] " + line)
        if (!BuildConfig.DEBUG) {
            SecureLog.critical(line)
        }
    }

    /** [Drop.reason] 的一种取值：这不是「文本被丢弃」，而是出站队列的僵尸行被恢复。 */
    const val REASON_STALE_SENDING_RECOVERED = "stale_sending_recovered"

    fun dropCount(): Int = synchronized(lock) { total }

    fun recentDrops(): List<Drop> = synchronized(lock) { recent.toList() }

    fun reset() {
        synchronized(lock) {
            recent.clear()
            total = 0
        }
    }
}
