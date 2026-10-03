package com.yunian.ai.feature.skills.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.NodeLookupScope

/**
 * [AccessibilityBridge] 的真机实现：把 9 个方法逐一映射到 assists 3.5.9 的
 * [AssistsCore]，语义逐条对齐迁移前的 `YuNianAccessibilityService`。
 *
 * 无状态 object：所有状态都在 assists 自己的单例里（`AssistsService.serviceRef` +
 * [YuNianAccessibilityService.instance]），本类不持有任何可变字段。
 *
 * ## 就绪判定：用 [YuNianAccessibilityService.isReady]，**不用** `AssistsService.getOrNull() != null`
 *
 * 两个信号**不等价**，不能混用：
 * - `AssistsService.onServiceConnected()` 在赋值 `serviceRef` **之后**还有
 *   `AssistsWindowManager.init(this)` 与 listener 分发，尾部还有一条不在 try/catch 内的
 *   utilcodex 日志；
 * - `AssistsService.getOrNull() != null` 在服务**连接失败 / 已解绑**时也可能为 true
 *   ——基类 `onCreate` 就赋值了 `serviceRef`，而系统在连接失败时**不一定**回调 `onUnbind`。
 *
 * 而 UI（`SkillsCenterScreen`）与工具层门控都是按 [YuNianAccessibilityService.isReady] 判定的。
 * 若本类换一个信号，就会出现「UI 说已开启、工具却按未就绪拒绝」这类自相矛盾。
 * 因此这里**沿用同一个判定**：isReady 为 true ⟺ 本类所有方法可用。
 *
 * ## 每条方法在服务未就绪时都不抛异常
 *
 * 工具层 `withBridge` 已做门控，但接缝自己也要健壮：所有方法先查 [isReady] 再执行，
 * 且 assists 的 API 内部本身也带 `runCatching` / null 安全（见各条 KDoc）。
 * 结果约定：读类返回空串，动作类返回 false。
 */
object AssistsAccessibilityBridge : AccessibilityBridge {

    /** 与迁移前 `YuNianAccessibilityService.NODE_MAX_DEPTH` 同值（读屏走树 / 上溯可点击祖先共用）。 */
    private const val NODE_MAX_DEPTH = 30

    override fun isReady(): Boolean = YuNianAccessibilityService.isReady

