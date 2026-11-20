# 05 · 网络与 API

> 本文与其余 docs 共用原《设计文档》的章节编号（如 §4.1、§15）。
> 跨文件引用照旧有效；完整文件索引见 [README](README.md)。

---

## 5. 网络层

### 5.1 通信协议

统一使用 **OpenAI 兼容 `/chat/completions`**，只做这一个适配器。
它同时覆盖 DeepSeek 官方全部能力（识图 + 思考）与 OpenCode Go 的约 24 个模型。

**不做** Anthropic `/messages` 与 Responses API `（代价：放弃 minimax / qwen / gpt-5.6-luna / grok-4.6，约 9 个模型）`。

### 5.2 Provider 配置

| Provider | Base URL | 默认模型 | 识图 | 思考 |
|---|---|---|---|---|
| DeepSeek 官方 | `https://api.deepseek.com` | `deepseek-flash` | ✅ | ✅ |
| DeepSeek 官方 | 同上 | `deepseek-v4-pro` | ❌ | ✅ |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | `deepseek-v4-flash` | 按模型 | 按模型 |
| 自定义 | 用户填 | 用户填 | 手勾 | 手勾 |

**已实测确认**（2026-09）：

- DeepSeek `GET /models` 只返回 **`deepseek-flash`** 与 **`deepseek-v4-pro`** 两个模型，
  与能力表一致。只有 `deepseek-flash` 支持识图。
- OpenCode Go `GET /models` **无需鉴权**即可访问，返回 38 个模型。
- ⚠️ **OpenCode Go 的 `/chat/completions` 必须携带 `x-opencode-session` 请求头**，
  否则直接返回 400：
  ```json
  {"error":{"type":"MissingSessionID",
            "message":"Error from provider (Console Go): Request is missing
                       x-opencode-session and cannot be routed efficiently."}}
  ```
  取值只需**安装级稳定标识**（UUID 即可），用于路由与流量识别。
  官方另要求客户端「明确标识自身，不要用宽泛的 User-Agent」，
  因此统一发送 `User-Agent: SimpleChat/<VERSION_NAME> (Android)` —— 版本号从 `BuildConfig` 取，**不写死**（写死之后改版本必忘，服务端看到的客户端版本就成了假的）。
  已由 `ProviderTest` 锁死这条回归。

**模型列表运行时拉取**：设置页「测试连接」与对话面板的「刷新」都会打
`GET /models`，拉到的 id **落库**并成为「模型」选择器的数据源；选择器把远端列表
排在前、内置能力表补在后（远端为空时只有内置表，自定义端点则可以手动填写 id）。
端点（服务商 / Base URL）一换，旧列表即作废。

**能力按 id 跨服务商查**：第三方端点常原样代理官方模型（id 与官方一致），
所以 `ModelCatalog.find` 先查当前服务商的能力表、再查全局表 —— 于是中转站上的
`deepseek-flash` 仍是 1M 上下文 + 识图 + 思考，而不是 128K 的未知模型。
两张表都没有的 id 保持保守默认值（128K / 不识图 / 不思考）：
**不按 id 字面猜**，上下文猜大 → 压缩不触发 → 服务端 400；猜小只是早点压缩。

### 5.3 请求构造要点

```jsonc
{
  "model": "deepseek-flash",
  "messages": [
    { "role": "system", "content": "<systemPrompt>" },
    // 当前对话 active=1 的消息，按 slotIndex 升序
    // ⚠️ 不发送 reasoning_content（见 §11.1）
    { "role": "user", "content": "你好" },
    { "role": "assistant", "content": "你好！" }
  ],
  "stream": true,
  "thinking": { "type": "enabled" },      // enabled | disabled
  "reasoning_effort": "high",             // low | high | max（开启思考时）
  "max_tokens": 8192,
  "temperature": 1.0,                     // ⚠️ 思考模式下不生效
  "top_p": 1.0                            // ⚠️ 仅思考模式生效，范围 0.95–1.0
}
```

**带图片时** `content` 改为块数组（仅 `user` 消息允许带图）：

```jsonc
"content": [
  { "type": "text", "text": "这张图片里有什么？" },
  { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,...", "detail": "low" } }
]
```

### 5.4 参数条件显示规则

第三方 API 的硬约束，UI 必须跟着变，否则用户会以为参数生效了其实没有：

| 参数 | 非思考模式 | 思考模式 |
|---|---|---|
| `temperature` | ✅ 生效 | ❌ **静默忽略** → 置灰 |
| `top_p` | ❌ 恒为 1.0 → 置灰 | ✅ 仅 0.95–1.0 |
| `thinking.type` | `disabled` | `enabled` |
| `reasoning_effort` | — | `low` / `high` / `max` |
| `max_tokens` | 默认 8K | 默认 64K（effort=max 时 128K），上限 384K |
| `frequency_penalty` | ❌ **已废弃** | ❌ 已废弃 → **从 UI 移除** |
| `presence_penalty` | ❌ **已废弃** | ❌ 已废弃 → **从 UI 移除** |

### 5.5 SSE 解析

