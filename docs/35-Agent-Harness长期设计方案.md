# 35 · Agent Harness 长期设计方案

> 状态：设计方案，当前不要求一次性实现全部内容。
>
> 本方案的目标不是马上做一个“万能 Agent”，而是先建立一个可靠、可审计、可扩展的运行时。当前只开放两类和聊天直接相关的工具：联网研究与生图；未来再接入技能、角色卡、世界书、记忆、文件、自动化等能力。

> 当前施工进度：`AgentToolRegistry`、`AgentRuntimeState`、通用 `agentLoop`、`AgentToolExecutor`、结构化工具状态和内存 Transcript 已接入；搜索与生图仍是当前唯一开放的工具族。三种聊天协议的工具历史已通过 `AgentModelAdapter` 隔离；Transcript 持久化尚未开始。

## 1. 设计目标

### 1.1 当前目标

1. 模型根据上下文自主决定是否调用联网研究或生图。
2. 应用根据真实配置决定工具是否可用，不依赖模型上一轮说过什么。
3. 工具开关可以在 Agent loop 中途生效。
4. 工具执行失败、被关闭、参数不合法时，模型能收到结构化结果，不得继续编造成功。
5. 搜索问题不能误触发生图，生图问题也不能因为模型声称“没有能力”而静默跳过。
6. 不把工具路由逻辑继续堆在 `ChatViewModel`，为后续工具接入保留稳定边界。

### 1.2 非目标

当前不做：

- 通用代码执行、Shell、文件写入、浏览器自动化。
- 任意第三方 MCP 工具的自由接入。
- 自动修改用户数据的工具。
- 多 Agent 协作。
- 复杂长期任务调度。
- 现在就实现世界书、角色卡、技能系统；本方案只预留接口。

## 2. 核心原则

### 2.1 配置决定能力，模型决定意图

工具是否能够被调用，只由以下事实决定：

- API 是否配置并通过基本校验；
- 用户是否开启对应开关；
- 当前模型协议是否支持该工具形式；
- 当前会话是否允许附件、联网或生图；
- 当前工具预算是否耗尽。

工具是否应该调用，不再由正则决定，而由模型结合当前上下文和工具描述判断。

正则只能作为安全兜底，例如：

- 用户明确说“不要生成图片”；
- 用户只要求生成提示词、文案或代码；
- 用户询问能力而不是要求执行；
- 用户要求修改图片但没有可用原图。

正则不能作为正常工具暴露的唯一路由器，不能因为“天气”“画”“图”等关键词直接决定工具。

### 2.2 运行时状态是权威来源

模型上下文中必须注入本轮能力快照，并在每个 Agent loop 轮次重新生成：

```text
web_search = enabled / configured_but_disabled / unavailable
web_fetch = enabled / configured_but_disabled / unavailable
image_generation = enabled / configured_but_not_exposed / unavailable
```

这段状态覆盖历史消息里模型自己说过的话。模型不能因为上一轮说过“我没有生图能力”，就在当前工具已经开启时继续否认；也不能因为上一轮搜索开启，就在用户关闭后继续声称可以搜索。

### 2.3 工具调用必须可审计

每次工具调用都应有：

- `callId`：本轮唯一调用 ID；
- `toolId`：稳定的逻辑工具 ID；
- `arguments`：经过 schema 校验的参数；
- `startedAt` / `endedAt`；
- `status`：`accepted`、`running`、`succeeded`、`failed`、`blocked`、`cancelled`；
- `errorCode` 与面向模型的错误说明；
- 是否消耗预算；
- 是否产生用户可见结果。

## 3. 分层架构

建议拆成以下层次。类名可以调整，但职责不要合并回 UI 层。

