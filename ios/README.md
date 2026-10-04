# 予念 iOS 端

> ## 第 99 轮：阶段性总账（数字由 verify_readme_accounting.py 把关，勿手改后不同步）
>
> **交付**：104 文件 / 25,551 行（Swift 58 / Python 36），
> 14 个测试文件，**33 项本地验证关卡全绿**（`run_all_gates.py` 一键驱动，
> 计数口径：驱动器里的条目数，不含被 import 的库与被删掉的检查器）。
>
> **契约层已完成**——schema 逐字节一致、104 条 SQL 对真实 schema 执行、
> 90 项字面量、7 个工具定义逐字比对、272 个自有类型声明、
> 内容过滤金标经 **Java 引擎**复核 18/18、`.lybk` 容器加解密有确定性夹具、
> 备份导入 9 分区 / 世界书合成各有 11–12 个用例。
>
> **修复了 13 项「本该对等却不对等」**：`orchestrator: nil`、`api_configs` 行、
> `listSkills(nil)` 越权、`load_skill` companionId 冻结、archive 语义、
> AUTOINCREMENT 键、clientId 编码、`maxRounds`、`secure_delete`、历史清洗、
> 内置技能种子、`set_worldbook` 未接、输入安全门条件语义。
>
> **剩余工作按「解除阻塞需要什么」排**：
>
> | 项 | 需要 | 我能单方面推进？ |
> |---|---|---|
> | **M0 实跑**（V1/V2/V3） | 一台 Mac | ❌ |
> | V8 真机端到端 | Android + iOS 设备 | ❌ |
> | 备份格式扩展 | Android 侧改导出 | ⚠️ 规格已交付 |
> | V9 签名一致性 | 服务端团队 | ❌ |
> | V6/V7 | $99/年 + 真机 + 7 天 | ❌ |
> | 领域工具批次3 | 对接外部服务 | ❌ |
>
> **结论**：离线可验的部分已到极限。第 93–98 轮的经历印证的正是这点——
> 机械检查器最近报出的多是间接可达的误报，因为**直接缺口已被前几轮挖完**。
>
### 第 100 轮：M0「可跑性」自查（新增第 19 道关卡）

按上一轮定下的方向，检查 **M0 本身是否真的能跑起来**，而不是继续加功能。

核对结果：
- `project.yml` 引用的 5 个路径全部存在（除**由构建脚本生成**的 xcframework/绑定）
- 构建脚本产物路径（`ios/Frameworks/lianyu_agent.xcframework`、`ios/Generated/`）
  与 `project.yml` 的期望**完全一致**
- runbook 里的关键命令与目录均在

**新发现的问题：无。** 但这件事本身值得做——`M0-RUNBOOK.md` 是交给别人的入口，
它引用的东西从来没被机器核对过。一旦 `project.yml` 引用了一个不存在的目录，
实跑的人会在 `xcodegen generate` 这一步拿到一个**难以归因**的错误，
然后把时间花在排查环境上。

已固化为第 19 道关卡 `verify_m0_prereqs.py`，核对四项：
1. `project.yml` 引用的路径存在（脚本产物按「应当缺失」豁免）
2. 构建脚本产物路径 ↔ `project.yml` 期望一致
3. runbook 含关键命令引用
4. iOS 目录齐全

### 第 101 轮：M0 的 Rust 侧前提也核对（并修掉一个"没生效的门禁"）

上一轮核对了 `project.yml` 与 runbook，这一轮顺着 M0 关键路径往上查：
`build_agent_ios.sh` 依赖的 **Cargo 配置**。

核对通过项：
- `[[bin]] name = "uniffi-bindgen"` + `required-features = ["cli-bin"]` + `cli-bin = ["uniffi/cli"]`
- `crate-type = ["staticlib", "cdylib", "rlib"]`（iOS 链接需要）
- `strip = "debuginfo"` —— **不是** `true`/`"symbols"`，否则 `.symtab` 被移除、
  `uniffi-bindgen` 的 library 模式抽不到 `UNIFFI_META_*` 符号，绑定生成失败
- `uniffi.toml` 的 `[bindings.swift]` 段完整（module_name / ffi_module_name / cdylib_name）

## ⚠️ 过程里抓到一次「没生效的门禁」

第一次做变异测试（删掉 `cli-bin` feature）时，**检查器照旧返回 0**。

原因是我上一轮的编辑**没有真正应用**（锚点字符串写错，`YuNianTests` 写成了 `YuNianTest`），
而我看到命令退出码 0 就直接去跑了变异测试。

**这正是我在第 98 轮刚写下「不能把『工具报了』当成『我验过了』」的同型错误** ——
这次是「编辑返回成功 ≠ 编辑生效」。修好锚点后变异测试照常失败，门禁才真正生效。

**教训**：任何「加了检查」的动作，都必须紧跟着一次「让它失败」的验证。
否则你拥有的只是一个看起来在工作的文件。

### 第 102 轮：核对 CI workflow 自身（新增第 21 道关卡）

19 道关卡在本机全绿，**不等于它们在 CI 里会跑**。若 job 依赖引用了不存在的 job、
步骤名重复（GitHub 会用后者覆盖前者的显示）、或 run 里写了不存在的脚本路径，
CI 会以一种"看起来在跑"的方式失败或跳过。

`verify_ci_workflow.py` 核对四项：job 识别、`needs` 引用、run 里的脚本路径、
同 job 内步骤名唯一性。**当前状态：4 个 job / 32 个具名步骤 / 脚本路径全部存在。**

## 这轮连续三次自己绊倒，值得完整记下

| 次 | 发生了什么 | 性质 |
|---|---|---|
| 1 | 变异测试 2（把脚本名改坏）**没有触发失败** | 因为 workflow 里根本还没引用这个检查器 —— 我建了工具却没接进 CI，变异测的是空气 |
| 2 | 补了 CI 引用后重测，仍然"通过" | 编辑又没生效（`file changed since read`），而我没先确认 |
| 3 | 再测，两类变异都"exit=0" | 我把 `Out-Null` 打成了 `Out.Null`，管道断了，`$LASTEXITCODE` 取的不是检查器的返回值 |

**三次的共同点**：我都在看一个**没有真正连接到我以为在测的东西**上的信号。
第一次是工具没接线，第二次是编辑没落地，第三次是管道断了 ——
而每一次我都准备据"通过"下结论。

**最终做法**（也是这几轮形成的固定次序）：
```
① 先确认「改动真的生效」（读回文件 / 看输出）
② 跑基线，确认期望值
③ 逐个变异，每个变异都要看到 exit=1
④ 还原，再跑基线确认回到 0
```
第 ① 步是我第 101 轮刚写下、这轮第一次真正执行的规则。它连续挡了两次错。

### 第 103 轮：Info.plist 缺 `UILaunchScreen`（已修）

核对 `Info.plist` 时发现一个真实问题：

- `project.yml` **未声明** `TARGETED_DEVICE_FAMILY` → XcodeGen 默认 **iPhone + iPad**
- `Info.plist` **没有** `UILaunchScreen`

两个条件叠加的后果：**iPad 上应用被信箱化运行**（塞进 iPhone 尺寸的窗口），
且现代 Xcode 会就此告警。Android 侧是纯手机端（plist 注释里也写了
「与 Android 侧的手机端体验一致」），所以这不是想要的行为。

已补 `UILaunchScreen`（空 dict，即 Xcode 模板的默认形态），
并把「plist 解析 + 7 个必要键」加进 `verify_m0_prereqs.py`（第 19 道关卡），
变异测试（删掉该键）确认能抓住。

### 第 104 轮：Asset Catalog 完整性纳入关卡；一次「措辞上的自我纠正」

核对 `Assets.xcassets`：顶层 Contents.json 合法、`avatar_xiaoyu.png` 存在
（718,957 字节）、所有 imageset 引用的文件都在。**资源本身没问题。**

## 一次刻意的「不夸大」

我发现 imageset 的 JSON 缺 `"scale" : "1x"`，第一反应是"发现一个缺陷"。
核对 Xcode 的行为后确认：**缺 `scale` 的单图 universal imageset 是合法的**
（`idiom` 一直都在，只差 scale）。

所以我把它降格为**无害的模板规范化** —— 对齐 Xcode 默认形态，降低将来
有人手动编辑资产时的误判成本，并**不声称修复了缺陷**。

这正是第 38/68/89 轮教训的应用：**没核实之前，不把"看起来不对"说成"错了"。**
如果我写了"修复资产缺陷"，下一个读的人会以为这里曾经坏过。

## 新增关卡（第 19 道扩展）

`verify_m0_prereqs.py` 增加 Asset Catalog 完整性核对：每个 imageset 的
`filename` 都必须存在。理由很直接 —— imageset 引用了不存在的图片时，
`actool` 会在 `xcodebuild` 阶段失败，而**错误信息难以归因到根因**
（它只说 catalog 有问题，不说哪张图）。

## ⚠️ 同一个 PowerShell 错误，第三次

做变异测试时我又把 `Out-Null` 打成 `Out.Null`（第 102 轮的同一次错）。
症状完全一样：管道断、`$LASTEXITCODE` 取的不是检查器的返回值、
于是"变异通过"是假的。

**连续三次同一个字符级错误，说明这不是"不小心"，是我的默认动作有问题。**
对策还是那条流程：**先确认改动生效、再读基线、再逐变异、每个都要看到 exit=1。**
这次是靠"变异 1 失败但变异 2 也失败"的**不一致**暴露的 ——
当两个都"该失败"的检查给出相同结果时，应当怀疑测试本身。

### 第 105 轮：`verify_generated_api_usage` 的结构体盲区（覆盖面 30 → 104）

一个变异测试暴露了我在编译风险面上最大的疏漏。

`verify_generated_api_usage.py` 的 docstring 自称「本地唯一能闭环验证『会不会编译不过』的风险面」。
但变异测试显示：把 `ApiProbeConfig(provider: ...)` 改成 `providerXXX: ...`
**照样通过** —— 因为 `TARGET_STRUCTS` 列表里只有 `AgentTurnRequest` 和 `AgentTurnResult`。

而我这几十轮新写的代码用了 `ApiProbeConfig` / `HttpHeader` / `ProbeMessage` /
`ToolDefinition` / `AgentEvent` —— **一个都没在校验范围内**。

补进去之后：

| 指标 | 补前 | 补后 |
|---|---|---|
| 核对的调用/构造点 | 30 | **104** |
| 覆盖的生成结构体 | 2 | 7 |

**104 个全部对上真实生成绑定。** 也就是说这 74 个此前没人验过的点，
一把过了 —— 但这是运气（我构造时都核对了字段名），不是保障。

## 这个发现的意义

它的价值不在"多查了 74 个点"，而在**证明了我的核心检查器有一个我没意识到的边界**。
docstring 说"唯一能闭环验证编译风险"，实际只覆盖了类方法 + 2 个结构体。
如果我没做这次变异，这个描述会一直是真的——直到 M0 编译失败。

**教训**：一个检查器的**自述能力**和它的**实际覆盖**是两件事，
而只有变异测试能区分。这也解释了为什么我这十几轮反复强调
「加检查后必须紧跟一次『让它失败』的验证」。

### 第 106 轮：对既有检查器补做变异测试（两个高负载项确认有效）

按第 105 轮的规则，把"检查器的自述能力 ≠ 实际覆盖"应用到其余关卡上。
本轮测了两个**负载最重、此前未确认**的：

**`verify_swift_syntax_smoke`**（词法冒烟）——三类变异全部正确失败：

| 变异 | 结果 |
|---|---|
| 删掉一个右花括号 | ✅ exit=1 |
| 在代码里插入孤立的三引号 | ✅ exit=1 |
| 删掉一个左圆括号 | ✅ exit=1 |

**过程中我自己做了一次无效变异**：先把 `"""` 插进 `///` 行注释里，"期望失败"却通过了。
核对后确认**检查器是对的**——`///` 注释内的 `"""` 不是三引号 token，本就不该报错。
是我的变异设计错了，不是检查器坏了。改用代码里的孤立三引号后才正确失败。

**`verify_swift_conformance`**（6 trait / 22 方法的 Swift↔Rust 签名核对）——两类变异全部正确失败：

| 变异 | 结果 |
|---|---|
| `func onTextDelta(text: String)` → `onTextDeltaXXX` | ✅ exit=1 |
| 参数 `String` → `Int` | ✅ exit=1 |

## 结论与边界

两个检查器**实际工作正常**，与它们的自述一致。

但这次 testing 的价值不在"确认没问题"，而在**我此前没有证据说它们没问题**。
第 105 轮的教训正是：没测过就不该假设。这两个现在测过了。

### 第 107 轮：`check_argument_counts` 此前是**死代码**（已修，57 个点首次真正校验）

这轮按上一轮的规则继续补做变异测试，结果挖出本轮最严重的发现。

对 `verify_swift_sql.py` 做「占位符与实参数量不一致」的变异，**没有失败**。
逐层查下去，根因是：

```python
re.finditer(r'sql:\s*"""(.*?)"""\s*,\s*arguments:\s*\[', src)
#                                                        ↑ 缺 re.S
```

没有 `re.S` 时 `.` 不匹配换行，而 SQL 几乎都在 `"""` 多行块里 ——
**实测：无 `re.S` 全仓匹配 0 处，有 `re.S` 57 处。**

也就是说这个函数从写下到现在，**一处都没校验过**，全仓 57 个
`execute(sql:…, arguments:[…])` 调用点一直在裸奔。

## 修好之后，它立刻报了 4 个错 —— 而四个都是检查器自己的假阳性

| 报错 | 真因 |
|---|---|
| `MessageRepository` ×3「6 占位符 vs 2 实参」 | `[a, b] + boundaryArgs` 是数组拼接；我的跳过逻辑写 `src[close+1] == "+"`，要求 `]` **紧贴** `+`，而实际是 `] +`（有空格） |
| `ApiConfigRepository`「41 占位符 vs 7 实参」 | 更荒谬的数字 → 正则把一个**不带** `arguments:` 的 `sql:` 块延伸到后面对了的 `"""`，与错误实参配对 |

两个根因都已修（跳过空白再判 `+`；SQL 段内含 `sql:` 即判定跨块错配）。

## 这轮的价值

`check_argument_counts` 是继第 105 轮之后**第二个"自述能力远大于实际覆盖"**的检查器，
而且这个更严重：第 105 轮是漏了一类输入，这轮是**整个函数没运行过**。

如果没做这次变异测试，它会继续以"已校验"的状态躺在我 21 道关卡的清单里。

### 第 108 轮：检查器是好的，**是我的测试脚本在骗我**

对 `verify_literals.py` 做变异测试时遇到一个诡异现象：
`替换生效: False`（脚本声称没改动文件），但检查器却失败了。

按上一轮的方式逐层排查 —— 字节级往返一致、无 BOM、连跑 5 次基线全绿、
检查不同字符串也一样。一度准备把它记为"未解释的现象"。

**最后发现根因在我的测试脚本，不在检查器**：

```powershell
"替换生效: " + ($m -ne $bak)      # PowerShell 的 -ne 默认**大小写不敏感**
```

而我的变异恰好只改大小写（`case chat = "chat"` → `"CHAT"`）——
于是 `-ne` 返回 False（"没变化"），**但文件其实已经被写坏了**，
检查器正确地报了失败。

改用大小写敏感的 `-cne` 后，一切符合预期：变异生效 → exit=1 → 还原 → exit=0。

**`verify_literals.py` 工作正常（90 项 + MIN_CHECKS 下限）。**

## 这一轮的性质

前三轮（101/102/107）抓到的是**检查器真的坏了**。这一轮是**我的测试工具坏了**。

两类问题的症状完全一样——"该失败的没失败/不该失败的失败了"——
但**归因方向相反**。如果我这轮停下来记"检查器 flaky"，
就会在一个好检查器上浪费一轮，还可能把结论写进 README 误导后来的人。

**四条判断规则（这四轮的共同产出）**：
1. 「替换/编辑成功」必须**大小写敏感**地验证，且最好字节级比对
2. 退出码必须来自**真正连通的管道**（`Out-Null`  spelled right）
3. 检查器报出的数字若荒谬（41 vs 7），先怀疑**配对错误**而非被检代码
4. 多个检查给出一致结论时，反而要怀疑**测试本身**

### 第 109 轮：变异测试固化为脚本（第 22 道关卡）

上一轮的观察是「我的测试设施比被测代码更容易出错」。这轮据此行动：
把散落在 PowerShell 单行命令里的变异测试，固化成
`ios/Tools/run_mutation_tests.py`。

**它一次性消除了第 101–108 轮的四类自伤错误**：

| 曾经的错 | 脚本如何避免 |
|---|---|
| 编辑没生效就去测 | 每个变异前先断言**原文在文件里**（大小写敏感 `in`） |
| `Out.Null` 打断管道 | 退出码来自 `subprocess.run()`，不经 PowerShell 管道 |
| 变异不构成错误 | 每个变异都先断言 `mutated != original`（**大小写敏感**） |
| `-ne` 大小写不敏感 | Python 的 `!=` 是大小写敏感的 |

**当前覆盖 8 个关卡，全部 PASS**：
literals / own_types / tool_contracts / docs_coverage（含新增文件探测）/
smoke / sql_check / conformance / m0_prereqs。

脚本还做了两件我以前没做的事：
1. **变异前先跑基线** —— 基线就失败时直接报"先修检查器"，不做无意义的变异
2. **还原后不二次校验内容** —— 但主流程每次都用 `write_text(original)` 还原，
   本轮末尾我另外人工核对了 4 个文件无变异残留

### 第 110 轮：死代码清理 + 新工具 `find_dead_swift.py`

在从未编译过的 55 个 Swift 文件里，一个**声明了却没人引用**的成员是双重负担：
它可能引用了错误的东西（没人调用就没人发现），也让读代码的人误以为某能力已实现。

`find_dead_swift.py` 找出这类声明（report-only）。

**第一版报了 39 处，逐条看后发现多数是假阳性**，根因是我的判定：
> 声明只扫 `YuNian`，**引用计数也只扫 `YuNian`** —— 于是「只被测试用到的声明」
> 全被判成死代码（`BackupCrypto` / `BackupImporter` / `importBackup` 就是这么误报的）。

修正后 33 → 32 处（删掉一个后）。**剩下的多为合理情形**：
Rust 回调方向（`onTextDelta` 等，由 Rust 调用）、`@main`（`YuNianApp`，按属性而非名字引用）、
trait 实现等。

**真正属于我的死代码删了一处**：`ApiProbeService.ProbeResult`（第 94 轮定义，
三个字段 `ok/message/models` 从未被任何地方读）。

## 这个过程与第 108 轮同型

第 108 轮是**测试脚本**大小写不敏感造成假信号；这轮是**检查器自身**的语料库选错造成假阳性。

### 第 111 轮：变异测试覆盖扩到 9 个关卡（含 report-only 的判据设计）

上一轮欠的账（`find_dead_swift` 未变异测试）这轮还清。

**难点在于它的变异判据不同**：前 8 个关卡是 gate，变异后期望"退出码非 0"；
而 `find_dead_swift` 是 **report-only**，基线本来就有 32 处死代码并通过。
所以为它设计了另一种判据：

> 注入一个必然无人引用的 `struct MutationProbeDeadCode`，
> 然后检查**输出里是否出现这个名字**。

这比"退出码"更贴切——report-only 工具的价值就在它报没报出来。

当前 **9/9 PASS**。已接为 CI 第 22 步，`find_dead_swift` 也单独成为第 23 步
（report-only，不因死代码失败，但让它在 CI 输出里可见）。

## ⚠️ `Out.Null` 第 5 次了

这轮又在 PowerShell 里把 `Out-Null` 打成 `Out.Null`——**第 5 次**。
（第 102 轮第一次、104、108、本轮各一次。）

它每次都让退出码取错值，而这轮我又是靠"全量 24 项 0 失败"与
"单独重跑 workflow/mutation 两个检查"交叉验证才发现并排除。

**这已经不能用"不小心"解释了**：`Out-Null` 是我的默认动作，
而 `function Run-X { ... *> $null }` 才是正确形态。
现在 `run_mutation_tests.py` 把大部分退出码判断搬进了 Python（不经 PowerShell 管道），
**这类错误的暴露面已经从"每次变异测试都有"降到"只有我手写的临时命令有"**。

剩下的对策只有一条：**临时命令也套 `function`**，不用裸 `| Out-Null`。

