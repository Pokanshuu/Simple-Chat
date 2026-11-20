# 06 · Markdown 渲染

> 本文与其余 docs 共用原《设计文档》的章节编号（如 §4.1、§15）。
> 跨文件引用照旧有效；完整文件索引见 [README](README.md)。

---

## 6. Markdown 渲染方案

### 6.1 选型

**采用第三方库 `com.mikepenz:multiplatform-markdown-renderer` 0.45.0**，
替代原计划的"自研 Compose 引擎 + 自绘公式排版"（后者预估 2500–3500 行，已弃用）。

| 模块 | 作用 |
|---|---|
| `multiplatform-markdown-renderer` | 核心解析与渲染（基于 `org.jetbrains:markdown`） |
| `-m3` | Material3 主题适配 |
| `-code` | 代码语法高亮（基于 `dev.snipme:highlights:1.1.0`） |
| `-coil3` | 图片加载 |

**能力**：标题、段落、粗斜体、删除线、行内码、围栏代码块（带高亮）、引用、
有序/无序列表、任务列表、表格、分割线、链接、图片、GitHub Alerts。

**数学公式**：Compose 原生 `latex-renderer` 处理。行内公式在 Markdown 规范化前替换成安全 token，再由 `InlineTextContent` 放回原段落；代码、链接目标与自动链接由 Markdown AST 保护。行间公式按块布局。施工和设备验收见 [LaTeX 施工记录](31-LaTeX排版与渲染施工方案-2026-11-20.md)。

### 6.2 实时渲染 + 节流（**取代**原先的「流式两阶段」）

**当前做法：流式期间就渲染完整 Markdown**，只对喂给渲染器的内容做节流。

```
数据层     每 16ms 合并已经收到的增量
              ↓
显示层     按屏幕帧间隔和积压量推进可见前缀，追平后挂起
              ↓
渲染层     对可见前缀切分公式与 Markdown 块，更新活动尾块
              ↓ 流结束
           不再节流，直接用最终文本（内容已经不动了）
```

#### 为什么推翻了两阶段

原方案是"流式纯文本 → 定稿才渲染"，理由是"库每次都重解析整篇文档，长回复必掉帧"。
实测下来这个取舍**站不住**：

1. **版式会跳**。流式纯文本与定稿 markdown 的段落间距、对齐方式、标题字重都不同，
   定稿那一瞬间整段内容重排。用户看到的是"渲染完成的瞬间跳一下"。
2. **段落之间的空会消失**。纯文本里一个空行是实打实的一整行（约 27dp），
   而 markdown 的块间距只有 12dp —— 用户的原话是"md 渲染前段落之间有空，渲染完就没了"。
3. "不实时渲染"本身就是个缺点，用户明确提了。

可见前缀按块渲染。已完成的块复用解析结果，尾块随显示进度更新。
普通正文的新增字符按实际换行位置短暂淡入，动画只触发绘制。

#### 节流为什么不能用 debounce

```kotlin
// ❌ 这是 debounce，不是 throttle
LaunchedEffect(value) { delay(80); shown = value }
```

连续收到增量时，以上写法会反复重启计时器，直到流暂停才更新。
当前实现用 `snapshotFlow` 等待内容变化，有积压时用 `withFrameNanos` 推进。
小数部分的显示预算可以留到下一帧，高刷新率屏也能保持相近的阅读速度。
停止和定稿时直接显示已收到的全文。

### 6.3 代码块增强（已实现）

库的 `highlightedCodeFence` 插槽**没有打开**它内部的 `showHeader`
（`MarkdownHighlightedCode(code, language, style, builder, showHeader, immediate)`
里那个参数确实存在，但插槽传的是关），所以语言标签与复制按钮一个都不显示。

做法是**自绘一条顶栏** + **自绘正文渲染**：

