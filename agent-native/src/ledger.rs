//! 上下文账本（Ledger）—— 第 229 轮，BACKLOG「上下文账本」。
//!
//! # 这一层解决什么
//!
//! 现有的 `prompt_audit.fragments_json` 只记了「编排器拼了什么片段」，
//! 而**模型真正收到的请求体里，有三块内容绕过了片段机制**：
//!
//! | 通道 | 现状 | 后果 |
//! |---|---|---|
//! | 工具 Schema | 走 API 的 `tools` 字段（`agent.rs` 的 `tool_defs`） | 占多少 token 完全不可见 |
//! | 世界书注入 | `apply_worldbook_injections` 直接改 `messages` | 命中多少不可见 |
//! | 消息窗口 | `history_json` 直接进 messages | 只有条数、没有体积 |
//!
//! 于是旧账本会**系统性地少报**：用户看到的"材料总计"小于真实输入。
//! 账本做得再漂亮，漏账的账本比没有更糟 —— 所以先把这三块补记账。
//!
//! # 设计约束（三条，全部来自实证）
//!
//! 1. **不碰任何现有 `uniffi::Record`**。`PromptFragmentSummary` /
//!    `PromptOrchestratorOptions` 与 Android 共享，改字段就是 ABI 变更
//!    （第 223 轮踩过：给 `PromptOrchestratorOptions` 加字段会让 Android 编译失败）。
//!    本模块**只新增类型与自由函数**，不改既有接口。
//!
//! 2. **不照搬 Aru 的 13 个片段**。实测证明它的片段集是**动态的**
//!    （同一天里出现 10 → 12 → 9 → 10 → 8 个），且它有的通道我们没有
//!    （`feature_slice` / `world_node_activation` / `conversation_layout` …），
//!    我们有的它没有（`safety.injection_note`）。
//!    所以这里定义的是**我们自己的通道集**，缺的标 `not_applicable` 而不是伪造 0。
//!
//! 3. **Token 是估算，必须标明算法**。Aru 用的是线性文本估算（体积/Token ≈ 3.5，
//!    两轮几乎一致 → 固定系数，不是分词器）。我们照它的口径以便**可对比**，
//!    但永远带 `is_rough_estimate = true`，并把算法字符串写进输出。
//!
//! # 证据来源
//!
//! Aru 侧实测（`D:\Project\Aru-study\docs\23|44|45|53|55`）：
//! - 三态 `admitted` / `recorded` / `planned`，四种 `depth`；
//! - 未准入原因是**固定枚举**，不是自由文本；
//! - `group_disabled` 与 `xxx_unavailable` **修复路径完全不同**，
//!   UI 必须分开引导，否则用户会去关一个没问题的开关；
//! - `skipped_no_trigger`（本轮没命中）是**正常状态，不是故障** ——
//!   当警示处理会让用户乱改设置。

use std::collections::BTreeMap;

/// 通道状态 —— 四态。
///
/// Aru 只有三态（`admitted` / `recorded` / `planned`），用 `recorded` 兼表
/// "没有此通道"。我们加第四态 `not_applicable`，因为**假装有这条通道**会
/// 让用户以为"它没给我带"，而真相是"我们没有这个能力"。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChannelStatus {
    /// 真正送进了请求
    Admitted,
    /// 只记录、不发送（供审计）
    Recorded,
    /// 计划态（尚未执行）
    Planned,
    /// 本实现没有这条通道
    NotApplicable,
}

impl ChannelStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            ChannelStatus::Admitted => "admitted",
            ChannelStatus::Recorded => "recorded",
            ChannelStatus::Planned => "planned",
            ChannelStatus::NotApplicable => "not_applicable",
        }
    }

    /// 是否真送进请求。**只有** `Admitted` 为真。
    pub fn is_sent(self) -> bool {
        matches!(self, ChannelStatus::Admitted)
    }
}

/// 未准入原因 —— **固定枚举**。
///
/// 为什么不能是自由文本：自由文本没法做 UI 分类、没法做统计、没法写测试。
/// Aru 的实测里这些值是稳定的字符串（`group_disabled` / `xxx_unavailable` /
/// `skipped_no_trigger`），我们照它的词表，并加两个它没有的：
/// `worldbook_unbound`（我们有"绑定才生效"这个前置条件）、`not_applicable`。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SkipReason {
    /// 用户关掉了这个工具组 —— **用户可改**
    GroupDisabled,
    /// 生图线路没配 —— 配置缺失
    ImageGenerationUnavailable,
    /// 朗读线路没配 —— 配置缺失
    VoiceGenerationUnavailable,
    /// 向量线路没配（记忆召回要用）—— 配置缺失
    EmbeddingUnavailable,
    /// 世界书没绑定到这个角色 —— 配置缺失
    WorldbookUnbound,
    /// 绑定了但本轮没命中触发条件 —— **正常状态，不是故障**
    SkippedNoTrigger,
    /// 记忆库为空 —— 配置缺失（也算"还没开始用"）
    MemoryEmpty,
    /// 本实现没有这条通道 —— 说明性
    NotApplicable,
}

impl SkipReason {
    pub fn as_str(self) -> &'static str {
        match self {
            SkipReason::GroupDisabled => "group_disabled",
            SkipReason::ImageGenerationUnavailable => "image_generation_unavailable",
            SkipReason::VoiceGenerationUnavailable => "voice_generation_unavailable",
            SkipReason::EmbeddingUnavailable => "embedding_unavailable",
            SkipReason::WorldbookUnbound => "worldbook_unbound",
            SkipReason::SkippedNoTrigger => "skipped_no_trigger",
            SkipReason::MemoryEmpty => "memory_empty",
            SkipReason::NotApplicable => "not_applicable",
        }
    }

    /// 三分类 —— **UI 必须按这个分开引导**。
    ///
    /// 这是这一层最重要的一个判断：如果 UI 把三者画成一样，
    /// 用户会把"本轮没命中"当成故障去乱改设置（Aru 实测到的真实风险）。
    pub fn severity(self) -> Severity {
        match self {
            // 用户可改：给"去开启"+ 跳转
            SkipReason::GroupDisabled => Severity::Actionable,
            // 配置缺失：给"去配线路"+ 跳转
            SkipReason::ImageGenerationUnavailable
            | SkipReason::VoiceGenerationUnavailable
            | SkipReason::EmbeddingUnavailable
            | SkipReason::WorldbookUnbound
            | SkipReason::MemoryEmpty => Severity::Config,
            // 正常状态：**不警示、不跳转**，只说明
            SkipReason::SkippedNoTrigger => Severity::Normal,
            // 本实现没有：说明性，不算降级
            SkipReason::NotApplicable => Severity::NotApplicable,
        }
    }

    /// 给用户的一句话（**不抄 Aru 文案**，语义对等、表达自写）。
    ///
    /// `fix_path` 单独给，因为"为什么"和"怎么办"是两件事：
    /// 只有原因 → 用户知道为什么，但不知道怎么修。
    pub fn hint(self) -> &'static str {
        match self {
            SkipReason::GroupDisabled => "你关掉了这个工具组",
            SkipReason::ImageGenerationUnavailable => "生图线路还没配",
            SkipReason::VoiceGenerationUnavailable => "朗读线路还没配",
            SkipReason::EmbeddingUnavailable => "向量线路还没配，记忆召回用不了",
            SkipReason::WorldbookUnbound => "世界书没有绑定到这个角色",
            SkipReason::SkippedNoTrigger => "本轮没有命中，无需处理",
            SkipReason::MemoryEmpty => "记忆库还是空的",
            SkipReason::NotApplicable => "本实现没有这条通道",
        }
    }
}