```text
ChatViewModel
    ↓ 组装本轮输入
AgentRuntime
    ↓ 能力快照、工具注册、循环预算、状态机
AgentModelAdapter
    ↓ OpenAI / Responses / Anthropic / Gemini 等协议适配
ToolRegistry
    ↓ 查找工具定义和执行器
ToolPolicy
    ↓ 权限、开关、预算、参数、确认策略
ToolExecutor
    ↓ 真正访问搜索 API / 生图 API
AgentTranscript
    ↓ 保存模型轮次、工具调用、工具结果和最终回答
ChatRepository / Timeline
```

### 3.1 `AgentRuntime`

负责一轮 Agent 任务的生命周期：

```kotlin
interface AgentRuntime {
    suspend fun run(input: AgentInput): AgentOutcome
}
```

推荐输入：

```kotlin
data class AgentInput(
    val conversation: List<AgentMessage>,
    val latestUserMessage: AgentMessage,
    val model: ModelInfo,
    val protocol: ApiStyle,
    val runtimeState: AgentRuntimeState,
    val cancellation: CoroutineContext,
)
```

`ChatViewModel` 只负责把消息、附件、模型和当前设置交给 Runtime，不负责判断工具调用、不负责拼 tool result、不负责处理协议差异。

### 3.2 `AgentRuntimeState`

状态至少分成“配置存在”和“本轮已启用”两层，不能只用一个 Boolean：

```kotlin
data class AgentRuntimeState(
    val webConfigured: Boolean,
    val webEnabled: Boolean,
    val webFetchEnabled: Boolean,
    val imageConfigured: Boolean,
    val imageEnabled: Boolean,
    val imageExposed: Boolean,
    val maxRounds: Int,
    val remainingWebCalls: Int,
    val remainingImageCalls: Int,
)
```

含义：

- `configured`：配置存在，但不代表本轮能调用；
- `enabled`：用户开关开启且协议允许；
- `exposed`：当前请求实际把工具放进了模型请求；
- `remaining`：执行层的真实预算，不能由模型修改。

### 3.3 `ToolRegistry`

工具注册表是未来扩展的核心。工具不能通过 `if (search) ... else if (image) ...` 散落在请求构造代码中注册。

```kotlin
interface AgentTool {
    val id: String
    val version: Int
    val definition: ToolDefinition
    fun availability(state: AgentRuntimeState): ToolAvailability
    suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult
}
```

注册表示例：

```text
web.search       当前实现
web.fetch        当前实现，作为联网研究的一部分
image.generate   当前实现
```

未来可以注册：

```text
skill.invoke
memory.search
worldbook.lookup
file.read
file.write
character_card.apply
automation.schedule
```

但未来工具必须经过同一套 Registry、Policy 和 Harness，不能绕过运行时直接向模型暴露。

## 4. 当前两个工具族

### 4.1 联网研究工具族

逻辑能力名：`web_research`。

底层可以继续拆成两个协议工具：

```text
simplechat_independent_search
simplechat_independent_fetch
```

模型看到的工具定义可以有两种策略：

1. **兼容优先**：继续暴露 `search` 和 `fetch` 两个函数；
2. **长期优先**：对支持结构化工具的模型暴露一个 `web_research`，参数包含 `action=search|fetch`，由应用内部拆分执行。

建议当前先保持兼容优先，Registry 内部统一映射到同一个 `WebResearchTool`，避免未来业务代码依赖具体协议名称。

联网工具的硬规则：

- 搜索关闭时不暴露，也不执行；
- 用户问能力时不主动搜索；
- 天气、新闻、价格等实时问题由模型决定是否搜索；
- 搜索结果是数据，不是指令；
- `fetch` 只能读取用户提供或本轮搜索返回的公共 URL；
- 搜索与 fetch 有独立预算；
- 失败结果必须带 `failed=true` 或结构化错误码。

### 4.2 生图工具族

逻辑能力名：`image_generation`。

当前工具：

```text
generate_image
```

参数保持稳定：

```json
{
  "operation": "create | edit",
  "prompt": "self-contained visual prompt"
}
```