```
Column(background = fieldBackground, SoftShape)
├── CodeHeader   ← 语言标签 + 总行数（左）+ 复制按钮（右，点后 1.5 秒变勾）
├── Row
│   ├── 行号栏（固定，不随横向滚动）
│   └── MarkdownBasicText(高亮后的 AnnotatedString)   ← 横向可滚
└── 「展开全部（N 行）」/「收起」（超 20 行时）
```

围栏解析仍走库的 `MarkdownCodeFence`（含 `replaceIndent()` 去公共缩进），
渲染原语仍用库的 `MarkdownCodeBackground` + `MarkdownBasicText` ——
**换掉的只是"高亮结果从哪来"**（见 §6.3.1），所以视觉与改造前逐行一致。

- 超过 **20 行**自动折叠，底部「展开全部（N 行）」
- 折叠用 `heightIn(max) + clipToBounds` 硬裁 —— 库的代码块是自绘的，
  拿不到内部测量结果，与其猜不如按高度截断
- 围栏拆分 `splitFence()` 对「已去围栏 / 还带围栏」两种输入都成立，
  不假设库对 `content` 的裁剪程度

#### 6.3.1 增量高亮 / 行号 / 收起态（v0.9.0-beta.4）

对着 `docs/长文本渲染优化对比报告.md` §2.4 —— 官方的做法是"高亮器返回
**已稳定高亮的前缀长度**，只对后缀重算"（`MarkdownCodeBlockContent.kt:95` 的
`code.substring(jq4Var.b.length())`）。落地在 `ui/markdown/CodeFence.kt`：

- **增量高亮**：新代码若是旧代码的**纯追加**（流式就是这个形态），除**最后一行**
  以外的高亮整段复用 —— 最后一行的 token 可能延伸到新追加的内容里，必须重算。
  换版本 / 换语言 / 换主题才整段重算。片段按起点排序（库给的是定位器顺序，
  两者天然不同；片段互不重叠，顺序不影响渲染，排一下只为结果确定）
- ⚠️ snipme 的高亮器**内部本来就有这套**（`CodeAnalyzer.analyze(code, language, snapshot)`
  + `CodeComparator`），但它的 `code` 在 Kotlin 侧是 private、语言 / 主题只读 ——
  每帧只能 `Builder().build()` 一个新实例，snapshot 是空的，等于整段重算。
  **这正是报告里那句"mikepenz 不给增量接口"的由来**
- ⚠️ **`ColorHighlight.rgb` 高位是 0**，`Color(rgb)` 解出来是**全透明** ——
  必须 `copy(alpha = 1f)`（库自己就是这么写的）。漏了的话被高亮的字符
  （标点 / 关键字 / 字符串）在屏幕上**直接看不见**：`<!DOCTYPE html>` 显示成
  `!DOCTYPE html`，**看起来像 markdown 把 HTML 标签吃掉了**
- **行号栏**：所有行号装在**一个** `Text` 里（`"1\n2\n3…"`），不是每行一个
  composable —— 几百行也只是两个 Text 节点，行高又天然等于"一行"；
  放在横向滚动**外面**（代码左右滚、行号不动）；与代码用**同一个**
  `MarkdownBasicText`（连 font padding 都一致，否则第一行基线就会差一点，
  越往下越明显）。单行代码块不显示行号
- **收起态只排版看得见的行**：`heightIn(max) + clipToBounds` 是**裁切**不是跳过 ——
  几千行的代码块为了显示十几行照样把整篇文字排一遍。收起时只喂前 60 行
  （折叠高度 300dp 的四倍余量），排版量从 O(全部) 降到 O(可见)。
  **这是代码块最大的一笔开销**
- 不再走库的"先纯文本再高亮"两段渲染（异步高亮结果没回来之前先显示纯文本），
  省掉一次全篇重排；同一份内容连 `AnnotatedString` 对象都复用

> **测法上的教训**：以上除了行号，单测都能钉住（`CodeFenceTest`：增量结果必须与
> 整段重算**逐条一致**）。唯独透明度那条**单测抓不到** —— 两侧走同一条路径，
> 透明也是"一致"的。它是靠**模拟器截图**看出来的。
> 涉及颜色 / 布局的改动，单测只能保证"自洽"，**看不出来的错要靠看**。