    /**
     * 读取当前**活动窗口**的全部可见文本（先 `text` 后 `contentDescription`，用换行符分隔）。
     *
     * ## 为什么保留手写走树，而不是改用 assists 的 `NodeTree` / `getAllText`
     *
     * 输出必须与迁移前的 DFS **逐字符等价**，而 assists 的取文本路径与它有实质差异：
     * - `AssistsCore.getAllNodes()` 是「先收根、再递归 child」的**无深度上限** DFS（仅 10000 节点总量护栏），
     *   且会把**根节点自身**也放进列表；迁移前是**深度上限 30**、从根开始但根不算「深度 1」。深度 >30 的子树里
     *   两者的收录范围不同。
     * - `AssistsCore.AccessibilityNodeInfo.getAllText()` 只取 `text`，**完全不含 `contentDescription`**，
     *   且它的去重 / 顺序规则与「先 text 后 des、逐个 append」也不一致。
     *
     * 两者都不满足「顺序 / 包含范围 / 去重差异都要说清」的门槛，所以按任务书给的**最低风险路径**办：
     * 用 assists 拿**活动窗口根节点**（[AssistsCore.getAccessibilityRootNodes] 的 `ActiveWindow` 分支
     * 就是 `listOfNotNull(service.rootInActiveWindow)`，与迁移前的 `rootInActiveWindow` 同一来源），
     * 走树逻辑原样保留。assists 与平台 API 混用是允许的。
     *
     * ## 与迁移前逐条对照（有意保留的行为，包括一个小怪癖）
     *
     * | 维度 | 迁移前 | 本实现 |
     * |---|---|---|
     * | 根节点 | `rootInActiveWindow` | `getAccessibilityRootNodes(ActiveWindow).firstOrNull()`（同源，null 时返回 ""） |
     * | 收录条件 | `text` 非空白 + `contentDescription` 非空白 | 同 |
     * | 顺序 | 先 text 后 des，均非空才补换行符再 append | 同 |
     * | 深度 | `depth > 30` 即停（根为 0） | 同 |
     * | 截断 | `take(maxLength)` | 同 |
     * | 提前返回 | 子循环内 `sb.length >= maxLength` 只 `return` **当前这一层**的 for | **同**（怪癖一并保留） |
     *
     * 最后一行是迁移前就有的行为：越界后只中断当前节点的子节点遍历，**外层兄弟分支仍会继续 DFS**
     * （每次进入新节点时长度检查重新生效），因此中途可能继续小幅增长，最终由 `take(maxLength)` 收口。
     * 本实现**不修这个怪癖** —— 任务要求是「等价」，不是「顺手改好」；改掉它会让输出与旧版本出现差异。
     */
    override fun readScreenText(maxLength: Int): String {
        if (!isReady()) return ""
        val root = runCatching {
            with(AssistsCore) { getAccessibilityRootNodes(NodeLookupScope.ActiveWindow) }.firstOrNull()
        }.getOrNull() ?: return ""
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > NODE_MAX_DEPTH) return
            node.text?.takeIf { it.isNotBlank() }?.let {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(it)
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(it)
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (sb.length >= maxLength) return
            }
        }
        walk(root, 0)
        return sb.toString().take(maxLength)
    }

    /**
     * 当前活动窗口的结构化节点树 JSON。
     *
     * `AssistsCore.getRootNodeTreeJson(prettyPrint, scope)` 的两个形参语义**已从源码确认**：
     * - 第 1 个是 `prettyPrint: Boolean`（是否格式化；`false` 用紧凑 Gson）—— **不传 scope 会踩坑**，
     *   因为它是第一参数而不是 scope；
     * - 第 2 个是 `scope: NodeLookupScope`，默认 `ActiveWindow`。
     *
     * 这里显式写 `NodeLookupScope.ActiveWindow` 而不是依赖默认值：与 `readScreenText` 的取根范围保持一致，
     * 也避免以后 assists 改默认值时静默改变行为。
     * **不用 `AllWindows`** —— 当前 `accessibility_service_config.xml` 没有 `flagRetrieveInteractiveWindows`，
     * `service.windows` 为空，`AllWindows` 会直接返回 ""。
     *
     * 未就绪 / 取不到根（如锁屏、切换窗口瞬间）或结果为空串时返回 ""（工具层据此回「未能读取界面节点树」）。
     */
    override fun dumpNodeTreeJson(): String {
        if (!isReady()) return ""
        return runCatching {
            with(AssistsCore) { getRootNodeTreeJson(false, NodeLookupScope.ActiveWindow) }
        }.getOrDefault("")
    }

    /**
     * 手势**短按**点击。
     *
     * 时长语义逐层核对：
     * - 迁移前：`tap(x, y) = dispatchPathGesture(x, y, x, y, 60)` → `StrokeDescription(path, 0, 60)`，
     *   即**按住 60ms**（不是 0ms 的瞬时 tap）。
     * - assists：`gestureClick(x, y, duration = 10)` 内部转成
     *   `gesture(floatArrayOf(x,y), floatArrayOf(x,y), startTime = 0, duration)`，
     *   而 `gesture(path, startTime, duration)` 与迁移前一样是
     *   `StrokeDescription(path, startTime, duration)` —— **形参含义完全一致**（毫秒，按住时长）。
     * - 因此显式传 `duration = 60`（**不依赖 assists 的默认值 10**，否则长按会短到 10ms，语义漂移）。
     *
     * ≥50ms 下限：迁移前在 `dispatchPathGesture` 里做了 `durationMs.coerceAtLeast(50)`；
     * assists 的 `gesture` / `gestureClick` **不做任何钳制**（源码逐行确认，只有 `runCatching` 兜异常），
     * 所以钳制必须自己保留 —— 虽然本实现固定传 60（本就 ≥50），仍然写上，作为与旧实现的对齐证据与防回归护栏。
     *
     * 失败语义：`gesture` 用 `CompletableDeferred` 桥接真实的 `GestureResultCallback`，
     * 取消返回 false、服务不在位返回 false，且整体包在 `runCatching` 里**不抛异常**。
     * （与旧实现的一个差别：旧实现返回的是 `dispatchGesture` 的「是否受理」，assists 返回的是「真实完成/取消」——
     * 更严格，工具层因此能如实报告失败。）
     */
    override suspend fun tap(x: Float, y: Float): Boolean {
        if (!isReady()) return false
        return with(AssistsCore) { gestureClick(x, y, duration = SAFE_GESTURE_MS) }
    }

    /**
     * 手势滑动（起点 → 终点）。
     *
     * 迁移前：`swipe(x1,y1,x2,y2, durationMs = 300)` → 同一条 `dispatchPathGesture`，
     * 含 `coerceAtLeast(50)`。assists 侧 `gesture(startLocation, endLocation, startTime, duration)`
     * 的形参含义与旧实现一致（`moveTo(start) → lineTo(end)`，`startTime` 为开始延迟），
     * 默认 `durationMs = 300` 与旧实现一致，**但同样不做钳制** → 这里自己保留 `coerceAtLeast(50)`。
     */
    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (!isReady()) return false
        val safeDuration = durationMs.coerceAtLeast(50)
        return with(AssistsCore) {
            gesture(floatArrayOf(x1, y1), floatArrayOf(x2, y2), 0L, safeDuration)
        }
    }

    /**
     * 按可见文本查找并点击。
     *
     * ## assists API 的形参语义（已从源码确认，别写错匹配范围）
     *
     * `findByText(text, filterViewId = null, filterDes = null, filterClass = null, scope = ActiveWindow)`：
     * - 第 1 个 `text` 才是要查找的文本，底层就是平台
     *   `AccessibilityNodeInfo.findAccessibilityNodeInfosByText(text)` —— 与迁移前**同一个匹配引擎**
     *   （**子串**匹配，不是全等；全等匹配另有 `findByTextAllMatch`）；
     * - 第 2/3/4 个是 `filterViewId` / `filterDes` / `filterClass` 三个**可选**过滤条件，
     *   `null` 或空串表示**不过滤**（源码 `isNullOrEmpty()`），三者同时非空时是 **AND**；
     * - 这里三个都传 `null`、scope 显式传 `ActiveWindow`，与迁移前「只用 text、只查活动窗口根」一致。
     *
     * ## 与迁移前的逐条对照
     *
     * 迁移前：`root.findAccessibilityNodeInfosByText(text)` → `.filter { it.isClickable || it.parent != null }`
     * → 优先 `firstOrNull { it.isClickable }` → 否则 `firstOrNull()` 上溯可点击祖先（深度 ≤30）
     * → `performAction(ACTION_CLICK)`；找不到返回 false。
     *
     * 本实现用 assists 的同名能力逐条对齐：
     * - 取节点 = `findByText(text)`（同引擎 / 同子串语义 / 同范围）；
     * - 优先可点击 = `firstOrNull { it.isClickable }`；
     * - 上溯祖先 = 基类的 `findFirstParentClickable()`，语义与旧 `climbToClickable` 一致
     *   （都是「沿 parent 上溯，返回第一个 `isClickable` 的祖先，没有则 null」）；
     * - 点击 = 基类的 `click()` = `performAction(AccessibilityNodeInfo.ACTION_CLICK)`，与旧实现同一调用。
     *
     * `findFirstParentClickable()` 的实现是**递归**（逐层 `parent?.findFirstParentClickable(...)`），
     * 与旧的 while + 深度计数相比没有显式深度上限；但它只在**已命中的叶子节点**上上溯真实的
     * view 层级深度（现实中远小于 30），且递归在第一个可点击祖先处立即返回，风险可忽略，故不再自造一份 while。
     *
     * ## 等价性证明：候选集与迁移前**逐节点等价**
     *
     * 1. **候选集的来源与旧实现同一个平台调用。** `findByText(text)` 解析到 assists 3.5.9 的顶层
     *    `AssistsCore.findByText(text, …, scope = ActiveWindow)`（`AssistsCore.kt:464-481`），
     *    它先 `getAccessibilityRootNodes(scope)`（`AssistsCore.kt:216-233` 的 `ActiveWindow` 分支
     *    = `listOfNotNull(service.rootInActiveWindow)`，与迁移前的 `service.rootInActiveWindow` 同源），
     *    再对每个根走节点级扩展（`AssistsCore.kt:514-530`），而后者的第一行就是
     *    `this?.findAccessibilityNodeInfosByText(text)`（`AssistsCore.kt:521`）—— **与旧实现同一平台调用**。
     *    活动窗口至多一个根、顺序唯一，故候选集来自**同一引擎**
     *    （`findAccessibilityNodeInfosByText` 的**子串**匹配语义）与**同一个根**。
     *    （后一条扩展开头是 `this?.`，理论上 `rootInActiveWindow == null` 时该扩展也不会 NPE；
     *    本方法另由 `isReady()` 挡一层，与旧实现一致。）
     * 2. **三个可选过滤器在全 null 时是内容恒等函数。** 上一条顶层 `findByText` 在收集完各根的命中后
     *    把整个列表交给 `filterNodes(list, ...)`（`AssistsCore.kt:541-555`），其每项条件都写成
     *    `filterX.isNullOrEmpty() || ...`；本方法三个过滤参数全传 `null` ⇒ 整条谓词恒为 true
     *    ⇒ `filterNodes` 返回**元素集合与顺序都与输入逐项相同**的列表（它确实是 `filter` 出来的新实例，
     *    但对「候选集 = 哪些节点、按什么顺序」而言是**恒等变换**）。
     *    故顶层 `findByText(text)` 的候选集 ≡ `root.findAccessibilityNodeInfosByText(text)` 的结果，**逐节点等价**。
     * 3. **候选集过滤已与旧实现逐字对齐。** 旧实现紧随其后的
     *    `.filter { it.isClickable || it.parent != null }` 这里原样补回（见下方实现），
     *    两者是同一条谓词、同一个位置（取候选集之后、选目标之前）。
     * 4. **后续三段也逐一对应。** 优先 `firstOrNull { it.isClickable }`；否则 `firstOrNull()` 走
     *    `findFirstParentClickable()`（`AssistsCore.kt:738-756`，沿 `parent` 上溯到第一个 `isClickable` 的祖先，
     *    没有则 null）；最后 `click()`（`AssistsCore.kt:963-965` = `performAction(ACTION_CLICK)`）。
     *
     * 结论：候选集（来源 + 顺序 + 过滤）与迁移前**逐节点等价**，动作路径同源同调用，
     * **不存在已知的可观察行为差异**。
     *
     * 历史痕迹：本方法的候选集过滤曾是与迁移前的**唯一**差异点（曾保留 `parent == null && !isClickable`
     * 的根节点，导致「根节点恰好命中文本且可点击」时会真的去点根节点、而旧实现返回 false）；
     * 现已按旧实现补齐该过滤，该差异不再存在。
     *
     * 未就绪 / 未命中 / 点击未受理一律返回 false，不抛异常。
     */
    override fun clickText(text: String): Boolean {
        if (!isReady()) return false
        val nodes = runCatching { with(AssistsCore) { findByText(text) } }
            .getOrDefault(emptyList())
            // 补齐迁移前的候选集过滤（见上方等价性证明第 3 条）：assists 的 findByText 没有这一步，
            // 少了它，活动窗口根节点恰好命中文本且 isClickable 时会被真的点掉（旧实现返回 false）。
            .filter { it.isClickable || it.parent != null }
        val direct = nodes.firstOrNull { it.isClickable }
        // 用显式 if/else 而不是 `?:` 链：后者会让 target 的可空性依赖跨行类型推断，
        // 显式分支既避免智能转换的歧义，也把「优先可点击 / 否则上溯祖先」两步写在明面上。
        val target: AccessibilityNodeInfo = if (direct != null) {
            direct
        } else {
            val first = nodes.firstOrNull() ?: return false
            with(AssistsCore) { first.findFirstParentClickable() } ?: return false
        }
        return runCatching { with(AssistsCore) { target.click() } }.getOrDefault(false)
    }

    /**
     * 向**唯一确定**的可编辑节点输入文本 —— 绝不猜输入目标。
     *
     * 目标选择规则（与 [AccessibilityBridge.inputText] 的契约逐字一致）：
     * 1. 优先「当前聚焦 且 `isEditable`」的节点；有多个时取遍历到的第一个（聚焦本身就意味着唯一）；
     * 2. 没有聚焦的可编辑节点时，要求活动窗口内 `isEditable` 节点**恰好 1 个**；
     * 3. **0 个**（没有输入框）或 **≥2 个**（多义）一律返回 false —— 猜错的代价是把内容打进
     *    搜索框 / 密码框 / 聊天框，宁可让工具层回「未找到可编辑的输入框」让用户先点一下。
     *
     * 遍历用 `AssistsCore.getAllNodes(scope = ActiveWindow)`：它是「根 + 全部子孙」的 DFS 收集
     * （10000 节点护栏），正好覆盖活动窗口整棵树。`isEditable` 是平台属性
     * （`AccessibilityNodeInfo.isEditable`），语义比按类名判断（`EditText`）更准 ——
     * WebView / Compose / 自定义控件里的可编辑节点类名未必是 `EditText`。
     *
     * 写入顺序**必须** `focus(node)` → `setNodeText(node, text)`：
     * assists 的 `setNodeText` 只发 `ACTION_SET_TEXT`，**自己不 focus**（源码确认），
     * 目标未聚焦时多数 IME 会直接返回 false；`focus` 则是 `ACTION_FOCUS`。
     * 两者都只在返回 true 时才继续，任何一个失败都返回 false（不吞失败）。
     */
    override fun inputText(text: String): Boolean {
        if (!isReady()) return false
        val editable = runCatching {
            with(AssistsCore) { getAllNodes(scope = NodeLookupScope.ActiveWindow) }
                .filter { it.isEditable }
        }.getOrDefault(emptyList())
        val target = editable.firstOrNull { it.isFocused }
            ?: editable.singleOrNull()
            ?: return false
        return runCatching {
            with(AssistsCore) { target.focus() } && with(AssistsCore) { target.setNodeText(text) }
        }.getOrDefault(false)
    }

    /**
     * 全局动作：返回（`AssistsCore.back()` = `GLOBAL_ACTION_BACK`）。
     * 未就绪时 assists 自己就返回 false（`getOrNull() ?: false`），本类再显式挡一层。
     */
    override fun back(): Boolean {
        if (!isReady()) return false
        return runCatching { with(AssistsCore) { back() } }.getOrDefault(false)
    }

    /**
     * 全局动作：回主屏（`AssistsCore.home()` = `GLOBAL_ACTION_HOME`）。
     * 与 [back] 同构：未就绪 false，不抛异常。
     */
    override fun home(): Boolean {
        if (!isReady()) return false
        return runCatching { with(AssistsCore) { home() } }.getOrDefault(false)
    }

    /** 与迁移前 `tap` 相同的按住时长（毫秒）。 */
    private const val SAFE_GESTURE_MS = 60L
}