### 第 112 轮：README 交付物统计漂移（已修 + 新增第 25 道关卡）

发现 README 顶部手写的统计**十几轮没更新**：写 83 文件 / 21,652 行 / Python 18，
实际已是 87 / 22,689 / 22。

**为什么值得单独立关卡**：README 是这个目录的状态源，读它的人拿这些数字
判断规模与完整度。一个过期统计比没有统计更糟——它会让人误判"还有多少没做"。

这与第 67 轮的 `verify_docs_coverage`（防 runbook 漏列测试文件）同类，
只是对象从清单换成数字。新关卡 `verify_readme_accounting.py`：
README 顶部的「N 文件 / M 行（Swift A / Python B）」必须与实测一致，
变异测试（改错数字）确认能抓住。

### 第 113 轮：import 覆盖核对 —— 第一跑抓到 **3 个真实编译错误**

上一轮我说"最需要的不是第 26 道关卡，是有人在 Mac 上跑一次"。
这一轮仍然建了一道关卡，但目标是具体的：`import` 覆盖。

**为什么选它**：清点「尚未覆盖的编译风险面」时发现这一类完全没人管——
冒烟检查只做括号配平，生成绑定核对只查调用与构造参数，都不管 import。
而这类错误的症状是 `Cannot find type 'Logger' in scope`，
**一个文件漏 import 会连带几十个误报**，让人以为出了大问题。

## 抓到 3 处真实错误（都是我这几十轮自己写出来的）

| 文件 | 缺什么 | 后果 |
|---|---|---|
| `DeviceTools.swift` | `import UIKit` + `import UserNotifications` | 用了 `UIDevice`/`UIPasteboard`/`UNMutableNotificationContent` 却只 import 了 Foundation → **必然编译失败** |
| `ChatSession.swift` | `import Combine` | 用了 `ObservableObject`（Combine 的协议，**SwiftUI 的传递导入帮不上**——本文件没 import SwiftUI） |
| `AppEnvironment.swift` | `import Combine` | 同上 |

这三处都在我第 41、61 轮新写的文件里。**它们会在 M0 第一次编译时以三个
`Cannot find type` 报错出现，而那时没人会立刻想到是少了一行 import。**

## 一个我特意验证过的假设

我起初以为「同模块内任何文件 import 了 UIKit，其他文件就能用」——
**这是错的**：Swift 的 import 是**按文件**生效的。这也正是为什么这三个文件会漏。

### 第 114 轮：试建 switch 检查器 → **主动删掉**（不可靠）

上一轮的教训是"别凭感觉判断，要清点具体失败模式"。这轮清点到 switch 穷尽性：
冒烟检查只查括号配平，生成绑定核对只查调用参数，都不覆盖它。
Swift 里对**非 frozen**枚举 switch 而不给 `default` 是编译错误。

写了 `verify_switch_coverage.py`，然后：

**第一版：13 处报错，全部假阳性** —— `switch self { case .none: ... }`
是对本模块自建枚举的穷尽 switch，**不带 default 完全合法**。

**修正：区分枚举声明位置**（`ios/YuNian` = 同模块免 default，`ios/Generated` = 跨模块必须 default）。
13 → 11，但剩下的仍是假阳性。

**根因是语言层面的，不是我的正则不够好**：

> Swift 的 switch 主语是**值**，不是类型。`switch self` / `switch event` /
> `switch result` 的主语都不含类型名，我无法从语法推断被 switch 的是哪个枚举，
> 因而无法判断它是否 frozen。

## 处理：删掉它

一个 **11 个假阳性、0 个真阳性**的检查器，比没有检查器更糟：
它会挡住 CI，并教会所有人忽略它的输出。这正是我在 `verify_literals` 的
`MIN_CHECKS` 教训里见过的退化模式。

### 第 115 轮：属性包装器核对（新增第 27 道关卡，一次通过）

继续清点具体的、未覆盖的失败模式。上一轮试了 switch 穷尽性并因**假阳性不可压**
而主动删除；这轮选了一个**能可靠判定**的：属性包装器与容器类型是否匹配。

| 包装器 | 只能用于 | 误用症状 |
|---|---|---|
| `@Published` | **class** | 写在 struct 上：`requires a class` |
| `@State` / `@StateObject` / `@ObservedObject` / `@EnvironmentObject` | **struct** | 写在 class 上：编译错误 |

**为什么这个能可靠判定而 switch 不能**：包装器的那一侧是**声明处**，
语法上就能确定容器是 struct 还是 class；而 switch 的主语是**值**，
推不出它是哪个枚举、是否 frozen。

实现上有一处必要的精细处理：必须取容器的**直接**体内（剔除嵌套类型），
否则外层 class 里嵌套的 struct 上的 `@State` 会被误判成 class 误用。

**结果**：40 个文件 / 15 处包装器使用，**0 问题**。
变异测试两类（struct 里放 `@Published`、class 里放 `@State`）均正确失败。

## 与上一轮的对照

| 轮次 | 面 | 能否可靠判定 | 处理 |
|---|---|---|---|
| 114 | switch 穷尽性 | ❌ 主语是值，推不出类型 | **删除**（90 行） |
| 115 | 属性包装器 | ✅ 声明处即确定 | **保留**（+85 行，0 问题） |

### 第 116 轮：类型名唯一性核对（新增第 28 道关卡）

按第 115 轮确立的判据（先问"能否从语法确定地判定"）继续清点。
同模块内两个同名类型是**确定性编译错误**（`invalid redeclaration`），
判据完全可靠。我写了 55 个文件分多轮增量，正是容易产生重名的场景。

**结果**：40 个文件 / 97 个类型声明，**0 重复**。
变异测试（加一个重名顶层类型）确认能抓住。

## 第一版同样误报，根因是嵌套限定留了空桩

```
- CodingKeys ×3（AgentDTOs.swift 内 3 个不同类型的嵌套枚举）
- Outcome：ChatInputGuard.swift(enum) vs DatabaseMaintenance.swift(struct)
```

两处都是**合法**的：`ChatInputGuard.Outcome` 与 `DatabaseMaintenance.Outcome`
在不同外层里，Swift 的嵌套类型是按命名空间隔离的。
我第一版的 `declarations()` 里留了一句 `pass  # 简化：嵌套限定单独处理` ——
**把"以后再说"写成了"现在能跑"**，于是它跑出了一个错误结论。

修正为按花括号配平维护外层栈后通过。

## 这轮复现了第 110 轮的模式

| 轮次 | 工具 | 第一版误报原因 |
|---|---|---|
| 110 | find_dead_swift | 引用计数语料库漏了 Tests |
| 116 | verify_unique_types | 嵌套类型限定是空桩 |

**新检查器第一版几乎必然误报**这条经验，现在是第三次印证（另有 114 的 switch）。
对策也一致：**先看它报的每一条，确认是真阳再动手改被检代码。**
### 第 117 轮：枚举 rawValue 唯一性（新增第 29 道关卡）

继续按"能否确定判定"清点。这个面我在第 11 轮（ConversationType 字面量）做过一次，
现在补成通用关卡。

**为什么它值得单独一道**：rawValue 重复**不是编译错误**，而是让 `init(rawValue:)`
运行时返回 nil——症状是"某个 case 明明存在却取不到"，极难归因。
而 iOS 侧多个枚举的 rawValue 是**与 Android 跨端对齐的契约**
（`ConversationType.chat = "chat"`、消息类型 `"TEXT"/"IMAGE"`…），
重复会让一端写出的值在另一端读不出来。

**覆盖范围**：只查**显式赋值**的 case（`case n = "v"`）。
隐式原始值（`case none, low, …` 无赋值）由编译器保证唯一，无需查。
这是刻意的收窄——它让判据完全可靠。

**结果**：6 个带原始值的枚举 / 2 个显式 case，**0 重复**。
变异测试（把 `case group = "group"` 改成 `"chat"`）确认能抓住。

## ⚠️ 过程中又栽在"手算的数字"上

更新 README 统计时，我按前一轮的差值手推出 `23,389 行`，关卡报错；
实测是 `23,381`。**差 8 行。**

这不是巧合性的算错——我连续两轮都在"心算增量"：
```
上一个数 + 新增文件行数（估）
```
而 `verify_readme_accounting.py` 第 112 轮建它时就已经写明"数字由关卡把关，勿手改后不同步"。

**正确做法：跑一次实测把数字抄进去，而不是推算。**

### 第 118 轮：把"更新统计"从手算变成自动（`--fix`）

上一轮我写下"正确做法是跑实测别推算"——但**这仍是提示，不是结构**。
证据是第 112、117 两轮我都"知道"要实测，默认动作仍是心算。

这轮做结构性修复：`verify_readme_accounting.py --fix` 按实测直接回填 README。
**从此更新数字的流程里没有手算空间。**

## 使用次序（重要，本轮最后一次才搞对）

```
① 编辑 README 正文
② 跑 verify_readme_accounting.py --fix     ← 必须最后
```

本轮我第一次跑完 `--fix` 后又编辑了 README（加说明节，约 40 行），数字再次漂移。
**因为 README 的行数本身是被统计项。** 自举问题没有终点，只有次序：
**先改内容，最后收数字。**

## 过程中 `--fix` 自己错了两版

| 版本 | 症状 | 原因 |
|---|---|---|
| 1 | 产出 `... Python 27）），` 残缺行 | 替换范围只吃到第一个 `）`，原文后半段残留 |
| 2 | 「没有可改写的统计」——修不动了 | 正则只容忍一个 `）`，而残留行有**两个** |

两版都是"我的工具在修我的工具造成的损坏"，而第二版连第一版的损坏形态都没料到。
最终用 `）+\s*[,，]?` 同时覆盖干净行与残缺行才通过。

## 更普适的观察

116/117/118 三轮里，我在同一类任务上出了四种错：心算增量两轮、
`--fix` 替换范围不足一轮、`--fix` 不容忍重复括号一轮。

**共同点：每一步我都只考虑"当前输入是干净的"这一个情形**，
而现实是输入会被我上一步的缺陷弄脏。对策不是更小心，
而是**让工具能吃自己产出的脏数据**——`）+` 就是这种考虑。

### 第 119 轮：给 `swift_text.py` 补"反序对照"（补上 66 轮前的缺口）

第 53 轮建 `swift_text.py` 时我说它"自带自测"并列出三种历史事故形态。
但那版自测只验证了**正序正确**，从未验证**反序会错**。

这轮补上反序对照：按第 8 轮的错法（先剥注释、后剥字符串）跑同一份样本，
断言 `asset://foo/bar` 会被截断。

**为什么必要**：如果反序也能得出同样结果，那"先字符串后注释"就不是必要的顺序，
我的自测只是在重复实现、没有区分力。现在它证明了顺序的必要性——
而这正是第 8 轮那个 bug 的根因。

## 第 120 轮：`find_dead_swift` 区分三态 —— 抓到 V8 导入链**没有 UI 入口**

按我自己的教训（第 95/97 轮两次抓到"实现未接线"）做自查：
`BackupImporter` / `BackupCrypto` 有 UI 入口吗？**没有，零引用。**

而更值得注意的是：**这个盲区是我自己第 110 轮造出来又盖回去的** ——
当时为消除「只被测试用」的假阳性，我把 YuNianTests 并入了引用计数语料库。
于是「已实现、已单测、用户触达不到」这个状态，在工具眼里和"已接线"长得一样。
**一次修正引入了一个更隐蔽的盲区。**

## 修正：语料库分两份，输出分三态

| 态 | 含义 | 处理 |
|---|---|---|
| 完全未引用 | 谁都不用 | 删，或加 TODO 说明预留 |
| **仅测试引用** | **已测但无 UI 入口** | **需要接界面才能交付** |
| 生产引用 | 正常 | — |

修正后立刻报出 **6 处「仅测试引用」**，其中 5 处构成 V8 整条导入链：

```
BackupCrypto / sealedBox / decryptToString     ← 容器解密
BackupImporter / importBackup                  ← 9 分区导入
RequestSigner.clientIdentifier                 ← 签名用的 clientId
```

## 这意味着什么

V8 我在第 71–86 轮投了约 15 轮（容器解密、9 分区导入、12 个测试、
合成流水线、格式扩展规格），**但用户从 UI 上没有任何办法触发它。**
那 15 轮的产物目前只能被单元测试消费。

补上入口需要：文件选择（`UIDocumentPickerViewController`）、密码输入、
导入结果反馈 —— 不是"加个按钮"那么简单，但它现在是**明确的一步**，
而不是藏在"已完成"里。

### 第 121 轮：V8 导入链补上 UI 入口（闭环上一轮的发现）

上一轮 `find_dead_swift` 报出「BackupImporter / BackupCrypto 仅被测试引用」，
这轮补上那一环。

**新增两个文件**：
- `Data/BackupImportService.swift` —— 端到端编排：文件字节 → 容器解密 →
  JSON 解析 → 9 分区写入 → 可观测的 `Outcome`。四个失败分支各有明确文案
  （非 .lybk / 过短 / 解密失败 / JSON 非法），不让用户看到裸异常。
- `Views/BackupImportView.swift` —— 文件选择 + 密码 + 导入 + 结果。
  用 `UIDocumentPickerViewController`（经 `UIViewControllerRepresentable` 桥接）。

**已在自检面板加「备份导入」导航入口**，`database == nil` 时禁用。

## 三处实现要点

1. **文件必须在 picker 回调的同一轮读完** —— 安全作用域 URL 在回调外不可用，
   所以 `loadFile` 里 `startAccessingSecurityScopedResource()` → 读 → `defer` 里停。
2. **PBKDF2 10 万次迭代是 CPU 密集** —— 放进 `Task.detached`，避免卡界面。
3. **幂等性写进界面** —— 同一备份可重复导入（伴侣按 name 匹配、消息按
   `(timestamp, isFromUser, content)` 去重），这句说明让用户敢重试。

## 闭环验证

`find_dead_swift` 的三态报告里，`BackupImportService` **已消失**
（从「仅测试引用」变为「生产引用」—— 它被 `BackupImportView` 调用了）。
而 `BackupImporter` / `BackupCrypto` 仍列在「仅测试引用」是**正确的**：
它们只被 `BackupImportService` 与测试引用，而后者已被 UI 引用 —— 链条通了。

**这是第 120 轮那个发现第一次真正闭环**：从"发现问题"到"补齐"到"工具确认状态改变"。

### 第 122 轮：`clientIdentifier` 是残留常量（删）—— "先查再定性"

接着上一轮的三态报告，还剩一项「仅测试引用」：`RequestSigner.clientIdentifier`，
对应 Android `RequestSecurityInterceptor:167` 的 `X-LianYu-Client: lianyu-1.5.1` 头。

按第 92/98 轮的教训**先查 Rust 侧是否发这个头**：

| 头 | Rust `native_gateway.rs` | Android Kotlin 路径 |
|---|---|---|
| `X-LianYu-Session` / `-Client-Id` | ✅ PARTNER 认证 | ✅ |
| 7 个签名头 `X-LianYu-Sig*` | ✅ | ✅ |
| **`X-LianYu-Client`** | **❌ 完全没有** | ✅ 拦截器 |

iOS 走 Rust 路径 → **与 Android 的 Rust 路径一致** → 不是缺口。
真正的问题是我在 iOS 侧声明了这个常量却从未使用：它是早期
「iOS 也会有网络拦截层」这个**从未成立的假设**的残留。已删除。

## 与第 108 轮同构

| 轮次 | 表面 | 真相 |
|---|---|---|
| 108 | 检查器"坏了" | 是我的测试脚本坏了 |
| 122 | 功能缺口（少发一个头） | 是残留常量，Rust 路径本就不发 |

两次都是：**工具报了一项，我没直接按报告动手，而是先查了它背后的真实语义。**
按第 116 轮立的默认假设（新检查器报的每条先当假阳性），这轮的方向是对的。

### 第 123 轮：统一关卡驱动器（退出码判断收进 Python）

第 102–122 轮我在 PowerShell 里读检查器退出码出了六次错
（`Out.Null` 拼写、`-ne` 大小写不敏感、`--check` 分支没跑到、管道断……）。
我第 108 轮的方子是「临时命令套 `function`」，但那只降低了概率。

这轮做结构性解决：`run_all_gates.py` 跑完全部关卡并自己汇总，
**我此后只需要读一个退出码，且是在 Python 里读自己的**。
已接为 CI「全部本地关卡（快速）」步（`--quick` 跳过四个慢关卡）。

**它第一次运行就抓到真实漂移**：README 统计仍是 Python 27，实际 28
（我刚加了 `run_all_gates.py`）。修完再跑，26 项全 PASS。

## 由此确认的次序（第三次印证）

本轮我编辑 workflow 后又跑 --fix，数字又错；**不再编辑任何文件、直接跑 --fix 之后才稳定**。

```
① 所有文件编辑
② verify_readme_accounting.py --fix
③ run_all_gates.py          ← 若此处 FAIL，回 ①，不要再插队编辑
```

这已是第三轮印证同一件事（118、123）。区别是这次它进了 CI 的一个步骤 ——
**顺序不再依赖我记得。**

### 第 127 轮：清点结论 —— 离线验证已到极限（不造新关卡）

上一轮我说"若没有具体、未覆盖、可确定判定的失败模式，就报告结论而非制造关卡"。
这轮做了那次清点。

**已覆盖**（26 道关卡）：类型引用、生成绑定调用与构造、SQL 有效性与参数配对、
Row 列名、Rust→Swift trait 签名、import 覆盖、属性包装器、类型名唯一、rawValue 唯一、
括号配平、工具契约、字面量契约、Java 正则引擎复核、容器加解密、备份导入语义、
世界书合成、分词向量、文档覆盖、README 统计、CI 结构、M0 前提。

**枚举不出新的。** 剩余候选都不满足"可确定判定"：
跨文件访问控制（需作用域解析，同第 114 轮 switch 一类）、
`@MainActor` 隔离（需调用图）、一般编译错误（只有编译器能做）。

**所以这轮没有建关卡**，改为收尾：runbook 第 5 步补到 **12 步**，
纳入第 94/95/121 轮新接的三个入口（测试连接、拉取模型列表、备份导入）——
否则跑 M0 的人不会知道要验它们。

### 第 128 轮：给最承重的关卡补变异测试（`generate_schema --check`）

上一轮我承诺"不扩张关卡数量，只保护已有资产"。这轮照做：
26 道关卡里 **17 道没有变异测试**，我选了其中最承重的一个补上 ——
`generate_schema.py --check`。

**为什么它最承重**：schema 是全线的地基。`YuNianSchema.swift` 与 Room `45.json`
一旦漂移，SQL 检查、Row 列名、生成绑定全部建立在一个错的地基上，
而它们的报错会指向别处。

**变异设计**（与被检文件形态匹配）：`--check` 比对的是**生成物**，
所以变异要破坏生成物而非源码。两类都验证通过：

| 变异 | 结果 |
|---|---|
| 列名 `conversationType` → `conversationTtype` | ✅ exit=1 |
| `static let version = 45` → `44` | ✅ exit=1 |

已固化进 `run_mutation_tests.py`（第 10 个案例），
并给 `CASES` 增加 `check_args` 字段以支持 `--check` 模式的检查器。**无残留。**

## 这与前几轮的"建新关卡"有何不同

| | 建新关卡 | 补变异测试 |
|---|---|---|
| 对象 | 未覆盖的失败模式 | 已存在但未证明有效的关卡 |
| 风险 | 可能不可靠（第 114 轮删过一个） | 低 —— 只是证明既有东西在工作 |
| 产出 | 新的保护面 | 既有保护面的可信度 |

第 127 轮我说"枚举不出新的失败模式"，这轮就转向**验证既有保护面是否真的在保护**。
同一个约束的两种执行方式。

### 第 129 轮：补种子类关卡的变异测试（含一次目标选错）

继续补变异测试。这轮加 `generate_seeds --check`（初始数据种子）与
`generate_security_seed --check`（**安全基线**：过滤词表 / 答题）。两者都通过，无残留。

## 过程中目标选错一次

我把安全种子的变异目标写成 `ios/YuNian/Data/Seed/SecuritySeed.swift` ——
**该文件不存在**。安全种子的产物是 JSON 资源
`ios/YuNian/Resources/SecuritySeed.json`。