### 6.4 排版：基准倍率与「为什么标题被压小了」

#### 一个常量管全局字号

`Type.kt` 里所有的 sp 都由 **`TypeScale`** 算出来：

```kotlin
/** 「整体都偏大 / 偏小」时唯一该改的地方。 */
private const val TypeScale = 0.925f
```

正文、标题、标签、代码块、markdown 六级标题**一起等比走** ——
逐个手改那二十来个数字，比例会因为四舍五入走样，下次想再调又得重来一遍。

`0.925` 正好是设置里「字体大小」滑块中**「较小」那一档的原值**：
原来要手动滑一档才舒服，现在把它变成了默认，滑块则回到"相对新基准"的语义。

> **「全局字号」和「正文基准」是两件事**，别混：
> 滑块（`LocalDensity.fontScale`）是运行时倍率，影响**所有** sp；
> `TypeScale` 是编译期基准，定义"标准档长什么样"。

#### 派生，不要手抄

`MarkdownBodyStyle` 与 `bodyLarge`、`ProcessTextStyle` 与 `bodyMedium`
原先各写了一份**相同的数值**。哪天改字号忘了同步，表现就是
"流式结束的瞬间字号跳一下"——看得见，但很难联想到是两处声明不同步。
现在它们都是 `get() = SimpleChatTypography.xxx`，从源头消除这种可能。

写死的字号也清掉了（例如原来 markdown 的
`inlineCode = CodeTextStyle.copy(fontSize = 15.sp)` —— 不跟着基准走）。
全项目现在只剩 `letterSpacing = 0.sp` 一处字面量。

#### 标题为什么被压小

`markdownTypography` 的 h1–h6 走 `MarkdownHeadingStyles`，
**故意比 Material 的 headline 系列小一大截**：H1 只比正文大一档（20sp vs 17sp）。

聊天窗口里 32sp 的 H1 比正文还抢眼，读起来像文档而不像对话。
层级靠**字号递减**表达；**六级一律 Bold**（原先 H1–H3 Bold、H4–H5 SemiBold、H6 Medium，
小标题在长文里发灰、认不出是标题）。

### 6.5 段落：段间距与软换行

#### 段间距

库默认几乎让块与块挨着，长文读起来是**一整片**。
用 `markdownPadding(block = 6.dp)` 给块之间留气口 ——
中文写作里段落是最基本的阅读单位，段间不留空等于没有分段。

#### 软换行：**看行尾决定**

markdown 语法里单个换行是"软换行"，渲染时会被合并成一个空格。
而中文创作里单换行常常就是分段 —— 不处理的话几个自然段会糊成一整块。

但**不能一律当分段**。实测模型（DeepSeek）是懂 markdown 的，它自己在用两套写法：

```
第一段。<LF><LF>              ← 空行 = 段落分隔
句子 A。  <LF>                 ← 行尾两个空格 = 段内换行（markdown 硬换行）
句子 B。<LF><LF>
```

所以 `normalizeSoftBreaks()`（纯函数 + 14 项单测）的规则是：

> **裸的单个换行 → 补成空行（当分段）；行尾带硬换行标记的 → 原样留着。**