- 逐行读取，识别 `data: ` 前缀；`data: [DONE]` 表示结束
- 增量字段分流：`choices[0].delta.content` → 正文；`choices[0].delta.reasoning_content` → 思考区
- 取消：`Call.cancel()` + 协程 cancel → 点「停止」瞬间生效
- 错误：解析 HTTP 状态码与 `error.message`，展示可重试提示

长请求处理（2026-11-18）：

- OpenAI / Anthropic 两通道发送前估算实际输入，包含系统提示词、摘要、文本附件与续写内容。
  输入加输出预算超过模型标注窗口时本地提示压缩；未指定输出上限时预留最多 4096 tokens。
  本地估算不等于服务端分词结果，也不保证覆盖服务端默认输出预算与隐藏的网关限制。
- JSON 先流式计数 UTF-8 字节，再流式上传，提供准确 `Content-Length`，不额外保留整份 JSON 副本。
  请求体达到 1 MiB 时写超时设为 120 秒；较小请求保持 30 秒，流式读取仍无固定超时。
- 大请求在收到事件前中断，或返回无法识别具体原因的 5xx 时，保留原有错误提示并补充压缩指引。
  DNS、证书、连接建立失败继续使用对应网络提示；不将所有断连认定为上下文超限。
- 保留 `finish_reason` / `stop_reason`。未收到结束标记或结束原因便 EOF 的响应视为中断，
  防止截断的摘要覆盖已有摘要。

### 5.6 流式事件模型

```kotlin
sealed interface StreamEvent {
  data class Content(val text: String) : StreamEvent
  data class Reasoning(val text: String) : StreamEvent
  data class Usage(val inTokens: Int, val outTokens: Int) : StreamEvent
  data object Done : StreamEvent
  data class Error(val message: String, val code: Int?) : StreamEvent
}
```

---

## 11. 附录：API 参考要点与已知坑

### 11.1 ✅ 规避：带 tools 时必须回传 reasoning_content

> DeepSeek 官方原文：「携带了 `tools` 参数的请求，在后续所有请求中，必须完整回传
> `reasoning_content` 给 API —— **即使该轮模型未实际进行工具调用**。若您的代码中
> 未正确回传 `reasoning_content`，API 会返回 400 报错。」

**本项目的规避方式**：本期不实现工具调用，因此请求**不带 `tools`**。
官方同时说明：「若请求未携带 `tools` 参数：`reasoning_content` 无需回传；
即使传入 API，也会被忽略，不会拼接进上下文。」

→ 因此构造请求时**直接不发送 `reasoning_content` 字段**，`reasoning` 仅存本地用于展示。
这条设计同时让 §9.2 的「任意位置编辑消息」变得完全安全。

### 11.2 ⚠️ 限制：Chat Completions 不支持在对话中间插入工具调用

> 官方原文：Chat Completion 接口不支持在对话中间插入工具调用，但支持在对话中间
> 插入 `system` 消息。如需插入工具调用，请改用 Anthropic API 或 Responses API。

**影响**：若未来启用工具调用，编辑历史消息会导致调用链断裂。
槽位模型（§4.1）天然规避——编辑只影响单个 slot，不重排历史。

### 11.3 图像理解限制（`deepseek-flash` 专属）

| 项 | 值 |
|---|---|
| 支持模型 | **仅 `deepseek-flash`**（`deepseek-v4-pro` 不支持图像） |
| 格式 | JPEG / PNG / GIF / WebP（**按文件实际内容判断，不认 MIME**） |
| 传入方式 | ① base64 data URL ② 外部 `http(s)` URL ③ Files API `file_id` |
| `detail` | `low`（缩到 512×512）/ `high` / `original` / `auto` |
| 每图 token | 上限 **1024**；<544×544 会放大，大图缩到约 1300×1300 等效 |
| 请求体上限 | 48 MiB |
| 单图上限 | ≤ 32 MiB（base64/URL）、≤ 64 MiB（`file_id`） |
| 单请求图片数 | 最多 600 张 |
| 单边最大 | 8192 px（≥15 张图时降为 4096 px） |
| **硬限制** | ⚠️ **图片只能出现在 `user` 消息中**，`system` / `assistant` 带图返回 **400** |

**本项目做法**（详见 §23）：图片压到最长边 **1600px**、JPEG q88、白底，
转 base64 内联（`data:image/jpeg;base64,...`），`detail` 固定 **`high`**
—— `low` 会把图缩到 512×512，而"看截图里的文字"是最常用的场景，小字会糊掉。

### 11.4 数学公式

数学公式已实现，当前采用 Compose 原生 `latex-renderer` 1.7.3。历史扩展方案已落地，当前实现与边界如下：

- **库**：`io.github.huarangmeng:latex-renderer`（Apache 2.0，Compose 原生，
  支持 450+ LaTeX 特性，含 `\frac` / `\sqrt` / `\binom` / 化学式 / 增量解析 / 无障碍）
- **当前集成**：Markdown 正文先将行内公式替换为 AST 安全 token，再以原生
  `InlineTextContent` 放回原段落；显式行间公式独立布局。兼容包装在显示层有限规范化，
  不修改消息源文本。不可解析的公式保留定界符和完整源码。