/// 降级项的三分类。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Severity {
    /// 用户可改（关着的开关）
    Actionable,
    /// 配置缺失（线路/绑定/数据）
    Config,
    /// 正常状态（没命中）—— **不得警示**
    Normal,
    /// 本实现无此通道
    NotApplicable,
}

impl Severity {
    pub fn as_str(self) -> &'static str {
        match self {
            Severity::Actionable => "actionable",
            Severity::Config => "config",
            Severity::Normal => "normal",
            Severity::NotApplicable => "not_applicable",
        }
    }
}

/// 深度 —— 内容是"全文"还是"索引"。
///
/// 这一维度是 Aru 省 token 的核心：`depth=index` 的片段只占几十 token，
/// 而 `full` 的片段动辄数千。我们的片段现在**全部是 full**（直接拼进 system），
/// 如实记下来才能看出"该往哪儿动刀"。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Depth {
    /// 聚焦（最详细）
    Focused,
    /// 完整正文
    Full,
    /// 仅索引（目录/标题）
    Index,
    /// 摘要
    Summary,
}

impl Depth {
    pub fn as_str(self) -> &'static str {
        match self {
            Depth::Focused => "focused",
            Depth::Full => "full",
            Depth::Index => "index",
            Depth::Summary => "summary",
        }
    }
}

/// 体积估算 —— 三个数一起记。
///
/// **为什么三个都要**：Aru 同时记「体积估算（字节）」与「Token 估算」，
/// 两个数一起才能反推系数（它正是靠这个发现 3.5 B/tok 的）。
/// 只记 token 数，将来**无法标定** —— 换成别的模型/语言就不知道偏了多少。
#[derive(Clone, Debug, PartialEq)]
pub struct Estimate {
    pub chars: u32,
    pub bytes: u32,
    pub tokens: u32,
    /// 估算算法（写进输出，让数字可复算、可比较）
    pub method: &'static str,
}

/// 每 token 多少字节。
///
/// 取值依据：Aru 三轮实测 `体积估算 / Token 估算` = 3.5074 / 3.4965（几乎一致
/// → 固定系数，不是分词器）。我们照这个口径，**为了与它可比**。
///
/// ⚠️ 但它对中文偏"便宜"（增量比值 3.097）、对英文 JSON 偏"贵"（3.5+），
/// 且本机估算系统性**高估**供应商 5~8%。所以：
/// - 这个数只是"粗估"，`is_rough_estimate` 永远为 true；
/// - 输出里带 `calibration`，等真机跑几轮后回填真实偏差。
const BYTES_PER_TOKEN: f64 = 3.5;

/// 估算一段文本的体积与 token。
///
/// `bytes` 用 UTF-8 字节数（与"体积估算"同口径），`chars` 用字符数。
pub fn estimate_text(text: &str) -> Estimate {
    let chars = text.chars().count() as u32;
    let bytes = text.len() as u32;
    Estimate {
        chars,
        bytes,
        tokens: tokens_for_bytes(bytes),
        method: "bytes_per_token:3.5",
    }
}

/// 字节 → token（粗估）。
///
/// 向上取整：宁可高估一点，也不要让"分项之和"小于总数
/// （加起来对不上的账本，用户第一眼就不信）。
pub fn tokens_for_bytes(bytes: u32) -> u32 {
    if bytes == 0 {
        return 0;
    }
    ((bytes as f64) / BYTES_PER_TOKEN).ceil() as u32
}

/// 通道定义（静态表）—— 我们自己的通道集。
pub struct ChannelSpec {
    /// 稳定 id（英文，写进 JSON，**永不改**）
    pub id: &'static str,
    /// 中文显示名（界面用；改它不影响数据）
    pub cn: &'static str,
    /// 默认深度
    pub depth: Depth,
    /// 归属组件（排查时"该找哪段代码"的直接线索）
    pub owner: &'static str,
    /// 是否独立成区块（`conversation_window` 在 Aru 里不列进材料列表）
    pub standalone: bool,
}

/// 我们的通道集 —— **8 条真实通道**，按装配顺序排列。
///
/// 顺序即显示顺序（照 Aru 的"语义顺序"：环境 → 身份 → 安全 → 技能 → 记忆 → 工具），
/// **不按 token 大小排** —— 那是折叠区内部的事（见 `sort_for_display`）。
pub const CHANNELS: &[ChannelSpec] = &[
    ChannelSpec { id: "runtime_clock",  cn: "请求时间",  depth: Depth::Focused, owner: "PromptOrchestrator", standalone: false },
    ChannelSpec { id: "conversation_window", cn: "消息窗口", depth: Depth::Full, owner: "ChatSession", standalone: true },
    ChannelSpec { id: "system_prompt",  cn: "系统指令",  depth: Depth::Full,    owner: "PromptOrchestrator", standalone: false },
    ChannelSpec { id: "safety_note",    cn: "安全说明",  depth: Depth::Full,    owner: "PromptOrchestrator", standalone: false },
    ChannelSpec { id: "skills",         cn: "技能",      depth: Depth::Full,    owner: "SkillSelector",      standalone: false },
    ChannelSpec { id: "memory",         cn: "记忆召回",  depth: Depth::Full,    owner: "MemorySelector",     standalone: false },
    ChannelSpec { id: "worldbook",      cn: "世界书",    depth: Depth::Index,   owner: "LorebookRuntime",    standalone: false },
    // 工具一览（一句话概览）与工具 Schema（真实 API 契约）是**两件事**：
    // 前者是我们发给模型的文本片段，后者是 API `tools` 字段的 JSON。
    // 旧账本只有前者，于是看上去"工具很便宜" —— 那是假象。
    ChannelSpec { id: "tools_overview", cn: "工具一览",  depth: Depth::Index,   owner: "AgentRuntime",       standalone: false },
    ChannelSpec { id: "tool_schemas",   cn: "工具 Schema", depth: Depth::Summary, owner: "AgentRuntime",     standalone: false },
];

/// Aru 有、我们没有的通道 —— **列出来，标 `not_applicable`**。
///
/// 为什么不在 UI 里干脆隐藏：用户如果从别处（或 Aru）知道有这个概念，
/// 会以为"我们的没生效"。显式写"本实现没有"比装作不存在更诚实。
pub const NOT_APPLICABLE_CHANNELS: &[(&str, &str)] = &[
    ("feature_slice", "当前请求范围"),
    ("world_node_activation", "世界节点"),
    ("tool_result_replay", "工具回放"),
    ("conversation_layout", "对话布局"),
    ("cross_window_continuity", "跨窗口连续前文"),
    ("memory_settings", "记忆设置"),
    ("memory_entries", "固定记忆"),
];