五类地方不动：围栏代码块内部、表格行、本来就是空行边界、
行尾有硬换行标记（两个空格 / `\`）、缩进续行。

> 表格那条有个反直觉的细节：**只看本行**，不看下一行。
> 相邻两行表格之间当然不能插空行（表头与分隔行必须紧挨着），
> 但正文与表格之间**要**插 —— GFM 的表格不能打断段落，
> 紧贴着正文的表格根本不会被解析成表格。

规范化在**流式阶段也做**：否则流式时"一段一段"、定稿后"糊成一坨"，
版式会在定稿瞬间跳一下。

### 6.6 CommonMark / GFM 能力对账（2026-09-25）

对账方式：分别解出 `org.jetbrains:markdown 0.7.9`（解析器）与
`com.mikepenz:multiplatform-markdown-renderer 0.45.0`（渲染器）字节码里的
**元素类型常量**，逐项比对「解析器会产生、渲染器有没有对应处理」。
默认 flavor 是 `GFMFlavourDescriptor`。

#### 已支持（解析器产生 → 渲染器处理）

| 类别 | 元素 |
|---|---|
| 标题 | `ATX_1`–`ATX_6`、`SETEXT_1` / `SETEXT_2` |
| 段落 | `PARAGRAPH`、`TEXT`、`HARD_LINE_BREAK` |
| 强调 | `EMPH`、`STRONG`、`STRIKETHROUGH` |
| 列表 | `ORDERED_LIST`、`UNORDERED_LIST`、`LIST_ITEM`、`LIST_BULLET`、`LIST_NUMBER`、`CHECK_BOX` |
| 引用 | `BLOCK_QUOTE`、GFM `ALERT` + `ALERT_TITLE`（NOTE / TIP / IMPORTANT / WARNING / CAUTION） |
| 代码 | `CODE_BLOCK`、`CODE_FENCE`、`CODE_SPAN`、`BACKTICK`、`FENCE_LANG` |
| 表格 | `TABLE`、`HEADER`、`ROW`、`CELL`、`TABLE_SEPARATOR` |
| 链接 | `INLINE_LINK`、`FULL_REFERENCE_LINK`、`SHORT_REFERENCE_LINK`、`AUTOLINK`、`EMAIL_AUTOLINK`、`GFM_AUTOLINK`、`LINK_DEFINITION` |
| 图片 | `IMAGE`（块级 + 行内） |
| 分隔 | `HORIZONTAL_RULE` |
| 转义 | 反斜杠转义（`EntityConverter.REGEX_ESCAPES`） |

#### 缺失或退化（需修）

| 项 | 现状 | 影响 | 状态 |
|---|---|---|---|
| **HTML 块** `HTML_BLOCK` / `HTML_BLOCK_CONTENT` | 解析器产生，渲染器**无对应组件** | 模型常输出 `<div>` / `<br>` / `<b>` / `<details>`；整块内容可能丢失或显示成标签原文 | ✅ 已修：`normalizeHtml()` 把 HTML 折算成 Markdown 等价物 |
| **行内 HTML** `HTML_TAG` | 同上 | `<br>` 不换行、`<b>` 不加粗 | ✅ 同上 |
| **数学公式** | 行内通过 AST token 与原生 `InlineTextContent` 混排；行间单独布局；显示层兼容适配和源码 fallback | 复杂公式、表格单元格、窄屏、流式、选择和导出分别回归；未支持语法保留源码 | ✅ 已实现；专项矩阵见 §11.4 与 [LaTeX 施工验收](31-LaTeX排版与渲染施工方案-2026-11-20.md) |
| **HTML 实体** | 依赖 `EntityConverter`，需实测 | `&amp;` `&nbsp;` `&#123;` 的还原正确性未验证 | ✅ 已修：实测渲染器不还原，改由 `normalizeHtml()` 解码；`&lt;` `&gt;` 还原后转义保字面量 |
| **引用式链接** `[x][id]` / `[x][]` / `[][id]` | **完全没解析成链接**，退回字面 `[x][id]` —— 分块渲染把定义与用法切进了不同块 | ✅ 已修：解析前收集全文定义、就地内联成 `[x](url)`；`[][id]` 用标题当文字。标题本身移动端无处显示，只有空文字时才取用 |
| **图片加载** | 组件存在，但 `ImageTransformer` 为 `NoOp` | 远程图片不加载，且图片节点会留一大块空白 | ✅ 已修：HTML `<img>` 与 markdown `![]()` 一律折算成 alt 文字，不留空白 |
| **任务列表勾选** | 复选框已自绘（描边 / 填充打勾） | — | ✅ 已修。**可点击交互刻意不做**（纯展示，不需要本地状态） |
| 脚注 `[^1]`、定义列表、上标下标、`==高亮==`、emoji shortcode | 解析器无对应类型 | 按纯文本显示 | 不做 |