错误被 `try_one` 的"文件不存在"检查挡住，未造成损害。
但这说明我选变异目标时又用了**假设**而非事实：我按"种子都是 Swift"的印象挑路径，
没先确认产物形态。与第 116 轮（`switch self` 推不出类型）同类 ——
**印象总在"该问事实是什么"的时刻失效。**

## 变异测试覆盖现状

| 已覆盖（12 案例） | 未补 |
|---|---|
| schema / seeds / security_seed（生成物一致性 ×3） | 其余 16 道关卡 |

未补的 16 道里，多为派生关卡（`verify_schema_sql` 复用 `contracts.py`、
`golden_content_filter` 与 `verify_literals` 共用词表），其可信度已由直接关卡间接支撑。

## 这是结论，不是中断

**离线部分已到极限。** 剩余风险（V1/V2/V3 能否编译与流式、V6/V7 APNs 与后台频率、
V8 真机端到端、V9 签名一致性）全部需要 Mac / 设备 / 付费账号 / 服务端配合。

在没有新外部输入前，我只做"保护已有资产"的零散工作，不再扩张关卡数量。

## 一个更一般的认识

我这几十轮建的关卡里，不少只有"正例通过"没有"反例会失败"。
第 101–109 轮的变异测试给**关卡**补上了这一点；这一轮给**模块自身的自测**补上。

两类是同一件事的不同位置：**任何"我验过了"的说法，都需要一个会失败的反例来支撑。**





```swift
switch self { case .none: ... case .critical: ... }   // ViolationLevel，同模块穷尽 ✓
switch result { case let .success(models): ... case let .failure(_): ... }  // Result，标准库 ✓
```

## 这轮的产出

不是工具，是**一个判断**：当检查器的假阳性率无法压到可用水平时，
正确动作是删除而不是调参。我为此写了 90 行、改了 3 版、最后删掉 90 行 ——
这 90 行的价值在于证明了"这条路不通"，以及把理由留给你们。



第一次跑新关卡就失败——因为**关卡自身的文件还没被算进统计**。
这是"检查自身会改变被检查对象"的经典情形：加了 1 个 Python 文件、约 100 行，
两个数都不对。

处理方式：取实测值回填 README。**这不是"凑数过关"，而是文档终于说了真话**——
前提是统计口径必须固定（本关口径：`ios` + `scripts` + `.github`，排除
`ios/Generated` 与 PNG，只计非 PNG 行数）。口径写在脚本 docstring 里，
改口径要同步改脚本。















这是 `docs/ios-port-feasibility.md` 所述**路线 B（SwiftUI 原生壳 + 复用 Rust Agent）** 的实现目录。

> **本目录完全在 Gradle 构建之外。** 25 个 Android 模块与 `settings.gradle.kts` 不受影响，
> 符合 AGENTS.md 的「架构最小变更原则」。iOS 侧唯一触碰的 Android 侧资产是
> `agent-native/` 的 `uniffi.toml`（**仅新增** `[bindings.swift]` 段）与只读引用
> `core/database/schemas/` 的 schema JSON。

---

## 目录结构

| 路径 | 说明 | 是否入库 |
|---|---|---|
| `project.yml` | XcodeGen 工程定义（声明式，替代 `.xcodeproj`） | ✅ |
| `Tools/generate_schema.py` | 由 Room v45 schema JSON 生成 GRDB 建库代码 | ✅ |
| `Tools/generate_seeds.py` | 由 Kotlin 源码生成初始数据种子（供应商预设 + 默认伴侣人设） | ✅ |
| `Tools/generate_security_seed.py` | 由 Kotlin 源码 + 加密资源生成安全基线（违禁词 / 题库 / 过滤词表） | ✅ |
| `Tools/contracts.py` | **字面量契约的唯一来源** —— 从 Android/Rust 源码推导，供各验证脚本 import | ✅ |
| `Tools/verify_literals.py` | 交叉核对 Swift 字面量与契约（枚举列 vs 业务字面量） | ✅ |
| `Tools/golden_content_filter.py` | 忠实转写 Kotlin 过滤逻辑，产出 Swift 测试用的金标向量 | ✅ |
| `Tools/verify_schema_sql.py` | **用真实 SQLite 验证建库脚本与 FTS 维护时序**（非 macOS 也能跑） | ✅ |
| `Tools/verify_swift_conformance.py` | **断言 Swift 实现与 UniFFI 生成签名逐字一致**（非 macOS 也能跑） | ✅ |
| `Tools/verify_swift_syntax_smoke.py` | Swift 结构冒烟（括号 / 三引号配平）—— 不能替代编译器 | ✅ |
| `YuNian/` | Swift 源码 | ✅ |
| `YuNian/Data/Schema/YuNianSchema.swift` | **生成物**，勿手改 | ✅（便于审阅 schema 差异） |
| `Generated/` | UniFFI 生成的 Swift API + C FFI 头 | ❌ 由脚本/CI 产出 |
| `Frameworks/` | `lianyu_agent.xcframework` | ❌ 由脚本/CI 产出 |

---

## 构建

**前置：必须有一台 Mac 或使用 CI。** 原因是 Rust 侧的 `ring`（rustls 的密码学后端）
与 `libsqlite3-sys`（rusqlite 的 bundled SQLite）都要编译 C/汇编源码，
交叉编译到 iOS 需要 iOS SDK 提供 clang 与头文件 —— 这与 UI 用什么写无关。
详见文档 §4.2。

### 方式一：云端（无 Mac 时唯一路径）

```bash
# 推送后由 GitHub Actions 在 macOS runner 上构建
# 产物：Actions → iOS Agent → Artifacts → lianyu-agent-ios
```

公开仓库的 macOS runner **免费**（本仓库已确认为 public）。见 `.github/workflows/ios-agent.yml`。

### 方式二：本地 Mac

```bash
# 1. 编 Rust + 生成 Swift 绑定 + 打包 xcframework
./scripts/build_agent_ios.sh

# 2. 生成并打开 Xcode 工程
cd ios
brew install xcodegen     # 若未安装
xcodegen generate
open YuNian.xcodeproj

# 或纯命令行验证编译（无需签名）
xcodebuild build -project YuNian.xcodeproj -scheme YuNian \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

### 方式三：只生成 Swift 绑定（**任意平台，含 Windows**）

```bash
./scripts/build_agent_ios.sh --bindings-only
python ios/Tools/verify_swift_conformance.py   # 校验实现与生成签名一致
```

**为什么这能在 Windows 上跑**：`uniffi-bindgen --library` 提取的是 dylib 里的
`UNIFFI_META_*` 元数据符号，而元数据与平台无关 —— Windows 的 `.dll`、Linux 的 `.so`
一样可用。**只有编译 Swift 才需要 Xcode。**

这条路径的实际价值：把「契约是否一致」的检查从昂贵的 macOS runner 上拿下来。
CI 里 `contract-checks`（ubuntu）先跑，通过了才轮到 macOS 任务。

### 重新生成 schema

改动了 Room schema 之后（注意：v41 起已冻结，见 `docs/database-schema-freeze.md`）：

```bash
python ios/Tools/generate_schema.py           # 重新生成
python ios/Tools/generate_schema.py --check   # CI 用：校验是否与磁盘一致
```

生成器带自检：会断言表数、索引数与 schema JSON 一致，且不残留 `${TABLE_NAME}` 占位符。

### 验证 SQL 与签名（不需要 Mac）

```bash
# 1) 用真实 SQLite 建库并跑一遍 FTS 维护时序与伴侣读写
python ios/Tools/verify_schema_sql.py