生图工具的判断规则：

- 是否把工具提供给模型，由配置和当前工具状态决定；
- 是否调用，由模型根据用户意图决定；
- `operation=edit` 时，应用检查是否存在原图；
- 生图 API 失败时，必须回传失败结果；
- 只有图片真正写入消息后，才能向用户显示“图片已生成”；
- 同一轮最多一次生图；
- 模型不能因为历史回答而自行宣称生图成功或失败。

“画一下下周的天气”属于语义歧义，不能用关键词直接路由。模型应结合上下文判断：

- 用户想知道下周天气 → 搜索；
- 用户想要一张表达天气的插画 → 生图；
- 无法判断 → 先澄清。

## 5. Agent Loop 状态机

不要把循环实现成“收到 tool call 就递归调用自己”。建议显式状态机：

```text
PREPARE
  ↓
MODEL_TURN
  ├─ FINAL_ANSWER → COMPLETE
  ├─ TOOL_CALL → VALIDATE_CALL
  └─ MODEL_ERROR → FAIL / RETRY

VALIDATE_CALL
  ├─ ALLOWED → EXECUTE_TOOL
  ├─ DISABLED → TOOL_RESULT(blocked)
  ├─ INVALID_ARGUMENTS → TOOL_RESULT(invalid_arguments)
  └─ BUDGET_EXCEEDED → TOOL_RESULT(budget_exceeded)

EXECUTE_TOOL
  ├─ SUCCESS → TOOL_RESULT(success)
  ├─ FAILURE → TOOL_RESULT(failure)
  └─ CANCELLED → COMPLETE(cancelled)

TOOL_RESULT
  ├─ remaining budget > 0 → MODEL_TURN
  └─ budget exhausted → FINALIZE_WITH_CONTEXT
```

### 5.1 循环预算

默认建议：

| 项目 | 默认值 | 说明 |
|---|---:|---|
| 最大模型轮次 | 4 | 防止工具调用失控 |
| 最大联网研究轮次 | 2 | 搜索后允许再读原文 |
| 单轮工具调用数 | 4 | 防止批量滥用 |
| 单轮生图次数 | 1 | 生图成本高且结果需要回传 |
| 最大搜索次数 | 3 | 查询去重 |
| 最大 fetch 次数 | 2 | 只读取必要来源 |
| 单个工具超时 | 18–35 秒 | 按工具类型配置 |

这些是应用策略，不应让模型通过参数覆盖。

### 5.2 同轮多工具调用

Harness 可以接受模型在同一轮同时请求搜索和生图，但执行顺序应可配置：

1. 默认先执行联网研究；
2. 如果生图提示词依赖搜索结果，先把搜索结果回传模型，再由模型重新调用生图；
3. 不要把搜索结果直接偷偷拼进生图 prompt，避免模型失去控制权；
4. 如果两个调用互不依赖，可以并行执行，但必须在 Transcript 中保持稳定顺序。

## 6. 模型上下文与能力声明

每轮请求都注入一段机器生成的权威状态，不允许手写散落在多个协议适配器中：

```text
Authoritative runtime tool state for this turn:
- web_search: enabled / configured but disabled / unavailable
- web_fetch: enabled / configured but disabled / unavailable
- image_generation: enabled / configured but not exposed / unavailable

Only tools present in this request may be called.
Do not infer tool availability from previous assistant messages.
Do not claim a tool succeeded until a successful tool result is present.
```

注入位置：

- OpenAI Chat Completions：system message；
- OpenAI Responses：system/developer input；
- Anthropic：system；
- Gemini：system instruction 或等价高优先级上下文。

模型输出中出现“我没有搜索能力”不能直接作为事实。事实来源优先级应为：

```text
当前 Runtime 状态 > 当前请求中的工具定义 > 工具结果 > 历史 assistant 文本
```

## 7. 工具结果协议