`normalizeHtml()` 折算范围：`br` `hr` `p` `div`/`section`/… `li` `h1`–`h6`
`strong`/`b` `em`/`i` `del`/`s` `code`/`kbd` `blockquote` `a[href]` `img[src]`。
**认不出的标签只去标签、保留内容**。围栏代码、行内代码、自动链接、HTML 实体一律不动。
见 `ui/markdown/HtmlNormalize.kt`（纯函数 + 28 项单测）。

#### 「排版乱」的候选来源

| 嫌疑 | 说明 | 状态 |
|---|---|---|
| 块间距两层叠加 | `MarkdownBlockGap 12dp`（库内）+ `MarkdownBlockColumnGap 24dp`（外层）合计约 108px；列表 / 表格 / 代码块之间同样吃这一层，可能稀疏不均 | ✅ 已修：收紧到 8dp + 16dp（约 72px） |
| `normalizeSoftBreaks` 拆段 | 裸单换行一律补成空行，段落被拆得比原文碎 | ✅ 已修：行尾是句中标点（`，、；：` 等）视为续行；下一行开新块（围栏/表格/列表/标题/引用）仍要分 |
| 表格溢出 | 无横向滚动，列多或内容长时被截断 | ✅ 已修：`scrollableTable` 外套 `horizontalScroll`，按表格自然宽度布局（根因见下条） |
| **代码块 / 表格的横向滚动** | `horizontalScroll` 形同虚设：长行被切在右边界、划它零位移 | ✅ 已修，**两个根因**。其一，`Text` 报给布局的是**约束宽度**不是文字实际宽度，可滚距离恒为 0 —— 用 `TextMeasurer` 量出代码自然宽度再 `requiredWidth` 钉住，滚距 `0 → 1102px`（探针实测）。其二，**带 `pointerInput` 的节点会把兄弟挡在命中路径外**，浮在上面的索引面板自己挂了手势，代码块压根收不到事件 —— 手势改挂到浮层的父节点即通。详见 [`07-功能设计.md`](07-功能设计.md) §34.7 |
| 标题层级弱 | H1 刻意只比正文大一档（§6.4），长文里层级感不足 | ✅ 已修：六级统一 Bold |
| 列表项目符号过小 | 真机实测：无序列表的圆点只有几个像素，几乎看不见 | ✅ 已修：`bullet` / `ordered` 两档样式只作用于符号，放大到 21f（行高与正文对齐） |
| 列表标记与正文错位 | 真机实测：三种列表正文左边界各不相同（`•` 窄、`1.` 宽、复选框更宽），符号中心也偏高 | ✅ 已修：自绘 `unorderedList` / `orderedList`，符号列定宽 `MarkerColumnWidth 20dp` + `MarkerTopOffset 2dp`；实测三种列表正文统一起于 x=116px |
| 任务列表复选框 | 渲染成等宽文字 `[ ]` / `[x]`，不像勾选；且比正文高 9px | ✅ 已修：自绘方框（未勾描边、勾选填主色打勾）+ `padding(top = 3.dp)` 与首行光学居中，实测偏差 0px |
| 引用 / 嵌套列表缩进 | 未做专项校准 | ✅ 已修：相邻引用行合并为一个引用块（多行引用不再各带一条竖线、GFM 提示块标题与正文不再分家）；嵌套列表沿用 `listIndent * depth` |

#### 下一步

1. 写一份**格式对照样例**（覆盖上表每一项），真机逐条截图比对。
2. LaTeX 支持边界、兼容策略和多容器布局验收见 [LaTeX 施工记录](31-LaTeX排版与渲染施工方案-2026-11-20.md)。