# 2) 断言 7 个 foreign trait 的实现签名与生成物逐个一致
python ios/Tools/verify_swift_conformance.py
```

`verify_schema_sql.py` 解析**生成的 Swift 文件**里的 DDL（即被测工件），建一个真实库，
然后按 `MessageRepository.swift` / `CompanionRepository.swift` 的 SQL 逐步操作并断言：

- 中文检索通路（分词 → FTS MATCH → 命中）；单字、多字、英文、空串
- 更新正文会刷新索引；归档后索引仍有效（`rowid` 不变）
- **顺序约定**：反例证明「先删元数据」会留下脏 FTS 行
- 清空会话后无 FTS 残留；外键 CASCADE 生效
- 伴侣读写，以及 Rust `load_companion` 的**原文 SELECT** 可用
- 上述全部在 FTS4 与 FTS5 两个变体上各跑一遍

当前：**46 项断言全部通过**。

`verify_swift_conformance.py` 把 Rust 的 `with_foreign` trait 生成结果与宿主实现对撞：
任何一个方法名或参数标签写错，CI 就会红。**这是把「命名靠推断」变成「命名靠断言」的那一步。**
当前：**6 个 trait / 22 个方法全部匹配**（`TurnStateController` 有意不实现 —— Rust 内置
`DefaultTurnStateMachine` 已提供等价行为）。

两个脚本都会在 CI 的 `contract-checks`（ubuntu）里跑。

---

## 当前进度（M0 / M1）

| 阶段 | 状态 | 说明 |
|---|---|---|
| M0 环境与打样 | **契约部分已验证，编译待 macOS** | `--bindings-only` 已在 Windows 上实跑通过（V2 达成）；Swift 编译与 xcframework 打包仍待 macOS 执行 |
| M1 Agent 核心跨端 | **大部完成** | 7 个 foreign trait 的 Swift 实现、20 个 UniFFI 对象的调用面已铺好 |
| M2 最小可用对话 | **代码就绪，待 macOS 实跑** | 对话链路已接通（DTO 契约 + 流式 sink + 打字机 UI）；`settings_json` / `credentials_json` / `history_json` 的键已与 Rust 源码逐键交叉核对 |
| M3 数据与记忆 | **数据层已完成** | v45 schema 逐字生成并校验；`CompanionRepository` + `MessageRepository`（含 FTS 维护时序）已实现并通过真实 SQLite 验证（46 项断言 × FTS4/FTS5 双变体）；首启播种默认伴侣 |
| M4 多媒体 | 未开始 | |
| M5 通知与主动消息 | 未开始 | 依赖服务端扩展（见文档 §5.6） |
| M6 安全与上架 | 部分 | Secure Enclave 签名、Keychain、AppPaths 已就位；App Attest 未接 |

### 已完成并可验证的

- **schema 逐字一致**：32 表（含 1 张 FTS）、61 索引（7 个 UNIQUE）、2 个 CASCADE 外键，
  DDL 文本与 Room `45.json` 逐字节比对通过
- **中文分词器逐字复刻**：unigram `u<hex>z` + bigram `b<hex>x<hex>z`，查询侧 ≥2 字符只用 bigram 并加引号
  （带 12 组金标向量的单测）
- **设备签名**：P-256 + Secure Enclave，含 **SPKI DER 手工构造**（`SecKeyCopyExternalRepresentation`
  给的是裸点，而服务端按 SPKI 校验 `keyId`，这是最容易做错的一处）
- **请求签名**：8 段 payload + 握手用的 4 段变体
- **数据库引导**：`DatabasePool`（WAL，Rust 只读并发的前提）+ `PRAGMA user_version = 45` + FTS 运行时探测
  + **供应商预设播种**（对应 Room `onCreate`，这点若漏掉是静默缺陷：表空但无人报错）
- **初始数据种子由源码生成**，不手抄：13 条供应商预设来自 `ApiProvider` 枚举 +
  `seedApiProviderPresets`，默认伴侣人设来自 `RolePresets`（小鱼 / 阿泽），
  含 Android 的 `deleted_by_user` 语义（用户删过就不再自动重建）
- **安全基线由源码 + 加密资源生成**：284 条违禁词 + 31 条题目
  （来自 `SecurityDataSeeder` 的 XOR 混淆串，程序化解码）
  + **290 条过滤正则 + 293 条过滤关键词**（来自 `content_filter_keywords.json.enc` 的
  **两层 XOR**：资源级运行时派生密钥 + 字符串级 `OBF_KEY`）
- **内容过滤已移植**：`ContentFilter`（四入口 `check` / `checkInput` / `checkFull` /
  `checkOutputSafety`、等级映射、按等级顺序取首个命中）+ `SemanticDetector`
  （绕过 / 意图 / 多语言三组规则、需同组 ≥2 条命中的意图判定），
  行为由金标向量锁定（含 `checkInput` 只拦 HIGH+ 等边界语义）

### ⚠️ 一个已修复的严重错误（值得记住）

`MessageRepository.ConversationType` 最初被我写成 `"COMPANION"` / `"GROUP"`，
而 Android 实际存的是 **`"chat"` / `"group"`**（小写业务字面量，不是枚举名）。

这个错误的隐蔽之处在于：**不会报错**。更糟的是 `verify_schema_sql.py` 里也写了同样的
错值，插入与查询**自洽**，64 项断言全过 —— 却与 Android 完全不同。
**自洽不等于忠实。**

根因是「猜」而不是「读」。现已修正，并加了两道防线：

1. `Tools/contracts.py`：**字面量契约的唯一来源**，全部从 Android/Rust 源码推导。
   任何脚本都不得再硬编码这些取值 —— 重复硬编码正是事故根因
   （当时 `verify_schema_sql.py` 里也写了同一个错值，两者互相掩盖）
2. `Tools/verify_literals.py` + `YuNianTests/LiteralContractTests.swift`：交叉核对 + 钉死取值

### ⚠️ 第二个已修复的严重错误：归档的语义

`MessageRepository.archiveOldest` 最初写成「`ORDER BY timestamp ASC LIMIT count`」，
也就是**移走最旧的 N 条**。而 Android 的 `archiveOldMessages` 是**裁到 N 条**：

```kotlin
val boundary = getArchiveBoundary(conversationId, type, retainCount) ?: return 0
// 边界 = 最新 N 条里最旧的那条；归档「严格早于边界」的行
```

两者在消息数远大于阈值时结果相同，但在**消息数少于阈值**时差别是灾难性的：
LIMIT 写法会把不足 5000 条的会话**整段归档清空**，而边界法此时返回 0。
边界法还天然幂等。

**这个错误是被「按 Android 原文写测试」暴露的** —— 我先前用 LIMIT 语义写测试，
与错误实现共享同一个误解，所以没能发现。现已改正，并在装置里加了一条
专门的回归断言：「消息数远小于阈值时归档 0 条（错误实现会清空）」。

### ⚠️ 第三个已修复的严重错误：新建记忆会覆盖上一条

`AgentStores.insertMemory` 原先用 `INSERT OR REPLACE INTO unified_memories (id, ...) VALUES (?, ...)`，
新建时把 `id` 绑成 `0`。而 `unified_memories.id` 是
`INTEGER PRIMARY KEY AUTOINCREMENT` —— SQLite 会把 `0` 当作**合法的 rowid 0 显式插入**，
于是第二次新建就 REPLACE 掉第一条：**记忆永远只剩一条。**

Room 对 `@PrimaryKey(autoGenerate = true)` 绑定的正是 `nullif(?, 0)`（把 0 变 NULL 触发自增）。
已用真实 SQLite 验证两种写法的差异，并在装置里锁住：

| 写法 | 第一次 | 第二次 |
|---|---|---|
| `VALUES (?, ...)` 传 0 | id 0 | **REPLACE 掉第一条** |
| `VALUES (NULLIF(?, 0), ...)` | id 1 | id 2 ✓ |

### 与 `MemoryStoreImpl` 对齐时发现的其它差异（均已修正）

| 项 | 原先（错） | Android 实际 |
|---|---|---|
| 召回过滤 | 只有 `isDeleted = 0` | 还有 **`deviceId = ?`** 与 **`(expiresAt IS NULL OR expiresAt > now)`** |
| 召回排序 | `importance DESC, lastAccessedAt DESC` | `importance DESC, **observedAt** DESC` |
| `source_id` 缺省 | 不过滤 | 取 **0**（仍参与过滤） |
| 活动时间来源 | `unified_memories.lastAccessedAt` | **`messages` / `archived_messages`**，且是 `recent ?: archived`（热表优先，不取最大值） |
| `companionId` 为 nil | 全局最大值 | **0** |
| 整理时间存储 | `app_meta` 表 + 自定义键 | UserDefaults + 键名 `consolidated_at_<id>` / `consolidated_at_global` |
| 空白 content | 照常落库 | **直接放弃写入** |
| `source` | 硬编码 `CHAT` | scope 为 `GROUP` 时用 `GROUP_CHAT` |
| `confidence` 默认 | 0.5 | **1.0** |
| `accessCount` 默认 | 0 | **1**（且下限为 1） |

**这些都不会报错，只会静默返回错误结果** —— 例如召回别的设备的记忆、
召回已过期记忆、或在错误的时机触发记忆整理。

### ⚠️ 第七个已修复的问题：`maxRounds` 取值是我自己定的

回合上限 `maxRounds` 我原先写 **16**，理由是「避免 0 被 Rust 兜底成 1」——
意图没错，但**取值是我自己定的**。Android 主对话路径
（`AgentDialogueCoordinator.kt`）用的是 **6u**（委托路径 3u、评估用例自定，均非聊天路径）。

Rust `agent.rs` 的循环是 `while rounds_used < max_rounds`，另有单回合工具调用硬上限
`MAX_TOOL_CALLS_PER_TURN = 64`。所以这个值直接决定**模型一个回合最多能调几轮工具**：
调大 → 同一问题反复调工具、成本与时延上升、行为与 Android 不同。

已改为 6，并加了契约断言把 Android 侧的 `maxRounds = 6u` 与 Swift 侧的值绑在一起。

### 已实现：记忆管理界面（`MemoryListView`）

M3 第一个 store 界面。设计要点：

- **管的是"会进提示词的那批"** —— `listActive()` 的过滤条件与
  `AgentStores.listMemories` 逐字一致，所以界面看到的 = 模型注入的
- **软删除**（`isDeleted = 1`），与 Android `softDelete` 一致；删除即时生效
- 摘要行显示 有效 / 已删除 / 合计，可切换显示已删除项
- 空状态给出可操作提示（而非空白列表）
- 删除失败会显式报出条数，不静默

接在自检面板「进入对话」旁，`environment.memoryRepo == nil` 时禁用。

### API 覆盖检查器增强：「无 UI 入口」判定 —— 以及它立刻骗了我一次

第 97 轮给检查器加了「视图层是否引用」判定。第一次运行报 6 项，
我逐条看可达路径，判断出 **1 个真缺口**（`cancelCurrentTurn`）和 5 个误报。

**第 98 轮核实后发现那个「真缺口」也是误报**：

```
ChatView.swift:40   Button("停止") { session.cancel(in: environment) }
ChatSession.swift:190 func cancel(in:) { environment.runtime?.cancelCurrentTurn() }
```

**「停止」按钮一直存在**，链路是 View → ChatSession → runtime 两跳，
而检查器只认**直接**包含 `.cancelCurrentTurn` 的视图层文件。
我在上一轮明明刚写下「机械检查缩小范围、判断仍要人做」，
**然后第一条就没亲自追可达路径就下了结论。**

**这个错误的性质比工具缺陷严重**：我把「工具报了」当成「我验过了」。
如果按上一轮的记录直接去做「加停止按钮」，会做出一个重复按钮 ——
而在一个没有编译过的代码库上，重复功能很可能 duplicate 到真实故障。

### 判定规则（修正后）

「无 UI 入口」清单的每一项，**必须手工追完整可达路径**才能定性：
- 追到 View / `AppEnvironment` 的公开方法 → **可达**，不是缺口
- 只追到服务层内部、任何入口都不到 → 才是真缺口

追不了完整路径时（跨文件、经协议、经回调），**宁可标「待核实」也不要标「缺口」**。
误报会让人去做重复功能，而漏报只是在清单上多留一行 —— **两种错误的代价不对称**。

### 「被引用但无 UI 入口」的 5 项已确认全部可达

| API | 可达路径 |
|---|---|
| `testOpenai` / `testAnthropic` | UI 按钮 → `testConnection` → 它们 |
| `runTurnStream` | ChatView → ChatSession → 它 |
| `setWorldbook` | ChatSession → `syncActiveToRuntime` → 它 |
| `cancelCurrentTurn` | ChatView「停止」→ `ChatSession.cancel` → 它 |

**∴ iOS 侧「停止」能力完整，无缺口。**

`ApiProbeService` 现已覆盖 Rust 的四个探测方法。`testConnection` 是其中最有用的一个：

| 方法 | 用途 | 与另两个的关系 |
|---|---|---|
| `fetchModels` | 拉模型列表 | 有些服务端**不支持 `/models`**（返回 HTML → Rust 报「该API不支持模型列表查询」） |
| **`testOpenai` / `testAnthropic`** | 发一个最小请求验证连通性 | **上面那条失败时的退路** —— 走真对话端点 |
| `queryBalance` | 查余额 | 多数服务端不返回结构化余额，Rust 侧失败时会带原因 |

**协议选择交给 Rust**：用生成绑定里的
`apiUsesAnthropicProtocol(provider:formatHint:)`（`LianyuAgent.swift:9098`）
而不是自己 `if provider == "ANTHROPIC"` —— 前者是权威判断，
`formatHint` 可能覆盖 provider 的默认行为。

`testConnection` 已接 UI（凭证区「测试连接」按钮）。

### ⚠️ `verify_api_coverage` 的一个已知局限

接入后 app→Rust 计数从 8 → 11，`ApiProbe` 从未评估清单消失。但**这个检查器
只验证「被引用」，不验证「用户能触达」**：

`queryBalance` 被算作"已调用"，是因为它在 `ApiProbeService` 里被引用了一次，
但**没有 UI 触发点** —— 用户根本摸不到它。上一轮的 `fetchModels` 也是同样情况
（先有服务层、下一轮才补按钮）。

**这意味着"未评估清单清空"不等于"功能可用"。** 判据只能是：
清单项 + 是否有 UI 入口，两者都要看。这个局限写在这里，
免得将来有人看到"0 项未评估"就认为探测功能完整。

### `fetchModels` 已有 UI 入口（凭证区）

上一轮只接了服务层（用户触达不到），本轮补上自检面板「凭证」区的
**「拉取模型列表」按钮**：显示进度、结果条数、失败原因，
并把模型名以**可选中复制**的形式列出（用户要把它填进 provider 配置）。

**过程中又核实出一处不能想当然**：我一开始调 `AgentSettings.buildExtraHeaders(...)`
—— 这个函数**不存在**。PARTNER 的 `X-LianYu-Session` / `X-LianYu-Client-Id` 头
在回合内是 **Rust 依 credentials 自行拼装**的，而 `fetchModels` 需要**宿主显式传**。
已改为 `ApiProbeService.authHeaders(provider:apiKey:partnerSession:partnerClientId:)`，
规则来自生成绑定的文档注释（PARTNER / XIAOMI→`api-key` / 其余→`Authorization: Bearer`）。

**两条路径用同一份 Keychain 数据，故结论一致** —— 这点写进了注释，
否则将来有人改了一处忘了另一处，会出现"这里能拉、回合里却失败"。

### 已接 `ApiProbe.fetchModels`（机械核对带来的第一批成果）

第 93 轮的机械核对发现 `ApiProbe` 四方法全未接，本轮接入 `fetchModels`
（`GET /models`）。

**过程中核实出两处"不能想当然"**：

1. **apiKey 不能从 `ApiConfig` 拿** —— 我的 `ApiConfig` 结构体刻意**不含** apiKey：
   iOS 侧没有 Android 的 Tink 列加密，把明文 key 写进 `api_configs` 行等于
   把密钥落进**未加密的 SQLite 文件**（第 40 轮的安全决策，源码注释标明"勿改"）。
   真 key 只在 Keychain（`KeychainStore.Key.apiKey`）。
   **若按直觉写 `config.apiKey`，会编译失败——但更糟的情况是它编译通过却传了空串。**
2. **错误类型是 `ApiProbeError.Message(message:)`，不是 `ProbeFailed`** ——
   我第一版按 Rust 的习惯命名猜的，核对生成绑定后改正。

**配置字段顺序**也从 `FfiConverterTypeApiProbeConfig`（`LianyuAgent.swift:7998-8006`）
核对过，而不是按直觉排：provider / baseUrl / apiKey / model / temperature /
maxTokens? / formatHint。

**机械核对的即时反馈**：接入后 app→Rust 方向的调用数从 7 升到 8，
`fetchModels` 从"未评估"消失，剩 `queryBalance` / `testOpenai` / `testAnthropic`
三个仍未接。**这个检查器第一天就证明了它能驱动工作。**

**仍未做**：UI 入口（自检面板里加一个"拉取模型列表"）。
当前只有服务层，用户还触达不到 —— 这一步留到与 provider 选择 UI 一起做会更自然。

### 新增：API 覆盖面机械核对（报告式）

`verify_api_coverage.py` 把此前靠人工对比（第 79、92 轮撞出 `set_worldbook` 与
`registerGlobalTools`）的事情机械化：从生成绑定抽公开 API，看 iOS 是否调用。

**第一版连错三次，都是我的抽取逻辑问题**：

| 版本 | 症状 | 根因 |
|---|---|---|
| 1 | 0 个 API | 只认 `public class`，而 UniFFI 用 `open class` |
| 2 | 226 条假缺口 | 未截断类体 → 最后一个类吃到 EOF，struct 字段全算它的成员 |
| 3 | 7 个 API | 用「第一个列 0 的 `}`」截断，截太早 |
| 4 | 0 个 API | 边界集合含 `func` —— **类成员 `open func` 本身就顶格在列 0** |

**分类比数量更重要**：69 个 API 里 **47 个是 Rust→iOS 方向**（我在 Swift 里实现其
trait，方法由 Rust 回调）。它们"iOS 无调用"是正常的，不参与检查。
真正该看的是 **app→Rust 的 22 个**。

**本轮产出（真正的新发现）**：`ApiProbe` 的四个方法全部未接 ——
`fetchModels` / `queryBalance` / `testOpenai` / `testAnthropic`。
Android 的 API 设置页因此能「测试连接 / 拉取模型列表 / 查询余额」，**iOS 不能**。
后果具体：iOS 用户填了 key 无法在保存前验证是否可用，也无法从服务端拉模型列表，
只能手填模型名。这直接影响首次配置体验。

**为何仍是 report 而非 gate**：「零调用」既可能是真缺口，也可能是合理不调用
（Rust→app 方向、诊断快照、测试专用）。自动 gate 无法区分，
强行用白名单消噪会让它退化成橡皮图章。故每次核对人工过一遍新增项，
判断后写进 `KNOWN_UNUSED` 并留理由 —— **判断记录留在代码里**。

### 方法论：连续两轮用同一条规则化解两个"缺口"

第 89、90 轮总结出的读取规则 —— **先找启用开关，再看调用点**：

| 轮次 | 表面现象 | 实际 |
|---|---|---|
| 89 | `ChatGenerationManager` 调 `BanManager.isBanned` | `BAN_ENABLED = false` → 整个机制关闭，iOS 不实现才等价 |
| 90 | 图像生成未移植（功能缺口） | `DEFAULT_IMAGE_GEN_ENABLED = false` + iOS 无启用入口 → 等价 |

两条调用路径都真实存在，但都被默认值挡在后面。
**若按调用点判断，我会去实现两个不需要的东西。**

其中第 90 轮那处更隐蔽：`AgentReplyText.forDisplay(raw, imageGenEnabled)` 出现在
三条渲染路径上，看着像"iOS 少了一层文本清洗"；读实现才知道 `if (!imageGenEnabled) return raw`
—— 关闭时原样返回，iOS 直接显示模型输出是等价的。

**这条规则的代价是两次 grep，收益是少写几百行会让产品变差的代码。**

### 世界书合成已配测试（11 个用例，锁四处"静默错"）

`WorldbookRuntimeSyncTests` 用真实 schema 的临时库构造夹具，重点锁四处**不报错、
只悄悄产出不同内容**的变换：

| 用例 | 锁什么 |
|---|---|
| `testPriorityFallsBackToOrderBaseMinusInsertionOrder` | 无 `_priority` 时 priority = **2^32 − insertion_order**（得 100），不是 0 |
| `testDedupTieKeepsLaterBook` | 同 content 且 priority 相等 → **后出现者胜** |
| `testDedupKeepsHigherPriority` | 同 content 不同 priority → 高者胜，**即使出现更早** |
| `testSortAndRenumber` | priority 降序、同值 id 升序；`insertion_order` 必须重编号为 1..N |
| `testDisabledAndBlankContentAreSkipped` | 禁用条目与空白 content 都跳过 |
| `testEnabledIsAlwaysExplicitlyWritten` | 源无 `enabled` 时输出仍显式带 `true`（否则 Rust 会让禁用条目生效） |
| `testEntriesAcceptsMapForm` / `ArrayForm` | ST World Info 的**两种形态**都能解 |
| `testDepthOnlyWrittenWhenSourceHasIt` | 只在源 JSON 带 `depth` 时才写 |
| `testTopLevelFields` / `testEmptyResultWhenNoEntries` | `token_budget=10000`、`scan_depth=众数`、空态返回 `""` |
| `testUnboundCompanionGetsDedicatedPlusGlobalBooks` | 未绑定 → 专属书 ∪ 全局书；**别人的专属书不进来** |

### 世界书按伴侣合成已实现（契约读全后才动手）

第 86 轮实现 `synthForCompanion`。此前三轮（84–86）先把契约读全并修正了两处自己的错误记录，
这轮按完整契约一次写对。

**一个省掉整张枚举映射表的等价化简**：Android 走「解码 JSON → StEntry → 处理 → 再编码」，
其中 `position`/`role` 要经 `stToPosition` → `positionToSt` 往返，需要读两张枚举映射表。
但**源和目标都是同一个 JSON**，且 Android 写入时已用同一编解码器规范化过 ——
所以这里**原样保留字符串**，输出与 Android 等价。

**照契约实现的 8 步**：绑定规则 → 逐本解码（map/array 双形态）→ 过滤
→ 按 content 去重（并列取后者）→ 排序（priority 降序、id 升序）→
insertion_order 重编号 → 13 字段组装 → 顶层 `assemble`。

**三处容易写错的、已按契约处理**：
1. `priority` 缺失时回落 `ORDER_BASE(2^32) − insertion_order`，不是 0
2. `enabled` 必须**显式写 true** —— Rust 默认 true，省略会让禁用条目意外生效
3. `depth` 仅在**源 JSON 带了该键**时才写（等价于 Android 的 AT_DEPTH 条件，
   且不依赖我不知道的位置字符串取值）

**过程中自查出两处**：误删了 `activeGlobalBookJson`（冒烟+引用检查抓住）、
`depth` 条件写成永真（自己复查发现并改成"源里有没有这个键"）。

### 🚨 第十三个已修复的缺失：输入安全检查从未接线

第 81 轮按"系统性对比每回合动作"的方式核对 `ChatGenerationManager` 的回合流程，
发现 `ContentFilter.checkInput` 在 iOS 侧**只有实现、没有调用**。

## 但真正重要的发现是：它**不该**每回合无条件执行

第一反应是"输入安全检查应该每回合都做"。读了 Android 的实际代码后，
它的语义完全相反：

```kotlin
if (apiConfigRepository.getActiveEnabledConfig() == null && !hasCompanionBoundConfig()) {
    for (msg in contentBatch) {
        val inputCheck = runCatching { ContentFilter.checkInput(msg) }.getOrNull()
        if (inputCheck == null) { Error("安全检查异常"); return null }
        if (inputCheck.isViolating) {
            BanManager.recordViolation(...); _events.tryEmit(ContentBlocked(...)); return null
        }
    }
    Error("请先配置API：我 → API设置 → 添加密钥"); return null
}
// 有配置时：直接进入生成，**不检查输入**
```

也就是说：过滤只在**完全没有 API 配置**时执行，作用是"在报『请先配置API』之前先挡一把"。
**有配置时输入是不检查的。**

按直觉改成每回合检查 → 拦住 Android 会放行的输入 → 真实的跨端不一致，
症状是「iOS 上某些话发不出去」且无法归因。

**已实现** `Agent/ChatInputGuard.swift` 并接进 `ChatSession.send`：
- `shouldCheckInput(activeEnabled:companionBound:)` 是**纯函数**（可测），
  精确对应 `getActiveEnabledConfig() == null && !hasCompanionBoundConfig()`
- `hasCompanionBoundConfig` 复刻 Android 那条链：`apiConfigId` 为 null/≤0 → false；
  配置不存在 → false；`apiKey` 非空白**或** provider 为 PARTNER → true
- 过滤器未装载 → `checkFailed`（对应 Android 的 `inputCheck == null` → 「安全检查异常」）
- 为此给 `ContentFilter` 补了 `checkInputOrNil`：非可空的 `checkInput` 无法表达"未装载"

**又一次：「等价优先于更好」不是口号。** 这是它第三次挡住我的直觉（前两次是
clientId 语义、archive 语义）——每次都避免了真实的跨端不一致。

### 🚨 第十二个已修复的缺失：`set_worldbook` 从未被调用

第 79 轮审计「Rust 暴露给宿主的 API 是否都被接上」时发现
`AgentRuntime::set_worldbook` 在 iOS 侧**从未调用**。

Android 在**每个回合前**调用它（`WorldbookRepository.syncActiveToRuntime`
→ `AgentFacade.setWorldbook`），内容是用户配置的世界书（SillyTavern World Info 格式）。
**缺失的后果**：用户配的世界书完全不进入提示词，AI 不知道那些设定。
与 `orchestrator: nil`（第 24 轮）同类 —— 都是「该接没接」。

**已实现** `Data/WorldbookRuntimeSync.swift` 并接进 `ChatSession.send` 的每回合路径。

**当前实现了哪一支**（如实说明）：Android 的 `syncActiveToRuntime` 有两个分支：
```kotlin
val payload = if (companionId > 0L) synthForCompanion(companionId)   // 按伴侣合成
              else dao.active()?.json ?: ""                          // 全局启用书原文
```
iOS 实现了 `companionId <= 0` 这一支（取启用书 `json` 原文）。
**`companionId > 0` 的合成尚未移植** —— 该分支会**传 nil 并打日志**，
而不是塞一本全局书：`effectiveRows` 的绑定规则、`entryToJson` 的 13 个字段映射、
`assemble` 的顶层结构都与直接传某本书不同，硬塞会**静默注入错误内容**。
完整契约已从 `WorldbookJsonCodec.kt` 逐条提取，写在 `WorldbookRuntimeSync.swift`
末尾的注释里，作为下一步的实现规格。

### 新增：用 Java 正则引擎复核内容过滤金标向量

`MessageSearchTokenizer` 与 `ContentFilter` 的金标向量此前都由 Python 的 `re` 算出，
但 Android 用的是 `java.util.regex`。两者在**大多数**模式上一致，但在回溯语义、
`\b` 的 Unicode 解释、占有量词等边界上可能不同 —— 一旦不同，「金标」本身就是错的。

`ios/Tools/gen_regex_probe.py` 把 290 条过滤正则与 18 个测试输入烘成一段自包含 Java
源码，用 `java.util.regex` 复跑，与 Python 侧逐条比对等级判定。

**结果：18/18 条输入，等级判定完全一致。** 这补上了金标向量的一个真实盲区 ——
此前它们的可信度只建立在「Python 足够像 Java」这个未经检验的假设上。

已接为第 17 道关卡；无 JDK 时自动跳过（CI 的 ubuntu runner 自带）。

#### 生成 Java 探针时踩的三个坑（都记在脚本注释里）

1. **`"` 不能转成 `\u0022`** —— Java 的 `\uXXXX` 转义由**编译器在词法分析之前**处理，
   会先变成真正的双引号，把字符串字面量提前终止。只对非 ASCII 用 `\u` 转义。
2. **`{ "LEVEL", new String[]{...} }` 不是合法 `String[]`** —— 混合类型数组字面量，
   javac 报 `illegal start of type`。等级名应直接作为首元素。
3. 输出里只带了输入**长度**没带文本，导致比对脚本解包失败 —— 探针协议要先想清楚。

### V8：容器解密层已完成（含确定性夹具）

上一轮我说这层"需要一条 pip install cryptography"才能造夹具。实测发现**本机 PowerShell 7（.NET 10）自带 `AesGcm`** —— 夹具自己就能造，不需要外部依赖。

**已实现** `Data/BackupCrypto.swift`：
- 容器解析（`LYBK` magic 校验 + 截断拒绝 + salt/iv/ciphertext/tag 切分）
- PBKDF2-HMAC-SHA256 / 100,000 次 / 256 位（走 `CommonCrypto`，CryptoKit 未暴露 PBKDF2）
- AES-256-GCM 解密（`CryptoKit.AES.GCM`，Java `doFinal` 的 ciphertext||tag 由
  `SealedBox(nonce:ciphertext:tag:)` 重组）

**夹具确定性来源**：固定 salt/IV/ASCII 密码，由 .NET 标准库生成，
参数与 Kotlin `BackupViewModel.Crypto` 逐字一致。650 字节 / 明文 602 字节 /
派生密钥 `b3a0b166c342adb56792e9122887d1059c1e3bf006ede51a0925f9f5926dd9e3`。

**测试覆盖**：magic 错误抛 `badMagic`、截断抛 `tooShort`、
**派生密钥与夹具一致**（PBKDF2 三参数对齐的唯一证据）、端到端解密后可直接喂给
`BackupImporter`、错误密码抛 `decryptionFailed`。

**非 ASCII 密码的歧义已排除（第 77 轮实测）**

原以为 `PBEKeySpec(char[])` 的密码编码需要真实导出文件才能对齐。实测发现**本机有 JDK 21**，
可以直接跑 Android 的同款 API：

| 密码 | Java 派生密钥（PBKDF2WithHmacSHA256） | Python UTF-8 | 一致 |
|---|---|---|---|
| `yunian-backup-test` | `2dfd0834...` | `2dfd0834...` | ✅ |
| `密码pass1😀` | `28b63441...` | `28b63441...` | ✅ |

**结论：OpenJDK 的 `PBKDF2WithHmacSHA256` 按 UTF-8 编码密码**，与 iOS 侧
`BackupCrypto.deriveKey` 用 `password.utf8` 完全一致。**该阻塞不存在。**

⚠️ 但过程值得记住：我**第一版探针是错的**——salt 用了十六进制字符串的 ASCII 字符
（48 字节）而不是那 16 个字节，导致三个标准编码全部对不上，据此差点写成"需要真实导出文件"。

**教训**：实测结果与预期不符时，先怀疑自己的测试装置，而不是急着记一个阻塞。
这次如果直接采信第一版结果，就会在 README 里留下一个虚假阻塞，并让 iOS 侧
去做一套不必要的兼容逻辑。

### ⚠️ V8 关键发现：备份文件是**密码加密容器**，我的导入器目前只吃明文

第 73 轮读 `BackupViewModel.Crypto` 才发现：`BackupData` 只是**明文结构**，
而 `.lybk` 文件是加密容器：

```
偏移  长度  内容
0     4     MAGIC = "LYBK"（UTF-8）
4     16     salt（SecureRandom）
20    12     AES-GCM IV
32    rest   ciphertext + 16 字节 GCM tag
```

密钥派生：`PBKDF2WithHmacSHA256`，**100,000 次迭代**，256 位，salt 取文件内 16 字节。
`BackupViewModel.kt:131-177`

**这意味着 `BackupImporter` 当前签名 `importBackup(_ json: String)` 只能处理明文，
还不能消费真实备份文件** —— 需要在其前面加一层容器解密。

**一个必须先拿到测试向量才能动工的歧义**：`PBEKeySpec(password.toCharArray(), ...)`
的密码编码。Java `PBKDF2WithHmacSHA256` 对 `char[]` 的字节化方式在纯 ASCII 密码下无歧义，
但**含非 ASCII 密码时 UTF-8 与 UTF-16BE 会产生不同密钥**，表现为「密码明明正确却解不开」
且无任何报错线索。

**因此这一层不能在没拿到真实导出文件前凭资料实现。**
需要的东西很具体：一个由 Android 侧导出的 `.lybk` 文件 + 对应密码，
在 iOS 侧跑通 PBKDF2 → AES-GCM → JSON 即为通过。

**已明确的部分**（可直接照做）：容器布局、迭代次数、密钥长度、
GCM 的 12 字节 IV 与 16 字节 tag、`require(magic == MAGIC)` 的校验。

#### 夹具可以本地生成（第 75 轮核实：不需要等 Android）

原以为必须有真实导出文件。实测本机环境后修正：

```python
$ python -c "import cryptography"   # ModuleNotFoundError
```

即本机**缺 AES-GCM**，但只缺这一个。以下 5 行即可生成合法夹具
（PBKDF2 参数与 Android 逐字一致；**ASCII 密码下无编码歧义**）：

