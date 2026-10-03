package com.yunian.ai.common.crash

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CrashRedactor] 与 [CrashBreadcrumbs] 的纯 JVM 单测（不依赖 Android Runtime）。
 */
class CrashDiagnosticsTest {

    @Before
    fun setUp() {
        CrashBreadcrumbs.clear()
    }

    @Test
    fun redactor_masksSkStyleKey() {
        val out = CrashRedactor.redact("config sk-abc123456789DEFghi used")
        assertFalse(out.contains("sk-abc123456789DEFghi"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_masksJwt() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = CrashRedactor.redact("Authorization token=$jwt")
        assertFalse(out.contains(jwt))
    }

    @Test
    fun redactor_masksKeyValueSecrets() {
        val out = CrashRedactor.redact("api_key=SECRETVALUE123 & token: \"tok_9f8e7d6c\"")
        assertFalse(out.contains("SECRETVALUE123"))
        assertFalse(out.contains("tok_9f8e7d6c"))
        assertTrue(out.contains("api_key="))
    }

    @Test
    fun redactor_masksBearer() {
        val out = CrashRedactor.redact("header Bearer abc.def.ghi")
        assertFalse(out.contains("abc.def.ghi"))
        assertTrue(out.lowercase().contains("bearer"))
    }

    @Test
    fun redactor_masksBareKeyAssignments() {
        // 覆盖项目里真实存在的 SecureLog 调用形态（AiService / SettingsViewModel）。
        val out = CrashRedactor.redact(
            "Final config: model=m, key=deadbeef1234..., userKey=cafe0123..., with key: feed9876..."
        )
        assertFalse("bare key= leak", out.contains("deadbeef1234"))
        assertFalse("userKey= leak", out.contains("cafe0123"))
        assertFalse("key: leak", out.contains("feed9876"))
    }

    @Test
    fun redactor_masksTruncatedSkFragment() {
        // QA 报告的真实泄漏形态（take(8) 后只剩 5 字符，旧 sk-{8,} 规则漏掉）。
        val out = CrashRedactor.redact("[AiService] Key失败冷却5s: sk-12345...")
        assertFalse("truncated sk- fragment leak", out.contains("sk-12345"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_masksContentFields() {
        // PushMessageDispatcher / Diary / Summary / TextProcessor 等内容类调用点。
        val out = CrashRedactor.redact(
            "Message from vendor: title=今晚吃什么 content=宝贝你在吗 body={\"err\":\"secret\"}"
        )
        assertFalse("title leak", out.contains("今晚吃什么"))
        assertFalse("content leak", out.contains("宝贝你在吗"))
        assertFalse("body leak", out.contains("secret"))
    }

    @Test
    fun redactor_masksOriginalQuotedContent() {
        val out = CrashRedactor.redact("WARNING: blank output. Original: '这是一段AI原文', stickers sent: 2")
        assertFalse("original content leak", out.contains("这是一段AI原文"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_keepsPlainText() {
        val plain = "user opened settings page"
        assertEquals(plain, CrashRedactor.redact(plain))
    }

    @Test
    fun breadcrumbs_keepsOrderAndContent() {
        CrashBreadcrumbs.add("T1", "first")
        CrashBreadcrumbs.add("T2", "second")
        val snap = CrashBreadcrumbs.snapshot()
        val firstIdx = snap.indexOf("first")
        val secondIdx = snap.indexOf("second")
        assertTrue("first entry present", firstIdx >= 0)
        assertTrue("second entry present", secondIdx >= 0)
        assertTrue("order preserved", firstIdx < secondIdx)
        assertTrue(snap.contains("[T1]"))
        assertTrue(snap.contains("[T2]"))
    }

    @Test
    fun breadcrumbs_isBounded_ringOverwrites() {
        val huge = "x".repeat(5000)
        repeat(1000) { CrashBreadcrumbs.add("T", "entry-$it-$huge") }
        val snap = CrashBreadcrumbs.snapshot()
        // 最新一条一定在，最早的已被环形覆盖。
        assertTrue(snap.contains("entry-999"))
        assertFalse(snap.contains("entry-0-"))
        // dump 有上界。
        assertTrue(snap.length < 64 * 1024)
    }

    @Test
    fun breadcrumbs_truncatesLongEntry() {
        CrashBreadcrumbs.add("T", "y".repeat(1000))
        val snap = CrashBreadcrumbs.snapshot()
        assertTrue(snap.length < 1000)
    }

    @Test
    fun breadcrumbs_redactsOnSnapshot() {
        CrashBreadcrumbs.add("NET", "posted api_key=SUPERSECRET999")
        val snap = CrashBreadcrumbs.snapshot()
        assertFalse(snap.contains("SUPERSECRET999"))
        assertTrue(snap.contains("[REDACTED]"))
    }

    @Test
    fun breadcrumbs_emptyReturnsPlaceholder() {
        assertEquals("(no breadcrumbs)", CrashBreadcrumbs.snapshot())
    }

    @Test
    fun breadcrumbs_versionIncrementsOnAdd() {
        val before = CrashBreadcrumbs.version()
        CrashBreadcrumbs.add("T", "x")
        CrashBreadcrumbs.add("T", "y")
        assertEquals(before + 2, CrashBreadcrumbs.version())
    }

    @Test
    fun exitPolicy_flagsOnlyAppErrors() {
        // 期望值**直接取框架常量**，杜绝「把错误数字写死进断言」——
        // 上一版正是把 isNotable(8) 写死为 true（8 实为 PERMISSION_CHANGE），21/21 全绿却语义错误。
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_SIGNALED))               // 2
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH))                  // 4
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH_NATIVE))           // 5
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_ANR))                    // 6
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)) // 7
        // ★ 权限变更（8）不是崩溃 —— 必须排除（上一版误报的根因）。
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PERMISSION_CHANGE))     // 8
    }

    @Test
    fun exitPolicy_ignoresNormalExits() {
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_UNKNOWN))                  // 0
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_EXIT_SELF))                // 1
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PERMISSION_CHANGE))        // 8
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)) // 9
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_USER_REQUESTED))           // 10
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_USER_STOPPED))             // 11
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_DEPENDENCY_DIED))          // 12
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_OTHER))                    // 13
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_FREEZER))                  // 14
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE))     // 15
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PACKAGE_UPDATED))          // 16
    }

    @Test
    fun exitPolicy_systemReclamationIsNeverAnException() {
        // 旧策略：REASON_LOW_MEMORY 在白名单内，仅当 importance ≤ 125 才提示。
        // 但本 App **常驻保活前台服务**，importance 恒为 125(FOREGROUND_SERVICE)，
        // 该收窄条件**恒为真** —— 等价于「每次被系统回收都弹窗」。
        // 实测（vivo V2324A / OriginOS，`dumpsys activity exit-info`）：
        //   reason=3 (LOW_MEMORY)  importance=125  description=single-cleaner
        // 每隔约 1 分钟出现一次，用户每次启动都看到「上次运行异常退出」。
        //
        // 现策略：LMK 是内核在内存紧张时的**正常行为**，与应用出错无关，
        // 因此整体移出白名单 —— 被回收**不是异常**，不提示。
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, userInitiated = false))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, userInitiated = true))
        // 反向保护：真正的应用错误仍必须提示（防止「一刀切」把白名单改坏）。
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_ANR))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_SIGNALED))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
    }

    // ── 回归：vivo 清理器伪装成 LOW_MEMORY@125，导致「每次划掉后台都提示闪退」────────────

    @Test
    fun exitPolicy_userInitiatedExitSuppressesAnyReason() {
        // vivo/OriginOS 的清理器给出的 reason 与「真被 LMK」**完全重合**
        // （实测 reason=3 LOW_MEMORY / importance=125 / description=single-cleaner），
        // 靠 reason 永远分不开，只能靠 Service.onTaskRemoved() 落盘的自有信号。
        // 带该信号后，**任何** reason 都不再提示为异常。
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH, userInitiated = true))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_ANR, userInitiated = true))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH_NATIVE, userInitiated = true))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_SIGNALED, userInitiated = true))
        // 反向保护：不带该信号时，真崩溃仍要提示。
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_ANR))
    }

    @Test
    fun exitPolicy_crashLikeMatchesOnlyReportProducingReasons() {
        // 崩溃报告由 Thread.setDefaultUncaughtExceptionHandler 落盘，本项目**无 native 信号处理器**，
        // 故实际只会对应 REASON_CRASH；另两个是「宁可多提示、绝不漏报」的保守纳入。
        assertTrue(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_CRASH))
        assertTrue(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_SIGNALED))
        // 下列原因**不可能**产生崩溃报告 → 磁盘上若仍有报告，那份报告必然是陈旧的（不该再展示）。
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_ANR))
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_EXIT_SELF))
        assertFalse(ApplicationExitPolicy.isCrashLike(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun exitPolicy_userInitiatedExitWindowIsBounded() {
        val removed = 1_000_000L
        // 实测「划掉 → 进程结束」约 2s；10s 必须仍判定为用户主动。
        assertTrue(ApplicationExitPolicy.isUserInitiatedExit(removed + 10_000L, removed))
        // 死亡与移除同一时刻也算。
        assertTrue(ApplicationExitPolicy.isUserInitiatedExit(removed, removed))
        // 远超窗口 → 与本次移除无关（划掉后进程仍长期存活、之后才因别的原因死亡）。
        assertFalse(ApplicationExitPolicy.isUserInitiatedExit(removed + 60_000L, removed))
        // 死亡早于移除 → 与本次移除无关。
        assertFalse(ApplicationExitPolicy.isUserInitiatedExit(removed - 1L, removed))
        // 无记录 / 非法值 → 不判定为用户主动（退回原有行为，绝不误抑制）。
        assertFalse(ApplicationExitPolicy.isUserInitiatedExit(removed, 0L))
        assertFalse(ApplicationExitPolicy.isUserInitiatedExit(0L, removed))
        assertFalse(ApplicationExitPolicy.isUserInitiatedExit(0L, 0L))
    }
}