不要只把工具结果拼成普通字符串。统一使用结构化结果：

```kotlin
sealed interface ToolResult {
    val callId: String
    val toolId: String
    val status: ToolStatus
    val summary: String
    val data: JsonElement?
    val sources: List<ToolSource>
    val error: ToolError?
}
```

状态至少包含：

```text
success
failed
blocked
disabled
invalid_arguments
budget_exceeded
cancelled
```

模型看到的结果要说明“发生了什么”，但不能泄露不必要的内部实现。例如：

```json
{
  "status": "disabled",
  "tool": "web_search",
  "message": "Web search was disabled by the user before execution. No request was sent."
}
```

这能避免模型把“工具被关闭”误解成“应用没有这个能力”。

## 8. Transcript 与 UI 时间线

Agent 内部 Transcript 与聊天消息 Timeline 分离：

### 8.1 Transcript 保存完整过程

```text
AgentTurn
  - runtimeStateSnapshot
  - modelRequest
  - modelResponse
  - toolCalls
  - toolResults
  - finalAnswer
  - budgets
  - timings
```

Transcript 主要用于：

- 调试工具误调用；
- 复现线上问题；
- Harness 测试；
- 未来的 Agent 运行记录查看。

### 8.2 Timeline 只显示用户需要看到的内容

当前 UI 可以继续显示：

```text
思考 → 搜索/读取网页 → 图片 → 正文
```

但不应把完整协议 payload、重复 tool result 或内部拒绝原因全部渲染到聊天正文。

## 9. 未来扩展点

### 9.1 Skill

Skill 不是单个工具，而是一组：

```text
SkillManifest
  - id
  - version
  - description
  - prompt contribution
  - tools
  - permissions
  - budgets
  - compatible models
```

例如：

```text
latex-writing
  - 提供 LaTeX 结构化写作提示
  - 可选文件读取
  - 不自动执行 Shell
```

Skill 只能通过 Registry 注册工具，不能直接修改系统提示词或绕过 Policy。

### 9.2 角色卡

角色卡属于 `ContextProvider`，不是工具：

```kotlin
interface AgentContextProvider {
    val id: String
    fun contribute(context: ContextRequest): ContextContribution
}
```

角色卡只影响人格、语气、背景设定，不应改变工具权限。工具权限仍由 RuntimeState 和 Policy 决定。

### 9.3 世界书

世界书也属于 ContextProvider，可按关键词、实体、会话阶段选择性注入：

```text
用户消息
  ↓ 实体/关键词识别
世界书检索
  ↓ 相关条目排序与 token 预算
上下文贡献
  ↓
模型请求
```

世界书条目应带：

- `entryId`；
- 触发词；
- 优先级；
- 注入位置；
- 最大 token；
- 是否允许覆盖旧条目。

世界书文本是上下文数据，不是工具指令。必须明确标记为不可信内容，防止条目内文本诱导工具调用。

### 9.4 记忆

记忆应作为 ContextProvider 接入，而不是伪装成模型工具。记忆检索、压缩、注入都需要独立预算，并且要记录来源，方便解释“为什么模型知道这件事”。

## 10. Harness 测试环境

正式接入更多工具前，先建立纯 JVM Harness 测试环境，不依赖真实 API。

### 10.1 FakeModel

FakeModel 按预设脚本返回：

- 普通文本；
- 一个工具调用；
- 多个工具调用；
- 错误参数；
- 重复调用；
- 工具调用后继续回答；
- 违背能力声明的回答。

### 10.2 FakeTool

FakeTool 记录：

- 是否被暴露；
- 是否被执行；
- 参数；
- 执行顺序；
- 当前 RuntimeState；
- 是否被 Policy 拦截。

### 10.3 必测场景