```bash
pip install cryptography
python - <<'EOF'
import hashlib, os, json
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
salt, iv = os.urandom(16), os.urandom(12)
plain = json.dumps({"version": 1, "exportedAt": 0, "appVersion": "fixture",
    "companions": [], "chatMessages": [], "chatGroups": [], "groupMessages": [],
    "memoryEntries": [], "tempMemories": [], "tokenUsages": [],
    "unifiedMemories": [], "diaries": []}).encode()          # 替换为任意备份内容
key = hashlib.pbkdf2_hmac("sha256", b"<ASCII密码>", salt, 100_000, 32)
blob = b"LYBK" + salt + iv + AESGCM(key).encrypt(iv, plain, None)
open("fixture.lybk", "wb").write(blob)
EOF
```

**PBKDF2 一侧已在本机独立验证**：`hashlib.pbkdf2_hmac("sha256", ...)` 可用，
与 `PBKDF2WithHmacSHA256` / 100,000 次 / 256 位一致（ASCII 密码下字节化无歧义）。

**结论**：容器解密层的阻塞已从「需要 Android 侧导出」降级为
「需要一条 `pip install cryptography`」。**这是我自己上一轮的判断过头** ——
我把"没有现成工具"当成了"需要对方配合"。

### V8：导入器已配测试（12 个用例，夹具来自权威格式）

`BackupImporterTests` 按 Android `BackupData.kt` 的字段形状构造夹具，重点锁三条易错语义：

| 用例 | 锁什么 |
|---|---|
| `testCompanionMatchedByNameAndChildrenRemapped` | 按 **name** 复用已有伴侣；消息改指向新 ID（而非快照里的 100） |
| `testCompanionCreatedWhenNameNotFound` | 无同名伴侣时新建，拿到**自增 ID 1** 而非快照 ID |
| `testDeviceIdIsRewrittenToLocal` | **最关键**：4 张表的 deviceId 都必须变成本机 ID；否则导入的记忆召不回（与第 10 轮修的 `listMemories` 过滤直接相关） |
| `testAnchorMessageIdFilledInSecondPass` | 锚点指向第一条的**新** ID，且不等于快照里的 900 |
| `testReimportIsIdempotent` | 同一备份导两次：伴侣 1 条、消息 2 条，不重复 |
| `testGroupMessageSystemSenderSentinel` | `companionId == -1` 原样保留，不进 ID 映射 |
| `testMalformedJSONThrows` | 非法 JSON / 数组均抛错 |

**新门禁立刻抓到文档漂移**：测试文件刚加完，`verify_docs_coverage` 就报
「BackupImporterTests 未列入 runbook」——补进清单后通过。第 67 轮建的那道门禁
在成立的同一轮就第一次兑现了价值。

### V8 首批交付：`BackupImporter`（iOS 侧导入 9 分区）

第 71 轮按 Android `BackupImportService` 逐行对照后实现。**三条极易做错的语义已复刻**：

1. **不保留 ID，按 name 重映射** —— 伴侣/群组匹配已有记录并复用其 ID，
   否则新建并入映射表；所有子记录经映射表改写。
   群组成员取「已有 ∪ 备份」并集。
2. **`deviceId` 改写成导入设备的 ID** —— 对应 Android 的 `deviceId = deviceId`。
   **这条最关键**：第 10 轮我刚修过 `listMemories` 的 deviceId 过滤，
   若这里沿用快照的源设备 ID，导入的记忆会「导入了但召不回」。
3. **`anchorMessageId` 必须二阶段** —— 先插完全部消息、映射表齐全后才回填锚点；
   边插边填会大面积丢失。
   另：群消息的 `companionId == -1` 是系统发送者哨兵，**不进 ID 映射**。

消息去重按 `(timestamp, isFromUser, content)`（群聊为 `(timestamp, senderId, content)`），
因此同一备份重复导入不会产生重复消息。

**门禁当场抓到 3 个真实错误**（新写 20 条 SQL 一上来就跑）：
```
[FAIL] no such table: temp_memories    → 实际表名 temp_memory（单数）
[FAIL] no such table: token_usages     → 实际表名 token_usage（单数）
[FAIL] no such table: diaries          → 实际表名 diary_entries
```
表名/列名已与 `45.json` 逐一核对一致（SQL 检查：100 条抽取 / 98 条执行 / 0 错误）。
这正是「新代码马上过门禁」的价值 —— 不需要等真机。

**一处刻意差异（已记录）**：Android 的 `message_bodies` 是
`ChatMessageCrypto` 密文（AndroidKeyStore 硬件密钥），导入是「解密 → 重加密」两段；
iOS 侧存明文，所以这里是「明文直写」。

### V8 评估完成：格式已明，缺口在覆盖面

第 70 轮把 V8（跨端数据迁移）的阻塞点摸清了。三个结论：

**① 加密不是阻塞点。** Android 的 `BackupExportService` 在导出时**于设备上解密**：
```kotlin
// BackupExportService.kt:37-41
val raw = db.messageDao().getAllMessagesSync(c.id, "chat")
chatMessages.addAll(ChatMessageCrypto.decryptFromStorage(raw.map { it.toChatMessage() })...)
```
`ChatMessageCrypto` 的密钥来自 **AndroidKeyStore（硬件、不可导出）**，
但导出链路是「设备内解密 → 写明文 JSON」。所以 iOS 侧拿到的是明文，
导入时用自己的（Keychain / Secure Enclave）密钥重新加密即可 —— **技术可行**。

这修好了我之前记录里的一处错误判断：我曾写「3 套字段级加密方案使 DB 文件迁移不可行」。
**准确说法是：DB 文件级迁移不可行（密钥不可导出），但逻辑导出可行。**

**② 格式已完全明确。** `BackupData` 是 kotlinx.serialization 的纯 JSON，
9 个分区，无整文件加密（`BackupData.kt` 全文 149 行，已逐字段核对）。

**③ 缺口在覆盖面 —— 这才是 V8 的真正工作。** 9 个分区 vs schema 的 32 张表，
**未覆盖**：`agent_skills`（技能定义 + 正文文件）、`worldbook_entities`、
`sticker_entries` / `sticker_tags`、`conversation_summary`、`app_meta`、
以及种子类表（`keywords` / `quiz_questions` / `api_provider_presets`）。
另注意 `api_configs` 是**刻意不迁移**的（`BackupData.kt:35` 注释：API 配置不随备份迁移）。

**下一步（我可单方面推进的部分）**：
1. 写 iOS 侧导入器，覆盖现有 9 个分区（可先用「从权威格式构造的夹具」测试，
   不需要真机导出）
2. 产出一份「导出格式需扩展的字段清单」给 Android 侧 —— 这是需要对方配合的部分

### 验收标准核对表（V1–V10，第 69 轮正式评估）

以前我描述进度用"M0–M5 完成度"，那是**自我叙述**。这一轮改为对着文档 §9 的
V1–V10 逐条核对 —— 结论比我之前讲的更冷峻：

| 编号 | 验收项 | 状态 | 证据 / 阻塞原因 |
|---|---|---|---|
| V1 | Rust Agent 编到 iOS | ❌ **未验证** | CI job 已配好但从未跑过；`ring` ARMv8 汇编与 bundled C 需真机编译。**这是路线 B 的最大复用点，也最关键的未知** |
| V2 | UniFFI 生成 Swift 绑定 | ⚠️ **部分** | 绑定**已生成**（`ios/Generated/LianyuAgent.swift`，Windows 上从 DLL 生成成功）；但「在 Xcode 工程 import 并调用」未验证 |
| V3 | 流式 HTTP 在 iOS 可用 | ❌ **未验证** | Swift 侧链路已写完（ChatSession → turnQueue → SSE → StreamSink），但从未运行 |
| V4 | FTS 可用性探测 | ✅ **完成** | 运行时探测 FTS4→FTS5 回退；Python 装置对两个变体各跑 140 项断言 |
| V5 | schema 能被 Rust 读写 | ✅ **完成** | `45.json` → 生成 Swift schema（与 Room 逐字节一致）、`user_version=45`；Rust 的两条真实 SELECT 已在真实 SQLite 上验证可用 |
| V6 | 付费账号可得 APNs | ❌ **阻塞** | 需 $99/年，未购买 |
| V7 | 「主动找你」可行上限 | ❌ **阻塞** | 需真机 + 7 天实测 |
| V8 | 跨端数据迁移 | ❌ **未做** | 属 M6；已确认 3 套字段级加密方案使 DB 文件迁移不可行，需逻辑导出 |
| V9 | 请求签名跨端一致 | ❌ **阻塞** | 3 处不一致点已定位并尽量向 Android 对齐；需服务端给权威 payload 规范 |
| V10 | 第三方 UI 库 iOS 产物 | ✅ **因设计选择而消解** | 走路线 B（原生 SwiftUI）而非 Compose Multiplatform，`kyant-*` / `iconsax` / `coil` 本就不用；该项风险不适用于本实现 |

**核对结果：2 项完成（V4/V5）、1 项因设计选择消解（V10）、2 项部分（V1/V2）、5 项未完成（V3/V6/V7/V8/V9）。**

这个账比我此前说的"M0–M3 基本完成"更准确：**我完成的集中在数据层与契约层（可离线验证的部分），
而涉及真实运行的部分全部未验证。**

由此得出下一步的真正排序（按"解除阻塞所需条件"而非工作量）：
1. **V1/V2/V3** —— 一台 Mac。三条一起解决，是 Route B 成立的前提
2. **V9** —— 与服务端团队一次沟通
3. **V6/V7** —— $99/年 + 一台真机 + 7 天
4. **V8** —— 我自己可推进的 M6 工作（逻辑导出/导入）

**换言之：剩余工作中，只有 V8 是我能单方面推进的。**
这解释了为什么我这一路的产出从"写功能"转向"补验证"—— 不是我变保守了，
是可推进的空间确实在收窄。

### M4 剩余项的分类修正：`set_alarm` 从「不可移植」降级为「未核实」

第 68 轮复核我自己的分类时发现一个问题：

我之前把 `device_set_alarm` 记为「**iOS 无等价 API**」，依据是「Android 只是跳转闹钟 UI
让用户确认，iOS 没有这个入口」。但这个判断建立在**未经核实的记忆**上 ——
iOS 26 引入了 AlarmKit（可弹出系统闹钟设置界面），而我**无法确认它是否 backward-deploy
到 iOS 17**（本项目的部署目标）。

当前环境无法联网核实（web_search 无 API key）。

**所以这个分类应当降级为「未核实」而不是「不可移植」。** 这个区别是实质性的：
- 若 AlarmKit 在 iOS 17 可用 → 这是一个可移植工具，我之前的分类会导致无理由跳过
- 若只在 iOS 26+ → 需要把部署目标提高到 26，那是产品决策

**待核实项**（一条命令可答）：
```bash
# 在有 Xcode 的机器上
xcrun --sdk iphoneos --show-sdk-version
# 然后查 AlarmKit 的 availability：
grep -r "AlarmKit" "$(xcrun --sdk iphoneos --show-sdk-path)/System/Library/Frameworks/" 2>/dev/null | head
```

### `web_fetch` 的移植评估（批次3 第一个）

比 `set_alarm` 明确：**可移植，但工作量不小**。

| 依赖 | Android | iOS 等价 |
|---|---|---|
| HTTP 客户端 | OkHttp（6s 连接 / 12s 读取） | `URLSession`（可配同等超时） |
| HTML → 纯文本 | 自建剥离 + 8000 字符上限 | `NSAttributedString(html:)` 或自建剥离 |
| GitHub raw 不可达 | `GitHubMirrors.candidates` 镜像优先 | 逻辑可直接移植 |
| 调用方式 | 同步 suspend | 需与 `open_url` 同类的异步桥接 |

**判断**：技术上没有阻塞项，但它是批次3 里第一个需要「真网络 + HTML 解析」的工具，
而这两项**恰恰是我无法本地验证的**。在 M0 跑通前做它，等于把不可验证的部分叠在
另一个不可验证的部分上。

### 文档漂移防护：runbook 必须覆盖全部测试文件

第 67 轮发现 **`M0-RUNBOOK.md` 漂移了**：它写了十几轮，期间新增了 4 个测试文件，
`DeviceToolsTests` 不在清单里。

代价具体说：跑 M0 的人拿着旧清单，看到莫名的测试失败会误判为自己环境的问题；
或者报告「全部通过」时其实漏跑了新测试。

已修文档 + 新增第 16 道门禁 `verify_docs_coverage.py`：
runbook 未提及任何一个测试文件即失败。经变异测试验证（塞一个 `ZZZTempTests.swift`
→ 立刻报出）。

顺带把 runbook 的手动步骤从 7 步补到 9 步（新增「表情标签」面板与
「凭证区换 provider」），并把 `device_open_url` / `device_notify` 的
**10 秒卡顿判据**写进第 5 步 —— 这样跑 M0 的人不需要从零排查桥接问题。

### 新增第 15 道门禁：工具定义交叉核对

`verify_tool_contracts.py`：从 Android Kotlin 源码抽取工具定义
（name / description / JSON Schema），与 Swift 侧逐字比对。

**为什么值得单独立门禁**：工具定义是**模型侧契约**，
description 或 Schema 差一点，模型看到的能力边界就不同，且**不报错**。
此前这个一致性只存在于我手写的 Swift 断言里——如果 Android 改了 description，
只有人记得同步测试才发现。

当前覆盖 7 个工具（6 个设备类 + `load_skill`），**全部逐字一致**。
另有 2 个 Android 工具显式跳过并记录原因：

```
[skip] device_open_app  —— iOS 无法按应用名枚举/启动其它 App
[skip] device_set_alarm —— iOS 无「跳转系统闹钟界面并预填」的入口
```

**用「显式白名单 + 记录原因」而不是忽略**，这样将来若有人补了这两个工具，
门禁会自动开始核对它们；若 Android 新增工具，也会立刻报「缺失」。

**经两类变异测试验证**：改 description → 报不一致；
改工具名 → 同时报「Swift 缺失」+「Swift 多余」。

### M4 批次2：`device_notify` 完成 —— 可移植项已全部做完

契约逐字对齐 Android。iOS 侧用 `UNUserNotificationCenter`，
**权限未授予时的文案照搬 Android**（「若未授予通知权限，请已在系统设置中允许」），
因为模型会把这句话原样转述给用户。

**两处诚实的记录**：

1. **通知 ID 无法跨端一致** —— Kotlin 的 `String.hashCode` 是 JVM 规范，与 Swift
   `Hasher` 不同。通知 ID 是**本地**概念、不跨端共享，所以我只保证
   「同一 title 得到同一 ID」（用于替换旧通知），并在注释里写明不保证数值一致。
2. **UserNotifications 的 API 凭资料写的、未经编译器验证** ——
   自有类型门禁只能确认「本仓没声明这些类型」，**无法确认框架 API 是否正确**。
   这一点已写进门禁白名单的注释里，作为首次编译的重点核对项。

**至此批次2 的可移植项全部完成（6/8）**：

| 工具 | 状态 |
|---|---|
| `device_get_time` / `device_battery_status` | ✅ |
| `device_get_clipboard` / `device_set_clipboard` | ✅ |
| `device_open_url` / `device_notify` | ✅（均带异步桥接，需实测） |
| `device_set_alarm` / `device_open_app` | ⚠️ **iOS 无等价 API**，需产品定方案 |

### M4 批次2：`device_open_url` 完成，但带一个必须实测的假设

契约逐字对齐 Android。**scheme 白名单只认 http/https** ——
这是安全边界：拒绝 `file://` / `javascript:` 等，避免模型诱导打开本地文件或危险协议
（大小写敏感，与 Kotlin `startsWith` 一致，已测）。

## ⚠️ 一个我在注释里标红的死锁风险

Android 的 `startActivity` 是同步的；iOS 的 `UIApplication.open` 是**异步、且必须主线程**，
而工具回调跑在 Rust 的回调线程上。我用 `DispatchSemaphore` + 派发主队列来桥接。

**这个桥接依赖一个我无法本地验证的假设**：主线程没有被 Rust 回合阻塞。

按当前实现，`ChatSession.send()` 把阻塞调用放到 `turnQueue`、主 actor 只是 `await` 挂起，
所以主队列应当空闲。**但"应当"不是证据。**

若假设不成立，症状是**打开网页卡 10 秒后超时**——不是明确死锁，而是一个难以归因的慢。
我加了这个判断依据：实测中若涉及打开 URL 的回合出现 10 秒卡顿，就是这个桥接的问题。

**纯逻辑（scheme 校验、参数提取）已抽成函数并测试**，与不可测的桥接部分分离。

### M4 批次2：剪贴板工具完成

`device_get_clipboard` / `device_set_clipboard` 均已实现，契约逐字对齐 Android。

**三处容易做错的点，都在代码注释里标明**：

1. **读取截断到 2000 字符** —— 对应 Android 的 `text.take(2000)`。
   这是一道安全护栏：防止把超大剪贴板内容灌进模型上下文（也降低泄露面积）。
2. **`length` 用 `utf16.count`，不是 `count`** —— Kotlin 的 `text.length` 是
   **UTF-16 代码单元数**，而 Swift `String.count` 是**字符数**。
   对含 emoji / 组合字符的文本两者不同；用 `count` 会报出与 Android 不一致的长度。
3. **iOS 的 `UIPasteboard.string` 把 Android 的两种情况合并为 nil**
   （服务不可用 vs 内容为空），因此共用同一句错误文案 —— 语义与 Android 对齐。

剪贴板属用户数据，两个工具的 description 都保留了 Android 的
「应用在前台时才能读取」提示（iOS 侧同样是前台才可读）。

### M4 批次2：`device_battery_status` 完成

Android 用 `ACTION_BATTERY_CHANGED` sticky broadcast；iOS 用 `UIDevice` 的 battery API。
**必须先开启 `isBatteryMonitoringEnabled`**，否则 `batteryLevel` 恒为 -1
（对应 Android 的 `level < 0` 异常路径）—— 这点已写进注释。

两个逻辑点抽成了纯函数以便测试（同 `weekdayName` 的做法）：

| 函数 | 测什么 |
|---|---|
| `percent(fromBatteryLevel:)` | 取整方式 —— 见下 |
| `isCharging(batteryState:)` | 全 4 个 BatteryState |

**一处有意的偏离，已用测试钉住**：Android 是整数除法 `level*100/scale`（**向下取整**），
而我用 `rounded()`。后果是 `0.999 → 100` 而非 `99`。
理由：iOS 的 `batteryLevel` 是浮点，`0.999` 在 UI 上本就显示为 100%，
取整成 99 会让模型读到与用户屏幕不一致的数字。
**这是"我判断更好的做法"，所以必须显式记录为偏离** —— 而不是假装与 Android 一致。

### M4 批次2：`device_get_time` 已配测试，星期映射锁死

新增 `DeviceToolsTests`，覆盖：

- **`weekdayName` 全 7 值 + nil + 越界**（0 / 8）—— 这是 `Calendar.weekday`（1=周日）
  与 Kotlin `DayOfWeek`（1=周一）**差 1** 的直接防线；
  另有一条断言 7 个值必须映射到 **7 个不同**星期名（防多处落同一分支）
- 工具契约：name / description / JSON Schema / toolsets 逐字对齐 Android
- 返回 JSON 的键与 Android `okResult("datetime", "weekday")` 一致

**测试本身抓到的两个自身缺陷**：
1. 第一版用固定 `Date` 断言绝对时间，但实现用 `Calendar.current`
   → **测试会依赖运行机器的 TZ**。已改为给 `executeGetTime` 注入固定时区的
   `Calendar`（默认仍是 `.current`，不影响生产路径）
2. 新引入的 `DateComponents` / `NSError` 触发自有类型门禁 →
   核实为真实 Foundation 类型后加进白名单（**先核实，不直接放过**）

### M4 批次2 开工：第一个设备工具 `device_get_time`

按纪律先核对 Rust 侧：内置工具只有 `emit_segmented` / `send_sticker` /
`emit_bubble`（`agent.rs:281-283`），**没有设备类** —— 确认是真正的新能力。

批次2 的 8 个工具里，从 `device_get_time` 开始，因为它风险最低：
无权限、无外部服务、无用户数据、纯本地计算。先用它验证「新增一个领域工具」的
完整接线，再逐步加其余的。

定义逐字对应 Android 的 `DeviceGetTimeTool`（description、JSON Schema、
返回键 `datetime` / `weekday`）。