- **分流规则**：代码、链接目标、金额和未闭合定界符不应被误识别；`$…$`、
  `\(…\)` 为行内公式，独占行 `$$…$$`、`\[…\]` 为行间公式；同一行的
  `$$…$$` 兼容为行内公式。
- **验证**：本节记录当前选型；公式支持矩阵和排版验收见 [`06-Markdown渲染.md`](06-Markdown渲染.md) 与 LaTeX 施工记录。
- **不适用的方案**：WebView + KaTeX（每个公式一个 WebView 实例，长列表滚动会卡）

### 11.5 其他 DeepSeek API 要点

- 上下文窗口 **1M**，输出上限 **384K**
- 思考模式**默认开启**，`reasoning_effort` 默认 `high`
- `temperature` 在思考模式下**不报错但静默失效**
- `top_p` 仅思考模式生效，有效范围 `0.95–1.0`，低于按 0.95 处理
- `frequency_penalty` / `presence_penalty` **已废弃**
- `max_tokens` 不传时：非思考默认 8K，思考默认 64K（effort=max 时 128K）
- 支持 `logprobs` / `top_logprobs`（≤20）/ `user_id`（可用于 KVCache 隔离）
- `strict` 模式（Beta）需 `base_url=https://api.deepseek.com/beta`
- 旧模型名 `deepseek-chat` / `deepseek-reasoner` **已退休**，请求由 V4.1-Flash 承接
- 计费区分**高峰/空闲时段**：北京时间周一至周五 9:00–12:00、14:00–18:00 为高峰，
  价格为空闲时段的 2 倍

### 11.6 OpenCode Go 要点

- Base URL：`https://opencode.ai/zen/go/v1`
- 认证：`Authorization: Bearer <OPENCODE_API_KEY>`
- `/chat/completions`（OpenAI 兼容）：deepseek 系列 / glm 系列 / kimi 系列 /
  mimo 系列 / grok-4.5 / hy3 等，约 24 个模型
- `/messages`（Anthropic 兼容）：minimax / qwen 系列，约 7 个 —— **本期不支持**
- `/responses`：gpt-5.6-luna / grok-4.6 —— **本期不支持**
- 模型列表：`GET /v1/models`，**无需鉴权**，实测返回 38 个模型
- 订阅制：$10/月，限额 5 小时 $12 / 周 $30 / 月 $60

---

## 11.7 实测验证记录（2026-09-21）

用真实 API Key 对协议层做过的验证，避免"纸面正确"：

### DeepSeek 真实 SSE 线格式

```jsonc
// 首条：role 出现，content 为显式 null，reasoning_content 为空串
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":""},"finish_reason":null}]}

// 思考增量：content 显式为 null（不是缺字段）
data: {"choices":[{"index":0,"delta":{"content":null,"reasoning_content":"我们需要"},"finish_reason":null}]}

// 正文增量：reasoning_content 变为 null
data: {"choices":[{"index":0,"delta":{"content":"收到","reasoning_content":null},"finish_reason":null}]}

// 末条：finish_reason 与 usage 在同一个 chunk
data: {"choices":[{"index":0,"delta":{"content":"","reasoning_content":null},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":35,"completion_tokens":46,"prompt_tokens_details":{"cached_tokens":0},
                "completion_tokens_details":{"reasoning_tokens":44},
                "prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":35}}

data: [DONE]
```

**对解析器的三点要求**（已满足）：
1. `content` / `reasoning_content` 是**显式 null**，不是缺字段 → 用 `String?` 接
2. 首条 chunk 的 `reasoning_content` 是**空字符串** → 必须过滤，否则会派发空事件
3. `usage` 与 `finish_reason` 在**同一个 chunk** → 两者都要处理，不能只取其一

### 其他确认项

| 项 | 结果 |
|---|---|
| DeepSeek `/models` | 200，仅 `deepseek-flash` + `deepseek-v4-pro` |
| DeepSeek 思考模式 | 200，`reasoning_content` 正常返回 |
| OpenCode Go `/models` | 200，**无需鉴权**，38 个模型 |
| OpenCode Go `/chat/completions` 不带 session 头 | **400** `MissingSessionID` |
| OpenCode Go 带 `x-opencode-session` | 200，正常返回 `content` + `reasoning_content` |

### 单元测试

协议层不依赖网络即可验证，这些用例是"改代码不破坏协议"的安全网：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `SseLineParserTest` | 20 | 思考/正文分流、显式 null、空串过滤、usage、`[DONE]`、心跳注释、脏数据、流中错误对象、错误信息提取 |
| `ProviderTest` | 18 | **`x-opencode-session` 回归**、地址拼接（含 `/v1` 与已完整路径）、模型能力表、远程列表合并、选择器列表（远端优先 / 去重 / 补内置）、跨服务商能力查表、未知 id 的保守默认值 |

**测试已经抓到两个真实缺陷**（都不是靠联调碰出来的）：
1. 流中 `{"error":{...}}` 被 `ignoreUnknownKeys` 静默解析成空 chunk，错误被吞掉
2. 漏发 `x-opencode-session` 导致 OpenCode Go 完全不可用