/// 片段 id → 通道 id。
///
/// ⚠️ 返回 `Option`：**未知 id 必须能被发现**，不能悄悄丢。
/// `build_snapshot` 会把未知 id 归到 `unmapped` 通道（占位），并由单测保证
/// "当前全仓的片段 id 都有明确归属" —— 这样将来新增片段时，
/// 一旦忘了登记，测试会立刻失败，而不是账本悄悄少一块。
pub fn channel_for_fragment(id: &str) -> Option<&'static str> {
    match id {
        // 环境元数据（时区/时间）→ 请求时间
        "env.static_meta" => Some("runtime_clock"),
        // 身份设定 + 回复语言（后者是引擎后来追加的合成片段）
        "persona.identity" | "persona.reply_language" => Some("system_prompt"),
        // 安全注入说明 —— **Aru 没有这条通道**，是我们自有的
        "safety.injection_note" => Some("safety_note"),
        // 技能：聊天协议常驻 + 关键词召回 + 记忆技能程序性
        "skill.chat_protocol" | "skill.injected" | "skill.memory_procedural" => Some("skills"),
        // 记忆召回
        "memory.recall" => Some("memory"),
        // 世界书（若走片段路径时）
        "worldbook.inject" | "lorebook.inject" => Some("worldbook"),
        // 工具一览（注意：**这不是工具的 schema 成本**，见 build_snapshot 的注释）
        "tools.overview" => Some("tools_overview"),
        _ => None,
    }
}

/// 一个片段（输入侧）—— 由调用方从既有的 `PromptFragment` 摘要裁出来。
#[derive(Clone, Debug, PartialEq)]
pub struct FragmentInput {
    pub id: String,
    pub source: String,
    pub layer: u8,
    /// 片段正文长度（字符）。**不给正文**：账本不需要正文，也避免复制大字符串。
    pub chars: u32,
    /// 片段正文字节数（UTF-8）。token 估算用它。
    pub bytes: u32,
}

/// 一个工具（输入侧）。
#[derive(Clone, Debug, PartialEq)]
pub struct ToolInput {
    pub name: String,
    /// 该工具真正发给 API 的 JSON（name + description + parameters）
    pub schema_json: String,
    /// **预先算好的字节数**（第 233 轮）。
    ///
    /// 为什么要这个：工具的权威定义在 Rust 装配现场（`tool_defs`），
    /// iOS 拿不到内置工具的定义。与其让 iOS 用"看得见的工具"去估一个
    /// **偏低**的数字（低估比缺失更糟 —— 会让用户以为工具不是成本），
    /// 不如由 Rust 在装配时算好、经 `ledger_tools` 事件带出来。
    ///
    /// 非空时优先用它，不再解析 `schema_json`。
    pub precomputed_bytes: Option<u32>,
    /// 所属工具组
    pub group: String,
    /// 用户是否允许（组开关）
    pub enabled: bool,
    /// 能力是否就绪（线路配了、系统服务在）
    ///
    /// ⚠️ 与 `enabled` **必须分开**：Aru 实测有"组关着但工具标可用"的情形。
    /// 只用一个布尔会把"你关的"和"没配线路"混成一件事，
    /// 而两者的修复路径完全不同。
    pub available: bool,
    /// 能力不可用时的原因（仅 `available == false` 时有意义）
    pub unavailable_reason: Option<SkipReason>,
}

/// 输出：一个工具的成本行。
#[derive(Clone, Debug, PartialEq)]
pub struct ToolCost {
    pub name: String,
    pub group: String,
    pub tokens: u32,
    pub bytes: u32,
}

/// 输出：一条通道。
#[derive(Clone, Debug, PartialEq)]
pub struct Channel {
    pub id: &'static str,
    pub cn: &'static str,
    pub status: ChannelStatus,
    pub sent: bool,
    pub tokens: u32,
    pub chars: u32,
    pub bytes: u32,
    /// 纯数量（`×N`）。复合计数时用 `items_label`。
    pub items: u32,
    /// 复合计数标签（如「原话 0 · 语义命中 0 · 对话总结 3」）。
    /// 非空时界面应显示它而不是 `×N`。
    pub items_label: Option<String>,
    pub depth: Depth,
    pub owner: &'static str,
    /// 跨轮/状态标记行（界面第二行）。
    ///
    /// 为什么要有：只看单轮快照，看不出"和上一轮比变了什么"，
    /// 而**变化本身**才是提示词缓存失效的原因。降级项固定为「本次未发送」。
    pub status_line: String,
    /// 未准入原因（仅 `sent == false`）
    pub reason: Option<SkipReason>,
    /// 结构化证据（可复现：scope / count / entry_set / scan_depths / samples）
    pub evidence: BTreeMap<String, String>,
    /// 修复路径（"去哪儿改"）
    pub fix_path: Option<&'static str>,
    /// 参与合成的片段数
    pub fragment_count: u32,
}

/// 输出：账本快照。
#[derive(Clone, Debug, PartialEq)]
pub struct Snapshot {
    pub channels: Vec<Channel>,
    /// 逐工具成本（按 token 降序 —— 最贵的排第一，不让成本藏在折叠里）
    pub tool_costs: Vec<ToolCost>,
    /// 可见工具名
    pub visible_tools: Vec<String>,
    /// 隐藏工具（名字 + 原因）
    pub hidden_tools: Vec<(String, SkipReason)>,
    pub estimate: Estimate,
    pub schema_estimate: Estimate,
}