**一个必须处理的偏移**：`Calendar.weekday` 是 **1 = 周日**，
而 Kotlin 的 `DayOfWeek` 是 **1 = 周一** —— 两者差 1。
所以我用显式 `switch` 分支而不是算法换算，并把偏移写进注释。
这类"差一"错误不报错，只是星期全错一位。

**过程中的两次自查**：
1. 删除旧代码时把注释和 `if let skill {` 合并到同一行 → 括号不平衡，
   被词法冒烟立刻抓住
2. 引入了无用的 `pad` 闭包（死代码）→ 自己复查时删掉

### M4 批次1 复查：大部分已在第 45 轮完成

第 58 轮准备开工「用户资料 / 记忆召回」两个工具，动工前核对 Rust 侧既有能力：

```rust
// memory_selector.rs:319-340
pub fn memory_tool_definitions(&self) -> Vec<ToolDefinition> {
    name: "recall_memory" ... name: "save_memory" ... name: "consolidate_memory"
```

**Rust 的 `MemorySelector` 已经定义并执行这三个工具**
（`memory_tool_definitions()` + `execute_memory_tool()`），
而第 45 轮的 `AgentToolCatalog` 接的正是这套（定义来自 Rust、执行委托 Rust）。

因此 Android 的 `MemoryRecallTool`（feature:memory）是**重复能力** ——
移植它只会造出同名工具（还会因 `seen.insert(t.name)` 去重而被丢弃）。

**连带确认**：`prompt_orchestrator.rs:293` 的「记忆技能」片段以
`available_tools` 同时含这三个工具为条件 —— 该条件已满足，
所以这条注入链也是通的。

**批次1 剩余**：只剩 `get_user_profile`（`core:agent`）。但它是
`appLocalOnly = true`，而 `availableTools()` 默认 `includeAppLocal = false`
→ **它在 Android 主对话路径上默认就不可见**。移植它的价值因此很低。

**修正后的批次1 结论**：实际已完成，无需开工。
下一个有真实价值的是**批次2（设备类工具）**：闹钟 / 通知 / 剪贴板 / 时间 / 电池
状态 —— 这些在 iOS 上都有等价 API，且默认可见。

### 范围修正：领域工具移植不是「M3 剩余项」，是独立的阶段

第 57 轮评估了最后一项「领域工具 ToolRegistry 移植」，结论是**它被我严重低估了**。

Android 侧有约 **40 个生产 `AiTool` 实现**，散落在 7 个模块：

| 模块 | 工具数 | 依赖 | iOS 可行性 |
|---|---|---|---|
| `core:agent` | 3 | ChannelSend / 用户资料 | 部分 |
| `feature:coffee` | 6 | 瑞幸下单 API + `CoffeeOrderProvider` | 需对接外部服务 |
| `feature:automation` | 5 | WorkManager / FGS 保活 | ❌ 不可移植 |
| `feature:chat` | 4 | 搜索 API / Timeline / Summary | 部分 |
| `feature:memory` | 1 | `MemoryProvider` | ✅ 已有等价物 |
| `feature:skills` | 21 | **DeviceTools 8 / AccessibilityTools 9 / Shizuku 1 / 技能市场 3** | 绝大多数 ❌ |
| `feature:mcp` | 1 | MCP 协议 | 可作为独立项 |

其中 **21 个在 `feature:skills`**，且：
- `AccessibilityTools`（9）依赖 `AccessibilityService` —— **iOS 无等价 API**
- `ShizukuTools`（1）依赖 Shizuku —— **Android 独有**
- `DeviceTools`（8）依赖 AlarmManager / 通知 / 剪贴板 / 电池状态 —— 需逐个重写为 iOS 等价物

**结论**：这不是「M3 的一个待办」，而是**需要重新排期的独立阶段**。
原计划里我把它记为一行待办，是对规模的误判。

**修正后的路线**：
- **M3 至此完成** —— 对话链路 + 记忆/技能 + 三个界面 + 工具注入（记忆技能）
- **M4（新）**：领域工具，按「可移植性」分批：
  1. 用户资料 / 记忆召回（已有等价物，可直接做）
  2. 设备类（闹钟/通知/剪贴板/时间）—— 逐个有 iOS 等价 API
  3. 咖啡下单 / 搜索 —— 需对接外部服务
  4. 自动化 / 无障碍 / Shizuku / 渠道发送 —— 需先解决「无等价物」的前提

### 新增：`M0-RUNBOOK.md` —— 让一次实跑带回最大信息量

我连续三十多轮建议"先跑 M0"，但每次只是给一条命令。这对收到建议的人不够——
**他们不知道失败时该回报什么，也不知道哪些是预期内的。**

现在有一份分步 runbook：6 步，每步都写「预期结果 / 失败说明什么 / 该回报什么」。

几个刻意设计：

- **第 1 步（构建 Rust）标为"最可能失败"** —— 我从没在本机编译过 Rust 侧，
  它的问题会以 `uniffi.toml` / crate-type / 平台适配的形态出现，
  而我完全无法预判
- **第 3 步列了报错形态对照表**，按我看到过的风险排序，
  第一项就是「引用不存在的自有类型」—— 第 50 轮真实发生过
- **第 4 步列出 10 个测试文件各自覆盖什么**，失败时能直接定位到层
- **第 5 步是功能冒烟**，7 个手动步骤，其中「发一条消息」一次验证
  orchestrator / settings / credentials / load_companion / 历史清洗 / SSE / emit_bubble
- **「回报的最小信息集」**：如果只能回报一样，回报第 3 步的前 20 个编译错误
  （不是全部 —— 第一个错误常引发连锁）
- **附：预期内的告警清单**，避免把正常现象当新问题回报
  （模拟器无 Secure Enclave、表情标签为空、并发警告等）

核对过：10 个测试文件、6 个命令/路径、6 个关键修复点全部在文档中被引用。

### 已实现：表情标签可诊断（而非一个永远空的「表情库」）

M3 最后一个 store 原计划是「表情库」界面。但第 42 轮已确认：
**`sticker_entries` 在全新安装下本就为空**（只有用户导入的表情才进表，
内置表情被 `!it.isBuiltIn` 排除）。所以一个永远显示空的列表几乎没有价值。

改为把**已有的标签同步变成可诊断的**：自检面板直接列出下发给 Rust 的标签。

理由：标签为空时 `send_sticker` 会**静默匹配不到**，
而「模型为什么发不出表情」这类问题，没有这个面板是查不出来的。
同时明确标注「全新安装下为空是正常的」，避免把它误读成故障。

**这是一个「先判断该不该做，再决定怎么做」的例子** ——
按原计划做一个空列表界面也能交付，但它不解决问题。

### 已实现：技能库界面（`SkillLibraryView`）

数据流在别处已验证（`SkillChainTests`：种子 → discover → loadContent），
所以这里显示的就是模型能通过 `load_skill` 加载的那批。

- 列表：`SkillStore.list_skills`（companionId 为 nil = 只列全局技能，与 Rust 语义一致）
- 详情：`get_skill_content` —— **带 SHA-256 校验**，失败会明确显示
  「Rust 会视为技能不存在」，而不是静默显示空内容
- 从自检面板进入，`stores == nil` 时禁用

**一处刻意的设计**：详情页把「正文校验失败」做成显式红色提示。
因为 Android 的 `SkillFileStore` 在文件被外部改动时就会走到这条路径 ——
静默显示空白会让人以为"这个技能没内容"，而真相是"校验失败"。

### 已偿还技术债：`swift_text.py` 共享模块

「剥离 Swift 注释与字符串」我重复实现过三次、也栽过三次
（第 8 / 51 / 52 轮），根因是每次只在当前路径打补丁，没有固化规则。

现已抽成 `ios/Tools/swift_text.py`，`verify_own_types.py` 改为委托它。
模块自带自测，覆盖三种历史事故形态：

```
[ok] 行尾注释未剥净      [ok] 字符串未剥净
[ok] 多行字符串未剥净      [ok] 文档注释未剥净      [ok] 保留空串占位
```

**下一次写需要处理 Swift 源码文本的工具，import 它，不要重写。**

⚠️ 而这个模块的初次提交**又栽了第四次**：它的 docstring 里写了
「井号加三引号」的原始字符串形态示例，其中的三引号把自己提前终止了。
已改为文字描述，并在 docstring 里留下维护提醒 —— 一个专门解决
「docstring 里有三引号」问题的模块，在自己的 docstring 里犯了同一个错。

### 已收尾：自有类型引用核对（现为第 13 道门禁）

`verify_own_types.py` 从 reporter 升级为门禁：

1. **降噪 150 → 21**：修掉剥离器的两个缺陷 —— `Self.` 未排除、**行尾注释未剥离**。
   后者是我第三次栽在"注释/字符串处理顺序"上（第 8 轮、第 51 轮、本轮），
   根本原因：我一直在用正则近似 Swift 的词法，而**正确顺序是「先字符串、后注释」**。
2. **归类固化 17 个唯一外部名**：Security 的 C 函数（`SecItemAdd` 等 10 个）、
   Foundation（`NSRange`、`CharacterSet` 等）、CryptoKit/UIKit（`SHA256`、`UIDevice`）。
   这些所属 API 都是**封闭集合**，固化之后任何新的未解析引用都会暴露。
3. **变异测试**：注入不存在的 `AgentHostTypo()` → 立即失败；还原 → 通过。

接进 CI（第 13 道关卡）。这正是第 50 轮事故的直接防线 ——
`AgentToolCatalog` 那个 bug 会被它抓住。

### 新增：`verify_own_types.py`（自有类型引用核对，当前为 reporter 非门禁）

第 50 轮抓到 `AgentToolCatalog` 引用了**不存在的自有类型** `AgentHost`，
而 13 道关卡全部放过。为此加了这个检查：收集全部自有类型声明，
再核对 `Type(` 与 `Type.member` 形式的引用。

**当前状态（诚实说明）**：
- 首轮 150 处命中，降噪后 **23 处** —— 且这 23 处**全是外部类型或 C 函数**
  （`SecItemAdd`、`CharacterSet`、`UIDevice`、`SHA256` 等），
  **没有一处是真实的缺失自有类型**
- 也就是说：第 50 轮那类 bug **没有复发**（这是有价值的阴性结果）

**为什么还没接进 CI**：残留噪声来自 Security 框架的 **C 函数**
（`SecItemAdd(...)` 形态与类型构造无法区分）。要变成门禁需要能区分
「C 函数」与「类型构造」——那需要真正的解析器，不是正则。
在此之前它作为 reporter 保留：改动后跑一次，人工确认新增项是外部依赖即可。
接入门禁的前提是把这 23 项逐一归类并固化。

### 🚨 本轮抓到真实编译错误：`AgentToolCatalog` 参数类型不存在

第 45 轮写的 `AgentToolCatalog.install(...)` 把参数类型写成了 `AgentHost` ——
**这个类型根本不存在**，真实类名是 `AgentToolHostImpl`（它位于 `AgentHost.swift` 里，
所以**文件名误导了我**）。

**13 道关卡全都没抓到**：词法冒烟只看括号配平（语法完全正确）；
生成绑定检查只覆盖 `Generated/` 的调用；其余与此无关。
这类「引用不存在的自有类型」正是我本地检查的**结构性盲区**。

已修（`AgentToolCatalog` 与测试各一处），并全仓确认无 `\bAgentHost\b` 残留。

### 新增：`AgentStoresTests`（补齐此前完全缺失的一层）

`AgentStores` 的三个 foreign trait 方法是 Rust 的**唯一数据来源**：
键名写错 → Rust 取不到 → 静默失效。

此前我只从 Rust 源码**逐键核对过**（`verify_swift_conformance.py` 的静态比对），
**从没在运行时验证过映射结果** —— 但这类方法其实不需要 Rust 引擎就能测
（建库、插行、直接调用）。

新增测试覆盖：
- `listMemories` 的键集合与 Rust 的 `MemoryMeta` 逐一对齐
- **过滤条件**：别的设备 / 软删除 / 已过期都不该出现（写错任一条都静默）
- `expires_at` 无值时为 **JSON null**（Android 的 `JSONObject.NULL`），不是 0 也不是缺键
- `insertMemory` 空白 content 返回 `""` 且不落库
- `source` 由 scope 推导（`GROUP` → `GROUP_CHAT`）

### 已加固：验证脚本的「覆盖面自证下限」

`verify_literals.py` 曾被我自己误插提前 `return`，26 项断言静默失效而**所有关卡仍全绿**。
这证明：**门禁本身需要被守护。**

为此给两个主要装置加了自证下限：

| 脚本 | 下限 | 当前 |
|---|---|---|
| `verify_literals.py` | 90 | 90 |
| `verify_schema_sql.py` | 140 | 140 |

断言数低于下限即失败（提示"覆盖可能被阉割，检查是否误插提前 return"）。
已用变异测试验证：下限抬到 999 立即失败，还原后恢复。

**新增断言时请同步抬高对应上限** —— 它是护栏，不是天花板。
这把"改完要数一遍"的人肉纪律变成了机器检查。

### ⚠️ 本轮自查：检查器被我自己静默阉掉了 26 项断言

给 `verify_literals.py` 加第 7b 节（per-session 冻结审计）时，我的编辑**插入了一个提前
`return`**，导致第 8/9/10 节（历史清洗、编排器注入、PARTNER 门控共约 26 项）
变成死代码。

**症状**：断言数从 84 掉到 66，而所有关卡仍全绿 ——
没有任何东西报警，因为报告的数字本身就是从被阄割后的代码里算出来的。

**怎么发现的**：我记得加第 7b 节后总数应该上升而不是下降，回查才看到提前 return。
如果当时只看"13 道关卡全绿"，这个损失就无声无息地留下来了。

**教训**：**门禁的覆盖面本身需要被守护。** 一个会自我阉割且仍报绿色的检查器，
比没有检查器更危险 —— 它提供虚假的安全感。已修为 90 项。

配套地，第 7b 节加了两条断言（安装期不接收 per-session 状态、DeviceIdentity 全为
计算属性），把第 46 轮的教训固化为可回归检查的契约。

### 本轮自查发现并修复：`load_skill` 的 companionId 被冻结了

上一轮刚写的 `AgentToolCatalog.install(...)` 有一个**自己引入的 bug**：
`load_skill` 执行器在**装配时**捕获 companionId。

`install` 发生在 `boot()`，那会儿还没有绑定伴侣 → 恒为 `nil`。而 Rust 的
`load_content` 用 companion_id 过滤技能列表：

```rust
// skill_selector.rs:191
let list_json = self.store.list_skills(companion_id);   // nil → 只返回全局技能
```

后果：**伴侣专属技能的 `load_skill` 必然返回「未找到」**。
（内置技能 `companion_id = NULL` 恰好还能用，所以症状是隐蔽的。）

**已修**：执行器改为从 Rust 传来的 `contextJson` 现取
（形如 `{"companion_id":123,"group_id":null}`，该约定见 `memory_selector.rs:350`），
`install` 也不再接收 companionId。

**教训**：装配期捕获 per-session 状态是个系统性隐患 ——
它编译通过、内置场景还正常，只在特定数据下失效。这类问题我的静态检查**结构上查不到**
（它不违反任何契约），是靠"回头看自己刚写的代码"发现的。

### 已实现：工具注入（`AgentToolCatalog`）—— 闭合 `load_skill` 缺失导致的两处失效

`request.tools` 原先恒为 `[]`。本轮补上记忆工具与 `load_skill`：

| 工具 | 定义来源 | 执行器 |
|---|---|---|
| 记忆工具（recall/save/consolidate 等） | **Rust** `MemorySelector.memoryToolDefinitions()` | `MemorySelector.executeMemoryTool(...)` |
| `load_skill` | 逐字对应 Android `skillToolDefinitions()` | `SkillSelector.loadContent(skillId:companionId:)` |

**`load_skill` 不是可选项** —— `prompt_orchestrator.rs:270` 的技能目录菜单注入
以 `available_tools` 含 `load_skill` 为前提：

```rust
|| options.available_tools.iter().any(|tool| tool == "load_skill")
```

不加它，`[可用技能目录]` 菜单永远不注入，模型不知道有技能可用。

领域工具（`ToolRegistry`）仍未移植 —— 这个**残余缺口继续标注**，
`verify_literals.py` 有一条断言专门守着（避免后人以为工具已齐全）。

### 已实现：provider 选择（闭合一个已记录的隐患）

第 40 轮补 `api_configs` 时，我留下一个默认值：`setAPIKey` 默认 `provider = "OPENAI"`。
这本身是个隐患——**选错 provider 会静默打到错误的 baseUrl**，而这正是我一路
最该避免的失败模式（不报错、只是不工作）。

已补上显式选择：凭证区加 `Picker`，数据源是 `YuNianSeed.apiProviderPresets`
（13 条，由 `generate_seeds.py` 从 `ApiProvider` 枚举程序化生成，
与 Android `seedApiProviderPresets` 逐条对应）。

两个细节：
- 视图出现时用**当前生效配置**回填选择器，避免用户误以为选的是别家
- 新增 `ApiConfigRepository.tryActiveConfig()`（非抛出版）供视图层使用，
  而不是在视图里写 `try?` —— 后者会把错误吞得看不出

### 一个安全决策：`api_configs.apiKey` 保持空串

第 40 轮补上 `api_configs` 行时，确认了 `all_api_keys` 的兜底链：
credentials 的 `api_key` → **行里的** `api_key`。

所以"把明文 key 写进行里"也能让 iOS 跑通。**但那等于把明文密钥落进未加密的
SQLite 文件** —— iOS 侧没有 Android 的 Tink 列加密，Android 行里存的是 `enc:v4:...`
密文（Rust 读到了也无法使用）。

因此保持空串，让密钥只走 `credentials_json`（Keychain 取出的那一份）：
- 与 Android 设计意图一致（Kotlin 解密后经 credentials 传入）
- 不把明文落盘
- Keychain 没 key 时报「没有可用的 API Key」，而不是拿空串去打注定失败的请求

这个理由已写进 `ApiConfigRepository.upsertActiveConfig` 的注释 ——
因为"顺手把 key 也写进行里"是个看起来很自然的"优化"，但它是安全回退。

### 启动期 seed/provision 核对清单（第 42 轮）

针对"6 个上线即故障里有 3 个是 seed 缺失"这个模式，逐项核对 Android
`YuNianApplication` 启动序列中与 Rust/DB 契约相关的调用点：

| Android 调用点 | iOS 状态 |
|---|---|
| `DefaultCompanionSeeder.seedIfNeeded` | ✅ 已移植 |
| `SecurityDataSeeder.seedIfNeeded` | ✅ 已移植（+ 关键词/题库/词表） |
| `BuiltinChatSkillPlugin` → `seedBuiltinChatToolSkill` | ✅ 第 39 轮补齐 |
| `ContentFilter.initialize` | ✅ 已移植 |
| `DataCleanupManager.schedulePeriodicCleanup` | ⚠️ 逻辑已移植，**调度**属 M5（BGTaskScheduler） |
| `AgentFacade.installRequestSigner` | ✅ `setSignatureProvider` |
| `api_configs` 的 provision | ✅ 第 40 轮补齐（`ensureActiveApiConfig`） |
| `StickerPreferencePlugin` → `ensureInitialized` | ✅ **阴性结果**：只有导入的表情才进 `sticker_entries`/`sticker_tags`（`!it.isBuiltIn`），全仓无 sticker seeding。所以空列表是 Android 全新安装的真实行为，**不是缺口** |
| `registerGlobalTools`（memory/skill 工具注入 Rust 全局表） | ❌ 未做 —— 这是已记录的「iOS 无工具」缺口 |
| `PushManager.init` / `WeChatChannelKeeper` | ❌ 不可移植（APNs 需付费账号；微信通道无 iOS 等价物） |

**这条清单的价值**：它把"靠撞"变成"可复核"。其中 sticker 一项是**阴性结果** ——
如果没有这次核对，我很可能给 iOS 加一个 Android 并不存在的内置表情 seed，
那会造成两端行为分歧（正是我一路最该避免的）。

### 🚨 第十一个已修复的问题：没有 `api_configs` 行，**对话完全无法工作**

第 40 轮系统核对 Rust 的 `load_*` 调用链时发现：

```rust
// native_gateway.rs:765 —— LLM 请求的关键路径
let cfg = self.load_api_config()?;
if cfg.model.trim().is_empty() { return Err("模型名未配置，请在「API设置」中重新测试连接".to_string()); }
let keys = self.resolve_keys(&cfg)?;
```