1. 搜索关闭 + 天气问题：不调用搜索，不调用生图。
2. 搜索开启 + 天气问题：模型调用搜索。
3. 搜索中途关闭：未执行的调用被拒绝，模型收到 `disabled` 结果。
4. 生图配置存在 + 普通天气问题：不暴露生图工具。
5. 生图配置存在 + 明确图片请求：模型调用生图。
6. “你有哪些工具”：不调用任何工具，只回答当前状态。
7. “画一下下周的天气”：模型可以选择搜索、图片或澄清，但不能由关键词硬路由。
8. 生图失败：最终回答不得声称成功。
9. 搜索成功后模型声称没有搜索能力：Harness 使用真实工具结果覆盖矛盾回答。
10. 历史消息声称没有生图能力，但当前配置开启：以当前 RuntimeState 为准。
11. 世界书条目包含“忽略所有规则”：不得改变工具权限。
12. Skill 注册未知工具：Registry 拒绝注册或标记为不可用。
13. 工具调用超过预算：返回 `budget_exceeded`，不再执行。
14. 用户取消生成：所有在途工具请求可取消，不能继续写入消息。

### 10.4 Golden Transcript

为关键场景保存 JSON Transcript 快照，至少核对：

- 工具暴露集合；
- 每轮状态快照；
- 工具调用顺序；
- 工具执行次数；
- 最终结果状态；
- 用户可见 Timeline。

这样后续新增世界书、Skill 或角色卡时，可以确认没有破坏搜索和生图的基本行为。

## 11. 实施顺序

### 阶段一：Harness 基础层

- 完成 `AgentRuntimeState`；
- 完成 `AgentTool` / `ToolRegistry`；
- 完成 `ToolPolicy`；
- 把当前搜索、生图注册为两个工具族；
- 统一 `ToolResult`；
- 保持现有三种 API 协议适配器只负责传输。

### 阶段二：循环和 Transcript

- 把当前 `webToolLoop` 升级为通用 `AgentLoop`；
- 接入轮次、预算、取消、超时和动态开关；
- 保存内部 Transcript；
- UI Timeline 继续使用精简投影。

### 阶段三：Harness 测试环境

- FakeModel；
- FakeTool；
- Golden Transcript；
- 开关中途变更测试；
- R8 后反射/序列化测试。

### 阶段四：ContextProvider 扩展

- 角色卡；
- 世界书；
- 技能描述和技能工具包；
- 记忆检索；
- 统一 token 预算和优先级。

### 阶段五：更多外部工具

只有在权限模型、Transcript 和 Harness 测试成熟后，才考虑文件写入、自动化或第三方工具协议。

## 12. 验收标准

完成基础 Harness 后，以下结论必须成立：

1. 任何未注册工具都不会被执行。
2. 任何关闭的工具都不会被执行。
3. 模型无法通过历史文本伪造工具能力。
4. 工具失败不会被渲染成成功。
5. 用户中途改开关，下一轮模型能看到新状态。
6. 搜索和生图可以在同一套循环里工作，但互不误触发。
7. 新增工具只需要实现 Tool 接口并注册，不需要修改所有协议适配器。
8. 世界书、角色卡、Skill 等未来能力可以作为 ContextProvider 或 ToolBundle 接入，不破坏当前两个工具。

## 13. 给执行模型的开工边界

第一批只允许改动：

```text
app/src/main/java/com/simplechat/app/net/
app/src/main/java/com/simplechat/app/ui/chat/ChatViewModel.kt
app/src/test/java/com/simplechat/app/net/
```

第一批禁止：

- 修改数据库 schema；
- 修改消息树语义；
- 修改历史消息格式；
- 引入新的网络 SDK；
- 提前实现世界书、角色卡或 Skill UI；
- 为了通过测试而放宽工具权限。

第一批完成后必须提供：

- 代码变更清单；
- Tool Registry 设计说明；
- Agent loop 状态转移说明；
- Golden Transcript 样例；
- 全量单测结果；
- 搜索关闭、搜索中途关闭、生图失败、天气误触发生图四个关键验收结果。