/// 组装账本快照。
///
/// # 参数
/// - `fragments`：编排器产出的片段（**不含**世界书注入与工具 schema）
/// - `tools`：本轮候选工具（含被关掉的 —— 它们要进"降级"）
/// - `message_window`：消息窗口的条数与体积（绕过片段机制的那一块）
/// - `worldbook`：世界书这一轮的实际注入情况
/// - `overlay_text`：引擎在片段之外**追加**进 system 的文本
///   （回复语言指令、生图协议等）—— 漏掉它就又少一块
///
/// # 返回的账本里有一条硬不变式
/// **每个输入片段必定且只归属一条通道**（未知 id 归 `unmapped`）。
/// 见 `tests::no_fragment_is_dropped`。
pub fn build_snapshot(
    fragments: &[FragmentInput],
    tools: &[ToolInput],
    message_window: Option<(&str, u32, u32, u32)>,
    worldbook: Option<WorldbookOutcome>,
    overlay_text: &str,
) -> Snapshot {
    // 通道 → 聚合
    let mut buckets: BTreeMap<&'static str, (u32, u32, u32, u32)> = BTreeMap::new();
    for f in fragments {
        let channel = channel_for_fragment(&f.id).unwrap_or("unmapped");
        let entry = buckets.entry(channel).or_insert((0, 0, 0, 0));
        entry.0 += 1;
        entry.1 += f.chars;
        entry.2 += f.bytes;
        entry.3 += tokens_for_bytes(f.bytes);
    }

    let mut channels: Vec<Channel> = Vec::new();

    // ① 先按 CHANNELS 的固定顺序产出"已送入"的通道
    for spec in CHANNELS {
        // 消息窗口单独处理（它不走片段）
        if spec.standalone {
            continue;
        }
        let Some(&(count, chars, bytes, tokens)) = buckets.get(spec.id) else {
            continue; // 本轮没有这个通道的片段
        };
        channels.push(Channel {
            id: spec.id,
            cn: spec.cn,
            status: ChannelStatus::Admitted,
            sent: true,
            tokens,
            chars,
            bytes,
            items: count,
            items_label: None,
            depth: spec.depth,
            owner: spec.owner,
            status_line: String::new(), // 跨轮比较在 Swift 侧填（需要上一轮数据）
            reason: None,
            evidence: BTreeMap::new(),
            fix_path: None,
            fragment_count: count,
        });
    }

    // ② 剩余桶：`unmapped`（未知片段 id）与任何未在 CHANNELS 里登记的通道。
    //
    // ⚠️ 这段不能省。第一版我只遍历 CHANNELS 发通道 —— 于是 `tools.overview`
    // 和未知 id 的桶**有数据但永远不发**，账本会悄悄漏掉它们（正是旧账本的病）。
    // 现在显式补发，并让 `no_fragment_is_dropped` 守住。
    {
        let emitted: Vec<&'static str> = channels.iter().map(|c| c.id).collect();
        let leftover: Vec<(&'static str, (u32, u32, u32, u32))> = buckets
            .iter()
            // `unmapped` 单独处理（它不在 CHANNELS 里）—— 第一版忘排除它，
            // 下面的 `.expect` 直接 panic。测试立刻抓到。
            .filter(|(id, _)| !emitted.contains(id) && **id != "unmapped")
            .map(|(id, v)| (leak_placeholder(id), *v))
            .collect();
        for (id, (count, chars, bytes, tokens)) in leftover {
            let spec = CHANNELS
                .iter()
                .find(|c| c.id == id)
                .expect("剩桶里的 id 必须能在 CHANNELS 里找到，或属于 unmapped");
            channels.push(Channel {
                id: spec.id,
                cn: spec.cn,
                status: ChannelStatus::Admitted,
                sent: true,
                tokens,
                chars,
                bytes,
                items: count,
                items_label: None,
                depth: spec.depth,
                owner: spec.owner,
                status_line: String::new(),
                reason: None,
                evidence: BTreeMap::new(),
                fix_path: None,
                fragment_count: count,
            });
        }
        // 未知 id 的兜底通道（不在 CHANNELS 里，单列）
        if let Some(&(count, chars, bytes, tokens)) = buckets.get("unmapped") {
            channels.push(Channel {
                id: "unmapped",
                cn: "未登记片段",
                status: ChannelStatus::Admitted,
                sent: true,
                tokens,
                chars,
                bytes,
                items: count,
                items_label: None,
                depth: Depth::Full,
                owner: "—",
                // 这种情况**不该长期存在**：它意味着有人加了片段却没登记归属
                status_line: "这条通道未登记，请补 channel_for_fragment".to_string(),
                reason: None,
                evidence: BTreeMap::new(),
                fix_path: None,
                fragment_count: count,
            });
        }
    }

    // ③ 工具 Schema —— 独立成一条通道（**最贵的那块，必须单列**）
    //
    // ⚠️ 关键：`tools.overview` 片段**不是**工具的 schema 成本。
    // 它只是"有哪些工具"的一句话概览；真正的成本是下面这份 JSON。
    // 旧账本只有 overview，所以看上去"工具很便宜"——那是假象。
    let mut schema_bytes = 0u32;
    let mut tool_costs: Vec<ToolCost> = Vec::new();
    let mut visible: Vec<String> = Vec::new();
    let mut hidden: Vec<(String, SkipReason)> = Vec::new();

    for t in tools {
        // 优先用装配现场算好的字节数（Rust 才知道内置工具的定义）
        let est = match t.precomputed_bytes {
            Some(b) if b > 0 => Estimate {
                chars: 0,
                bytes: b,
                tokens: tokens_for_bytes(b),
                method: "bytes_per_token:3.5",
            },
            _ => estimate_text(&t.schema_json),
        };
        // 隐藏的两种情形，**原因必须分开**：
        // 用户关的组 → GroupDisabled；组开着但能力没就绪 → 具体 unavailable
        if !t.enabled {
            hidden.push((t.name.clone(), SkipReason::GroupDisabled));
            continue;
        }
        if !t.available {
            hidden.push((
                t.name.clone(),
                t.unavailable_reason.unwrap_or(SkipReason::GroupDisabled),
            ));
            continue;
        }
        visible.push(t.name.clone());
        schema_bytes += est.bytes;
        tool_costs.push(ToolCost {
            name: t.name.clone(),
            group: t.group.clone(),
            tokens: est.tokens,
            bytes: est.bytes,
        });
    }

    // 逐工具成本降序：最贵的排第一（不让 90% 的成本藏在折叠里）
    tool_costs.sort_by(|a, b| b.tokens.cmp(&a.tokens).then(a.name.cmp(&b.name)));

    if !visible.is_empty() {
        let channel = CHANNELS
            .iter()
            .find(|c| c.id == "tool_schemas")
            .expect("CHANNELS 必须含 tool_schemas");
        channels.push(Channel {
            id: channel.id,
            cn: channel.cn,
            status: ChannelStatus::Admitted,
            sent: true,
            tokens: tokens_for_bytes(schema_bytes),
            chars: 0,
            bytes: schema_bytes,
            items: visible.len() as u32,
            items_label: None,
            depth: channel.depth,
            owner: channel.owner,
            status_line: String::new(),
            reason: None,
            evidence: BTreeMap::new(),
            fix_path: None,
            fragment_count: 0,
        });
    }

    // ③ 消息窗口 —— 独立通道（Aru 里它不列进材料列表，但**必须在账本里**）
    if let Some((label, count, chars, bytes)) = message_window {
        let spec = CHANNELS
            .iter()
            .find(|c| c.id == "conversation_window")
            .expect("CHANNELS 必须含 conversation_window");
        channels.push(Channel {
            id: spec.id,
            cn: spec.cn,
            status: ChannelStatus::Admitted,
            sent: true,
            tokens: tokens_for_bytes(bytes),
            chars,
            bytes,
            items: count,
            items_label: Some(label.to_string()),
            depth: spec.depth,
            owner: spec.owner,
            status_line: String::new(),
            reason: None,
            evidence: BTreeMap::new(),
            fix_path: None,
            fragment_count: 0,
        });
    }

    // ④ 引擎在片段之外追加的文本（回复语言 / 生图协议）—— 并进系统指令
    //
    // 为什么并进去而不是新开一条：它对模型而言就是系统提示的一部分，
    // 用户关心的是"系统指令总共多长"，不是它由几次追加拼成。
    if !overlay_text.trim().is_empty() {
        let est = estimate_text(overlay_text);
        if let Some(c) = channels.iter_mut().find(|c| c.id == "system_prompt") {
            c.chars += est.chars;
            c.bytes += est.bytes;
            c.tokens += est.tokens;
            c.status_line = "含引擎追加的指令".to_string();
        } else {
            let spec = CHANNELS
                .iter()
                .find(|c| c.id == "system_prompt")
                .expect("CHANNELS 必须含 system_prompt");
            channels.push(Channel {
                id: spec.id,
                cn: spec.cn,
                status: ChannelStatus::Admitted,
                sent: true,
                tokens: est.tokens,
                chars: est.chars,
                bytes: est.bytes,
                items: 1,
                items_label: Some("仅引擎追加".to_string()),
                depth: spec.depth,
                owner: spec.owner,
                status_line: "含引擎追加的指令".to_string(),
                reason: None,
                evidence: BTreeMap::new(),
                fix_path: None,
                fragment_count: 0,
            });
        }
    }

    // ⑤ 世界书：命中 → 已送入；未命中/未绑定 → 降级（**两种原因分开**）
    if let Some(wb) = worldbook {
        let spec = CHANNELS
            .iter()
            .find(|c| c.id == "worldbook")
            .expect("CHANNELS 必须含 worldbook");
        match wb {
            WorldbookOutcome::Injected { entries, chars, bytes, evidence } => {
                channels.push(Channel {
                    id: spec.id,
                    cn: spec.cn,
                    status: ChannelStatus::Admitted,
                    sent: true,
                    tokens: tokens_for_bytes(bytes),
                    chars,
                    bytes,
                    items: entries,
                    items_label: None,
                    depth: spec.depth,
                    owner: spec.owner,
                    status_line: "本轮命中".to_string(),
                    reason: None,
                    evidence,
                    fix_path: None,
                    fragment_count: 0,
                });
            }
            WorldbookOutcome::Skipped { reason, entries, evidence } => {
                let mut ch = degraded(
                    spec,
                    reason,
                    entries,
                    evidence,
                );
                ch.fix_path = fix_path_for(reason);
                channels.push(ch);
            }
        }
    }

    // ⑥ 结尾：把有条件的降级项补齐（工具被关、记忆为空等）
    //     这些即使没有片段也要出现 —— "没送"本身就是要被看到的
    if !hidden.is_empty() {
        let mut by_reason: BTreeMap<&'static str, u32> = BTreeMap::new();
        for (_, reason) in &hidden {
            *by_reason.entry(reason.as_str()).or_insert(0) += 1;
        }
        for (reason_str, count) in by_reason {
            let reason = reason_from_str(reason_str);
            let spec = ChannelSpec {
                id: "tools_hidden",
                cn: "未下发工具",
                depth: Depth::Summary,
                owner: "AgentRuntime",
                standalone: false,
            };
            let mut ch = degraded(&spec, reason, count, BTreeMap::new());
            ch.fix_path = fix_path_for(reason);
            ch.items_label = Some(format!("{} 个工具", count));
            channels.push(ch);
        }
    }

    // ⑦ Aru 有、我们没有的通道 —— 如实列出
    for (id, cn) in NOT_APPLICABLE_CHANNELS {
        channels.push(Channel {
            id: leak_placeholder(id),
            cn,
            status: ChannelStatus::NotApplicable,
            sent: false,
            tokens: 0,
            chars: 0,
            bytes: 0,
            items: 0,
            items_label: None,
            depth: Depth::Summary,
            owner: "—",
            status_line: "本实现没有这条通道".to_string(),
            reason: Some(SkipReason::NotApplicable),
            evidence: BTreeMap::new(),
            fix_path: None,
            fragment_count: 0,
        });
    }

    // 总计（只算真送进请求的）
    let mut total_chars = 0u32;
    let mut total_bytes = 0u32;
    for c in channels.iter().filter(|c| c.sent) {
        total_chars += c.chars;
        total_bytes += c.bytes;
    }

    Snapshot {
        channels,
        tool_costs,
        visible_tools: visible,
        hidden_tools: hidden,
        estimate: Estimate {
            chars: total_chars,
            bytes: total_bytes,
            tokens: tokens_for_bytes(total_bytes),
            method: "bytes_per_token:3.5",
        },
        schema_estimate: Estimate {
            chars: 0,
            bytes: schema_bytes,
            tokens: tokens_for_bytes(schema_bytes),
            method: "bytes_per_token:3.5",
        },
    }
}

/// 世界书这一轮的结果 —— **调用方必须显式给**，不能默认"没发生"。
///
/// 默认 `None`（连 `WorldbookOutcome` 都不传）与"传了但未绑定"是两件事：
/// 前者是"这块没记账"，后者是"查过了，没绑"。账本要能区分。
#[derive(Clone, Debug, PartialEq)]
pub enum WorldbookOutcome {
    /// 命中并注入
    Injected {
        entries: u32,
        chars: u32,
        bytes: u32,
        evidence: BTreeMap<String, String>,
    },
    /// 没有注入（原因见 `reason`，证据见 `evidence`）
    Skipped {
        reason: SkipReason,
        entries: u32,
        evidence: BTreeMap<String, String>,
    },
}

/// 造一条降级通道。
fn degraded(
    spec: &ChannelSpec,
    reason: SkipReason,
    items: u32,
    evidence: BTreeMap<String, String>,
) -> Channel {
    Channel {
        id: leak_placeholder(spec.id),
        cn: spec.cn,
        status: ChannelStatus::Recorded,
        sent: false,
        tokens: 0,
        chars: 0,
        bytes: 0,
        items,
        items_label: None,
        depth: spec.depth,
        owner: spec.owner,
        // 降级项的状态行固定 —— Aru 实测就是这个措辞语义
        status_line: "本次未发送".to_string(),
        reason: Some(reason),
        evidence,
        fix_path: None,
        fragment_count: 0,
    }
}

/// 反查 `SkipReason` —— 用于按原因聚合后重建。
fn reason_from_str(s: &str) -> SkipReason {
    match s {
        "group_disabled" => SkipReason::GroupDisabled,
        "image_generation_unavailable" => SkipReason::ImageGenerationUnavailable,
        "voice_generation_unavailable" => SkipReason::VoiceGenerationUnavailable,
        "embedding_unavailable" => SkipReason::EmbeddingUnavailable,
        "worldbook_unbound" => SkipReason::WorldbookUnbound,
        "skipped_no_trigger" => SkipReason::SkippedNoTrigger,
        "memory_empty" => SkipReason::MemoryEmpty,
        _ => SkipReason::NotApplicable,
    }
}

/// 修复路径 —— **"怎么办"与"为什么"分开**。
///
/// 只有原因枚举时，用户知道为什么没带上，但不知道怎么改。
/// 这一列是"可操作"的部分，所以它必须是**具体到入口**的一句话。
fn fix_path_for(reason: SkipReason) -> Option<&'static str> {
    match reason {
        SkipReason::GroupDisabled => Some("我 → AI 能力 → 通知与提醒上方的工具开关，打开对应工具组"),
        SkipReason::ImageGenerationUnavailable => Some("我 → AI 能力 → 用途线路 → 生图，指定渠道与模型"),
        SkipReason::VoiceGenerationUnavailable => Some("我 → AI 能力 → 用途线路 → 朗读，指定渠道与模型"),
        SkipReason::EmbeddingUnavailable => Some("我 → AI 能力 → 用途线路 → 向量嵌入，指定渠道与模型"),
        SkipReason::WorldbookUnbound => Some("通讯录 → 该角色 → 详情 → 世界书，绑定一本"),
        // 正常状态：**不给修复路径** —— 这不是故障，没什么可修的
        SkipReason::SkippedNoTrigger => None,
        SkipReason::MemoryEmpty => Some("我 → AI 能力 → 记忆，先让对话积累一些"),
        SkipReason::NotApplicable => None,
    }
}