```sql
SELECT provider, apiKey, extraApiKeys, baseUrl, model, temperature, maxTokens, formatHint
FROM api_configs WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1
```

无行时报 **「无可用 API 配置」**。它提供 provider / baseUrl / model 等，
而 `api_key` 由 `all_api_keys` 优先取 `credentials_json` 的明文（Keychain 那部分是对的）。

**问题在于：iOS 从来没创建过 `api_configs` 行** —— M2 只把 key 写进 Keychain。
于是用户输入密钥 → 回合发出 → Rust 报「无可用 API 配置」→ **对话完全无法工作**。

讽刺的是：第 2 轮我就记录过「Rust 只读 2 张表：`api_configs` + `companions`」，
**`api_configs` 明明在契约里**，但后续实现时把它忘成了 M3 待办。

**已修复**：
1. `ApiConfigRepository.upsertActiveConfig(...)` —— 写入/更新唯一一条启用配置
2. `ensureActiveApiConfig()` —— 已有可用配置时不动；完全没有时用预设默认建一条
3. `setAPIKey` 与 `boot()` 都会调用它

**一处不猜的设计决定**：provider 由调用方显式传入，从 `api_provider_presets`
（13 条预设）取默认 baseUrl / model / formatHint。选错 provider 会静默打到
错误的 baseUrl，所以**不让代码替用户决定**。完整的 provider 选择 UI 仍是 M3。

### 🚨 第十个已修复的问题：内置聊天协议技能未播种

Rust 的 L4 技能注入层（`prompt_orchestrator.rs:218`）要求 `agent_skills` 里
存在 `builtin_chat_tool_protocol`：

```rust
if options.available_tools.iter().any(|t| t == "emit_bubble") {
    if let Some(content) = skill.load_content(BUILTIN_CHAT_TOOL_SKILL_ID.to_string(), ...)
```

Android 由 `BuiltinChatSkillPlugin` → `AgentFacade.seedBuiltinChatToolSkill(app)`
播种，iOS 侧**此前没有任何等价物**。

后果（与 `orchestrator: nil` 同类，静默）：模型看不到「必须用 emit_bubble 输出气泡」
的协议，工具调用积极度不足。Rust 注释原文：「模型若不自发 load_skill 就看不到…
导致工具调用积极度不足」。

而 `available_tools` 含 `emit_bubble` 这个条件**在 iOS 上恒真**（内置工具不受
`request.tools` 门控），所以这不是可选项。

**已修复**：新增 `Tools/generate_builtin_skill_seed.py`，从 Kotlin 源码
**程序化提取**正文与 meta（884 字符、36 个标签、version 7），生成
`Data/Seed/BuiltinChatToolSkill.swift`，并在 `boot()` 里调用
`BuiltinChatToolSkill.seed(into: stores)`。

`AgentStores.saveSkill` 就是 Android 走的那条路径，因此行为一致。

### 已验证：记忆注入**不**依赖 `request.tools`（决定 M3 界面的价值）

做记忆设置界面前，先验证了一件事：会不会因为 iOS 的 `tools: []` 导致
「界面能看、模型用不到」？

`prompt_orchestrator.rs::build_user_context`：

```rust
if let Some(memory) = &self.memory {
    let working = memory.select_working(scope_json, options.working_memory_limit.max(1));
    if !working.is_empty() { out.push_str("[近期记忆]\n"); ... }
}
```

**只看 `PromptOrchestrator` 持有的 `MemorySelector`，完全不看 `request.tools`。**
而该 selector 在修复 `orchestrator: nil` 时已一并注入。
**结论：界面显示的记忆确实是会进提示词的那批**，三个 store 界面都有实际价值。

这正是「先验证再判断」的价值 —— 上一轮刚因为跳过验证而误判过 stickers。

已实现 `MemoryRepository`（读侧），其过滤条件与 `AgentStores.listMemories`
**逐字一致**（deviceId + isDeleted + expiry，`importance DESC, observedAt DESC`）——
两处必须同时成立，否则界面显示的与会话实际用的不是同一批。这个约束写进了注释。

### 已实现：表情可用标签同步（`StickerTagProvider`）

对应 Android `StickerPreferenceFacade.availableTagsWithFallback`，两级逻辑：

1. **偏好先验** —— `StickerPreferenceSelector.snapshot().tagPrior` 按值降序取 Top-N。
   引擎参数逐字对齐 Android `defaultParams()`（τ=30 天、n0=20、n1=200、
   ε=0.05、α=1.0、β=1.0、γ=0.5、driftWindow=100、driftThreshold=0.15、
   maxClassSize=5、sampleSeed=20260810）。
2. **兜底** —— 先验为空时查 `sticker_tags ORDER BY stickerCount DESC, tag ASC` 取 Top-N。

经 `syncRuntimeConfig()` → `runtime.updateStickers()` 每回合同步。

**一处有意的确定性改进**：Kotlin 的 `sortedByDescending` 对相同 value 保持原顺序，
而它的输入来自 HashMap —— 顺序本身不确定。我改成按 `(-value, key)` 排序，
使结果**确定性可复现**。这不改变语义（两端都未对此做过契约承诺），
但避免了「同值标签顺序随机」导致的提示词抖动。

### 已修正的判断：内置工具**不受** `request.tools` 门控

我曾在代码注释与文档里断言「iOS 的 `tools: []` 让模型看不到任何工具，
所以连工具说明都不会注入，`stickers` 也可以先不管」。

**这个判断是错的。** `agent.rs::available_tool_definitions` 的拼接顺序是：

```rust
self.registry.builtin_tool_definitions()      // ← 内置，永远注入
    .chain(self.registry.global_tool_definitions())
    .chain(request.tools.iter().cloned())     // ← 宿主传的才受门控
    .chain(self.core_plugin_tools())
```

即 **`send_sticker` / `emit_bubble` / `emit_segmented` 三个内置工具
不受 `request.tools` 影响，始终会注入模型**。

两个后果：
1. iOS 上模型**能**看到并调用这三个内置工具。缺的只是记忆/技能/领域工具。
2. `send_sticker` 依赖 `AgentGlobalConfig.stickers` 里的可用标签做精确匹配校验，
   所以 **stickers 同步不是死路径，是活需求**（此前误判为可延后）。

已改正 `ChatSession.swift` 的注释，并把「内置 vs session 工具」的区分写清楚 ——
混为一谈会导致后续排期做出错误决策。

### 静态排查「首次构建/测试失败」的高危项

没有 Mac 就无法编译，但可以把**已知高危**的失败原因逐项排掉。本轮结果：

**① Asset Catalog 里的 WebP（已修）**
`avatar_xiaoyu.imageset` 原先引用 `.webp`。actool 对 WebP 的支持历来不可靠，
这是高概率的首次构建失败。已用 Pillow 转成 PNG（1080×1033，58 KB → 702 KB）
并更新 `Contents.json`。`AvatarResolver` 用的是资产名而非文件名，故无需改代码。

**② 单测读不到 app 资源（已修，见上一节）**

**③ Info.plist（已校验，无需改）**
XML 良构、17 个键、必需项齐全、竖屏限定、**刻意不声明**
`UIBackgroundModes` 与 `aps-environment`（免费 Apple ID 无法启用 APNs，
M5 服务端方案就位前打开会导致签名失败）。

**仍未消解、需要在 Mac 上才能回答的：**
- `xcodebuild` 首次需联网解析 GRDB SPM 包（离线会失败）
- 缺 `Frameworks/lianyu_agent.xcframework` 时链接期 undefined symbols
  —— 必须先跑 `scripts/build_agent_ios.sh`（脚本与 CI 均已覆盖）
- `@testable import YuNian` 依赖 Debug 的 testability（Xcode 默认开启，但需实测）
- Swift Concurrency：已设 `SWIFT_STRICT_CONCURRENCY: minimal` 降低 `Sendable` 报错面，
  但 `@MainActor` 与 `turnQueue` 闭包的交互只有编译器能判

### 已修复：单测里读不到 app 资源（只有真机才暴露）

`ContentFilterTests` / `SecuritySeedLoaderTests` 都依赖 `SecuritySeed.json`，
而 `SecuritySeedLoader.load()` 默认读 `Bundle.main`。

**坑在于**：单测运行时 `Bundle.main` 是**测试 bundle**，不是 app ——
于是 `ContentFilter.load()` 返回 false，金标向量测试在 macOS 上全红。
这个错误在本地 12 道关卡里**完全看不出来**（它们都不经过 bundle），
只会在第一次 `xcodebuild test` 时暴露。

修法（两处配套）：
1. `project.yml` 把 `SecuritySeed.json` 显式复制进 `YuNianTests` target
2. 测试侧改用 `Bundle(for: type(of: self))` 读取

顺带说明：这类"资源/契约只在真机可见"的问题，正是我坚持要跑 M0 的原因 ——
本地验证再密，也有它结构上覆盖不到的层面。

### 重新检验「本地不可验」清单（第 38 轮）

我曾把一些事情归入"只有 Mac 能验"。逐项重查后，**有两项是我的误判**——我把可验的判成了不可验，代价是我正因此打算去写更多未编译的 UI 代码。

| 原判断 | 重查结果 |
|---|---|
| GRDB `Row` 列名拼错 → 不可验 | **可验**。79 处 `row["col"]` 已与 schema 比对，且有变异测试 |
| GRDB `Row` 类型转换 → 不可验 | **可验**。77 处转换目标全部是 GRDB 内建原生类型（String 36 / Int64 27 / Int 10 / Double 3 / Data 1），无 `Bool`/`Date`/自定义类型 |
| Swift 并发隔离违规 → 不可验 | **部分可验**。检查了危险模式（`@MainActor` 状态被非隔离闭包捕获）：`turnQueue.async` 的闭包只捕获局部 `let`，**不捕获 `self`/`environment`**，模式正确。且 `SWIFT_STRICT_CONCURRENCY: minimal` 下这类违规是**警告而非错误**，本来就不会挡构建 |
| SwiftUI 能否编译 | 仍不可验 |
| UniFFI FFI 链接 | 仍不可验（需 `build_agent_ios.sh` 产物） |

**教训**：「本地验不了」这个判断本身也需要被检验。低估可验范围比高估更危险——它会推动你去做本可避免的未验证工作。

### 新增检查：`row["列名"]` 访问与 schema 比对

GRDB 的 `Row["xxx"]` 是**运行时**字典查找，列名拼错**编译器不报错、运行期才抛**。
这是本地能提前抓的又一类错误。

实现：从 `YuNianSchema.swift` 解析 31 张表的列定义，抽取全部 `row["col"]` 访问
（当前 **79 处**，分布于 5 个文件），报告 schema 中不存在的列名。

**经变异测试验证有效**：注入 `row["personalityTYPO"]` 后被抓住（exit=1），
还原后 exit=0。若没有这一步，一个"总匹配 0 处"的检查会假绿。

至此，`verify_swift_sql.py` 覆盖三类本地可闭环的 GRDB 风险：
SQL 语法与表/列存在性、占位符与实参数个数、以及 `Row` 列名访问。

### 本地可闭环的「SQL 字符串」检查：`verify_swift_sql.py`

业务代码里的 SQL 是**字符串**，编译器完全不检查 —— 列名拼错、表名写错、
GRDB 插值出错，全都运行时才炸。本脚本把它们抽出来，**拿到真实 v45 schema 上执行**：

1. 从 Swift 源码抽出所有 `sql: """…"""`（含单行形式），共 **70 条**
2. 静态解析 Swift 插值（本地字面量、`Type.rawValue`、`Self.selectColumns`；
   字面量表**跨文件全局**收集并支持末段回退）
3. 按占位符个数绑定后真正执行；写操作用 savepoint 包裹并回滚

**结果：68 条在真实 schema 上执行通过、0 个 SQL 错误、0 处参数个数不符**
（共 70 条，另 2 条因插值不可静态求值而明确列出）。

修掉了检查器自身三处不精确：错误分类（把绑 `None` 导致的执行期报错当成 SQL 错误，
而真正的 SQL 错误发生在准备期）、单行正则把三引号 SQL 的开头匹配成空串
（虚增 39 条且被静默跳过，**掩盖计数不实**）、arguments 区间被
`meta["tags"]` 的 `]` 提前截断（把 16 个实参数成 15，假阳性）。

**它查不了什么**：语义正确性（WHERE 条件写反、JOIN 条件错）、
GRDB 的 `Row` 解码与 `DatabaseValue` 转换、以及一切 Swift 类型系统问题。
后者只有编译器与真机能回答。

**一个关键的设计决定 —— 错误分类**：最初把所有执行失败都当 bug，报出十几个
`NOT NULL constraint failed` / `datatype mismatch`。逐个核实后发现它们**全部是
我把占位符一律绑成 `None` 的产物**，不是 SQL 的问题。真正的 SQL 错误
（表/列不存在、语法错）发生在**准备期**。因此按错误类型分类，只对准备期错误报警 ——
这也让输出可读（而非又一次「狼来了」）。

调试过程中还修掉工具自身两处不精确：字面量表按文件收集导致跨文件常量
（`YuNianSchema.ftsTableName` 用在 `YuNianDatabase.swift`）漏解析，
以及插值表达式带类型前缀时匹配不上。

**它查不了什么**：语义正确性（WHERE 条件写反、JOIN 条件错）、
以及 GRDB 特有的 `DatabaseValue` 转换与 `Row` 解码。后者仍要真机。

### 本地可闭环的「编译期风险」检查：`verify_generated_api_usage.py`

没有 Mac 就无法编译，但**有一类编译错误是可以在本地闭环验证的**：
业务代码调用了 UniFFI 生成绑定里**不存在**的方法 / 构造参数。

`ios/Generated/LianyuAgent.swift` 是固定的 API 表面，所以只要把它的成员抽出来、
与业务代码里的调用逐一对账即可。本脚本检查三类：

1. `Type.member` —— 如 `AgentGlobalConfig.dbPath`
2. `runtime.xxx(...)` —— 如 `updateSettings` / `updateCredentials` /
   `setSignatureProvider` / `cancelCurrentTurn` / `runTurnStream`
3. 构造器的**参数标签** —— 如 `AgentGlobalConfig(dbPath:)` 拼错成 `dBpath`

**脚本本身经过变异测试验证**（这不是"写了就算完"）：
注入三类错误（不存在的方法 / 构造器多一个字段 / 参数标签拼错）均能被抓住，
正常代码零误报。过程中还修掉了检查器自身的两处不精确：
文档注释里的 `Type.member` 被当成调用、以及嵌套构造器
（`PromptOrchestrator(...)` 嵌在 `AgentGlobalConfig(...)` 里）导致内层标签被当成外层参数。

**它查不了什么**（诚实说明）：GRDB / SwiftUI / 系统框架的用法、类型推断、
并发隔离、泛型约束，以及一切 Rust 侧行为。这些只有编译器与真机能回答。

### ⚠️ 第十一个已修复的问题：伴侣绑定失败时静默降级

`ChatView` 的 `.task` 里原来是：

```swift
guard session.companionId == nil,
      let companion = environment.defaultCompanion else { return }   // ← 静默返回
```

绑定失败（如播种失败 / 库未就绪）时界面**什么都不说**，但回合照样能跑：
Rust 的 `load_companion(None)` 会让人设注入缺失，模型仍能回答 ——
用户看到的是一个「没有角色的机器人」，且**不报错、无提示**。

已改为给出可见的橙色警示条（`companionWarning`），把失败说出来而不是让用户自己察觉。
自检面板（`RootView`）本来就有「默认伴侣 缺失」一行，但「能进入对话」和
「对话里有没有人设」是两件事，后者必须就地提示。

### ⚠️ 第十个已修复的问题：配置只在启动时同步一次

Android 在**构造每个回合请求之前**都会调用 `syncRuntimeConfig()`
（`AgentDialogueCoordinator.kt:284`），而不是只在启动时配一次。它刷新
stickers、PARTNER 会话、**从库里现取现解密的 API Key**、以及 settings。

iOS 原先只在 `boot()` 里设一次，于是任何**不经 setter** 的变更都不生效 ——
例如 PARTNER 会话被 `RemoteKeyProvider` 刷新、API 配置在其它入口被修改。

已补 `AppEnvironment.syncRuntimeConfig()` 并在 `ChatSession.send()` 的回合起点调用。

**iOS 侧仍存的两处简化（M3 补齐，已写进注释）**：
- `stickers` 仍为空：未实现 `availableTagsWithFallback`（依赖表情偏好引擎 +
  `sticker_tags` 表的 Top-N 查询）
- `session` / `client_id` **未按 active provider 是否为 PARTNER 门控**。
  Android 是 `if (isPartner) partnerSession?.token else null`，iOS 还没有
  `api_configs` 的「当前启用配置」读取。实践影响有限（Rust 只在 PARTNER 分支
  消费这两个键），但发出的 JSON 会与 Android 不同

### 🚨 第九个已修复的严重问题：`orchestrator: nil` 让回合**根本没有系统提示**

`AgentGlobalConfig.orchestrator` 是 `Option<Arc<PromptOrchestrator>>`，我原先传 `nil`
（看到是 Option 就以为"可为空 = 空着没问题"）。查 Rust 消费端才发现后果严重：

```rust
// agent.rs:1091 —— 用户上下文前缀
if let Some(orchestrator) = &self.orchestrator { ... build_user_context(...) ... }
// agent.rs:1186 —— 系统提示词组装
if let Some(orchestrator) = &self.orchestrator { ... build_system_prompt(...) ... }
```

nil 时两处都不成立，于是：
1. 用户上下文前缀不注入 → 丢 `[当前时间]` / `[对话轮数]` / `[近期记忆]`
2. **`system_prompt` 完全不组装，保持空串** → 回合在没有任何人设、
   没有环境上下文的情况下运行，模型只看到历史消息

Android 侧 `AgentFacade.runtime()` 传的是真实的 `promptOrchestrator(context)`，从来不是 nil。

**已修复**（三个构造函数都由 UniFFI 暴露，见 `ios/Generated/LianyuAgent.swift`）：
```
MemorySelector(store:)      ← AgentStores 实现 MemoryStore
SkillSelector(store:)       ← AgentStores 实现 SkillStore
PromptOrchestrator(memory:skill:)
```
注意 `memory` / `skill` 两个参数**本身**可为空（空选择器时对应层不产出），
但**编排器本身必须存在**——这里的区别很容易看混。

装置加了两条守护：`AgentGlobalConfig 未传 orchestrator: nil` 与
`编排器由 MemorySelector / SkillSelector 组装`。

### ⚠️ 第八个已修复的问题：历史没有经过 `AiDialogueHistoryPolicy` 清洗

Rust 的两处注释明确写着 `history_json` 是「**Kotlin 侧 AiDialogueHistoryPolicy 产物**」
（`agent.rs:1059`、`native_gateway.rs:12`）——也就是说**模型看到的历史是清洗过的**，
不是原始消息列表。iOS 侧原先直接全量映射，整层清洗被跳过。

`AiDialogueHistoryPolicy.sanitizeForModel` 做三件事（已逐条移植到
`DialogueHistoryPolicy.swift`）：

1. **角色归一化**
2. **过滤**：
   - 去掉零宽空格 `\u200B` 后为空的 → 丢
   - 操作性消息（`[TOAST]` 前缀 / 9 条精确文案 / 23 条前缀，含「网络连接超时」
     「内容已拦截」这类错误提示与系统噪声）→ 丢
   - 内容是 `[工具调用结果]` 但角色不是 TOOL 的 → 只保留 USER
3. **合并相邻同角色**（TOOL 除外）→ `trimEnd + "\n" + trimStart`

后果不清洗的话：把 toast、API 错误提示、空消息也喂给模型；且相邻同角色消息不合并。

**一个必须照抄的细节**：判空/判操作性用的是**清洗后**的 content，
但保留下来的消息仍带**原始** content。例如 `"\u200Bhello"` 判空时是 `"hello"`（非空→保留），
但列表里那条内容依然是 `"\u200Bhello"`。把消息内容也替换掉会改变发给模型的内容。

已用 Python 转写 Kotlin 逻辑生成 13 组金标向量（含零宽空格、toast 前缀、
TOOL 不合并、过滤先于合并等边界），全部一致。

### 🚨 已知功能性缺口：iOS 侧智能体**没有任何工具**

Android 主对话路径传的是真实工具列表：

