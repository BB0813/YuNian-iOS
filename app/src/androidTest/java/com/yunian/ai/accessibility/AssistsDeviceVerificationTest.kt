package com.yunian.ai.accessibility

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunian.ai.agent.plugin.PluginBlueprintStatus
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.feature.skills.accessibility.AssistsAccessibilityBridge
import com.yunian.ai.feature.skills.accessibility.YuNianAccessibilityService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * ui.assists（无障碍自动化插件，构建在 assists 3.5.9 之上）的真机仪器化验证。
 *
 * ## 这份测试在验证什么
 *
 * 迁移把自研无障碍代码换成 assists，并把 9 个工具从「启动期直接注册」改为 Cordis 插件
 * ui.assists 装配。编译与 JVM 单测已过；真机运行时链路从未被验证：
 * 服务能否被系统连接、isReady 能否置位、9 个工具是否真的进了注册表、
 * 渠道收窄是否生效、接缝的 9 个方法在真实窗口上是否可用。
 * 本文件就是这条链路的唯一运行时证据来源。
 *
 * ## 用例顺序
 *
 * 用例按名字字典序执行（FixMethodOrder + NAME_ASCENDING 的 t01..t11），
 * 因为最后一条会做全局动作（back/home）离开本应用，必须收尾。
 *
 * ## 强断言 vs 只记录（本文件最重要的设计约定）
 *
 * - 强断言（失败 = 真缺陷）：t01..t09、t11 的核心判据；
 * - 只记录（通过但不判定）：t09 找不到「可点击的安全文本节点」、
 *   t10 当前界面没有 EditText。这两种情况下「没有可点/可输入的控件」是界面的
 *   合理状态（Compose 首页本就不该有 EditText），把它判成缺陷就是误报。
 *
 * ## 前置条件不成立时的处理（刻意区分「环境不允许」与「功能真坏了」）
 *
 * - 无障碍服务未就绪：t01 强断言失败并打印完整 shell 诊断（区分
 *   「设备不允许 shell 改设置」与「服务真的起不来」）；依赖用例走
 *   [assumeTrue] 记为跳过，避免同一根因刷出 9 个重复失败。
 * - 予念不在前台：读屏/手势用例一律 [assumeTrue] 跳过 —— 既保证
 *   「手势只发生在予念自己的界面上」这条安全红线，也避免读到别的应用的界面
 *   却被当成「通过」的假绿。
 *
 * ## 真机安全（这是用户的日常手机）
 *
 * - 测试开始前把予念自己拉到前台（[prepareAppForeground]，startActivity + 1.5s 静置）；
 * - 任何手势（tap / swipe / clickText / inputText / back / home）之前都要求
 *   「活动窗口的包名 == 本应用」，否则跳过，绝不点别的应用或系统 UI；
 * - 唯一允许改的系统设置是无障碍开关，且先读后写、保留既有服务
 *   （不覆盖用户已开启的其它无障碍服务）；
 * - 不卸载、不清除数据、不改其它设置；
 * - t10 若发现输入框已有内容（用户草稿）则只记录、不覆盖。
 *
 * ## 已知的 OEM 依赖点（vivo / OriginOS 可能不成立，均有降级路径）
 *
 * 1. shell 写 enabled_accessibility_services：Android 13+ 对旁载应用的「受限设置」
 *    可能挡住无障碍授权；此时 t01 失败并把 settings / dumpsys 原文打出来，
 *    依赖用例记为跳过（不是「功能坏了」），调度者可改由 adb 手动开启后重跑。
 * 2. rootInActiveWindow 取不到包名时退化为 dumpsys window / dumpsys activity 证据。
 * 3. tap/swipe 的返回值语义是 assists 的「真实完成/取消」，个别 ROM 上报取消即为 false。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AssistsDeviceVerificationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instrumentation.targetContext

    // ───────────────────────────── 用例 ─────────────────────────────

    /**
     * t01 · 前置：无障碍服务被启用且接缝就绪（强断言）。
     *
     * 先读当前 enabled_accessibility_services / accessibility_enabled（只读，不改动），
     * 只有未就绪时才写：保留既有服务、把我们自己的组件追加进去（不覆盖用户的其它无障碍服务），
     * 再置 accessibility_enabled 1。随后轮询 YuNianAccessibilityService.isReady 最多 25s。
     *
     * 失败时不抛模糊失败：把 settings get ×2、写入输出、dumpsys accessibility grep yunian
     * 连同 isReady 最终值一起写进日志与断言消息，供调度者区分：
     * (a) 设备/ROM 不允许 shell 改设置（settings 回写值没变）；
     * (b) 设置写进去了但服务起不来（dumpsys 里看不到本服务 / 绑定失败）。
     *
     * 证据：TAG=A11yDeviceVerify，关键字 t01。
     */
    @Test
    fun t01_serviceEnabledAndBridgeReady() {
        val probe = probeReadiness()
        logKey(
            "t01: isReady=" + probe.isReadyFinal +
                " 轮询耗时=" + probe.elapsedMs + "ms" +
                " 是否发生过写入=" + probe.wroteSettings,
        )
        probe.diagnosticsLines().forEach { log(it) }
        assertTrue(
            "t01_serviceEnabledAndBridgeReady 失败：轮询 " + probe.elapsedMs + "ms 后 isReady 仍为 false。\n" +
                "请据下方诊断区分「设备不允许 shell 改无障碍设置」与「服务真的起不来」：\n" +
                probe.diagnosticsText(),
            probe.isReadyFinal,
        )
    }

    /**
     * t02 · 插件 ui.assists 已注册且已装载（强断言）。
     *
     * 应用初始化（StaticApkShell.onCreate -> YuNianApplication.initBusiness）在后台协程里
     * 注册插件并装载默认蓝图，所以这里必须轮询等待（最多 30s），不能假定已就绪。
     * 失败时附加 PluginBlueprintStatus 的进程级结局，用来区分「蓝图没跑到」
     * 与「蓝图跑了但本插件装载失败」。
     */
    @Test
    fun t02_pluginUiAssistsLoaded() {
        val host = awaitValue(INIT_TIMEOUT_MS, "ServiceRegistry.get(PluginHost)") {
            ServiceRegistry.get(PluginHost::class.java)
        } ?: run {
            fail(
                "t02_pluginUiAssistsLoaded 失败：ServiceRegistry 里取不到 PluginHost —— " +
                    "应用初始化（registerServiceProviders）未完成或失败。蓝图诊断：" + blueprintDiagnostics(),
            )
            error("unreachable")
        }

        val loaded = awaitTrue(INIT_TIMEOUT_MS, "PluginHost.isLoaded(ui.assists)") {
            host.isLoaded(PLUGIN_ID)
        }
        logKey(
            "t02: isLoaded(" + PLUGIN_ID + ")=" + loaded +
                " isRegistered=" + host.isRegistered(PLUGIN_ID) +
                " loadedIds=" + host.loadedIds().sorted(),
        )
        assertTrue(
            "t02_pluginUiAssistsLoaded 失败：isLoaded(" + PLUGIN_ID + ")=false；" +
                "isRegistered=" + host.isRegistered(PLUGIN_ID) +
                " loadedIds=" + host.loadedIds().sorted() +
                "；蓝图诊断：" + blueprintDiagnostics(),
            loaded,
        )
    }

    /**
     * t03 · 9 个工具都已注册 + 默认渠道收窄（强断言）。
     *
     * 三条独立判据：
     * 1. ToolRegistry.all(includeAppLocal = true) 的名字集合含全部 9 个（契约逐个断言）；
     * 2. 默认渠道 availableTools()（includeAppLocal 默认 false）不含
     *    screen_input_text / screen_dump_ui（appLocalOnly 收窄生效）；
     * 3. 正向对照：availableTools(includeAppLocal = true) 含这两个 —— 证明它们
     *    「因为 appLocalOnly 被挡住」而不是「因为不可用/没注册而缺席」；
     *    同时既有 7 个工具（appLocalOnly=false）在默认渠道里必须仍可见
     *    （迁移不改既有 7 个的可见性，改了就破坏了外部桥接会话的既有行为）。
     */
    @Test
    fun t03_nineToolsRegisteredAndChannelNarrowed() {
        val allPresent = awaitTrue(INIT_TIMEOUT_MS, "ToolRegistry 含全部 9 个工具") {
            ToolRegistry.all(includeAppLocal = true).map { it.name }.containsAll(TOOL_NAMES)
        }

        val allNames = ToolRegistry.all(includeAppLocal = true).map { it.name }.toSet()
        val missing = TOOL_NAMES.filterNot { allNames.contains(it) }
        logKey("t03: all(includeAppLocal=true) 注册池大小=" + allNames.size + " 缺失=" + missing)
        assertTrue(
            "t03_nineToolsRegisteredAndChannelNarrowed 失败：注册池缺少工具 " + missing +
                "；当前注册池=" + allNames.sorted() +
                "；插件诊断：" + pluginDiagnostics() +
                "；蓝图诊断：" + blueprintDiagnostics(),
            allPresent,
        )

        val defaultNames = ToolRegistry.availableTools().map { it.name }.toSet()
        val localNames = ToolRegistry.availableTools(includeAppLocal = true).map { it.name }.toSet()
        logKey("t03: 默认渠道 availableTools() 大小=" + defaultNames.size)
        logKey("t03: 本机渠道 availableTools(true) 大小=" + localNames.size)

        val leakedToDefault = APP_LOCAL_ONLY_TOOL_NAMES.filter { defaultNames.contains(it) }
        logKey("t03: 默认渠道泄漏的本机敏感工具=" + leakedToDefault)
        assertTrue(
            "t03 失败：默认渠道 availableTools() 不得含本机敏感工具，实际泄漏=" + leakedToDefault,
            leakedToDefault.isEmpty(),
        )

        val missingInLocal = APP_LOCAL_ONLY_TOOL_NAMES.filterNot { localNames.contains(it) }
        assertTrue(
            "t03 失败：本机渠道 availableTools(includeAppLocal=true) 应含 " + APP_LOCAL_ONLY_TOOL_NAMES +
                "，实际缺失=" + missingInLocal + "（缺了说明不是被收窄，而是没注册或 isAvailable=false）",
            missingInLocal.isEmpty(),
        )

        val legacy = TOOL_NAMES.filterNot { APP_LOCAL_ONLY_TOOL_NAMES.contains(it) }
        val legacyMissing = legacy.filterNot { defaultNames.contains(it) }
        logKey("t03: 既有 7 个在默认渠道缺失=" + legacyMissing)
        assertTrue(
            "t03 失败：既有 7 个工具（appLocalOnly=false）在默认渠道必须仍可见，实际缺失=" + legacyMissing,
            legacyMissing.isEmpty(),
        )

        val defaultJson = ToolRegistry.toolDefinitionsJson()
        val localJson = ToolRegistry.toolDefinitionsJson(includeAppLocal = true)
        val jsonLeak = APP_LOCAL_ONLY_TOOL_NAMES.filter { defaultJson.contains("\"" + it + "\"") }
        val jsonLocal = APP_LOCAL_ONLY_TOOL_NAMES.filter { localJson.contains("\"" + it + "\"") }
        logKey("t03: toolDefinitionsJson() 含本机敏感工具=" + jsonLeak + " toolDefinitionsJson(true) 含=" + jsonLocal)
        assertTrue(
            "t03 失败：toolDefinitionsJson()（默认渠道，即真实进模型的 tools 数组）不得含 " + jsonLeak,
            jsonLeak.isEmpty(),
        )
        assertTrue(
            "t03 失败：toolDefinitionsJson(includeAppLocal=true) 应含 " + APP_LOCAL_ONLY_TOOL_NAMES + "，实际=" + jsonLocal,
            jsonLocal.size == APP_LOCAL_ONLY_TOOL_NAMES.size,
        )
    }

    /**
     * t04 · accessibility_status 工具端到端（强断言，逐字节）。
     *
     * 就绪时返回值必须逐字节等于常量 [EXPECTED_STATUS_JSON]（key 顺序、无空格、中文原样）。
     * 不等时额外打印「首个差异位置」，便于区分 key 顺序问题 / 中文编码问题。
     */
    @Test
    fun t04_statusToolEndToEnd() {
        assumeTrue("t04 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t04"))

        val actual = executeTool("accessibility_status", "{}")
        logKey("t04: 期望=" + EXPECTED_STATUS_JSON)
        logKey("t04: 实际=" + actual + "（长度 " + actual.length + "，期望长度 " + EXPECTED_STATUS_JSON.length + "）")
        if (actual != EXPECTED_STATUS_JSON) logKey("t04: " + firstDifference(EXPECTED_STATUS_JSON, actual))
        assertEquals(
            "t04_statusToolEndToEnd 失败：accessibility_status 的返回必须逐字节等于期望串；" +
                firstDifference(EXPECTED_STATUS_JSON, actual),
            EXPECTED_STATUS_JSON,
            actual,
        )
    }

    /**
     * t05 · screen_dump_ui 工具端到端（强断言，含前台守卫）。
     *
     * 断言返回以 [DUMP_UI_OK_PREFIX] 开头；日志打印前 300 字符 + 内层节点树 JSON 的长度、
     * 根节点字段名与节点计数（这些是 assists 的 getRootNodeTreeJson 到底吐什么的第一手证据）。
     */
    @Test
    fun t05_dumpUiToolEndToEnd() {
        assumeTrue("t05 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t05"))
        assumeTrue("t05 前置不成立：予念不在前台，读到的会是别的窗口", awaitOwnAppActive("t05"))

        val result = executeTool("screen_dump_ui", "{}")
        logLong("t05: screen_dump_ui 返回前 300 字符", result.take(300))
        assertTrue(
            "t05_dumpUiToolEndToEnd 失败：screen_dump_ui 应返回以 " + DUMP_UI_OK_PREFIX +
                " 开头（实际前 120 字符：" + result.take(120) + "）",
            result.startsWith(DUMP_UI_OK_PREFIX),
        )

        val uiJson = runCatching { JSONObject(result).optString("ui", "") }.getOrDefault("")
        logKey("t05: 内层节点树 JSON 长度=" + uiJson.length)
        val root = runCatching { JSONObject(uiJson) }.getOrNull()
        if (root == null) {
            logKey("t05: 内层 JSON 解析失败（前 200 字符：" + uiJson.take(200) + "）")
        } else {
            val keys = root.keys().asSequence().toList()
            val nodes = collectNodes(root)
            logKey("t05: 根节点字段=" + keys)
            logKey("t05: 收集到节点数=" + nodes.size + " 其中有文本的=" + nodes.count { it.text.isNotBlank() })
            logKey("t05: 根节点 packageName=" + root.optString("packageName", ""))
        }
    }

    /**
     * t06 · 接缝 readScreenText 真机读屏（强断言，含前台守卫）。
     *
     * 前台守卫是必须的：readScreenText 读的是活动窗口，若予念不在前台，
     * 「非空」会读到别的应用而假绿。
     */
    @Test
    fun t06_readScreenTextNonEmpty() {
        assumeTrue("t06 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t06"))
        assumeTrue("t06 前置不成立：予念不在前台，读到的会是别的窗口", awaitOwnAppActive("t06"))

        val text = AssistsAccessibilityBridge.readScreenText(3000)
        logKey("t06: readScreenText(3000) 长度=" + text.length)
        logLong("t06: readScreenText 前 300 字符", text.take(300))
        assertTrue(
            "t06_readScreenTextNonEmpty 失败：予念在前台时 readScreenText(3000) 不得为空串" +
                "（空串意味着 assists 取不到 rootInActiveWindow，或节点树里没有 text/des）",
            text.isNotEmpty(),
        )
    }

    /**
     * t07 · 接缝 dumpNodeTreeJson 真机取树（强断言，含前台守卫）。
     *
     * 额外记录：JSON 里是否出现本应用包名（出现说明 packageName 字段有值、
     * scope 确实是 ActiveWindow 而不是空的 AllWindows）。
     */
    @Test
    fun t07_dumpNodeTreeJsonNonEmpty() {
        assumeTrue("t07 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t07"))
        assumeTrue("t07 前置不成立：予念不在前台，读到的是别的窗口", awaitOwnAppActive("t07"))

        val json = AssistsAccessibilityBridge.dumpNodeTreeJson()
        logKey("t07: dumpNodeTreeJson() 长度=" + json.length)
        logLong("t07: dumpNodeTreeJson 前 400 字符", json.take(400))
        val containsOwnPackage = json.contains(ctx.packageName)
        logKey("t07: 含本应用包名 " + ctx.packageName + " = " + containsOwnPackage)
        assertTrue(
            "t07_dumpNodeTreeJsonNonEmpty 失败：予念在前台时 dumpNodeTreeJson() 不得为空串" +
                "（空串意味着 assists 取不到 rootInActiveWindow）",
            json.isNotEmpty(),
        )
    }

    /**
     * t08 · 手势 tap / swipe（强断言，含前台守卫）。
     *
     * 用 displayMetrics 取屏宽高；tap(w/2, h/2) 与 swipe(w/2, 0.7h, w/2, 0.35h, 300) 各一次，
     * 断言两次调用都不抛异常且返回 true。屏幕中心是唯一允许的固定坐标，
     * 且此刻予念必须在前台（守卫不成立即跳过，绝不在别的应用上落手指）。
     *
     * 额外证据：再多做一次工具层 screen_tap，验证
     * 「插件 -> 工具 -> 接缝 -> assists 手势」的整条端到端路径。
     *
     * 注意：assists 的手势返回语义是「真实完成/取消」而不是旧实现的「是否受理」，
     * 因此这里断言 true 比迁移前更严格；个别 ROM 若上报取消，会在这里暴露出来。
     */
    @Test
    fun t08_gestureTapAndSwipeReturnTrue() {
        assumeTrue("t08 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t08"))
        assumeTrue("t08 前置不成立：予念不在前台，禁止在别的界面上做手势", awaitOwnAppActive("t08"))

        val metrics = ctx.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        log("t08: displayMetrics=" + w + "x" + h + " density=" + metrics.density)

        var thrown: Throwable? = null
        val tapOk = try {
            runBlocking { AssistsAccessibilityBridge.tap(cx, cy) }
        } catch (t: Throwable) {
            thrown = t
            false
        }
        logKey("t08: tap(" + cx + ", " + cy + ") 返回=" + tapOk + " 异常=" + describeThrowable(thrown))
        assertTrue("t08 失败：tap 抛异常 " + describeThrowable(thrown), thrown == null)
        assertTrue("t08 失败：tap(" + cx + ", " + cy + ") 返回 false（assists 手势被取消/服务不在位）", tapOk)

        SystemClock.sleep(400)

        var swipeThrown: Throwable? = null
        val swipeOk = try {
            runBlocking { AssistsAccessibilityBridge.swipe(cx, h * 0.7f, cx, h * 0.35f, 300L) }
        } catch (t: Throwable) {
            swipeThrown = t
            false
        }
        logKey(
            "t08: swipe(" + cx + ", " + (h * 0.7f) + " -> " + cx + ", " + (h * 0.35f) + ", 300ms) 返回=" + swipeOk +
                " 异常=" + describeThrowable(swipeThrown),
        )
        assertTrue("t08 失败：swipe 抛异常 " + describeThrowable(swipeThrown), swipeThrown == null)
        assertTrue("t08 失败：swipe 返回 false（assists 手势被取消/服务不在位）", swipeOk)

        // 额外证据：工具层 screen_tap（与上面同一次手势语义；失败说明工具层映射有问题）
        val toolTap = executeTool("screen_tap", "{\"x\":" + (w / 2) + ",\"y\":" + (h / 2) + "}")
        logKey("t08(extra): screen_tap 端到端返回=" + toolTap)
        assertTrue(
            "t08 失败：screen_tap 工具端到端应回 ok=true，实际=" + toolTap,
            toolTap.startsWith("{\"ok\":true,"),
        )
    }

    /**
     * t09 · 按文本点击（有候选则强断言，无候选则只记录）。
     *
     * 从 screen_dump_ui 的节点树 JSON 里挑一个「本应用界面上的、可点击的、文本安全的」节点，
     * 用接缝 clickText(text) 点击并断言 true。
     *
     * 候选只取自当前活动窗口（守卫已确认是予念），不存在点到别的应用的可能。
     * 安全收敛：跳过含「删除/清空/退出/同意/允许…」等破坏性或授权性文案的节点；
     * 这类节点被排除后若没有可用候选，则只记录（不算失败）——「首屏没有可点击的安全文本」
     * 是合理状态，不是缺陷。
     */
    @Test
    fun t09_clickTextOnOwnUi() {
        assumeTrue("t09 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t09"))
        assumeTrue("t09 前置不成立：予念不在前台，禁止在别的界面上点击", awaitOwnAppActive("t09"))

        val nodes = currentUiNodes()
        if (nodes == null) {
            logKey("t09: 记录-only —— 拿不到节点树（screen_dump_ui 未返回可解析的 ui 字段）")
            return
        }
        // 纵深防御：只认包名为本应用（或空）的节点 —— 候选里出现别的包名就绝不点。
        val ownNodes = nodes.filter { it.packageName.isEmpty() || it.packageName == ctx.packageName }
        val textNodes = ownNodes.filter { it.text.isNotBlank() }
        val candidates = textNodes.filter { it.clickable && isSafeClickText(it.text) }
        logKey(
            "t09: 节点总数=" + nodes.size + " 非本应用包名=" + (nodes.size - ownNodes.size) +
                " 有文本=" + textNodes.size + " 可点击且安全=" + candidates.size,
        )
        log("t09: 文本样例=" + textNodes.take(8).map { it.text })
        if (candidates.isEmpty()) {
            logKey("t09: 记录-only —— 本应用当前界面没有「可点击 + 文本安全」的候选节点，未做点击")
            return
        }

        val chosen = candidates.first()
        logKey(
            "t09: 选中候选 text=" + chosen.text + " className=" + chosen.className +
                " bounds=(" + chosen.centerX + "," + chosen.centerY + ")",
        )
        val ok = AssistsAccessibilityBridge.clickText(chosen.text)
        logKey("t09: clickText(" + chosen.text + ")=" + ok)
        assertTrue(
            "t09_clickTextOnOwnUi 失败：对自家界面上可点击文本 " + chosen.text +
                " 的 clickText 返回 false（assists 的 findByText + 点击路径有问题）",
            ok,
        )
    }

    /**
     * t10 · 输入文本（找到空输入框则强断言，否则只记录）。
     *
     * 从节点树里找 className 含 EditText 的节点 -> 取其 boundsInScreen 中心先 tap 聚焦 ->
     * inputText(INPUT_PROBE) -> 断言「界面里出现该串」。
     *
     * 两处安全收敛（都在真机上保护用户数据）：
     * 1. 输入框已有内容时只记录、不覆盖（那是用户的草稿）；
     * 2. 断言用「readScreenText 含探针 或 节点树 JSON 含探针」两个等价观测取或 ——
     *    输入法弹出时活动窗口归属在不同 ROM 上不一致，只认其中一个会误报。
     * 输入完成后 best-effort 清空探针（不发送任何消息）。
     */
    @Test
    fun t10_inputTextIntoEditText() {
        assumeTrue("t10 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t10"))
        assumeTrue("t10 前置不成立：予念不在前台，禁止在别的界面上输入", awaitOwnAppActive("t10"))

        val nodes = currentUiNodes()
        if (nodes == null) {
            logKey("t10: 记录-only —— 拿不到节点树（screen_dump_ui 未返回可解析的 ui 字段）")
            return
        }
        // 纵深防御：只认包名为本应用（或空）的节点。
        val ownNodes = nodes.filter { it.packageName.isEmpty() || it.packageName == ctx.packageName }
        val editBoxes = ownNodes.filter {
            it.className.contains("EditText", ignoreCase = true) ||
                it.className.contains("AutoCompleteTextView", ignoreCase = true)
        }
        logKey(
            "t10: 节点总数=" + nodes.size + " 非本应用包名=" + (nodes.size - ownNodes.size) +
                " 含 EditText 的节点=" + editBoxes.size,
        )
        if (editBoxes.isEmpty()) {
            logKey("t10: 记录-only —— 当前界面没有 EditText 节点，没有可输入的目标（不算失败）")
            return
        }
        val box = editBoxes.first()
        logKey(
            "t10: 选中输入框 className=" + box.className + " bounds=(" + box.centerX + "," + box.centerY +
                ") 现有文本=" + box.text,
        )
        if (box.text.isNotBlank()) {
            logKey("t10: 记录-only —— 输入框已有内容（用户草稿），按安全约定不覆盖")
            return
        }
        if (box.centerX <= 0 || box.centerY <= 0) {
            logKey("t10: 记录-only —— 输入框 bounds 无效，无法定位中心点")
            return
        }

        val focusOk = runBlocking { AssistsAccessibilityBridge.tap(box.centerX.toFloat(), box.centerY.toFloat()) }
        logKey("t10: 聚焦 tap(" + box.centerX + ", " + box.centerY + ")=" + focusOk)
        assertTrue("t10 失败：点击输入框中心以聚焦时 tap 返回 false", focusOk)
        SystemClock.sleep(600)

        var thrown: Throwable? = null
        val inputOk = try {
            AssistsAccessibilityBridge.inputText(INPUT_PROBE)
        } catch (t: Throwable) {
            thrown = t
            false
        }
        logKey("t10: inputText(" + INPUT_PROBE + ")=" + inputOk + " 异常=" + describeThrowable(thrown))
        assertTrue("t10 失败：inputText 抛异常 " + describeThrowable(thrown), thrown == null)
        assertTrue("t10 失败：已聚焦唯一输入框时 inputText 应返回 true", inputOk)
        SystemClock.sleep(500)

        val screenText = AssistsAccessibilityBridge.readScreenText(3000)
        val treeJson = AssistsAccessibilityBridge.dumpNodeTreeJson()
        val inScreenText = screenText.contains(INPUT_PROBE)
        val inTreeJson = treeJson.contains(INPUT_PROBE)
        logKey(
            "t10: 校验 readScreenText 含探针=" + inScreenText + " dumpNodeTreeJson 含探针=" + inTreeJson +
                "（readScreenText 长度 " + screenText.length + "，节点树长度 " + treeJson.length + "）",
        )
        logLong("t10: 输入后 readScreenText 前 300 字符", screenText.take(300))

        // best-effort 清理：把探针从输入框移除（不发送任何消息），失败不影响判定
        val cleaned = runCatching { AssistsAccessibilityBridge.inputText("") }.getOrDefault(false)
        logKey("t10: best-effort 清空输入框=" + cleaned)

        assertTrue(
            "t10 失败：inputText 返回 true 但界面观测里都没有探针串 " + INPUT_PROBE +
                "（readScreenText 与节点树 JSON 双向核对均未命中）",
            inScreenText || inTreeJson,
        )
    }

    /**
     * t11 · 全局动作 back / home（强断言，必须最后执行）。
     *
     * 两个全局动作会作用于整个设备，所以同样要求予念在前台（守卫失败即跳过，
     * 避免在别的应用上误触返回）。home() 之后本应用退到后台，故放在最后一条。
     */
    @Test
    fun t11_backAndHomeReturnTrue() {
        assumeTrue("t11 前置不成立：无障碍服务未就绪（详见 t01 的 shell 诊断）", ensureReady("t11"))
        assumeTrue("t11 前置不成立：予念不在前台，禁止在别的界面上发全局返回", awaitOwnAppActive("t11"))

        val backOk = AssistsAccessibilityBridge.back()
        logKey("t11: back()=" + backOk)
        assertTrue("t11 失败：back() 返回 false（GLOBAL_ACTION_BACK 未受理）", backOk)
        SystemClock.sleep(800)

        val homeOk = AssistsAccessibilityBridge.home()
        logKey("t11: home()=" + homeOk + "（此后予念退到后台，本用例是最后一条）")
        assertTrue("t11 失败：home() 返回 false（GLOBAL_ACTION_HOME 未受理）", homeOk)
    }

    // ───────────────────────── 前台准备 / 前置守卫 ─────────────────────────

    /**
     * 每个用例开始前把予念自己拉到前台并静置 1.5s。
     *
     * 仪器化测试不会自动启动任何 Activity（进程由 am instrument 拉起），
     * 不拉前台的话活动窗口可能是桌面或上一个应用 —— 读屏会读到别人，手势会点到别人。
     */
    @Before
    fun prepareAppForeground() {
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) }.getOrNull()
        if (launch == null) {
            logKey("前台准备: getLaunchIntentForPackage(" + ctx.packageName + ") = null，无法拉起本应用")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val failure: Throwable? = try {
            ctx.startActivity(launch)
            null
        } catch (t: Throwable) {
            t
        }
        logKey(
            "前台准备: startActivity(" + (launch.component?.flattenToShortString() ?: launch.toString()) +
                ") -> " + describeThrowable(failure),
        )
        if (failure != null) {
            // 兜底：上下文 startActivity 在 Android 10+ 的后台启动限制下可能被拒；
            // 这里用 shell 拉起本应用自己的 launcher Activity（不涉及任何其它应用/系统设置）。
            val shellOut = shell("am start -n " + ctx.packageName + "/" + ctx.packageName + ".MainActivity")
            logKey("前台准备: 兜底 am start 输出=" + oneLine(shellOut, 300))
        }
        SystemClock.sleep(SETTLE_MS)
    }

    /**
     * 等待「活动窗口的包名 == 本应用」成立（最多 [FOREGROUND_TIMEOUT_MS]）。
     *
     * 判据优先用 UiAutomation.getRootInActiveWindow 的 packageName ——
     * 它与 readScreenText/dumpNodeTreeJson 用的是同一个活动窗口，语义最贴切；
     * 取不到时退化为 dumpsys window / dumpsys activity 的 shell 证据。
     */
    private fun awaitOwnAppActive(caseName: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + FOREGROUND_TIMEOUT_MS
        var pkg: String? = null
        while (true) {
            pkg = runCatching { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (pkg == ctx.packageName) {
                log(caseName + ": 活动窗口包名=" + pkg + "（予念在前台）")
                return true
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(250)
        }
        val shellEvidence = windowFocusEvidence()
        logKey(
            caseName + ": 活动窗口包名=" + (pkg ?: "null") + "（期望 " + ctx.packageName +
                "）；shell 前台证据=" + shellEvidence,
        )
        if (shellEvidence.contains(ctx.packageName)) {
            log(caseName + ": 依 shell 证据判定予念在前台（rootInActiveWindow 判据不可用）")
            return true
        }
        logKey(caseName + ": 予念未在前台 —— 本用例不做任何手势/读屏，按前置条件不成立跳过")
        return false
    }

    /** 前台证据（只读 dumpsys，不改任何东西）。多条命令兜底，避免个别 ROM 少打印某一行。 */
    private fun windowFocusEvidence(): String {
        val window = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (window.isNotBlank()) return oneLine(window, 300)
        val activity = shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        if (activity.isNotBlank()) return oneLine(activity, 300)
        return oneLine(shell("dumpsys window | head -40"), 300)
    }

    // ───────────────────────── 就绪探测（t01 的核心） ─────────────────────────

    /** 一次完整的就绪探测结果：足够区分「设备不让改设置」与「服务起不来」。 */
    private class ReadinessProbe(
        val isReadyFinal: Boolean,
        val elapsedMs: Long,
        val wroteSettings: Boolean,
        val servicesBefore: String,
        val enabledBefore: String,
        val desiredServices: String,
        val putServicesOutput: String,
        val putEnabledOutput: String,
        val servicesAfter: String,
        val enabledAfter: String,
        val dumpsysYunian: String,
        val dumpsysEnabledServices: String,
        val apiLevel: Int,
        val device: String,
    ) {
        fun diagnosticsLines(): List<String> = listOf(
            "t01 诊断[设备] " + device + " / API " + apiLevel,
            "t01 诊断[是否写入过设置] " + wroteSettings,
            "t01 诊断[写入前 enabled_accessibility_services] " + servicesBefore.ifEmpty { "<空>" },
            "t01 诊断[写入前 accessibility_enabled] " + enabledBefore.ifEmpty { "<空>" },
            "t01 诊断[写入值] " + desiredServices.ifEmpty { "<未写入>" },
            "t01 诊断[settings put services 输出] " + putServicesOutput.ifEmpty { "<无输出>" },
            "t01 诊断[settings put accessibility_enabled 输出] " + putEnabledOutput.ifEmpty { "<无输出>" },
            "t01 诊断[写入后 enabled_accessibility_services] " + servicesAfter.ifEmpty { "<空>" },
            "t01 诊断[写入后 accessibility_enabled] " + enabledAfter.ifEmpty { "<空>" },
            "t01 诊断[dumpsys accessibility | grep -i yunian] " + dumpsysYunian.ifEmpty { "<无命中>" },
            "t01 诊断[dumpsys accessibility enabled services 段] " + dumpsysEnabledServices.ifEmpty { "<无命中>" },
            "t01 诊断[isReady 最终值] " + isReadyFinal + "（轮询 " + elapsedMs + "ms）",
        )

        fun diagnosticsText(): String = diagnosticsLines().joinToString("\n")
    }

    /**
     * 启用无障碍服务（只在未就绪时写设置）并轮询 isReady 最多 [READINESS_TIMEOUT_MS]。
     *
     * 写入策略：先读后写、保留既有服务 —— 若 enabled_accessibility_services 里已有
     * 其它无障碍服务（用户自己开的），保留它们并把本应用组件追加到末尾，
     * 绝不整体覆盖（用户的日常手机上不该因为跑一次测试就关掉别的无障碍服务）。
     */
    private fun probeReadiness(): ReadinessProbe {
        val apiLevel = Build.VERSION.SDK_INT
        val device = Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE

        val servicesBefore = shell("settings get secure enabled_accessibility_services")
        val enabledBefore = shell("settings get secure accessibility_enabled")

        var wroteSettings = false
        var desiredServices = ""
        var putServicesOutput = ""
        var putEnabledOutput = ""

        if (!YuNianAccessibilityService.isReady) {
            val current = servicesBefore.takeIf { it.isNotBlank() && it != "null" } ?: ""
            val parts = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }
            desiredServices = when {
                parts.contains(A11Y_COMPONENT) -> current
                current.isEmpty() -> A11Y_COMPONENT
                else -> current + ":" + A11Y_COMPONENT
            }
            putServicesOutput = shell("settings put secure enabled_accessibility_services " + desiredServices)
            putEnabledOutput = shell("settings put secure accessibility_enabled 1")
            wroteSettings = true
            logKey("t01: 已通过 shell 写入无障碍开关（保留既有服务）：" + servicesBefore + " -> " + desiredServices)
        } else {
            logKey("t01: isReady 已为 true，跳过 shell 写入（不改动任何系统设置）")
        }

        val start = SystemClock.uptimeMillis()
        var lastLogAt = start
        while (!YuNianAccessibilityService.isReady) {
            val now = SystemClock.uptimeMillis()
            if (now - start >= READINESS_TIMEOUT_MS) break
            if (now - lastLogAt >= 5000) {
                log("t01: 等待无障碍服务连接中… 已等 " + (now - start) + "ms / " + READINESS_TIMEOUT_MS + "ms")
                lastLogAt = now
            }
            SystemClock.sleep(200)
        }
        val elapsed = SystemClock.uptimeMillis() - start
        val ready = YuNianAccessibilityService.isReady

        val servicesAfter = shell("settings get secure enabled_accessibility_services")
        val enabledAfter = shell("settings get secure accessibility_enabled")
        val dumpsysYunian = shell("dumpsys accessibility | grep -i yunian")
        val dumpsysEnabled = shell("dumpsys accessibility | grep -i -A2 'enabled services' | head -30")

        return ReadinessProbe(
            isReadyFinal = ready,
            elapsedMs = elapsed,
            wroteSettings = wroteSettings,
            servicesBefore = servicesBefore,
            enabledBefore = enabledBefore,
            desiredServices = desiredServices,
            putServicesOutput = putServicesOutput,
            putEnabledOutput = putEnabledOutput,
            servicesAfter = servicesAfter,
            enabledAfter = enabledAfter,
            dumpsysYunian = dumpsysYunian,
            dumpsysEnabledServices = dumpsysEnabled,
            apiLevel = apiLevel,
            device = device,
        )
    }

    /**
     * 依赖用例的就绪门。返回 false 时调用方 assumeTrue 跳过。
     *
     * 负结果缓存放 companion：JUnit 每个用例新建一次实例，只有 companion 能跨用例记住
     * 「t01 已经等过 25s 且失败」，否则 9 个依赖用例会各再等 25s。
     */
    private fun ensureReady(caseName: String): Boolean {
        if (YuNianAccessibilityService.isReady) {
            log(caseName + ": 前置就绪已满足（isReady=true）")
            return true
        }
        if (readinessProbeFailed) {
            log(caseName + ": 本次运行的就绪探测已失败（证据见 t01），不再重复等待 25s")
            return false
        }
        val probe = probeReadiness()
        if (!probe.isReadyFinal) {
            readinessProbeFailed = true
            probe.diagnosticsLines().forEach { log(it) }
        }
        return probe.isReadyFinal
    }

    // ───────────────────────── 工具 / 插件取用 ─────────────────────────

    /** 工具层执行：找不到工具就直接失败（并把当前注册池打出来）。 */
    private fun executeTool(name: String, argumentsJson: String): String {
        val tool = ToolRegistry.all(includeAppLocal = true).firstOrNull { it.name == name }
        if (tool == null) {
            fail(
                "工具 " + name + " 未注册（all(includeAppLocal=true)=" +
                    ToolRegistry.all(includeAppLocal = true).map { it.name }.sorted() +
                    "）；插件诊断：" + pluginDiagnostics() + "；蓝图诊断：" + blueprintDiagnostics(),
            )
            error("unreachable")
        }
        return runBlocking { tool.execute(argumentsJson) }
    }

    private fun pluginDiagnostics(): String {
        val host = runCatching { ServiceRegistry.get(PluginHost::class.java) }.getOrNull()
            ?: return "PluginHost 未注册"
        return "isRegistered(" + PLUGIN_ID + ")=" + host.isRegistered(PLUGIN_ID) +
            " isLoaded(" + PLUGIN_ID + ")=" + host.isLoaded(PLUGIN_ID) +
            " loadedIds=" + host.loadedIds().sorted()
    }

    private fun blueprintDiagnostics(): String = runCatching {
        "attempts=" + PluginBlueprintStatus.attempts() + " outcome=" + PluginBlueprintStatus.outcome()
    }.getOrElse { "读取失败: " + it.javaClass.simpleName }

    // ───────────────────────── 节点树解析（org.json，无额外依赖） ─────────────────────────

    /** 当前活动窗口节点树里的一个节点（只取本文件用得到的字段）。 */
    private class UiNode(
        val text: String,
        val className: String,
        val packageName: String,
        val clickable: Boolean,
        val centerX: Int,
        val centerY: Int,
    )

    /** 走 screen_dump_ui 工具拿当前界面的节点树并解析；拿不到返回 null。 */
    private fun currentUiNodes(): List<UiNode>? {
        val result = executeTool("screen_dump_ui", "{}")
        logLong("t09/t10: screen_dump_ui 返回前 200 字符", result.take(200))
        val uiJson = runCatching { JSONObject(result).optString("ui", "") }.getOrNull() ?: return null
        if (uiJson.isBlank()) return null
        val root = runCatching { JSONObject(uiJson) }.getOrNull() ?: return null
        return collectNodes(root)
    }

    /**
     * 递归收集节点。
     *
     * 字段名来自 assists 3.5.9 的 AssistsCore.NodeTree / NodeBounds（已用 javap 核对）：
     * text / des / className / packageName / isClickable / boundsInScreen{centerX,centerY} / children。
     */
    private fun collectNodes(node: JSONObject): List<UiNode> {
        val out = ArrayList<UiNode>()
        val bounds = node.optJSONObject("boundsInScreen")
        out.add(
            UiNode(
                text = jsonString(node, "text"),
                className = jsonString(node, "className"),
                packageName = jsonString(node, "packageName"),
                clickable = node.optBoolean("isClickable", false),
                centerX = bounds?.optInt("centerX", -1) ?: -1,
                centerY = bounds?.optInt("centerY", -1) ?: -1,
            ),
        )
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                out.addAll(collectNodes(child))
            }
        }
        return out
    }

    /** 取字符串字段：缺字段 / JSON null / 字面量 null 一律当空串（否则会点到一个叫 null 的节点）。 */
    private fun jsonString(node: JSONObject, key: String): String {
        if (node.isNull(key)) return ""
        val value = node.optString(key, "")
        return if (value == "null") "" else value
    }

    /**
     * 文本候选是否「安全可点」：长度 2..16、含字母/汉字、且不含破坏性或授权性文案。
     * 目的是让 t09 绝不点到「删除 / 清空 / 退出 / 同意」这类会改变用户数据或授权的按钮。
     */
    private fun isSafeClickText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..16) return false
        if (trimmed.startsWith("http", ignoreCase = true)) return false
        if (!trimmed.any { it.isLetter() }) return false
        return UNSAFE_CLICK_TEXTS.none { trimmed.contains(it) }
    }

    // ───────────────────────── 日志 / shell / 通用工具 ─────────────────────────

    /** 每条观察都进 logcat（调度者从 logcat 取证据）。 */
    private fun log(message: String) {
        Log.i(TAG, message)
    }

    /** 关键结论：logcat + stdout 双写。 */
    private fun logKey(message: String) {
        Log.i(TAG, message)
        println(TAG + ": " + message)
    }

    /** 长值分片打印（避免单条 Log 过长被截断）。 */
    private fun logLong(label: String, value: String) {
        val safe = value.ifEmpty { "<空串>" }
        if (safe.length <= 800) {
            log(label + " = " + safe)
            return
        }
        val total = (safe.length + 799) / 800
        var index = 0
        var part = 1
        while (index < safe.length) {
            val end = minOf(index + 800, safe.length)
            log(label + " [part " + part + "/" + total + "] = " + safe.substring(index, end))
            index = end
            part++
        }
    }

    /** 跑一条 shell 命令并读回输出（只读诊断 + 那唯一一次无障碍开关写入）。 */
    private fun shell(command: String): String {
        return try {
            instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    input.bufferedReader().readText().trim()
                }
            }
        } catch (t: Throwable) {
            "<<shell 失败: " + t.javaClass.simpleName + ": " + t.message + ">>"
        }
    }

    private fun oneLine(value: String, max: Int): String =
        value.replace('\n', '|').replace('\r', ' ').take(max)

    private fun describeThrowable(t: Throwable?): String =
        if (t == null) "无" else t.javaClass.simpleName + ": " + t.message

    /** 轮询直到 produce() 返回非 null；超时返回最后一次的值（可能仍为 null）。 */
    private fun <T : Any> awaitValue(timeoutMs: Long, label: String, produce: () -> T?): T? {
        val start = SystemClock.uptimeMillis()
        var lastLogAt = start
        while (true) {
            val value = runCatching { produce() }.getOrNull()
            if (value != null) {
                log(label + " 已就绪（耗时 " + (SystemClock.uptimeMillis() - start) + "ms）")
                return value
            }
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) {
                log(label + " 等待 " + timeoutMs + "ms 后仍未就绪")
                return null
            }
            if (now - lastLogAt >= 5000) {
                log(label + " 尚未就绪（已等 " + (now - start) + "ms）")
                lastLogAt = now
            }
            SystemClock.sleep(250)
        }
    }

    /** 轮询直到条件为 true。 */
    private fun awaitTrue(timeoutMs: Long, label: String, condition: () -> Boolean): Boolean {
        val start = SystemClock.uptimeMillis()
        var lastLogAt = start
        while (true) {
            if (runCatching { condition() }.getOrDefault(false)) {
                log(label + " = true（耗时 " + (SystemClock.uptimeMillis() - start) + "ms）")
                return true
            }
            val now = SystemClock.uptimeMillis()
            if (now - start >= timeoutMs) {
                log(label + " 在 " + timeoutMs + "ms 内始终为 false")
                return false
            }
            if (now - lastLogAt >= 5000) {
                log(label + " 仍为 false（已等 " + (now - start) + "ms）")
                lastLogAt = now
            }
            SystemClock.sleep(250)
        }
    }

    /** 两个字符串的首个差异位置（逐字节断言失败时定位用）。 */
    private fun firstDifference(expected: String, actual: String): String {
        val limit = minOf(expected.length, actual.length)
        for (i in 0 until limit) {
            if (expected[i] != actual[i]) {
                return "首个差异 index=" + i + "：期望 " + expected[i] + "(" + expected[i].code +
                    ") 实际 " + actual[i] + "(" + actual[i].code + ")"
            }
        }
        if (expected.length != actual.length) {
            return "前缀相同但长度不同：期望 " + expected.length + " 实际 " + actual.length
        }
        return "两者相同"
    }

    companion object {
        /** 固定 TAG：调度者按它从 logcat 抓证据。 */
        const val TAG = "A11yDeviceVerify"

        /** 无障碍服务组件名（debug/release 的 applicationId 都是 com.yunian.ai）。 */
        const val A11Y_COMPONENT =
            "com.yunian.ai/com.yunian.ai.feature.skills.accessibility.YuNianAccessibilityService"

        /** Cordis 插件 id（蓝图 app/src/main/assets/blueprints/default.json 里的装载项）。 */
        const val PLUGIN_ID = "ui.assists"

        /** 就绪时 accessibility_status 的逐字节期望值。 */
        const val EXPECTED_STATUS_JSON = "{\"ok\":true,\"enabled\":true,\"hint\":\"手机控制通道可用\"}"

        /** screen_dump_ui 成功返回的前缀。 */
        const val DUMP_UI_OK_PREFIX = "{\"ok\":true,\"ui\":"

        /** 输入文本用例的探针串。 */
        const val INPUT_PROBE = "yunian-a11y-test"

        /** 9 个工具的名字契约（顺序与插件注册顺序一致）。 */
        val TOOL_NAMES = listOf(
            "accessibility_status",
            "screen_read",
            "press_back",
            "go_home",
            "screen_tap",
            "screen_swipe",
            "screen_click_text",
            "screen_input_text",
            "screen_dump_ui",
        )

        /** appLocalOnly=true 的 2 个新增工具：默认渠道必须看不到。 */
        val APP_LOCAL_ONLY_TOOL_NAMES = listOf("screen_input_text", "screen_dump_ui")

        /** t09 的文本黑名单：破坏数据 / 改授权 / 不可逆动作，一律不点。 */
        val UNSAFE_CLICK_TEXTS = listOf(
            "删除", "清空", "清除", "退出", "注销", "卸载", "解绑", "重置", "还原",
            "同意", "允许", "授权", "停止", "关闭", "退出登录", "拉黑", "举报", "格式化",
        )

        /** 就绪轮询上限（约 25s）。 */
        const val READINESS_TIMEOUT_MS = 25_000L

        /** 应用初始化 / 蓝图装载的轮询上限（initBusiness 在后台协程里，必须等）。 */
        const val INIT_TIMEOUT_MS = 30_000L

        /** 前台守卫轮询上限。 */
        const val FOREGROUND_TIMEOUT_MS = 8_000L

        /** 拉起本应用后的静置时间。 */
        const val SETTLE_MS = 1_500L

        /** 就绪探测的负结果缓存（跨用例共享；JUnit 每个用例新建实例，故必须放 companion）。 */
        @Volatile
        private var readinessProbeFailed: Boolean = false
    }
}