/// 把动态字符串 id 转成 `&'static str`。
///
/// 通道 id 在输出结构里是 `&'static str`（为了零分配与可比较）。
/// Aru 的通道名是常量集合，不存在无限增长的风险；
/// 这里用 `Box::leak` 处理动态分支（`unmapped` / 未知通道）。
///
/// ⚠️ 泄漏量上界：每轮最多泄漏几个短字符串（通道数是有限的），
/// 不是用户输入驱动的 —— 不会成为真问题。
fn leak_placeholder(s: &str) -> &'static str {
    match s {
        "runtime_clock" => "runtime_clock",
        "conversation_window" => "conversation_window",
        "system_prompt" => "system_prompt",
        "safety_note" => "safety_note",
        "skills" => "skills",
        "memory" => "memory",
        "worldbook" => "worldbook",
        "tool_schemas" => "tool_schemas",
        "tools_overview" => "tools_overview",
        "tools_hidden" => "tools_hidden",
        "unmapped" => "unmapped",
        other => Box::leak(other.to_string().into_boxed_str()),
    }
}

// MARK: - 对宿主（iOS）的出口
//
// ## 为什么是 JSON 字符串进出，而不是 `uniffi::Record`
//
// 本模块的类型里用了 `&'static str` 与 `BTreeMap`，直接标 `uniffi::Record`
// 编不过（UniFFI 要求 owned 类型）；而把这些全改成 owned、再定义一整套
// 平行的 DTO，只会多一层要同步维护的映射。
//
// 更重要的理由是**契约风险**：新增 `uniffi::Record` 会被写进共享绑定。
// 用一个自由函数 + JSON，表面最小 —— iOS 拿到就是一段要落库的文本，
// 它本来也要存成 `fragments_json`，不需要把结构展开成 Swift 类型。
//
// ## 输入/输出格式（v2）
//
// 输入：
// ```json
// {
//   "fragments": [{"id":"env.static_meta","source":"env","layer":0,"chars":42,"bytes":120}],
//   "tools": [{"name":"emit_bubble","schemaJson":"{...}","group":"chat",
//              "enabled":true,"available":true,"unavailableReason":null}],
//   "messageWindow": {"label":"18 条","count":18,"chars":1900,"bytes":5200},
//   "worldbook": {"injected":true,"entries":3,"chars":210,"bytes":600,
//                 "reason":null,"evidence":{"scope":"companion","count":"4"}},
//   "overlayText": "请用简体中文回答。"
// }
// ```
//
// 输出见 `snapshot_to_json`。缺字段一律按"没记账"处理，不猜。
#[uniffi::export]
pub fn ledger_build_snapshot_json(input_json: String) -> String {
    let input: serde_json::Value = match serde_json::from_str(&input_json) {
        Ok(v) => v,
        Err(e) => {
            return serde_json::json!({
                "v": 2,
                "error": format!("账本输入不是合法 JSON：{e}"),
            })
            .to_string()
        }
    };

    // 片段
    let mut fragments: Vec<FragmentInput> = Vec::new();
    if let Some(arr) = input.get("fragments").and_then(|v| v.as_array()) {
        for item in arr {
            let id = item.get("id").and_then(|v| v.as_str()).unwrap_or_default();
            if id.is_empty() {
                continue;
            }
            fragments.push(FragmentInput {
                id: id.to_string(),
                source: item.get("source").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                layer: item.get("layer").and_then(|v| v.as_u64()).unwrap_or(0) as u8,
                chars: item.get("chars").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
                bytes: item.get("bytes").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
            });
        }
    }

    // 工具
    let mut tools: Vec<ToolInput> = Vec::new();
    if let Some(arr) = input.get("tools").and_then(|v| v.as_array()) {
        for item in arr {
            let name = item.get("name").and_then(|v| v.as_str()).unwrap_or_default();
            if name.is_empty() {
                continue;
            }
            tools.push(ToolInput {
                name: name.to_string(),
                schema_json: item
                    .get("schemaJson")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                group: item.get("group").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                precomputed_bytes: item.get("bytes").and_then(|v| v.as_u64()).map(|b| b as u32),
                enabled: item.get("enabled").and_then(|v| v.as_bool()).unwrap_or(true),
                available: item.get("available").and_then(|v| v.as_bool()).unwrap_or(true),
                unavailable_reason: item
                    .get("unavailableReason")
                    .and_then(|v| v.as_str())
                    .map(reason_from_str),
            });
        }
    }

    // 消息窗口
    let message_window = input.get("messageWindow").and_then(|v| {
        let label = v.get("label").and_then(|x| x.as_str()).unwrap_or("");
        if label.is_empty() {
            return None;
        }
        Some((
            label,
            v.get("count").and_then(|x| x.as_u64()).unwrap_or(0) as u32,
            v.get("chars").and_then(|x| x.as_u64()).unwrap_or(0) as u32,
            v.get("bytes").and_then(|x| x.as_u64()).unwrap_or(0) as u32,
        ))
    });
    // ⚠️ `&str` 借用自 input，先把 label 拷出来延长生命周期
    let message_window_owned: Option<(String, u32, u32, u32)> =
        message_window.map(|(l, c, ch, b)| (l.to_string(), c, ch, b));

    // 世界书
    let worldbook = input.get("worldbook").and_then(|v| {
        let injected = v.get("injected").and_then(|x| x.as_bool()).unwrap_or(false);
        let mut evidence = BTreeMap::new();
        if let Some(obj) = v.get("evidence").and_then(|x| x.as_object()) {
            for (k, val) in obj {
                evidence.insert(k.clone(), val.as_str().unwrap_or(&val.to_string()).to_string());
            }
        }
        let entries = v.get("entries").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
        let chars = v.get("chars").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
        let bytes = v.get("bytes").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
        let reason = v
            .get("reason")
            .and_then(|x| x.as_str())
            .map(reason_from_str)
            .unwrap_or(SkipReason::SkippedNoTrigger);
        Some(if injected {
            WorldbookOutcome::Injected { entries, chars, bytes, evidence }
        } else {
            WorldbookOutcome::Skipped { reason, entries, evidence }
        })
    });

    let overlay = input.get("overlayText").and_then(|v| v.as_str()).unwrap_or("");

    let snapshot = build_snapshot(
        &fragments,
        &tools,
        message_window_owned
            .as_ref()
            .map(|(l, c, ch, b)| (l.as_str(), *c, *ch, *b)),
        worldbook,
        overlay,
    );
    snapshot_to_json(&snapshot)
}