```kotlin
tools = (AgentFacade.memoryToolDefinitions(context) +
    AgentFacade.skillToolDefinitions() +
    AgentFacade.toolDefinitionsFor(companionId, ToolRegistry.availableTools()))
    .distinctBy { it.name }
```

即**记忆工具 + 技能工具 + 全部已授权领域工具**。iOS 侧 `ChatSession` 传的是 `tools: []`。

后果（都是静默的，不报错）：
- 回合退化为「纯对话」，模型无法主动读写记忆、调用技能、查用户资料
- Rust 只在 `tools` 非空时才在提示词里注入工具说明与可用清单，
  所以连「告诉模型有哪些工具」这层也没有
- 依赖工具的功能（如「记住这件事」）在 iOS 上不生效

这不是等价移植，是**尚未实现的能力**。补全路径：按 Android 的三段式实现
`ToolDefinition` 组装，并经 `AgentToolHost` 提供调用实现。
`ChatSession.swift` 中该处有详细标注，且 `verify_literals.py` 会断言标注存在——
**避免后人把 `tools: []` 当成无害的默认值「顺手清理」**。

### ⚠️ 第六个已修复的问题：`secure_delete` —— 删除的内容会残留在磁盘上

Android 在 `AppDatabase.kt` 的 `MIGRATION_36_37` 里执行了 `PRAGMA secure_delete = ON`。
它是**每连接** PRAGMA（不落库），且写在迁移内部 —— 也就是说 Android 的实现本身并不可靠，
只对恰好执行那次迁移的连接生效。

iOS 侧原本没有它。已用真实 SQLite 验证这个差异是**真实存在**的：

| 场景 | 删除后主库文件是否仍含敏感串 |
|---|---|
| 删除前 | 是 |
| `secure_delete = ON` 删除后 | **否**（内容清零） |
| 不开（对照组）删除后 | **是**（内容残留在空闲页） |

也就是说，不开它时，已删除的聊天内容按原样留在数据库的空闲页里，可被恢复。
对一个带内容过滤与加密的安全基线产品，这不是可忽略的差异。

已在 `YuNianDatabase.prepareDatabase` 中显式设置，理由（含「Android 实现不可靠、
iOS 无迁移路径故必须显式」）写进注释。装置里是**功能断言**而非形式断言 ——
同时验证「开启后清零」与「不开则残留」两个方向。

### ⚠️ 第五个已修复的严重错误：多发 `settings.session_id` 会改变系统提示词

我一度给 `settings_json` 加了 `session_id`（值恒为 `"ios-default"`），以为是「会话复用」。
核实后发现：

- Android 的 `AgentFacade.buildSettingsJson` **只发 4 个键**
  （`role` / `timezone` / `working_memory_limit` / 条件性的 `image_gen_rules`），
  全仓所有调用点都走它，**没有别的写入方**；
- Rust `agent.rs::orchestration_options` 会读 `session_id` / `owner_name`，
  并经 `prompt_orchestrator.rs` **注入 system prompt**
  （`会话ID：{sid}` / `群主：{owner}` 两行）。

于是 iOS 每条提示词都比 Android 多一行 `会话ID：ios-default` ——
**这不是无害的冗余，而是系统提示词层面的行为分歧**，会直接改变 AI 的表现。

已删除 `session_id` 字段及其生成函数，并加了守护断言：
*「Swift settings 键是 Android 的子集」+「不含 session_id」*。

**保留的一处有分歧**：`owner_name`（昵称功能）。这是明确的产品功能而非移植失误，
但同样会让 iOS 提示词多一行 `群主：{昵称}`。已在 `AgentSettings.ownerName`
注释中标注为「**有意的跨端分歧，需产品确认**」，并要求它在源码中保留标注
（否则 `verify_literals.py` 会失败）——防止后人以为它无害。

### `credentials_json` 的两条编码规则（原为错误实现）

Android `buildCredentialsJson` 的规则，`JSONEncoder` 的默认行为**都不满足**：

1. **空白串等同 nil，一律省略**（Kotlin `isNullOrBlank()`）。
   默认编码会把 `""` 写成 `{"api_key":""}` → Rust 的 `provider_headers`
   带上空会话头，与 Android 不同。
2. **`session` 与 `client_id` 要么都写、要么都不写**。少任一个时 Kotlin 连另一个也不写。
   分别判断会出现「只有 session」的挂载形态。

已实现自定义 `encode(to:)` 逐字复刻（含空结果返回字符串 `"{}"`），
并用 Python 转写 Kotlin 逻辑交叉验证 9 个用例，全部一致。

### 与 `DeviceRequestSigner` 对齐（keyId / SPKI / deviceId）

| 项 | Android | iOS | 结论 |
|---|---|---|---|
| `keyId` | `sha256Hex(publicKey.encoded).take(32)` | `sha256Hex(spki).prefix(32)` | ✅ 等价（`encoded` 即 SPKI DER） |
| `publicKeyBase64` | `Base64(publicKey.encoded, NO_WRAP)` | `spkiDER().base64EncodedString()` | ✅ 等价 |
| `deviceId` | `sha256("$FINGERPRINT\|$MANUFACTURER\|$MODEL\|$BRAND").take(32)` | `sha256("iOS\|$IDFV\|$model").take(32)` | ⚠️ **形状一致**（32 位小写 hex），材料不同 —— 需服务端接受（V9） |

**SPKI 前缀已用真实 DER 解析验证**（不是靠读注释）：外层 `30 59`、
内层两个 OID 分别为 `ecPublicKey` 与 `prime256v1`、`BIT STRING` 的
`unused bits = 0` 且长度为 66、外层长度 89 = 内层整体 21 + BIT STRING 整体 68。
解析脚本顺带抓出了我自己在断言里写错的算术（把内层内容 19 写成了 21）。

⚠️ `identifierForVendor` 在「卸载同厂商全部 App」后会变化，届时 `deviceId` 改变而
Secure Enclave 里的 `keyId` 不变。Android 的 `Build.FINGERPRINT` 也会随系统更新漂移，
因此这是**同类语义**而非 iOS 独有；服务端设备注册需容忍 (deviceId, keyId) 的变化 —— 同属 V9。

### 与 `RequestSecurityInterceptor` 对齐（签名链路）

签名 payload 的**任一字节**不同都会导致服务端拒签，因此逐字核对：

| 项 | 结论 |
|---|---|
| payload 分段 | `v1\n{method}\n{path}\n{bodyHash}\n{ts}\n{nonce}\n{clientId}\n{deviceId}` —— 8 段 ✅ 一致 |
| 时间戳单位 | `System.currentTimeMillis() / 1000` → **秒** ✅ 一致 |
| nonce | 12 字节 `SecureRandom` → 24 位小写 hex ✅ 一致 |
| 空 body 哈希 | `sha256Hex(ByteArray(0))` ✅ 一致 |
| 头名 | 7 个头逐字一致 ✅ |
| `X-LianYu-Client` | `appId = "lianyu-1.5.1"` ✅ 一致 |

**修正的两处：**

1. **path 必须用百分号编码后的形态**。Android 用 OkHttp 的 `encodedPath`
   （保留 `%20` / `%2F`），我原先用 Swift 的 `url.path`（**已解码**）。
   纯 ASCII 路径恰好相同，含空格/非 ASCII/`%2F` 时会不一致。已改用
   `percentEncodedPath` / `percentEncodedQuery`。

2. **`clientId` 的大小写陷阱**。Kotlin 的判断是 `startsWith("Bearer ", ignoreCase = true)`，
   但剥离用的 `removePrefix("Bearer ")` **区分大小写**：
   ```kotlin
   if (!authorization.startsWith("Bearer ", ignoreCase = true)) return ""
   return authorization.removePrefix("Bearer ").substringBefore(':')
   ```
   于是 `Authorization: bearer abc` 时，判断通过而剥离失败，
   整个 `bearer abc` 被当作 clientId。我原先写成「判断通过后 dropFirst(7)」得到 `abc`，
   **与服务端签出的 payload 不同**。已如实复刻该行为并在代码注释与测试中说明理由：
   *跨端一致性优先于「看起来更对」*。

### 与 `CompanionDao` 对齐时发现的差异（均已修正）

| 项 | 原先（错） | Android 实际 |
|---|---|---|
| 亲密度累加 | `SET intimacy = MAX(0, intimacy + ?), updatedAt = ?` | **`SET intimacy = intimacy + :amount`**（**无钳制**、**不动 `updatedAt`**） |
| `update` 的时间戳 | 无条件写 `updatedAt = now` | `@Update` 只写实体自身的值；刷新走独立的 `updateTimestamp` |
| `fetchAll` 排序 | `ORDER BY updatedAt DESC, id DESC` | `ORDER BY updatedAt DESC`（**无次级排序**） |

**再一次是「测试与实现共享同一个误解」**：装置里当时写的正是我臆造的
`MAX(0, intimacy + ?), updatedAt = ?`，所以它「通过」了。
现已改为 Android 原文，并加了三条断言把差异钉死，其中两条是**反例**：

```
[ok] 亲密度无下限钳制（-8 后为 -3，与 Android 一致）
[ok] 亲密度累加不改动 updatedAt（时间戳由 updateTimestamp 单独负责）
[ok] updateTimestamp 可单独刷新时间戳
```

### ⚠️ 第四个已修复的严重错误：`usageHistory` 顺序反了

我按 Rust trait 的注释「按时间**升序**，供漂移窗口分析」写了 `.reversed()`。
但 Kotlin `StickerPreferenceStoreImpl` 的类注释明确指出那是**过时表述**，实际必须**降序**。

证据在消费端 `sticker_preference.rs::check_drift`：

```rust
let user = points.iter().filter(|p| p.source == "user").collect();
let recent  = &user[..w];    // ← 取数组「前」w 个当作近期窗口
let history = &user[w..];
```

`user[..w]` 被当作 recent，所以数组**必须最新在前**。按升序返回会让漂移检测
把**最旧的** w 条当成近期窗口 —— 结论完全反过来，且不会报错。

**教训**：两份文档互相矛盾时，**以消费端代码为准**。装置里现在有一条反例断言
（「升序会让 recent 窗口取到最旧的」）把这个方向钉死。

### 与 `StickerPreferenceStoreImpl` 的其它差异（均已修正）

| 项 | 原先（错） | Android 实际 |
|---|---|---|
| `listEntries` 排序 | 无 `ORDER BY` | `ORDER BY id ASC` |
| CSV 切分 | `split(",")` 直接映射 | **逐项 trim + 丢弃空项**（`"a, b, ,c"` → `["a","b","c"]`） |
| `context_tags` 解析 | 只试 JSON，失败转 CSV | 先判 `hasPrefix("[")`，再 JSON，失败转 CSV（转 CSV 时同样 trim） |
| `usageHistory` limit | 直接用 | 下限为 **1** |

### 与 `SkillStoreImpl` 对齐时发现的差异（均已修正）

| 项 | 原先（错） | Android 实际 |
|---|---|---|
| `listSkills(nil)` | 返回**全部**技能（越权可见各伴侣私有技能） | 只返回全局技能（`companionId IS NULL`） |
| 正文哈希校验 | `contentHash` 非空才校验 | **无条件校验**（空哈希的行也必须比较） |
| 返回给模型的正文 | 原文（含 frontmatter） | **`SkillContentParser.parse(content).body`**（剥离 frontmatter） |
| `skill_id` 为空 | 直接返回 -1（导入社区技能必失败） | **生成 UUID** |
| name/description | 只用入参 | **frontmatter 优先覆盖**，name 逐级回退到 skillId |
| `category` 无法识别 | 空串 | 回落 **`CUSTOM`** |
| `contentPath` | 绝对路径 | **相对路径** `agent_skills/<id>/content.md` |
| `meta.json` | 未写 | 写元数据快照（FS 为唯一事实源，可据此重建索引） |
| `deleteSkill` 索引行不存在 | 返回 `true` | 返回 **`false`**（`deleted > 0`） |
| `searchSkills` | 无 `enabled` 过滤 | **`enabled = 1`**（禁用技能不该被搜到）+ 只搜全局 |
| `companion_id`（输出 JSON） | 无值时填 `0` | 无值时填 **`null`** |

新增 `SkillContentParser.swift`（纯函数）并把 Kotlin 逻辑用 Python 转写后交叉验证 19 个用例，
全部一致。这类无平台依赖的纯逻辑是**当前环境下少数能确定正确**的部分，
因此优先补测试（`YuNianTests/SkillContentParserTests.swift`）。

### 易混淆点（已写进代码注释）

- `conversationType` 是**普通 TEXT 列** → 存业务字面量 `chat` / `group`
- `type` / `fileFormat` / `memoryType` / `scope` / `source` 是**枚举列** →
  Room 转换器按 `value.name` 存**大写**（`@SerialName("text")` 只影响 JSON）
- 自增主键的新建插入必须用 `NULLIF(?, 0)`，否则 `0` 会被当合法 rowid 显式写入
- **三个 JSON 入参契约**（`settings_json` / `credentials_json` / `history_json`）已从 Rust 源码逐键提取，
  并用交叉核对脚本确认：**Swift 声明的每个键 Rust 都真的读，且 Rust 读取的入站键无遗漏**
- **对话链路已接通**：`ChatSession` → `AgentHostThreading.turnQueue`（阻塞）→ Rust 决策 + LLM SSE
  → `StreamSink` 增量 → 打字机效果。含死锁防护（Rust 未回调 `onDone` 时强制收束流）

### 明确的待办与已知风险

1. **~~Swift 方法名需与 bindgen 产物校对~~** —— **已完成**。
   `ios/Generated/LianyuAgent.swift` 已在 Windows 上生成（见「方式三」），
   7 个 foreign trait / 22 个方法与宿主实现**逐字一致**，
   并由 `Tools/verify_swift_conformance.py` 固化为 CI 断言。
   同时顺带验证了 `uniffi.toml` 的 `[bindings.swift]` 配置正确
   （产物名为 `LianyuAgent.swift` + `lianyu_agentFFI.h/.modulemap`，与配置一致）。
   **仍未被验证的是「Swift 能否编译」** —— 那需要 Xcode。
2. **三处跨端签名不一致未解决**（`PATH` 前缀 / `CLIENT_ID` 来源 / `X-LianYu-Pub`），
   必须先与服务端对齐 —— 文档 §7.5 与 V9。主 API 验签代码不在本仓库，无法自查。
3. **`embed_text` 有意返回 nil**，Rust 会降级为关键词召回（M3 接入）。
4. **`system_prompt` 传 nil**：system prompt 完全由 Rust 侧编排器组装。
   若将来改由宿主提供，应走 `AgentTurnRequest.systemPrompt`，
   **不要**塞进历史 —— 历史里的 system 消息会走 Rust 的人设替换逻辑，语义不同。
5. **`image_gen_rules` 未下发**（Rust 侧 `setting_str("image_gen_rules")` 读取），
   生图协议文本的迁移属 M3。
6. **~~FTS 索引的维护点未复刻~~** —— 已在 `MessageRepository` 补齐：
   写入（含正文更新）、按 id 删、按会话清空、删最旧、归档，全部与 Android
   `MessageDao` 的 14 个维护点对应。其中一条**顺序约定**已用反例验证其必要性：
   删最旧/清空会话时**必须先删 FTS 行再删元数据行**，否则 `rowid IN (SELECT id FROM messages ...)`

## ⚠️ 第 142 轮更正：上面第 6 条的表述会让人误判 FTS 已"可用"

三态死代码报告显示：
```
YuNianDatabase.searchMessageIds        ← 声明后从未被调用
MessageRepository.hotMessageCount      ← 同上
```
即 **FTS 索引在 iOS 侧只写不读** —— 分词、写入、维护、清空全部实现并通过真实
SQLite 验证，但**没有任何查询消费方**（上面第 6 条说的"补齐"只覆盖写入侧）。

准确表述应为：
- **FTS 写入/维护侧**：✅ 已实现并验证（14 个维护点 + 顺序约定反例）
- **FTS 查询侧**：⚠️ `searchMessageIds` 已实现但无调用方，因为 iOS 侧**没有消息搜索界面**
  （Android 的聊天页有搜索框；`ChatView` 目前是极简版）

这与第 112 轮 README 统计漂移、第 125 轮关卡数虚高是同型：
**把「已实现」说成「已可用」。** 区别在于这次先说清了两侧的边界。
   子查询会因元数据已消失而返回空集，留下无法清理的脏索引
   （证据：`Tools/verify_schema_sql.py` 的「反例」断言）。
7. **`embed_text` 之外，记忆/技能/表情三个 store 尚未接 UI**：Rust 回调已实现，
   但没有设置界面去增删记忆、安装技能、导入表情包（M3 剩余工作）。
8. **`archiveOldest` 尚无触发者**：Android 侧由 `DataCleanupManager`（WorkManager 每日维护）
   调用，iOS 侧需在 M5 的 `BGTaskScheduler` 里安排等价任务。
9. **过滤规则的「分级口径」与 DB 违禁词表不同，这是有意的**。
   `content_filter_keywords.json.enc`（实际过滤用）与 `SecurityDataSeeder`
   （DB `keywords` 表）是**两套独立数据**：同一个词在两边可能分属不同等级
   （例："hypothetically speaking" 在过滤表是 SEVERE，在 DB 表是 LOW）。
   iOS 侧两份都如实保留，不试图统一。
10. **过滤词表的切分易错点**：每个等级的数组在资源里是
    「前半 patterns + 后半 kwds」的拼接，必须按 `loadFromAsset` 从中间劈开。
    生成器已按此实现，并有断言防止回归（用「以 `(` 开头或含 `(?i)`」作为正则的
    无歧义判据 —— 不要用「是否含正则元字符」，纯关键词本来就可能含 `+`，如 `r18+`）。
10. **⚠️ 安全基线数据里的中日文条目是乱码（Android 侧既有问题，如实保留）**。
    实测：`keywords` **94/284** 条、`filterPatterns` **317/583** 条为
    「UTF-8 字节被按 Latin-1 解读」的双重编码产物。
    例如 `児童ポルノ` 被存成 15 个 Latin-1 字符。

    **证据链**（均已核实）：
    - XOR 解出的原始字节是 `44414ec3a3c283c2a2…` —— `C3 A3 C2 83 C2 A2` 正是
      `U+00E3 U+0083 U+00A2` 的 UTF-8 编码，即**双重编码**；
    - `ContentFilter.checkBlocking` 用 `p.matcher(text)` 直接匹配原始输入，
      **全程未对输入做归一化/转码**（已逐处确认）。
    → 这些 CJK 规则**无法匹配真实中文/日文输入**，即 Android 的内容过滤
      在 CJK 维度上实际不生效（英文部分 266 条正常）。

    **iOS 侧的处理**：**如实保留**，`SecuritySeedLoader.restoreDoubleEncodedEntries`
    默认为 `false`。理由是若 iOS 单方面修复，其过滤强度会**高于** Android ——
    这属于产品/安全决策，不该由移植方单方面改变。

    **修复是确定性的**：`s.encode('latin-1').decode('utf-8')`。
    Swift 侧 `restoreDoubleEncoded(_:)` 已实现并有金标单测；
    启用开关前**必须同时修改 Android 的 `d()` 等价实现**，
    否则两端过滤强度分歧。生成器已把统计写入 JSON 的 `_diagnostics`。
10. **`avatar_aze`（男友默认头像）在 iOS 上不可用**：Android 侧是矢量 drawable XML
    （`app/src/main/res/drawable/avatar_aze.xml`），iOS 无法直接使用。
    本机无 SVG 光栅化工具，**无法验证转换结果的渲染效果**，因此不生成未经验证的产物
    —— `AvatarResolver` 对该资源返回 nil，UI 退化为占位图。
    处理方式见 `AvatarResolver.swift` 的注释（三选一，需设计侧决定）。
    女友头像 `avatar_xiaoyu`（WebP）已搬运至 `Resources/Assets.xcassets`。

---

## 与 Android 侧的边界

- iOS 侧**不修改**任何 `.rs` 文件 —— Rust Agent 零代码改动即可编到 iOS
- iOS 侧**不修改**任何 Kotlin 文件
- 唯一对 Android 侧资产的改动：`agent-native/uniffi.toml` 新增 `[bindings.swift]` 段
  （对 `[bindings.kotlin]` 无影响，Kotlin 绑定生成行为不变）
- 若将来需要把逻辑下沉到 Rust（Kotlin → Rust），必须走 AGENTS.md 的「冲突预审」清单