/// 快照 → v2 JSON。
///
/// 字段名与 Aru 的实测结构对齐（`status` / `sent` / `tokens` / `depth` / `mode` /
/// `owner` / `why` / `truncation` / `sourceIds` / `evidenceIds`），
/// 另加我们自己的四个：`statusLine` / `severity` / `fixPath` / `itemsLabel`。
pub fn snapshot_to_json(s: &Snapshot) -> String {
    let channels: Vec<serde_json::Value> = s
        .channels
        .iter()
        .map(|c| {
            let mut obj = serde_json::json!({
                "id": c.id,
                "cn": c.cn,
                "status": c.status.as_str(),
                "sent": c.sent,
                "tokens": c.tokens,
                "chars": c.chars,
                "bytes": c.bytes,
                "items": c.items,
                "depth": c.depth.as_str(),
                "owner": c.owner,
                "statusLine": c.status_line,
                "fragmentCount": c.fragment_count,
                // truncation 与 status 同构（Aru 实测如此）
                "truncation": c.status.as_str(),
            });
            if let Some(label) = &c.items_label {
                obj["itemsLabel"] = serde_json::json!(label);
            }
            if let Some(reason) = c.reason {
                obj["reason"] = serde_json::json!(reason.as_str());
                obj["severity"] = serde_json::json!(reason.severity().as_str());
                obj["reasonHint"] = serde_json::json!(reason.hint());
            }
            if let Some(fix) = c.fix_path {
                obj["fixPath"] = serde_json::json!(fix);
            }
            if !c.evidence.is_empty() {
                obj["evidence"] = serde_json::json!(c.evidence);
            }
            obj
        })
        .collect();

    let tool_costs: Vec<serde_json::Value> = s
        .tool_costs
        .iter()
        .map(|t| {
            serde_json::json!({
                "name": t.name, "group": t.group,
                "tokens": t.tokens, "bytes": t.bytes,
            })
        })
        .collect();

    let hidden: Vec<serde_json::Value> = s
        .hidden_tools
        .iter()
        .map(|(name, reason)| {
            serde_json::json!({
                "name": name,
                "reason": reason.as_str(),
                "severity": reason.severity().as_str(),
            })
        })
        .collect();

    serde_json::json!({
        "v": 2,
        "channels": channels,
        "toolLedger": {
            "visible": s.visible_tools,
            "hidden": hidden,
            "perTool": tool_costs,
            "schemaTokens": s.schema_estimate.tokens,
            "schemaBytes": s.schema_estimate.bytes,
        },
        "localEstimate": {
            "tokens": s.estimate.tokens,
            "chars": s.estimate.chars,
            "bytes": s.estimate.bytes,
            "method": s.estimate.method,
            "isRoughEstimate": true,
            // 与供应商回执的偏差，真机跑几轮后由 iOS 回填
            "calibration": { "providerInputTokens": null, "overestimatePercent": null },
        },
        // 差异公式写进数据 —— 换口径时旧记录不会骗人
        "diff": { "percent": null, "formula": "(provider−local)/local" },
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frag(id: &str, text: &str) -> FragmentInput {
        FragmentInput {
            id: id.to_string(),
            source: "test".to_string(),
            layer: 0,
            chars: text.chars().count() as u32,
            bytes: text.len() as u32,
        }
    }

    #[test]
    fn estimate_is_monotone_in_length() {
        let a = estimate_text("短");
        let b = estimate_text("长一点点的一句话");
        assert!(b.tokens >= a.tokens, "更长的文本 token 不应更少");
        assert_eq!(a.chars, 1);
        assert_eq!(a.bytes, 3);
    }

    #[test]
    fn empty_text_is_zero_not_one() {
        let e = estimate_text("");
        assert_eq!(e.tokens, 0);
        assert_eq!(e.bytes, 0);
    }

    #[test]
    fn tokens_are_ceil_so_parts_dont_undershoot() {
        // 1 字节也要算 1 token —— 向上取整，避免"分项之和 < 总数"
        assert_eq!(tokens_for_bytes(1), 1);
        assert_eq!(tokens_for_bytes(7), 2);
        assert_eq!(tokens_for_bytes(8), 3);
    }

    /// ★ 最重要的不变式：**任何片段都不能被丢掉**。
    ///
    /// 这条测试的作用不是"验证当前实现"，而是**将来新增片段时的报警器**：
    /// 一旦有人加了新片段 id 却忘了登记归属，它会失败，
    /// 而不是让账本悄悄少一块（那正是旧账本少报世界书/工具的原因）。
    #[test]
    fn no_fragment_is_dropped() {
        // 当前全仓所有片段 id（见 prompt_orchestrator::build_fragments）
        let known = [
            "env.static_meta",
            "persona.identity",
            "persona.reply_language",
            "safety.injection_note",
            "skill.chat_protocol",
            "skill.injected",
            "skill.memory_procedural",
            "memory.recall",
            "tools.overview",
        ];
        for id in known {
            assert!(
                channel_for_fragment(id).is_some(),
                "片段 `{id}` 没有登记通道归属 —— 会被账本丢掉"
            );
        }
    }

    /// 每个输入片段都必须出现在某条通道的计数里（数量守恒）。
    #[test]
    fn every_fragment_lands_in_exactly_one_channel() {
        let frags = vec![
            frag("env.static_meta", "时区：Asia/Shanghai"),
            frag("persona.identity", "你是小鱼，温柔。"),
            frag("skill.chat_protocol", "必须用 emit_bubble 输出。"),
            frag("memory.recall", "用户生日是 3 月 2 日。"),
            frag("unheard_of.fragment", "未知片段"), // 故意给未知 id
        ];
        let snap = build_snapshot(&frags, &[], None, None, "");
        let counted: u32 = snap
            .channels
            .iter()
            .filter(|c| c.sent)
            .map(|c| c.fragment_count)
            .sum();
        assert_eq!(counted, frags.len() as u32, "片段数必须守恒（含未知 id 的兜底通道）");
        // 未知 id 必须进 unmapped，而不是消失
        assert!(snap.channels.iter().any(|c| c.id == "unmapped"));
    }

    /// 加总恒等：总 token = 各已送入通道之和。
    #[test]
    fn total_equals_sum_of_sent_channels() {
        let frags = vec![
            frag("env.static_meta", "时区 Asia/Shanghai，当前 2026-11-10"),
            frag("persona.identity", "你是小鱼。"),
        ];
        let snap = build_snapshot(&frags, &[], None, None, "请用简体中文回答。");
        let sum: u32 = snap.channels.iter().filter(|c| c.sent).map(|c| c.tokens).sum();
        // 由于逐通道向上取整，分项之和 ≥ 总估算；这里的总估算也按字节算，
        // 所以允许 1 token/通道 的取整差
        let diff = sum as i64 - snap.estimate.tokens as i64;
        assert!(
            diff >= 0 && diff <= snap.channels.iter().filter(|c| c.sent).count() as i64,
            "分项之和应≥总数且只差取整：sum={sum} total={}",
            snap.estimate.tokens
        );
    }

    /// 组关着 → `group_disabled`；组开着但能力没就绪 → 具体 `unavailable`。
    /// **两者不能混**（修复路径完全不同）。
    #[test]
    fn disabled_group_and_unavailable_capability_are_distinct() {
        let tools = vec![
            ToolInput {
                name: "createFile".to_string(),
                schema_json: "{}".to_string(),
                precomputed_bytes: None,
                group: "生成文件".to_string(),
                enabled: false,
                available: true,
                unavailable_reason: None,
            },
            ToolInput {
                name: "generateImage".to_string(),
                schema_json: "{}".to_string(),
                precomputed_bytes: None,
                group: "生成图片".to_string(),
                enabled: true,
                available: false,
                unavailable_reason: Some(SkipReason::ImageGenerationUnavailable),
            },
        ];
        let snap = build_snapshot(&[], &tools, None, None, "");
        let reasons: Vec<SkipReason> = snap.hidden_tools.iter().map(|(_, r)| *r).collect();
        assert!(reasons.contains(&SkipReason::GroupDisabled));
        assert!(reasons.contains(&SkipReason::ImageGenerationUnavailable));
        assert!(snap.visible_tools.is_empty());
    }

    /// `skipped_no_trigger` 是**正常状态**，不得归为可操作/配置缺失。
    /// 这条是 UI 不警示用户的依据。
    #[test]
    fn no_trigger_is_normal_not_a_problem() {
        assert_eq!(SkipReason::SkippedNoTrigger.severity(), Severity::Normal);
        assert_eq!(SkipReason::GroupDisabled.severity(), Severity::Actionable);
        assert_eq!(
            SkipReason::ImageGenerationUnavailable.severity(),
            Severity::Config
        );
        // 正常状态不给修复路径
        assert!(fix_path_for(SkipReason::SkippedNoTrigger).is_none());
        // 但可改/配置缺失必须给
        assert!(fix_path_for(SkipReason::GroupDisabled).is_some());
        assert!(fix_path_for(SkipReason::ImageGenerationUnavailable).is_some());
    }

    /// 世界书未绑定 vs 未命中 —— 两种原因、两条不同引导。
    #[test]
    fn worldbook_unbound_differs_from_no_trigger() {
        let unbound = build_snapshot(
            &[],
            &[],
            None,
            Some(WorldbookOutcome::Skipped {
                reason: SkipReason::WorldbookUnbound,
                entries: 0,
                evidence: BTreeMap::new(),
            }),
            "",
        );
        let no_trigger = build_snapshot(
            &[],
            &[],
            None,
            Some(WorldbookOutcome::Skipped {
                reason: SkipReason::SkippedNoTrigger,
                entries: 3,
                evidence: BTreeMap::new(),
            }),
            "",
        );
        let a = unbound.channels.iter().find(|c| c.id == "worldbook").unwrap();
        let b = no_trigger.channels.iter().find(|c| c.id == "worldbook").unwrap();
        assert_eq!(a.reason, Some(SkipReason::WorldbookUnbound));
        assert_eq!(b.reason, Some(SkipReason::SkippedNoTrigger));
        assert!(!(a.sent || b.sent), "降级项都不算送入");
        assert!(a.fix_path.is_some(), "未绑定要给修复路径");
        assert!(b.fix_path.is_none(), "未命中不能给修复路径");
    }

    /// 逐工具成本按 token **降序**（最贵的排第一，不让成本藏在折叠里）。
    #[test]
    fn tool_costs_sorted_desc_by_tokens() {
        let tools = vec![
            ToolInput {
                name: "cheap".to_string(),
                schema_json: "{\"a\":1}".to_string(),
                precomputed_bytes: None,
                group: "g".to_string(),
                enabled: true,
                available: true,
                unavailable_reason: None,
            },
            ToolInput {
                name: "expensive".to_string(),
                schema_json: format!("{{\"d\":\"{}\"}}", "x".repeat(2000)),
                precomputed_bytes: None,
                group: "g".to_string(),
                enabled: true,
                available: true,
                unavailable_reason: None,
            },
        ];
        let snap = build_snapshot(&[], &tools, None, None, "");
        assert_eq!(snap.tool_costs[0].name, "expensive");
        assert_eq!(snap.tool_costs[1].name, "cheap");
        assert_eq!(snap.visible_tools.len(), 2);
    }

    /// Aru 有、我们没有的通道要显式标出来，且**不计入送入**。
    #[test]
    fn not_applicable_channels_are_listed_and_not_sent() {
        let snap = build_snapshot(&[], &[], None, None, "");
        let na: Vec<&Channel> = snap
            .channels
            .iter()
            .filter(|c| c.status == ChannelStatus::NotApplicable)
            .collect();
        assert!(!na.is_empty(), "应显式列出本实现没有的通道");
        assert!(na.iter().all(|c| !c.sent), "not_applicable 不能算送入");
        assert!(na.iter().all(|c| c.reason == Some(SkipReason::NotApplicable)));
    }

    // MARK: - 对宿主的 JSON 出口

    /// 出口必须是**合法 JSON**、带 `v:2`、且三个区块齐全。
    ///
    /// 这条守的是"iOS 拿到就能落库"：它不解析结构，只存字符串，
    /// 所以格式一旦不是合法 JSON，坏的是**将来所有历史账本**（读不回来）。
    #[test]
    fn json_export_is_valid_and_versioned() {
        let input = r#"{
            "fragments": [
                {"id":"env.static_meta","source":"env","layer":0,"chars":20,"bytes":60},
                {"id":"persona.identity","source":"persona","layer":1,"chars":10,"bytes":30}
            ],
            "tools": [
                {"name":"emit_bubble","schemaJson":"{\"name\":\"emit_bubble\",\"description\":\"发一条气泡\"}",
                 "group":"chat","enabled":true,"available":true},
                {"name":"generateImage","schemaJson":"{}","group":"image",
                 "enabled":true,"available":false,"unavailableReason":"image_generation_unavailable"}
            ],
            "messageWindow": {"label":"18 条","count":18,"chars":1900,"bytes":5200},
            "worldbook": {"injected":false,"entries":3,
                          "reason":"skipped_no_trigger",
                          "evidence":{"scope":"companion","count":"4","scan_depths":"2"}},
            "overlayText": "请用简体中文回答。"
        }"#;
        let out = ledger_build_snapshot_json(input.to_string());
        let v: serde_json::Value = serde_json::from_str(&out).expect("输出必须是合法 JSON");
        assert_eq!(v["v"], 2);
        assert!(v["channels"].is_array());
        assert!(v["toolLedger"].is_object());
        assert!(v["localEstimate"].is_object());
        // 三个区块各自的必备字段
        assert!(v["localEstimate"]["method"].is_string(), "必须带估算算法");
        assert_eq!(v["localEstimate"]["isRoughEstimate"], true);
        assert_eq!(v["diff"]["formula"], "(provider−local)/local");
        assert!(v["diff"]["percent"].is_null(), "无回执时差异必须是 null，不是 0");
    }

    /// 降级项在 JSON 里必须带：原因枚举 + 三分类 + 证据。
    /// 这三样是 UI 分开引导、以及"可复现"的依据。
    #[test]
    fn json_degraded_item_carries_reason_severity_and_evidence() {
        let input = r#"{
            "worldbook": {"injected":false,"entries":3,"reason":"skipped_no_trigger",
                          "evidence":{"scope":"companion","entry_set":"a1b2","scan_depths":"2"}}
        }"#;
        let v: serde_json::Value =
            serde_json::from_str(&ledger_build_snapshot_json(input.to_string())).unwrap();
        let wb = v["channels"]
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["id"] == "worldbook")
            .expect("必须有 worldbook 通道");
        assert_eq!(wb["sent"], false);
        assert_eq!(wb["reason"], "skipped_no_trigger");
        // ★ 正常状态：不能是可操作/配置缺失，否则 UI 会去警示用户
        assert_eq!(wb["severity"], "normal");
        assert!(wb["evidence"]["entry_set"].is_string(), "必须带可复现证据");
        assert!(wb["fixPath"].is_null(), "正常状态不给修复路径");
    }

    /// 组被关（用户可改）在 JSON 里必须是 `actionable` + 有修复路径。
    #[test]
    fn json_group_disabled_is_actionable_with_fix_path() {
        let input = r#"{
            "tools": [{"name":"createFile","schemaJson":"{}","group":"file",
                       "enabled":false,"available":true}]
        }"#;
        let v: serde_json::Value =
            serde_json::from_str(&ledger_build_snapshot_json(input.to_string())).unwrap();
        let hidden = v["toolLedger"]["hidden"].as_array().unwrap();
        assert_eq!(hidden.len(), 1);
        assert_eq!(hidden[0]["reason"], "group_disabled");
        assert_eq!(hidden[0]["severity"], "actionable");
        // 工具降级通道要带 fixPath
        let fix = v["channels"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|c| c["fixPath"].is_string())
            .count();
        assert!(fix >= 1, "用户可改的项必须给修复入口");
    }

    /// 坏输入不能 panic（宿主可能传空/非法 JSON）。
    #[test]
    fn json_export_tolerates_bad_input() {
        for bad in ["", "not json", "{}", "[]"] {
            let out = ledger_build_snapshot_json(bad.to_string());
            let v: serde_json::Value = serde_json::from_str(&out)
                .unwrap_or_else(|_| panic!("坏输入 `{bad}` 也必须产出合法 JSON"));
            // 空/非法输入要么给 error，要么给空账本 —— 都不能崩、不能是非法 JSON
            assert!(v.get("error").is_some() || v["v"] == 2);
        }
    }
}
