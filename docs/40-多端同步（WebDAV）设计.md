# 50 · 多端同步（WebDAV）

> 状态：设计草案（2026-11-29），待评审后进入施工。
>
> 文件序号 40 由 `docs/README.md` 的阅读顺序决定；正文用 **§50** 编号，接在
> [38-分享截图个性化样式设计](38-分享截图个性化样式设计.md) 的 §49 之后。文件序号与
> 章节号解耦，二者都遵循「章节编号只增不改」（见 [README](README.md) 维护约定）。
>
> 本文只做设计与文档，不改任何代码、不跑 Gradle。代码引用一律给出
> `文件:行号`，行号以本次编写时的工作区（`main` + 待提交修改）为准。
>
> 关联文档：备份格式与 Key 加密见 [04-数据模型](04-数据模型.md) §14.4/§17/§18/§19；
> 附件与导出范围见 [07-功能设计](07-功能设计.md) §22/§23；收藏夹的备份口径见
> [37-收藏夹功能设计](37-收藏夹功能设计.md) §48.12/§48.13；文档、commit、release 规范见
> [09-文档规范](09-文档规范.md)；Non-Goals 现状见 [01-PRD](01-PRD.md) §1.3。
>
> **代码引用约定**：`data/…` = `app/src/main/java/com/simplechat/app/data/…`，
> `net/…` = `app/src/main/java/com/simplechat/app/net/…`，`ui/…` =
> `app/src/main/java/com/simplechat/app/ui/…`，`db/…` =
> `app/src/main/java/com/simplechat/app/db/…`，`res/…` =
> `app/src/main/res/…`。例：`data/ChatRepository.kt:1303`。

## 50.1 结论

先给结论，后文逐条展开依据。

| # | 议题 | 结论 |
|---|---|---|
| 1 | 需求理解 | 用户原话「webdev多端同步」按 **WebDAV** 理解：用户自备网盘，手机 A ↔ 手机 B（未来其它端）同步聊天数据。不新建官方账号体系，不做托管服务器 |
| 2 | 同步模型 | **以现有 JSON 备份格式为中心、按「会话分片」做快照同步**（方案 B）。否决：单文件整体替换、增量变更日志、逐行合并/CRDT |
| 3 | 数据单位 | 一个会话 = 一个分片文件，内含消息树全部版本、挂载预设、压缩摘要、内联附件。分片即「只有一条会话的 `BackupFile`」，格式与 `version` 不变 |
| 4 | 服务端布局 | 用户指定根目录下固定子目录 `SimpleChat/`：`conversations/`、`tombstones/`、`devices/`、`manifest.json`、`favorites.json`（P1）、`settings.json`（P1）。文件名用会话 id 的 SHA-256，避免中文/特殊字符路径 |
| 5 | 并发控制 | 每个分片文件用 **ETag + `If-Match`/`If-None-Match` 乐观锁**；正确性只依赖 PROPFIND + 内容哈希，`manifest.json` 只作展示与快照，不作判据 |
| 6 | 冲突判定 | **三向比较**：`本地当前哈希` vs `基线哈希` vs `远端内容哈希`。不看时钟、不看「最后修改时间」 |
| 7 | 冲突默认策略 | **远端为规范版本，本机旧版本另存为「同步副本」**（复用导入 `RENAME` + `withFreshIds`），默认两份都留、不丢数据；冲突记入本地状态并在设置页提示 |
| 8 | 删除传播 | 只对**永久删除**写墓碑（回收站软删除不传播）。墓碑先到而本机有改动时**保留本机**并标记冲突（删除让位于数据） |
| 9 | 同步范围（MVP） | 会话（含消息树/分支/压缩摘要/挂载预设/附件）、永久删除墓碑。**不同步**：收藏夹、设置、壁纸/字体文件、草稿、回收站（理由见 §50.5） |
| 10 | 同步范围（P1） | 收藏夹与收藏、全局预设库、设置白名单（API Key 永不同步）、自动同步与后台策略 |
| 11 | 附件 | 跟随会话内联（现有备份格式的必然结果）。单分片上限 **64 MiB**，超限会话跳过并如实列出，不静默丢图 |
| 12 | 认证 | **Basic over HTTPS** + 应用专用密码；拒绝 `http://`；不做 Digest、不做证书固定、不支持自签证书 |
| 13 | 后台 | **不引入 WorkManager**（仓库当前没有该依赖）。MVP 手动同步 + 前台协程；「自动同步」在 App 进入前台时机会式触发并节流，不承诺后台常驻 |
| 14 | 凭据 | 复用 `SecretBox`（Keystore AES-256-GCM）加密 WebDAV 密码；加密失败即保存失败，**绝不回落明文**（与 `setApiKey` 同口径） |
| 15 | 隐私 | 远端 JSON 为**明文聊天正文与图片**，首次配置必须明示风险；P2 提供口令加密（E2EE-ish）开关。用户可一键删除云端 `SimpleChat/` 目录，删除前校验 marker 文件 |
| 16 | 兼容 | 分片沿用 `BackupFile.VERSION = 2`；旧版 App 读到新增字段按 `ignoreUnknownKeys = true` 忽略；未来升 v3 时同步侧要求「同 schema 才写回」，否则只读并提示升级 |
| 17 | 已知风险 | 鸿蒙/卓易通容器里 `File.renameTo()` 可能跨挂载点失败 —— 下载与状态落盘**必须复用** `data/ExportFiles.kt` 的原子写（失败回退流式复制），见 §50.13 |
| 18 | 交付 | MVP（会话双向 + 冲突 + 墓碑）→ P1（收藏/设置/自动同步）→ P2（加密、附件寻址、增量优化、其它端） |

一句话：**把现有「一份 JSON 备份 = 一份完整数据」的约定拆成「一个会话 = 一个 JSON」，用 WebDAV 当用户自己的中转站；用内容哈希判变化、用 ETag 防覆盖、用墓碑传删除，冲突时另存副本不丢数据。**

## 50.2 背景与问题

### 50.2.1 需求与理解

用户提出「webdev多端同步」。按上下文与产品形态，这里按 **WebDAV 网盘同步** 理解：用户已有坚果云、Nextcloud、群晖或 AList 这类 WebDAV 服务，希望「手机 A 写的内容，手机 B 打开就能看到」，且不把数据交给第三方托管。

本文把需求收敛为三条可验收的句子：

1. 用户在设置里填一个 WebDAV 地址、账号与应用密码，点「立即同步」，两台（或多台）设备上的会话最终一致。
2. 同一会话在两端都改过时，**不静默丢任何一端的正文**；冲突可被看见、可被处理。
3. 删除能传播，但「另一端正在写」时不因一条删除指令丢稿。

### 50.2.2 现状：跨端只有「手动导出 → 手动导入」

当前跨设备迁移的唯一路径是数据管理页的导出/导入（`ui/settings/SettingsScreen.kt:2191` 起的 `DataPage`）：

| 现有能力 | 位置 | 现状与缺口 |
|---|---|---|
| 导出所有数据（JSON） | `data/ChatRepository.kt:1303` | 全量一个文件，含所有会话与内联附件；手动选择保存位置 |
| 导出 ZIP 归档 | `data/ChatRepository.kt:1375` | `manifest.json` + `backup.json` + 每会话 Markdown；仍然「一个包」 |
| 导入备份（MERGE / REPLACE_SAME / RENAME） | `data/ChatRepository.kt:1630`、`:1881` | 三种模式都围绕**整份文件 / 整条会话**设计，没有「更新同一条会话」模式 |
| 导入对账 | `data/ImportReport.kt:28` | 已经能回答「声明多少、进来多少、差在哪」 |
| 回收站（15 天） | `db/Entities.kt:45-51`、`data/ChatRepository.kt:255` | 软删除只在本地，无跨端语义 |
| 收藏夹 | [37-收藏夹功能设计](37-收藏夹功能设计.md) | 随 JSON/ZIP 备份走（`data/import/OwnBackupStream.kt:24-25`、`:174-189`）；同步 MVP 不单独传收藏（§50.5） |

所以「多端」今天等于：**用户手动把文件从一台设备搬到另一台，且每次都要全量、每次都要自己判断覆盖还是合并。**

### 50.2.3 现有实现盘点（设计复用面）

本文不另造一套数据格式，所有分片都走已有链路：

| 能力 | 现有实现 | 复用方式 |
|---|---|---|
| 备份格式 | `data/Backup.kt:33-52`：`format = "simplechat-backup"`、`version = 2` | 分片就是「只有一条会话的 `BackupFile`」，字段与版本完全一致 |
| 流式导出 | `data/BackupStream.kt:14-58`、`:100-140` | 逐条消息写 JSON，附件内联 base64；内存只与单条消息有关 |
| 流式导入 | `data/import/OwnBackupStream.kt:18-59`、`:152-197` | 逐会话落临时文件、逐条消息读；对版本做严格相等校验（`:194-195`） |
| 逐条写库 | `data/import/ImportSession.kt:30-44` | 一条会话一个事务，坏会话隔离，不毁整次导入 |
| 会话写库策略 | `data/ChatRepository.kt:2188`（`writeStreamedSession`）、`:2271`（`prepareConversation`） | MERGE 按 id 跳过、REPLACE_SAME 删同名、RENAME 换 id 并存 |
| 导入对账 | `data/ImportReport.kt:28-54`、`:75-95` | 同步结果页复用同一套「声明/成功/失败/原因」口径 |
| 图片加密/落盘 | `data/SecretBox.kt:30-58`、附件落盘见 [07-功能设计](07-功能设计.md) §23.8 | WebDAV 密码、同步状态、墓碑复用同一套存储与原子写 |
| 网络 | `net/ChatApi.kt:230-254` | 复用 OkHttp 5.5.0 与超时/UA 约定；WebDAV 另建专用 Client |
| 错误文案 | `net/NetworkErrors.kt:37-64`、`data/AppText.kt:62-71` | 数据层只带资源 ID，UI 层翻译；同步错误沿用这条约定 |

一个必须点明的细节：**全量备份当前包含回收站会话**。`conversations.getAllRaw()` 的查询是
`SELECT * FROM conversations`（`db/ChatDao.kt:141-143`），没有 `deletedAt IS NULL` 过滤；
而消息侧 `MessageTree.navigable()` 会排除回收站消息（`db/MessageTree.kt:91-105`）。
同步分片必须显式只导出 `deletedAt == null` 的会话（§50.8.3），否则回收站里的会话会被
同步到另一台设备并「复活」。

### 50.2.4 与现有 Non-Goals 的关系（必须先改文档口径）

[01-PRD](01-PRD.md) §1.3 明确写着「账号体系、云同步、多设备」不做，`TODO.md:280` 写着
「自动备份、云同步、账号体系」不做，[37-收藏夹功能设计](37-收藏夹功能设计.md) 也把
「云同步 / 跨设备」列为 Non-Goal。

本设计**不推翻**这些 Non-Goal 的内核（不建官方账号、不托管服务器、不做社交），但确实
新增了「用户自备 WebDAV 的端到端同步」。因此施工前必须先改口径，否则新功能与产品文档
自相矛盾：

| 文件 | 现状 | 建议改法 |
|---|---|---|
| `docs/01-PRD.md` §1.3 | 「账号体系、云同步、多设备」 | 改为「**官方账号体系、官方云同步、托管服务器**；允许用户自备 WebDAV 的端到端同步（§50）」 |
| `docs/01-PRD.md` §9.1 | P2 未做含「云同步」 | 从「未做」挪到「P1 增强」并注明「自备 WebDAV」 |
| `TODO.md:280` | 「自动备份、云同步、账号体系」 | 改为「**官方**云同步、账号体系」；新增「WebDAV 多端同步」待办并链到本文 |
| `docs/37-收藏夹功能设计.md:94` | 「云同步 / 跨设备」列为不做 | 改为「官方云同步不做；收藏跨设备随 WebDAV 同步（P1，§50.5）」 |
| `docs/README.md` | 索引表 | 增一行 `40-多端同步（WebDAV）设计.md` → §50 |
| `docs/10-CHANGELOG.md` | 未发布区 | 施工完成时按 [09-文档规范](09-文档规范.md) 写一条用户可见变更 |

> 这也是 [README](README.md)「改设计先改这里」的落地：**先改口径，再写代码**。

## 50.3 目标、非目标与术语

### 50.3.1 目标

| # | 目标 | 可验收的句子 |
|---|---|---|
| G1 | 多端最终一致 | 两台设备各写不同会话，同步后两端会话集合与内容一致 |
| G2 | 不丢正文 | 同一会话两端都改，同步后两版内容都能在某一端找到（规范版 + 同步副本） |
| G3 | 删除可传播 | 一端永久删除会话，另一端同步后也删除；若另一端有更新则保留并提示冲突 |
| G4 | 幂等 | 同一次同步重复跑、任意步骤中断后重跑，不产生重复会话或损坏数据 |
| G5 | 复用格式 | 同步文件可被现有「导入备份」直接读入；现有 JSON 备份可被同步侧识别 |
| G6 | 低风险 | 不新增数据库迁移、不引入新运行时依赖（WorkManager 等）；同步失败不影响本地使用 |
| G7 | 隐私可控 | 凭据不落明文、不进日志；用户能随时关闭同步并删除云端数据 |
| G8 | 可解释 | 每次同步有「传了几条、拉了几条、跳过几条、冲突几条、失败原因」的对账结果 |

### 50.3.2 非目标

| 不做 | 理由 |
|---|---|
| 实时协同编辑 / 光标级合并 | 产品是单人创作工具，不是协作文档；实时协同要 OT/CRDT 与服务端，成本与收益不匹配 |
| 官方账号体系 / 官方托管服务器 / 计费 | 违反 [01-PRD](01-PRD.md) §1.2「轻量优先、不做账号与云」的内核；WebDAV 的卖点正是用户自己掌握存储 |
| 增量变更日志（op log） | 见 §50.4.3；要把所有写路径改为「先写日志再写库」，与现有导入/回收站/分支语义纠缠，收益主要是带宽，而大头是附件 |
| 逐行/逐消息合并（CRDT、行级 LWW） | 消息树有 `parentId`/`variantIndex`/`active`/`versionGroup` 语义，逐行合并无法定义「同一父亲下只能一条 active」的收敛规则；还要行级墓碑，长期元数据膨胀 |
| 服务端组件（自建 API/中转） | 那就变成「托管」，与 G7 的定位冲突 |
| WebDAV 之外的协议（S3、SFTP、Syncthing） | MVP 只做一种；分片格式与传输层解耦，未来可加适配器 |
| 同步 API Key / 端点密钥 | `SecretBox` 密文绑定本机 Keystore（`data/SecretBox.kt:30-58`），拷到另一台设备解不开；同步明文等于把密钥上传到网盘，风险不可接受 |
| 后台常驻自动同步（精确到小时级） | 不引入 WorkManager（仓库无该依赖，见 §50.9.5）；Android 14+ 的 `dataSync` 前台服务有时长限制，为一个非实时需求付这个复杂度不值得 |
| 其它平台客户端（iOS/桌面/Web） | G5 的分片格式已经为它们留了口子（可直接解析 `BackupFile`），但本文只交付 Android |
| 服务端版本历史 / 回收站 | 由用户网盘自己的版本功能兜底；我们不维护 N 份历史，避免存储与清理责任 |

### 50.3.3 术语

| 术语 | 含义 |
|---|---|
| **分片（shard）** | 一个会话对应的一份 `BackupFile` JSON，文件名由会话 id 哈希得到 |
| **基线（baseline）** | 上次成功同步后记录的「该会话内容哈希 + 远端 ETag」，存在本机 `sync-state.json` |
| **三向比较** | `本地当前哈希` / `基线哈希` / `远端当前哈希` 三者比较，判定「谁变了」 |
| **墓碑（tombstone）** | 一条「某会话在某设备被永久删除」的远端标记文件 |
| **payload 哈希** | 对规范化分片字节算 SHA-256；规范化的关键是 `exportedAt` 固定为 0 |
| **乐观锁** | PUT 时带 `If-Match: <etag>` 或 `If-None-Match: *`，防止覆盖别人刚写的内容 |
| **设备文件（device file）** | 每台设备只写自己那一份的 `devices/<deviceId>.json`，用于展示设备名与最后同步时间 |
| **同步副本** | 冲突时把本机旧版本改名另存出的会话，标题带「同步副本」后缀 |
| **marker** | 远端 `SimpleChat/.simplechat-sync`，用于证明目录确实是本应用创建的，防止误删用户其它文件 |

## 50.4 同步模型选型

### 50.4.1 候选方案

| 方案 | 做法 | 实现量 | 与现有代码的关系 |
|---|---|---|---|
| **A. 整份备份文件同步** | 每台设备上传 `backup-<deviceId>.json`，拉取别人的整份文件后导入 | 最小 | 完全复用 `exportBackup` / `importBackup` |
| **B. 会话分片同步（推荐）** | 一个会话一个 JSON；PROPFIND 列表 + 内容哈希判变化 + ETag 乐观锁 | 中 | 复用 `BackupStream` / `OwnBackupStream` / `ImportMode`，新增编排层与一种导入模式 |
| C. 增量变更日志 | 本地记录每次写操作的 op，按序上传并回放 | 大 | 需要在所有写路径前挂钩，和导入/恢复/回收站/分支全冲突 |
| D. 逐行合并 | 按消息行比对/合并，冲突用 LWW 或 CRDT | 很大 | 需要行级版本与墓碑，现有树语义无法直接收敛 |
| E. 只读旁路备份 | 只自动把整份备份上传，恢复靠手动导入 | 小 | 只解决「有备份」，不解决「多端」 |

### 50.4.2 推荐：方案 B（会话分片快照同步）

**为什么是 B：**

- **粒度对齐**：会话是导入导出、事务与冲突判定的单位；按会话同步能直接说清「这条是谁的版本」。
- **复用最大化**：分片 = 单会话 `BackupFile`；`BackupStream` 流式写、`OwnBackupStream` 流式读、
  `writeStreamedSession` 一事务一条，几乎没有新的解析/写库代码。
- **只传变化**：内容哈希让没变的会话一个字节都不上传，对附件体积尤其重要。
- **冲突可定位**：冲突落在单会话上，可复用 `RENAME` 另存，而不是整库覆盖。
- **删除可表达**：墓碑是独立小文件，重试与清理都简单。
- **未来端友好**：分片是公开格式（[04-数据模型](04-数据模型.md) §14.4），其它端不必复刻客户端逻辑。

**否决 A：** 每次上传整库（附件 base64 再放大 1/3，[07-功能设计](07-功能设计.md) §23.2），
慢且易撞配额；两端都写时只能整库二选一。A 的唯一优点是简单，但它把复杂度挪给了用户。
**否决 C：** 变更日志要「先记日志再写库」的双写一致性，导入/恢复/回收站/分支/压缩摘要
全要产出正确 op，漏一条就静默不同步；它省的只是带宽，而附件才是带宽大头。
**否决 D：** 消息树身份是 `parentId` + `variantIndex` + `active`（[04-数据模型](04-数据模型.md)
§4.1/§18），同一父亲只能一条 active；两端各激活一个新兄弟时逐行合并没有自然解。
删除还会重挂子节点（`messages.versionGroup`、`moveAfterDeletedMessage`），等于重写树合并。
**否决 E：** 只解决「有远端备份」，用户仍要手动导入且每次全量，和本地导出无本质区别。

### 50.4.3 MVP 采用哪种

**MVP 采用 B 的最小闭环：会话分片 + 手动双向同步 + 内容哈希判变化 + ETag 乐观锁 +
墓碑 + 冲突副本。** 不做收藏/设置/文件同步，不做自动后台，不做增量日志。

阶段边界刻意这样切：先把「会话语义与冲突安全」做对（这部分最难、最容易返工），
收藏与设置属于「个人偏好/资产」，可以后加且失败影响小。

## 50.5 同步范围矩阵

「同步」指跨设备传播；「不同步」指该数据只存在本机；「需确认」指默认不传、由用户显式打开。

| 数据 | MVP | P1 | P2 | 理由 / 备注 |
|---|---|---|---|---|
| 会话（标题/置顶/模式/补充提示词/记忆开关） | 同步 | | | `conversations` 行，随分片走 |
| 消息树（全部版本、`parentId`、`variantIndex`、`active`） | 同步 | | | 分片导出的是 `MessageTree.navigable()` 全量（`data/ChatRepository.kt:1443`） |
| 消息正文与思考内容 `reasoning` | 同步 | | | 正文是核心资产；思考内容在备份里本来就有（`data/Backup.kt:130`） |
| 消息附件（图片 base64 / 文本附件） | 同步 | | | 备份格式内联，分片必然带；见 §50.9.3 上限 |
| 附件落盘引用 `attachment_refs` | 不直接同步 | | | 派生数据；导入时由图片落盘重新生成（`data/ChatRepository.kt:2258-2262`） |
| 消息时间线 `timeline`（搜索/引用块） | 同步 | | | 备份字段已在（`data/Backup.kt:145`）；丢了引用角标就永久没了 |
| 消息状态 `status/errorMessage` | 同步 | | | 失败回复也应一致；流式中不导出（见下） |
| 压缩摘要 `compactions` | 同步 | | | 随会话，`writeCompactions` 按边界消息是否落地过滤（`data/ChatRepository.kt:2348-2351`） |
| 会话挂载预设（id + 顺序 + enabled） | 同步 | | | `enabledPresetIds` 在分片里（`data/Backup.kt:96-97`） |
| 全局预设库（未被任何会话挂载的） | 不同步 | 同步 | | MVP 只随会话带「被挂载的那几个」；P1 单独 `presets.json`，删除传播语义另定 |
| 收藏夹结构（`favorite_folders`） | 不同步 | 同步 | | 跨端合并/删除语义复杂，单独文件 + 行级墓碑，见 §50.5.1 |
| 收藏正文快照 + 图片 | 不同步 | 同步 | | 图片内联体积大；P1 用 `favorites.json`，允许 `附件图片不同步` 开关 |
| 设置：外观（主题/主题色/字号/字体选择/分享图样式） | 不同步 | 需确认 | | 个人偏好，跨端一致有用但冲突频繁；P1 白名单同步，默认开 |
| 设置：对话行为（思考折叠、搜索范围、记忆上限、收藏分组） | 不同步 | 同步 | | 与内容无关的偏好，冲突按最后写入 |
| 设置：语言、地区、朗读音色、引导完成标记 | 不同步 | 不同步 | | 设备/系统相关（音色依赖本机 TTS 引擎），跨端照搬会坏 |
| API 配置：Base URL / 模型 / 协议通道 | 不同步 | 需确认 | | 可能绑定不同端点与 Key；同步而不带 Key 容易造成「有配置但发不出去」的困惑 |
| **API Key / 各类服务密钥 / `web_search_write_id`** | **不同步** | **不同步** | 需确认（口令加密） | 密文绑定本机 Keystore，跨机解不开；明文上传网盘不可接受。见 §50.10 |
| 模型目录缓存 / 模型能力覆盖 | 不同步 | 不同步 | 同步 | 端点相关，随 API 配置一起才有意义 |
| 壁纸文件 + 会话壁纸 | 不同步 | 不同步 | 同步（blob） | 几 MB 级文件；P2 按内容哈希单独传，不塞进会话分片 |
| 自定义字体文件 | 不同步 | 不同步 | 同步（blob） | 单文件上限 24 MB（`data/CustomFont.kt:49-50`），适合内容寻址 |
| 主题文件（`.sctheme`） | 不同步 | 不同步 | 待实现 | 主题导入本身未实现（[33-主题系统](33-主题系统与主题文件规范.md) §33.6） |
| 草稿（`drafts`） | 不同步 | 不同步 | 需确认 | 未发送的临时内容；两机同写一条草稿会互相覆盖，且 `drafts` 刻意不进备份（`db/Entities.kt:66-67`） |
| 回收站（软删除会话/消息） | 不同步 | 不同步 | 需确认 | 本地宽限期；同步它会让「另一端回收站里突然多出东西」 |
| 同步状态（基线/墓碑本地副本） | 不同步 | 不同步 | 不同步 | 设备私有；随「删除云端数据」清理 |
| 用量统计 / 余额缓存 | 不同步 | 不同步 | 不同步 | 服务端账单数据，不属于聊天数据 |
| 诊断日志 / 导入报告 | 不同步 | 不同步 | 不同步 | 含设备信息与路径，[07-功能设计](07-功能设计.md) §29 已约定不外传 |
| 已删除消息（`isDeletedNode`） | 同步 | | | 备份里保留删除节点（`data/Backup.kt:151`），否则另一端树会断链 |

### 50.5.1 收藏与设置为什么放到 P1

- **收藏**：收藏行有「一条消息只有一份副本」的唯一约束与多夹关系（[37-收藏夹功能设计](37-收藏夹功能设计.md)
  §48.5.6），两端给同一条消息各收藏一次会产生两个不同 `favoriteId` 指向同一 `messageId`；
  现有 `importFavorites` 只按 id `insertIgnore`（`data/ChatRepository.kt:1924-1933`），
  不会合并归属与备注。跨端正确合并需要「按 messageId 去重 + 归属并集 + 删除墓碑」，
  是一套独立的小设计，不该阻塞会话同步。
- **设置**：`SettingsStore` 是一个整包 DataStore（`data/SettingsStore.kt:28-31`、`:992-1087`），
  整包同步会把密钥、设备相关项一起带走。P1 用**白名单投影**成 `settings.json`，
  并且只在用户显式打开「同步偏好设置」后启用。

## 50.6 WebDAV 协议层设计

### 50.6.1 目录与文件布局

用户填写的「服务器地址」记为 `base`（如 `https://dav.jianguoyun.com/dav/`），应用在其下
创建固定子目录：

```text
<base>/SimpleChat/                        ← MKCOL，应用专属根（可配置子目录名，默认 SimpleChat）
├── .simplechat-sync                      ← marker：格式/版本/创建者，删除云端数据的前置校验
├── manifest.json                         ← 可选索引快照（展示用，非判据）
├── devices/
│   ├── <deviceId>.json                   ← 每台设备只写自己这份
│   └── ...
├── conversations/
│   ├── <sha256(conversationId)>.json     ← 会话分片（BackupFile，单会话）
│   └── ...
├── tombstones/
│   ├── <sha256(conversationId)>.json     ← 永久删除标记
│   └── ...
├── favorites.json                        ← P1
├── settings.json                         ← P1（白名单，绝不含密钥）
└── blobs/<sha256>                        ← P1/P2：壁纸、字体、可选的外置附件
```

| 文件 | 内容 | 写入者 | 冲突策略 |
|---|---|---|---|
| `conversations/<hash>.json` | 单会话 `BackupFile`，`version = 2` | 所有设备 | ETag 乐观锁 + `If-Match`；冲突按 §50.7 处理 |
| `tombstones/<hash>.json` | 删除标记（含删除时内容哈希） | 所有设备 | `If-None-Match: *` 只创建一次；同 id 已存在则不覆盖 |
| `devices/<deviceId>.json` | 设备名、版本、最后同步时间 | 该设备自己 | 天然无冲突 |
| `manifest.json` | schema、计数、最后写入者、更新时间 | 每次同步结束的设备 | 可被并发覆盖；**任何逻辑不得依赖它做正确性判断** |
| `.simplechat-sync` | `{"format":"simplechat-sync","version":1}` | 首次创建时 | 已存在则不覆盖；内容不匹配则拒绝同步 |

**为什么用 `sha256(conversationId)` 而不是直接拿 id 当文件名：**

- 会话 id 的来源不止 UUID：导入的外部备份可能带任意字符串（`data/import/ExternalBackup.kt:438-439`），
  而「同名新命名」产生的 id 形如 `<id>~<uuid>`（`data/ChatRepository.kt:2308`）；
- 中文、`%`、`?`、`#`、空格在部分 WebDAV 服务器上会被二次解码/拒绝（见 §50.6.9）；
- 哈希名是固定 64 位小写十六进制，长度稳定、全 ASCII、无大小写歧义。
- **代价**：看到文件名无法还原会话 id，需要读文件内容。这恰好与同步动作一致 ——
  未知文件本来就要下载才能应用，列表阶段不需要 id（远端 ETag 与本地基线靠 state 里的
  `remotePath → id` 映射，首次见到的新文件再 GET）。

### 50.6.2 分片文件格式

```jsonc
// conversations/<sha256(id)>.json —— 与 data/Backup.kt:33-52 完全同构
{
  "format": "simplechat-backup",
  "version": 2,
  "exportedAt": 0,                      // ← 同步分片固定为 0，见下
  "conversations": [ { /* 一条 BackupConversation，含 messages / compactions */ } ],
  "presets": [ { /* 该会话挂载的预设；未被挂载的不带 */ } ],
  "favoriteFolders": [],
  "favorites": []
}
```

两条硬约定：

1. **`exportedAt = 0`。** 现有 `exportBackup(file, onlyConversation)` 写的是
   `System.currentTimeMillis()`（`data/ChatRepository.kt:1319`），每次导出字节都不同，
   内容哈希就失去意义。同步专用导出入口必须把 `exportedAt` 固定为 0，让**内容相同 ⇒ 字节相同**。
   该字段在导入时不被读取，置 0 无害。
2. **只导出 `deletedAt == null` 的会话，且不带收藏/全局预设。** 分片是「会话内容」的单位，
   收藏与全局预设分别是 P1 的独立文件。分片里的 `presets` 只含该会话挂载的预设
   （现有 `onlyConversation` 分支已这样过滤，`data/ChatRepository.kt:1313-1314`）。
3. **流式中的消息不进分片。** 导出时跳过 `status == STREAMING` 的消息（或按 `STOPPED` 归一），
   否则另一端会收到一条永远在生成的消息；等终态后的下一次同步再带完整内容
   （打开会话时本就会清扫卡死的流式状态，`data/ChatRepository.kt:938`）。

> 为什么不是 ZIP：ZIP 是「一个文件好传」的产物（`data/archive/ChatArchive.kt:14-36`），
> 而同步要的是「一个会话一个文件」的可寻址单位。ZIP 内嵌 Markdown 对同步毫无意义，
> 拆包反而增加一层解压与临时文件管理。

### 50.6.3 设备文件、墓碑与 marker

```jsonc
// devices/<deviceId>.json
{
  "format": "simplechat-sync-device",
  "version": 1,
  "deviceId": "5b0f…",                  // 本机 UUID，首次开启同步时生成并持久化
  "deviceName": "Pixel 9",              // Build.MANUFACTURER + MODEL，可被用户改名
  "appVersion": "1.3.0",
  "appVersionCode": 70,
  "syncSchema": 1,                      // 同步语义版本（不是 BackupFile.version）
  "lastSyncAt": 1796000000000,          // 仅展示；不参与冲突判定
  "lastShardCount": 128
}
```

```jsonc
// tombstones/<sha256(conversationId)>.json
{
  "format": "simplechat-sync-tombstone",
  "version": 1,
  "conversationId": "…",
  "deletedAt": 1796000000000,
  "deviceId": "5b0f…",
  "contentHash": "9f2c…"                // 删除时基线里该会话的 payload 哈希；未知时为 null
}
```

```json
// .simplechat-sync
{"format":"simplechat-sync","version":1,"createdBy":"simplechat-android"}
```

| 安全动作 | 规则 |
|---|---|
| 连接测试 | 在 `SimpleChat/` 下写一个临时文件再删掉；失败则给出可行动错误 |
| 删除云端数据 | 只删 `SimpleChat/` 下**已知文件名/已知子目录**；删除前必须读到 `.simplechat-sync` 且 format/version 匹配，否则拒绝，避免用户把服务器根目录填成别的目录时误删 |
| 目录不存在 | 逐级 `MKCOL`（`SimpleChat`、`conversations`、`tombstones`、`devices`）；已存在返回 405 视为成功 |
| 目录被外部删除 | 下次同步重新 MKCOL；本地 state 视为「远端为空」，按「远端缺失 = 异常」规则补传（§50.7.7），不删本地 |

### 50.6.4 必需方法矩阵

| 方法 | 用途 | MVP 必需 | 要点 |
|---|---|---|---|
| `PROPFIND` | 列目录与取 ETag/大小/最后修改 | **是** | `Depth: 1`；请求体只取 `getetag`/`getlastmodified`/`getcontentlength`/`resourcetype`；同时用于「连接测试」 |
| `MKCOL` | 建目录 | **是** | 逐级创建；201/405 都算成功 |
| `GET` | 下载分片/墓碑/设备文件 | **是** | 支持 `If-None-Match` 可减少流量（非必需）；下载到临时文件 |
| `PUT` | 上传分片/墓碑/设备文件/manifest | **是** | 带 `Content-Length`（先落本地文件）；带 `If-Match`/`If-None-Match` |
| `DELETE` | 清墓碑、删云端数据、清理失效文件 | **是** | 204/200 成功；404 视为已删 |
| `HEAD` | 取 ETag/大小 | 否 | 部分服务器对文件 `HEAD` 返回 405；一律改用 `PROPFIND Depth: 0` 或 GET |
| `MOVE` | 服务端改名/归档 | 否 | MVP 不做服务端归档；冲突副本在**本地**生成后作为新会话上传 |
| `OPTIONS` | 探测 DAV 能力 | 否 | 仅用于诊断信息；不因不支持而阻断同步 |
| `LOCK`/`UNLOCK` | 排他锁 | 否 | 不用 WebDAV 锁：锁会因客户端崩溃残留，且部分网盘不支持；用 ETag 乐观锁更稳 |

### 50.6.5 认证

| 项 | 结论 | 为什么 |
|---|---|---|
| 方案 | HTTP Basic，仅 HTTPS | 覆盖坚果云/Nextcloud/群晖/AList 的通用做法；Digest 在 OkHttp 里没有内置实现，需手写 challenge 流程 |
| 账号 | 用户名 + **应用专用密码** | 开启两步验证（2FA）后，Nextcloud/群晖/坚果云通常要求应用密码；向导里明确提示「不要填网页登录密码，去后台生成应用密码」 |
| 编码 | `Credentials.basic(user, password)`（OkHttp 用 UTF-8 拼串再 Base64） | 建议使用 ASCII 密码；部分服务器按 ISO-8859-1 解码，中文密码可能失败，错误文案提示改用应用密码 |
| `http://` | **拒绝**，直接提示只支持 HTTPS | Android `targetSdk 37` 默认禁明文；聊天正文不应走明文 |
| 自签证书 | 不支持 | 需在系统安装 CA 或用受信证书；不引入「忽略证书」开关，避免用户被中间人 |
| 证书固定 | 不做 | 用户自备服务器，固定证书无法覆盖众多种类；TLS 依赖系统信任链即可 |
| URL 内嵌账号 | 禁止 | `https://user:pass@host/` 会把密码写进日志/崩溃栈；输入框只收纯 URL |
| 401/403 | 不重试，提示检查账号/应用密码/目录权限 | 重试只会触发服务器风控（尤其坚果云） |
| 2FA 提示 | 首次配置向导第 2 步固定一行说明 | 减少「密码明明对却 401」的客服成本 |

### 50.6.6 连接、超时与重试

专用 `OkHttpClient`，**不复用** `ChatApi.defaultClient()`（那条链路 `readTimeout = 0`，
为 SSE 设计，见 `net/ChatApi.kt:238-254`）：

| 参数 | 值 | 理由 |
|---|---|---|
| `connectTimeout` | 30 s | 与现有网络约定一致 |
| `readTimeout` | 60 s | 下载单文件；过大文件会分片（§50.9.3） |
| `writeTimeout` | 180 s | 上传几十 MB 分片需要更久 |
| `callTimeout` | 不设 | 大文件上传不应被总时限打断；由协程取消控制 |
| `retryOnConnectionFailure` | false | 自己控制重试次数与退避，避免「一次请求偷偷重试三轮」 |
| `followRedirects` | true | 部分服务用 301/302 到 `/dav`；OkHttp 跨域会去掉 Authorization（正确行为） |

重试规则：

| 场景 | 处理 |
|---|---|
| 连接失败 / 读超时 / 5xx | 重试 3 次，退避 1 s → 4 s → 9 s（含 ±20% 抖动）；仅幂等操作（GET/PROPFIND/PUT 同内容/DELETE）重试 |
| 429 | 读 `Retry-After`（秒或 HTTP 日期），等待后重试 1 次；没有该头则按退避 |
| 507 Insufficient Storage | **不重试**，立即停止并提示「网盘空间不足」；已传内容保留（幂等） |
| 507 之外的 4xx（400/401/403/404/409/412/423） | 不盲目重试；按语义处理：401/403 停止并提示认证；404 目录缺失 → MKCOL 后重试一次；409 父目录缺失 → 补建；412 冲突 → 走冲突流程；423 锁定 → 退避重试 1 次 |
| TLS/证书错误 | 不重试，提示检查证书/地址 |
| 响应不是 XML（返回登录页/错误页） | 视为「认证或地址错误」，附 HTTP 状态与截断后的响应首行（**不含凭据**） |
| 取消 | 协程取消时立即停；临时文件清掉；已成功 PUT 的文件与 state 保持一致（state 只记成功项） |

### 50.6.7 上传、下载与「断点」

**不做字节级断点续传。** RFC 9110 的 Range 与 WebDAV PUT 组合在主流网盘上支持极不一致
（很多服务只支持 GET Range），为它写一套分块协议（Content-Range + 服务端合并）会引入
服务端依赖，违背「任意 WebDAV 都能用」。

采用的替代方案：

| 环节 | 做法 |
|---|---|
| 导出 | 先流式导出到本地 `cacheDir/sync/<hash>.json`（`data/BackupStream.kt:14-58`，内存有界） |
| 上传 | `File.asRequestBody("application/json; charset=utf-8")` 直接 PUT，`Content-Length` 已知；上传中崩溃后重试只需重传该文件，**不需要重新导出** |
| 下载 | 先写 `cacheDir/sync/<hash>.json.part`，按 `onProgress` 更新进度；完成后校验长度，再用**原子写**改名为正式文件 |
| 「断点」 | 文件级：一次同步中断后，已成功的分片由 `sync-state.json` 记录，重跑只处理未完成项。**不承诺**同一个大文件的上传续传 |
| 幂等 | PUT 同一内容重复执行结果相同；导入 `SYNC_APPLY` 重复应用同一分片结果相同 |

> **容器注意**：下载/状态文件从 `.part` 改名成目标时**必须检查 `renameTo` 返回值**并回退
> 流式复制。仓库已在 `data/ExportFiles.kt:61-89`（`writeFileAtomically`）与
> `net/UpdateDownloader.kt:110-134` 修过这个坑（鸿蒙/卓易通容器私有目录可能跨挂载点），
> 同步侧一律复用，不准再写裸 `renameTo`。见 §50.13。

### 50.6.8 列表与 ETag 细节

```http
PROPFIND /dav/SimpleChat/conversations/ HTTP/1.1
Depth: 1
Content-Type: application/xml; charset=utf-8

<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:getetag/>
    <d:getlastmodified/>
    <d:getcontentlength/>
    <d:resourcetype/>
  </d:prop>
</d:propfind>
```

| 细节 | 规则 |
|---|---|
| 响应 | `207 Multi-Status`，解析每个 `<response>` 的 `href`、`propstat/prop`；只取 `propstat` 状态 200 的属性 |
| XML 解析 | 用 JDK 自带 `DocumentBuilderFactory`（namespace-aware 关闭），兼容 `d:`/`D:`/无前缀与不同默认命名空间；**不引 XML 库**（与「依赖精简」一致） |
| `href` | 可能是绝对 URL 或相对路径，且可能百分号编码；按路径最后一段匹配，先 URL 解码再比对 |
| 目录项 | `resourcetype` 含 `collection` → 跳过 |
| ETag | 原样保存（可能带引号或 `W/`）；比较时先 `trim()` 再统一去引号；`If-Match` 用**原样值**（服务器认自己的格式）。弱 ETag 不用于 `If-Match`，此时降级为「先 GET 取内容哈希再决定」 |
| 无 ETag 服务器 | 用 `getlastmodified` + `getcontentlength` 作弱判据；内容是否变化最终以下载后的 payload 哈希为准（会多下载，可接受） |
| 大小写 | 服务器路径大小写敏感，固定目录名全小写/固定大小写，不做猜测 |
| 中文路径 | 本应用创建的目录与文件名全 ASCII；用户 `base` 里的中文路径由 OkHttp 正常百分号编码 |
| 412 / 409 | 412 = 乐观锁失败（冲突）；409 = 父目录不存在（补建后重试一次） |

### 50.6.9 常见服务器兼容性与已知坑

| 服务 | 已知情况 | 应对 |
|---|---|---|
| **坚果云** | WebDAV 地址 `https://dav.jianguoyun.com/dav/`；需在网页端生成**应用密码**；有请求频率/上传流量限制；部分接口对 `PROPFIND Depth: infinity` 不友好 | 只用 Depth 1；串行请求、请求间留 50–100 ms；429 按 `Retry-After` 退避；文案里直接写「坚果云请使用应用密码」 |
| **Nextcloud** | 功能完整；开启 2FA 必须用应用密码；PHP/反向代理可能限制请求体（`client_max_body_size`、`post_max_size`）；大文件可能触发 413 | 单分片 64 MiB 上限；413 提示「网盘限制单文件大小，请调大上传限制或在会话里减少图片」 |
| **群晖 Synology** | 需在套件中心启用 WebDAV Server；默认端口 5005/5006；DSM 2FA 下 WebDAV 可能仍要求应用密码；部分版本 ETag 行为不一致 | 连接测试给出端口提示；无 ETag 时降级用哈希 |
| **AList** | 聚合网盘，WebDAV 由 AList 提供；底层驱动可能不支持 `MKCOL` 嵌套/`PROPFIND` 全属性，或 PUT 需要 `Content-Length` | 逐级 MKCOL；PUT 必带 Content-Length；不支持时提示「AList 驱动不支持 WebDAV 写入，请换用原生网盘」 |
| **通用 Nginx + `dav_module`** | 需 `dav_methods PUT DELETE MKCOL PROPFIND`；默认可能不返回 ETag | 文档写清服务端配置；无 ETag 走哈希判据 |
| **对象存储网关（S3→WebDAV）** | 常见 `HEAD`/`PROPFIND` 缺失、ETag 非 MD5、不支持 `If-Match` | 只作为「尽力兼容」；检测到不支持时降级为「单端写入」并在 UI 标注 |

**共性坑清单：**

1. **大小限制**：413 与 507 要分开提示（请求太大 vs 空间不足）。
2. **429 限流**：必须退避，不能「失败就重试三次」连环打；请求串行化。
3. **HEAD 不支持**：不要用 HEAD 探测存在性，改用 PROPFIND Depth 0。
4. **中文/特殊字符路径**：本应用自己生成的文件名全哈希化；用户目录名交给 OkHttp 编码。
5. **`If-Match` 不支持**：部分服务器忽略该头，导致「乐观锁失效」。检测方式：PUT 后返回的 ETag
   与预期不符，或服务器对 `If-Match` 不返回 412。此时降级为「同步前 PROPFIND 对比 ETag，
   变了就走冲突流程」，并接受极小概率的竞态（单用户双设备场景可接受）。
6. **`PROPFIND` 返回 301 到登录页**：视为认证失败，不要当成「目录为空」。
7. **大小写/末尾斜杠/服务器时钟**：URL 统一以 `/` 结尾再拼子路径，避免 301 与路径截断；`getlastmodified` 只用于展示，不当真相。

## 50.7 同步算法与冲突解决

### 50.7.1 不变量

| # | 不变量 | 保障方式 |
|---|---|---|
| I1 | 一个远端分片只对应一条会话；文件名由会话 id 唯一决定 | `sha256(id)`，且分片内 id 与状态映射一致 |
| I2 | 同一会话在任一设备上最多一个「规范版本」 | 三向比较 + 冲突策略「远端为规范、本机转副本」 |
| I3 | 基线只记录**成功应用或成功上传**的状态 | state 仅在 PUT 2xx / 导入事务提交后更新 |
| I4 | 墓碑与分片不会同时「活着」 | 删除时先写墓碑、再删分片；读取时墓碑优先 |
| I5 | 任何时刻中断，重跑结果一致 | 幂等 PUT / 幂等 `SYNC_APPLY` / state 可重放 |

### 50.7.2 本地同步状态

避免新增 Room 表与迁移（`db/AppDatabase.kt:34` 当前 v18；每次迁移都要配显式
`Migration`，见 `:63-72`），同步状态存放在私有目录：

```text
filesDir/sync/state.json       ← 基线、墓碑缓存、冲突、最近结果
cacheDir/sync/                 ← 分片导出与下载的临时文件（系统可清）
```

```jsonc
// filesDir/sync/state.json
{
  "format": "simplechat-sync-state",
  "version": 1,
  "remoteKey": "sha256(baseUrl\nusername)",   // 换服务器/账号时自动失效重来
  "deviceId": "5b0f…",
  "lastSyncAt": 1796000000000,
  "lastResult": { "pushed": 3, "pulled": 2, "deleted": 1, "conflicts": 1, "failed": 0 },
  "conversations": {
    "<conversationId>": {
      "path": "conversations/9f2c….json",
      "baseHash": "…",            // 最后一次「与远端一致」时的 payload 哈希
      "etag": "\"abc123\"",       // 远端 ETag 原样
      "remoteModifiedAt": 1795…,  // 仅展示
      "pushedAt": 1795…           // 仅展示
    }
  },
  "tombstones": {
    "<conversationId>": { "contentHash": "…", "deletedAt": 1795…, "deviceId": "5b0f…" }
  },
  "conflicts": [
    { "conversationId": "…", "copyId": "…", "at": 1795…, "reason": "BOTH_CHANGED" }
  ]
}
```

写入时机：**每次同步结束、以及每处理 20 个分片**写一次（原子写，`writeFileAtomically`）。
崩溃最多重做 20 个分片，因幂等而无害。

**为什么不用 Room：** state 是派生数据，丢了最多多传一遍；不值得为它加一次 schema 迁移、
`18.json → 19.json` 与迁移回归（[37-收藏夹功能设计](37-收藏夹功能设计.md) §48.14.3 那套成本）。

### 50.7.3 本地变更检测

每次同步开始时：

1. 取所有 `deletedAt == null` 的会话 id（`db/ChatDao.kt:141-143` 的查询含回收站，需新增过滤版）。
2. 对每个会话用同步专用导出入口写分片到 `cacheDir/sync/`（`exportedAt = 0`）。
3. `localHash = SHA-256(分片字节)`。
4. 与 `state.conversations[id].baseHash` 比较：不等 = 本地变了。

**成本与优化：**

| 阶段 | 成本 | 处理 |
|---|---|---|
| MVP | 每次同步全量导出并按会话哈希，成本 ≈「导出所有数据」拆成单会话（含附件解码），几百 MB 库可能分钟级 | 明确告知用户同步耗时与进度；只允许前台同步 |
| P1 | 给会话表加「脏标记」：在 Repository 的几个写入口（`appendUserMessage`、`editMessageInPlace`、`addActiveVariant`、`activateVariantAt`、`deleteMessageVersions`、`changeMessageTrash`、`renameConversation`、`setPinned` 等）更新 `conversations.updatedAt` 之外的同步脏表/脏文件 | 只导出脏会话；全量校验保留为「手动校验」按钮 |
| 兜底 | 用户手动「强制全量校验」 | 处理「改了但没标脏」的极端情况 |

**为什么不在 MVP 就直接做脏标记：** 写入口有十几处（`data/ChatRepository.kt` 的
`appendUserMessage` / `editMessageInPlace` / `addActiveVariant` / `activateVariantAt` /
`deleteMessageVersions` / `changeMessageTrash` 等），任何一处漏标就会静默不同步，
而这类 bug 极难发现。先全量导出保证正确，再优化性能，符合本项目「先正确后快」的顺序。

### 50.7.4 同步主流程

```text
sync():
  state = loadState()                       // 失败则视为首次（全量重建基线）
  session = connect(base, user, pass)       // TLS + 连接测试 + 目录确保 + marker 校验
  remote = session.propfind(conversations/) // path → {etag, size, modifiedAt}
  local  = exportAllShards()                // id → {file, hash}
  tomb   = session.propfind(tombstones/)

  plan = plan(state, local, remote, tomb)   // §50.7.5 决策表
  report = SyncReport()

  // 1) 先处理删除，避免刚拉下来的会话又被删
  for (t in plan.applyTombstones) applyTombstone(t, local, state, report)
  // 2) 再拉取/冲突
  for (p in plan.pulls) pull(p, state, report)
  // 3) 最后上传本机变化
  for (p in plan.pushes) push(p, state, report)
  // 4) 清理：墓碑写回、设备文件、manifest、本地 staging
  flushTombstones(state)
  writeDeviceFile()
  writeManifestBestEffort()
  saveState(state)
  return report
```

**顺序为什么是「删 → 拉 → 推」：** 删除先落地可避免「拉下来又推回去」；先拉后推能让
推到一半失败时，本机已拥有远端最新状态，下一轮更容易收敛。

### 50.7.5 三向比较决策表

记：`base` = 基线哈希（无基线为 `null`）；`local` = 本机当前哈希（会话不存在为 `null`）；
`remote` = 远端当前哈希/ETag（不存在为 `null`）；`tomb` = 远端墓碑（不存在为 `null`）。

| # | local | base | remote | tomb | 判定 | 动作 |
|---|---|---|---|---|---|---|
| 1 | 有 | = local | 有且 = base | 无 | 都没变 | 跳过 |
| 2 | 有 | = local | 有且 ≠ base | 无 | **仅远端变** | 拉取 → `SYNC_APPLY` 覆盖本机 |
| 3 | 有 | ≠ base | 有且 = base | 无 | **仅本机变** | `PUT If-Match: base.etag` |
| 4 | 有 | ≠ local | 有且 ≠ base，且 local = remote | 无 | 两端改成了一样 | 只更新 state（otag/hash） |
| 5 | 有 | ≠ base | 有且 ≠ base，且 local ≠ remote | 无 | **双边冲突** | 冲突流程（§50.7.6） |
| 6 | 有 | null | 有 | 无 | 首次基线（两端都有） | 哈希相等 → 记基线；不等 → 首次冲突（默认保留两份，可由向导选策略） |
| 7 | 有 | null | null | 无 | 本机新增 | `PUT If-None-Match: *` |
| 8 | 有 | 任意 | null | 有 | 远端已永久删除 | 本地哈希 = 墓碑 `contentHash` → 删除本地；否则 → **删除让位**：保留本地并记冲突 |
| 9 | 有 | 任意 | null | 无 | 远端分片消失（异常/服务器被清） | **不删本地**；记异常并 `PUT If-None-Match: *` 补传（远端缺失按「本机为准」恢复） |
| 10 | null | 有 | 有 | 无 | 本机已永久删除（待推墓碑） | 远端哈希 = 基线 → 写墓碑；远端已变 → **保留远端**、撤销删除、记冲突 |
| 11 | null | 有 | null | 有 | 已删除且已墓碑 | 清理 state |
| 12 | null | null | 有 | 无 | 远端新增 | 拉取导入（`MERGE`/`SYNC_APPLY` 均可，目标就是新增） |

> 表 8/10 的两条是刻意的不对称：**删除可以等，正文不能丢**。任何「删除 vs 修改」的
> 竞争都让修改胜出，并把决定权交回用户。

### 50.7.6 冲突处理（默认：远端为规范，本机旧版转副本）

当表 5/6 判定冲突时：

```text
resolveConflict(id, remoteFile):
  # ① 先把本机当前版本导出，并作为「同步副本」导入（换成新 id，原会话不动）
  localShard = exportShard(id)                       # exportedAt = 0
  copyId = import(localShard, mode = RENAME, suffix = "同步副本")   # 复用 uniqueCopyTitle / withFreshIds

  # ② 用远端内容覆盖规范会话
  import(remoteFile, mode = SYNC_APPLY, targetId = id)

  # ③ 记状态：规范会话跟远端，副本是「本机旧内容」，待下轮作为新会话上传
  state.conversations[id] = { baseHash: hash(remoteFile), etag: remoteEtag }
  state.conflicts += { id, copyId, at: now, reason: BOTH_CHANGED }
  report.conflicts += { title, copyTitle }

  # ④ 副本已经是新会话 id → 下轮 push 时按「新增会话」上传；两端最终各有一份
```

为什么是「远端为规范」：

- 规范版本应等于服务器上已有的那份；否则每次冲突都改写远端，另一端基线立刻失效，变成两台设备互相覆盖的乒乓。
- 本机内容不丢：被换下的那版立刻成为可见的「同步副本」，可继续用、可删除、下轮同步到其它设备。
- 否决「本机为规范」（失败时本机成孤岛）与「逐条弹窗二选一」（批量同步时用户无从下手）。

副本命名复用现有机制：`uniqueCopyTitle`（`data/Backup.kt:312-324`）与 `withFreshIds`
（`:332-345`）；标题后缀由同步侧传入（新增可选参数，默认仍是现有「副本」文案，
见 §50.8.2），例如「第一章（同步副本 · 手机 B 11-29）」。

**冲突可见性：**

- 同步结果页显示「冲突 N 个」；
- 设置 → 云同步页保留「冲突记录」列表，点开可看到规范会话与副本；
- MVP 的处理动作：`删除副本`（以远端为准）、`知道了`（保留两份，默认已保留）。
  「把副本内容设为规范」（以本机为准）放 P1 —— 它需要把副本内容按原 id 重放，
  属于「合并工具」，不是同步正确性的必需品。

### 50.7.7 删除与墓碑

| 本地动作 | 是否写墓碑 | 说明 |
|---|---|---|
| 删除会话（进回收站，`trashConversations`） | **否** | 回收站是本地宽限期（15 天，`db/Entities.kt:45-51`）。另一端不该因为这边「先扔进回收站」就删 |
| 回收站恢复（`restoreConversations`） | 否 | 不产生远端变化 |
| 彻底删除 / 清空回收站 / `purgeExpiredTrash` | **是** | 最终调用 `deleteConversations(ids)`（`data/ChatRepository.kt:468`）→ 在同一路径写墓碑 |
| 「删除所有历史对话」（`deleteAllConversations`） | **需用户确认** | 见下 |
| 导入「覆盖同名」删旧会话（`deleteConversationRecords`） | 否 | 这是本地替换，不是用户删除；新内容马上会写入同名/同 id 会话 |

墓碑生成规则：

```text
onPermanentDelete(ids):
  for id in ids:
    contentHash = state.conversations[id]?.baseHash   // 可能为 null
    tombstone = { id, deletedAt: now, deviceId, contentHash }
    本地 tombstone 列表 += tombstone                    // 不阻塞删除，后台尝试上传
```

「删除所有历史对话」的特殊处理（重要）：

- 这是本机数据的**重置**，不是「逐条删除」。如果按普通删除逐条写墓碑，会在云端抹掉
  所有会话，而用户对「清空本机」的预期通常不包括「清空另一台设备」。
- 默认行为：**弹窗让用户选择**
  - 「只清空本机，保留云端」（默认）：清空本地后**暂停自动同步**，`state` 标记为
    `resetPending`；用户下次手动同步时按「首次同步」向导重新选择合并策略；
  - 「同时清空云端」：确认后删除 `conversations/` 下全部分片并写墓碑（或直接清目录 +
    设备文件重置），明确写「不可恢复」。
- 无论哪种，都不得在用户不知情时把删除传播到另一端。

墓碑处理（拉取端）：

```text
applyTombstone(t):
  id = t.conversationId; localHash = hashes[id]; remoteHash = session.getShardHash(id)
  if local 有会话:
     if t.contentHash == null || localHash == t.contentHash:
        deleteLocalConversation(id)          # 永久删除，不写新墓碑
        state.tombstones[id] = t
     else:
        # 删除 vs 修改：保留本地，撤销删除
        report.conflicts += { id, reason: DELETE_VS_EDIT }
        state.tombstones[id] = t             # 记住已见过，避免反复提示
  else:
     # 本机没有：尝试删除远端残留分片（若有），保留墓碑防复活
     if remoteHash != null: session.deleteShard(id)
     state.tombstones[id] = t
```

墓碑清理：

| 策略 | MVP | P1 |
|---|---|---|
| 保留期 | 永久保留（文件很小），不做 GC | 按设备水位清理：当所有 `devices/*.json` 的 `lastSyncAt` 都晚于墓碑 `deletedAt` 时删除墓碑 |
| 长期离线设备 | 超过保留期的设备重新同步时，旧会话会作为「新增」传回（不会丢数据，只是「复活」） | 用设备水位避免；复活作为可接受副作用写进帮助文案 |

### 50.7.8 重复同步、时钟漂移与幂等

| 情形 | 处理 |
|---|---|
| 同一次同步重复执行 | 全部操作幂等（同内容 PUT、`SYNC_APPLY` 同分片、墓碑 `If-None-Match`） |
| 两端时钟差几分钟 | 不参与任何判定；`deletedAt`/`lastSyncAt` 只用于展示与墓碑清理水位 |
| 设备时间被手动改到未来 | 同上；另在 UI 用「mtime 不可信」的说明避免用户以为是同步时间戳 |
| 同步中途换账号/改地址 | `remoteKey` 变化 → state 失效，进入首次同步向导，不拿旧基线套新服务器 |
| 同步中用户继续聊天 | 分片在同步开始时导出，快照一致；同步结束后本地又有新改动 → 下一轮按「本机变」上传，不丢 |
| 同步中删除会话 | 该会话在快照里仍存在，可能被作为「本机新增」上传；实现上在导出后、导入/上传前**重查会话存在性**（`findConversation` 为空则跳过），避免「删了又被传回去」 |
| 多次点击「立即同步」 | 互斥锁（单例 `SyncCoordinator` 的 `Mutex`），第二次直接返回「正在同步」 |

### 50.7.9 首次同步（两端都有数据）

首次同步没有基线，无法判断谁新谁旧。向导给三个选项（默认第 1 个）：

| 选项 | 行为 |
|---|---|
| **保留两份（默认）** | 同 id 且哈希不同的会话：按冲突策略（远端规范 + 本机副本）；不同 id：各自保留并互传 |
| 以本机为准 | 同 id 不同哈希：本机覆盖远端（`PUT` 忽略 `If-Match` 前先确认）；风险：覆盖另一台设备的最近修改 |
| 以远端为准 | 同 id 不同哈希：远端覆盖本机；风险：丢掉本机最近修改 |

首次同步完成后记录基线，之后进入三向比较。**默认选项必须是「保留两份」**：
迁移场景下用户最怕的是「点了同步，手机 A 的新稿子没了」。

## 50.8 数据与代码改动设计

> 本节只给施工路径与接口约定，不写实现。所有新类名是建议名，施工时可调整，
> 但**职责边界**要保持：网络、编排、数据访问三层分开，便于 JVM 单测。

### 50.8.1 新增模块

| 层 | 建议文件 | 职责 |
|---|---|---|
| 网络 | `net/WebDavClient.kt` | OkHttp + Basic + PROPFIND 解析 + PUT/GET/DELETE/MKCOL + ETag 归一化 + 状态码映射；只做 HTTP，不懂会话 |
| 网络 | `net/WebDavXml.kt` | 纯函数：multistatus → `List<DavEntry>`；ETag 归一化；路径拼接；可 JVM 单测 |
| 编排 | `data/sync/SyncEngine.kt` | 三向比较、计划生成、冲突流程、墓碑、进度、报告；依赖两个接口（见下），可 JVM 单测 |
| 编排 | `data/sync/SyncGateway.kt` | `interface`：`list(path): List<DavEntry>`、`get(path, file): Etag`、`put(file, ifMatch): Etag`、`delete(path)`、`ensureDirs()`；生产实现走 `WebDavClient` |
| 编排 | `data/sync/ShardSource.kt` | `interface`：`exportShard(id, file)`、`listLocalIds()`、`applyRemote(file, mode)`、`deleteLocal(id)`、`hashOf(file)`；生产实现走 `ChatRepository` |
| 编排 | `data/sync/SyncState.kt` | state JSON 序列化、原子读写、remoteKey、冲突/墓碑结构；纯函数可测 |
| 编排 | `data/sync/SyncCoordinator.kt` | 单例：互斥、进度 StateFlow、自动同步节流、取消；由 `AppContainer` 持有 |
| 数据 | `data/ChatRepository.kt` | 新增 `exportSyncShard`、`importSyncShard`，并在构造处装配墓碑 sink（§50.8.4） |
| 数据 | `data/Backup.kt` | `ImportMode` 增加 `SYNC_APPLY`；`RENAME` 支持自定义标题后缀参数 |
| 设置 | `data/SettingsStore.kt` | 新增同步配置键与凭据（§50.8.5） |
| UI | `ui/settings/SettingsScreen.kt` | 数据管理页入口 + 云同步二级页；`SettingsPage.SYNC`（枚举在 `ui/settings/SettingsScreen.kt:168`） |
| UI | `ui/settings/SyncPage.kt`、`SyncDialogs.kt` | 页面、向导、冲突列表、删除确认、错误展示 |
| 资源 | `res/values/strings.xml`、`values-en/strings.xml` | §50.11.5 的成对文案 |

### 50.8.2 新增导入模式 `ImportMode.SYNC_APPLY`

现有三模式都不适合「按 id 更新同一条会话」：

| 模式 | 现状（`data/ChatRepository.kt:2280-2313`） | 用于同步的问题 |
|---|---|---|
| `MERGE` | 按 id 存在即跳过 | 远端更新永远进不来 |
| `REPLACE_SAME` | 先按 id、再按**标题**找冲突并删除旧会话 | 标题相同但不同会话会被误删（`TODO.md:252` 已记录「覆盖同名仅按标题判定」的风险）；删除会清草稿、断收藏链接（`data/ChatRepository.kt:2359-2365`） |
| `RENAME` | 撞 id/标题就换 id、加副本 | 无法「更新同一条」；只用于冲突副本 |

新增 `ImportMode.SYNC_APPLY` 的语义：

| 项 | 规则 |
|---|---|
| 定位 | **只按会话 id**；不做标题匹配 |
| id 已存在 | 在同一事务里用分片内容替换该会话（会话行、消息、预设挂载、压缩摘要、附件引用），**保留草稿与收藏**：删除前记录收藏行 `messageId`，替换后按相同 `messageId` 重新挂链；找不到对应消息的收藏置空 `messageId`（与现有断链语义一致，`data/ChatRepository.kt:2362-2363`） |
| id 不存在 | 新插入（等价 MERGE 的新增分支） |
| 消息 id | 保持分片里的 id，不做重映射（同一会话的消息 id 跨设备一致，是同步的地基） |
| 预设 | 按 id upsert（沿用现有行为，`data/ChatRepository.kt:2105-2110`） |
| 失败 | 整条事务回滚，计入失败，不影响其它会话（沿用 `writeStreamedSession` 的隔离方式） |
| 返回 | 复用 `ImportReport` 或其子集（`data/ImportReport.kt:28`） |

> 实现建议：在 `prepareConversation`（`data/ChatRepository.kt:2271`）加分支返回「替换」标记，
> `writeStreamedSession`（`:2188`）在收到标记时先收集收藏链接、再删旧会话记录、写新会话。
> 不要把 `SYNC_APPLY` 混进 `REPLACE_SAME`，两者的身份判定（id vs 标题）是安全边界。

**`RENAME` 的自定义后缀：** 现在后缀固定在 `prepareConversation` 内部取
`R.string.conversation_fork_suffix`（`data/ChatRepository.kt:2305-2306`）。同步需要
「同步副本 · 设备 · 日期」，建议给导入入口加一个可选 `copySuffix: String? = null`，
默认 `null` 保持现有行为，冲突副本才传自定义值。

### 50.8.3 新增导出入口 `exportSyncShard`

```kotlin
// 建议签名（data/ChatRepository.kt）
suspend fun exportSyncShard(conversationId: String, file: File, onProgress: (Float) -> Unit = {})

// 行为：
// 1) row = conversations.findById(id) ?: return null；row.deletedAt != null → 跳过（回收站不同步）
// 2) headers = row.toBackup(emptyList(), 挂载预设 id 列表, compactions)
// 3) presets = 仅该会话挂载的预设（沿用 data/ChatRepository.kt:1313-1314 的过滤）
// 4) writeBackupStream(writer, exportedAt = 0, headers, presets, favoriteFolders = emptyList(),
//                      favorites = emptyList(), attachmentSource = { AttachmentFiles.open(...) })
//    —— 逐条消息写，内存有界（data/BackupStream.kt:14-58）
// 5) 导出后 ensureActive + 校验文件非空
```

与现有 `exportBackup(file, onlyConversation = id)` 的差异（`data/ChatRepository.kt:1303-1336`）：
`exportedAt` 固定 0、显式过滤回收站、不写收藏（现状 `onlyConversation` 已不写收藏，
`:1315`；但会写 `System.currentTimeMillis()` 的 `exportedAt`，`:1319`）。

### 50.8.4 本地永久删除与墓碑回调

`deleteConversations(ids)`（`data/ChatRepository.kt:468`）目前只做本地清理。墓碑必须覆盖
所有「永久删除」入口：用户删除、清空回收站、`purgeExpiredTrash` 自动清理
（`data/ChatRepository.kt:255`，由设置页 `LaunchedEffect` 触发，`ui/settings/SettingsScreen.kt:306`）、
「覆盖同名」删旧会话（`deleteConversationRecords`，`:2359`）。**不能靠调用方逐个传参**：

- 方案 A（推荐）：`ChatRepository` 构造时注入可空的 `SyncTombstoneSink`（`AppContainer`
  启动时按配置装配，未启用同步就传空实现）；`deleteConversations` 与 `deleteConversationRecords`
  在事务提交后调用，自动清理路径也覆盖，数据层不需要知道网络细节。
- 方案 B（否决）：每个调用点显式传 sink —— 四个入口容易漏，`purgeExpiredTrash` 就是被设置页直接调用的。

sink 只把「id + 基线哈希 + 时间」写进本地墓碑列表，真正上传由下一次同步完成
（**不在删除路径同步网络**，避免删除变慢或失败）。

### 50.8.5 设置与凭据

新增 DataStore 键（`data/SettingsStore.kt:992` 的 `Keys`）：

| 键 | 类型 | 说明 |
|---|---|---|
| `sync_enabled` | Boolean | 是否启用同步（默认 false） |
| `sync_base_url` | String | 如 `https://dav.jianguoyun.com/dav/` |
| `sync_username` | String | 账号（可含邮箱） |
| `sync_password_enc` | String? | `SecretBox.encrypt(password)`（`data/SecretBox.kt:52-58`） |
| `sync_subdir` | String | 默认 `SimpleChat` |
| `sync_device_id` | String | 首次生成 UUID |
| `sync_device_name` | String | 默认「厂商 + 型号」，可改 |
| `sync_wifi_only` | Boolean | 默认 true（仅自动同步时强制；手动同步允许蜂窝并提示） |
| `sync_auto` | Boolean | 默认 false |
| `sync_last_at` | Long | 仅展示 |
| `sync_last_result` | String(JSON) | 仅展示（计数） |
| `sync_wizard_done` | Boolean | 首次向导完成标记 |

实现要求（与 `setApiKey` 完全同口径，`data/SettingsStore.kt:484-501`）：

1. 加密失败（返回 null）→ **抛 `AppTextException`**，不保存、不回落明文；
2. 明文密码不写任何日志、不进 `CrashDiagnostics`（现有崩溃日志会带异常栈，见 `data/CrashDiagnostics.kt`）；
3. 退出同步/删除配置时：删 `sync_password_enc`、`sync_base_url`、`sync_username`、
   `state.json`、`cacheDir/sync/`；**不删本地聊天数据**；
4. 「删除云端数据」是独立危险动作，不随退出账号自动执行。

### 50.8.6 同步报告与错误模型

| 类型 | 结构 | 说明 |
|---|---|---|
| `SyncReport` | `pushed/pulled/deleted/conflicts/skipped/failed` 计数 + `conflicts: List<Conflict>` + `failures: List<SyncFailure>` | 结果页展示；`SyncFailure` 复用 `ImportFailure.Reason` 的呈现方式（`data/ImportReport.kt:75-95`） |
| 同步错误 | `AppText` + 资源 ID（`data/AppText.kt:62-71`） | 数据层不拼中文，便于单测与双语 |
| 新增原因 | `AUTH`、`TLS`、`QUOTA`、`TOO_LARGE`、`NOT_DAV`、`SERVER_BUSY`、`CONFLICT`、`STATE_CORRUPT` | 在 `strings.xml` 里与现有 `import_fail_*` 并列 |

### 50.8.7 不新增依赖、不加数据库迁移

| 议题 | 结论 | 依据 |
|---|---|---|
| HTTP | 复用现有 OkHttp 5.5.0 | `gradle/libs.versions.toml:26`、`app/build.gradle.kts:158` |
| JSON | 复用 `kotlinx.serialization` + `backupJson` | `data/Backup.kt:18-22` |
| XML | JDK 自带 `DocumentBuilderFactory` | 不新增库 |
| 后台调度 | **不引 WorkManager** | `app/build.gradle.kts:131-162` 没有 `androidx.work`；`gradle/libs.versions.toml` 也没有。仓库对小型本地任务明确选择「不引 WorkManager」（`data/ChatRepository.kt:248-254`、`ui/settings/SettingsScreen.kt:300-307`） |
| 数据库 | **不加表、不升版本** | 同步状态是派生数据，放文件（§50.7.2）；避免 `db/AppDatabase.kt:34` 的 v18 → v19 迁移与回归成本 |
| 通知 | MVP 不需要 | 同步在前台页面进行；进程被杀由用户重新点击 |

## 50.9 增量与性能

### 50.9.1 传输量

| 数据 | 是否上传 | 说明 |
|---|---|---|
| 未变化会话 | **不传** | `localHash == baseHash` 且远端 ETag 未变 |
| 变化会话 | 整个分片重传 | 快照语义；单会话通常几 KB–几 MB |
| 附件 | 跟随变化会话 | 现有格式内联；图片变化即整片重传（P2 可外置为 blob 去重） |
| 墓碑 | 每条一个小文件 | 只写一次 |
| 设备/manifest | 每次同步各一次 | 极小 |

### 50.9.2 本地成本

| 阶段 | 成本 | 控制 |
|---|---|---|
| 导出 | 读取会话全部消息与附件并转 base64 | 流式写（`data/BackupStream.kt:14-58`），单条消息内存峰值；MVP 为全量导出（§50.7.3） |
| 导入 | 一个会话一个事务 | 沿用 `writeStreamedSession` 的分批（512k 字符/批，`data/ChatRepository.kt:2235-2238`） |
| 哈希 | SHA-256 一遍字节 | 与上传共用同一份临时文件，不重复读盘 |

### 50.9.3 单文件上限与超限处理

| 项 | 值 | 理由 |
|---|---|---|
| 单分片软上限 | 32 MiB | 超过时 UI 提示「该会话较大，同步可能较慢」，仍允许 |
| 单分片硬上限 | **64 MiB** | 超过则**跳过该会话**，列入 `skipped` 并显示标题；建议用户减少图片或改用整包备份 |
| 分片总数 | 不设硬上限 | 2000 会话即 2000 次 PUT 会慢；MVP 允许，P1 加并发/批量 |
| 附件单图 | 沿用附件规则（压缩后通常 40–400 KB，`docs/07:562`） | 不额外限制 |
| 请求体 | 先落文件，`Content-Length` 已知 | 避免 chunked 上传被部分服务拒绝 |
| 413 | 不重试，提示「服务器限制单文件大小」 | 与 507 分开 |

### 50.9.4 网络与节流

| 项 | 结论 |
|---|---|
| Wi-Fi only | 自动同步**强制** Wi-Fi（`ACCESS_NETWORK_STATE` 已声明，`AndroidManifest.xml:5`）；手动同步在蜂窝上弹确认 |
| 并发 | 网络请求串行（1 条）；下载/上传同一时间一个文件；P1 可提到 2 |
| 请求间隔 | 每次请求之间 50–100 ms，降低网盘风控概率（尤其坚果云） |
| 进度 | 两段：`扫描/导出`（按会话数）+ `传输`（按文件数/字节，`LinearProgressIndicator`，与导入页一致，参考 `ui/settings/SettingsScreen.kt` 的导入进度区） |
| 取消 | 任何阶段可取消；已完成项保留在 state；临时文件清理 |
| 节流 | 同一账号 5 分钟内不重复自动触发；手动点击不受限（有互斥锁） |

### 50.9.5 前台 / 后台

| 场景 | MVP | 说明 |
|---|---|---|
| 用户在同步页 | 前台协程 + 进度 | 离开页面**不取消**（同步由 `SyncCoordinator` 的应用级 scope 持有），回到页面继续显示 |
| App 退到后台 | 继续跑，但随时可能被系统杀掉 | 中断后 state 一致，下次重跑 |
| 自动同步 | App 进入前台时触发一次检查：开关开 + Wi-Fi + 距上次 ≥ 24h + 非首次向导中 | 与回收站清理同样的「机会式维护」思路（`ui/settings/SettingsScreen.kt:300-307`） |
| 可靠后台同步 | **不做** | 不引 WorkManager；Android 14+ 的 `dataSync` 前台服务有时长与用户可见性限制，为一个非实时需求付这个复杂度不值得。P1 若要做，单独评估 `androidx.work` 的体积/行为后再加 |

## 50.10 凭据与隐私安全

### 50.10.1 凭据存储

| 项 | 结论 | 依据 |
|---|---|---|
| WebDAV 密码 | `SecretBox.encrypt` → base64 密文存 DataStore | `data/SecretBox.kt:52-58`；与 API Key 同一套 |
| 加密失败 | 保存失败并提示，不回落明文 | `data/SettingsStore.kt:484-501` 同口径 |
| 密钥丢失（清数据/换机） | 解密返回空串 → 显示「未配置」，让用户重填 | `data/SecretBox.kt:60-79` |
| 账号/地址 | 明文存 DataStore | 应用私有目录 + `allowBackup = false`（`AndroidManifest.xml:39`），不随系统备份外泄；但**不进日志** |
| 同步配置是否进备份 | **否** | 备份格式（`data/Backup.kt:33-52`）没有这些字段；以后也不加 |
| 配置是否进同步 | **否** | 每台设备单独填写；避免密码被上传到网盘 |

### 50.10.2 日志脱敏

| 位置 | 规则 |
|---|---|
| 网络日志 | 不打印 `Authorization` 头、不打印完整 URL 的 userinfo（输入框也不接受 userinfo） |
| 错误上报 | 只记 `HTTP <code>` + 服务器返回的首行摘要（截断 200 字符）+ 主机名；密码/用户名不出现 |
| 崩溃日志 | `CrashDiagnostics` 已有的异常栈可能含 URL；同步层抛出的异常消息**不得拼接密码或完整认证头** |
| 诊断导出 | 同步诊断只输出「地址主机 + 目录 + 步数/字节数/错误码」，不输出正文（与现有「诊断日志不含聊天正文」一致，`TODO.md` 相关约定） |

### 50.10.3 传输安全

| 项 | 结论 |
|---|---|
| 协议 | 只支持 `https://`；`http://` 直接拒绝并说明原因 |
| TLS 校验 | 系统默认信任链；**不做**「忽略证书错误」 |
| 证书固定 | 不做（用户服务器种类太多） |
| 重定向 | 允许；OkHttp 跨主机重定向时会移除 `Authorization`（正确），若最终主机变了按认证失败提示 |
| 代理 | 跟随系统代理；不做额外配置 |

### 50.10.4 远端数据本身的敏感性

必须向用户说清：**同步上去的是明文 JSON，含聊天正文、思考内容、预设、会话标题，以及内联的图片 base64。**

| 风险 | 缓解 |
|---|---|
| 网盘服务商/共享目录能看到正文 | 首次配置向导与同步页固定显示一行警告；建议使用**专用目录/专用应用密码**，不要把 `SimpleChat/` 放进公开分享的目录 |
| 网盘账号被撞库 | 要求应用密码；不保存网页登录密码 |
| 链接分享泄露 | 文档与 UI 建议不要在网盘里对该目录开启公开分享 |
| 设备丢失后远端仍可读 | 用户可在任意设备「删除云端数据」；删除前校验 marker，避免误删 |
| 未来需要更强保护 | P2：口令加密（见下） |

**P2 口令加密预案（设计留口）：**

- 用户设置「同步口令」；分片上传前加密为 `.scsync`：`magic(8B)+salt(16B)+nonce(12B)+AES-256-GCM(明文分片)`，密钥 PBKDF2-HMAC-SHA256 派生（迭代次数写文件头）。
- 文件名仍是 `sha256(id)`；内容哈希对**明文**计算，三向比较不受影响，下载后先解密再算。
- 代价：口令丢失 = 云端不可读（本地不受影响）；默认关闭，开启时强提示不可找回。
- 否决「用 `SecretBox` 加密后同步」：Keystore 密钥不可导出，另一台设备解不开（`data/SecretBox.kt:81-102`）。

### 50.10.5 删除远端数据

| 动作 | 行为 |
|---|---|
| 入口 | 云同步页底部危险操作「删除云端同步数据」 |
| 前置校验 | PROPFIND `SimpleChat/`，读 `.simplechat-sync` 且 format/version 匹配；不匹配 → 拒绝 |
| 删除范围 | 只删 `conversations/`、`tombstones/`、`devices/`、`manifest.json`、`favorites.json`、`settings.json`、`blobs/`、`.simplechat-sync`；**不递归删除 `SimpleChat/` 之外的任何东西** |
| 二次确认 | 输入「删除」或勾选确认；文案写明「只影响云端，本机聊天记录不受影响」 |
| 失败 | 逐项删除、失败的列出来，不谎报成功 |
| 删除后 | 清空本地 state 与 staging；保留同步配置，下次同步视为首次 |

## 50.11 UI / UX

### 50.11.1 入口与信息架构

```text
设置
└── 数据管理（现有二级页，ui/settings/SettingsScreen.kt:2191 起）
    ├── 导出所有数据 / 导出 ZIP / 导出 DeepSeek（现有）
    ├── 导入备份 / DeepSeek 分享导入（现有）
    ├── 收藏夹（现有）
    ├── ☁ 云同步（WebDAV）            ← 新增行：未配置时显示「未配置」；已配置显示状态
    ├── 回收站（现有）
    └── 重置（现有）
```

点「云同步」进入 `SettingsPage.SYNC`（在 `ui/settings/SettingsScreen.kt:168` 的枚举里加一项，
`parent = DATA`，自动获得返回方向与转场）。

### 50.11.2 云同步页

```text
┌ 云同步（WebDAV）──────────────────────────────┐
│ 状态：已开启 · 上次同步 今天 14:32            │
│ 服务器：dav.jianguoyun.com/dav/SimpleChat     │
│ 本机设备：Pixel 9（可改名）           [编辑]  │
│                                                │
│ [立即同步]                                     │
│ 自动同步（Wi-Fi）                [开关]       │
│ 仅在 Wi-Fi 下同步                [开关]       │
│ 冲突记录（1）                    [>]          │
│ 同步的设备（2）                  [>]          │
│                                                │
│ 高级：子目录、强制全量校验、导出同步诊断       │
│ ─────────────────────────────────────────────│
│ 删除云端同步数据（危险）                       │
└────────────────────────────────────────────────┘
```

| 元素 | 取值 / 规则 |
|---|---|
| 页面骨架 | 与现有数据管理页一致：`SettingsSectionLabel` + `SettingsCard` + `SettingsActionRow` / `SettingsSwitchRow` / `SettingsNavRow` |
| 行高与触控 | 沿用现有设置页（≥ 46 dp 可点，见 [37-收藏夹功能设计](37-收藏夹功能设计.md) §48.10.6 的无障碍约定） |
| 未配置 | 整页只显示引导卡：「连接你的 WebDAV 网盘 →」+ 隐私提示一行 |
| 同步中 | 按钮变「同步中…」+ 进度条 + 当前文件名/阶段；禁止重复点击 |
| 上次结果 | 「上传 3 · 下载 2 · 删除 1 · 冲突 1 · 跳过 1」；点击查看详情（对账风格同导入结果，`data/ChatRepository.kt:2008-2059`） |
| 冲突 | 单独一行 + 数量；点开列表，MVP 支持「删除副本 / 知道了」 |
| 设备 | 列表来自 `devices/*.json`：设备名 + 最后同步时间 + 版本号 |
| 错误 | 行内红色文案 + 「重试」；错误文案见 §50.11.5 |
| 危险区 | 「退出同步（保留云端）」「删除云端同步数据」分开两个动作 |

### 50.11.3 首次配置向导

```text
第 1 步 服务器地址     [https://…]            说明：坚果云/Nextcloud 地址怎么填
第 2 步 账号与密码     [用户名] [应用密码]     固定提示：请使用「应用专用密码」，不要填网页密码
       → 「测试连接」：MKCOL + PROPFIND + PUT/DELETE 探测；成功显示「连接成功」
第 3 步 本机与同步方式 设备名 [Pixel 9]         自动同步 [关] · 仅 Wi-Fi [开]
第 4 步 首次合并策略   (•) 保留两份（推荐） ( ) 以本机为准 ( ) 以远端为准
第 5 步 隐私确认       ☐ 我知道同步到网盘的是明文聊天内容
                        [开始同步]（勾选后才可点）
```

| 规则 | 说明 |
|---|---|
| 可跳过 | 第 3/4/5 步可后退；退出向导不保存半配置（或保存但不启用） |
| 「测试连接」写什么 | 只写/删临时探测文件（`SimpleChat/.probe-<uuid>`），不写任何聊天数据；失败按状态码给文案 |
| 密码字段 | 可粘贴；显示为掩码；不提供「显示密码」以外的记忆项 |
| 首次同步 | 在向导内跑，显示进度；失败可稍后重试（配置已保存） |
| 完成页 | 显示「已同步 N 个会话」+ 提示「另一台设备也这样配置一次」 |

### 50.11.4 关键交互规则

| 情形 | 行为 |
|---|---|
| 点击「立即同步」时本机正在聊天/流式生成 | 允许同步（导出的是开始时刻的快照）；提示「同步期间可继续使用」 |
| 同步中退出页面 | 继续后台（应用级 scope）；再进入显示进度 |
| 同步中杀掉 App | 下次启动无提示；用户再次同步时从断点（state）继续 |
| 网络切换 Wi-Fi/蜂窝 | 手动同步继续（已确认过）；自动同步在蜂窝上直接跳过并记原因 |
| 服务器返回 401 | 停止，弹「认证失败：请检查账号与应用密码」，提供「去修改」直达配置页 |
| 服务器返回 507 | 停止，提示「网盘空间不足或超出配额」；列出已传/未传数量 |
| 云端被清空 | 提示「云端目录为空，已把本机数据重新上传」；列出数量 |
| 冲突发生 | 同步继续；结束后弹一次汇总（不逐条打断），标题「有 N 个会话在两端都改过，已各保留两份」 |
| 用户删除「同步副本」 | 走普通删除会话（本地删除，下轮写墓碑 → 云端副本也删） |
| 退出同步 | 只停同步，**不删本地数据**；保留云端文件；再开启时按首次同步向导处理基线 |
| 删除云端数据 | 危险确认；成功后提示「云端已清空，本机数据保留」 |

### 50.11.5 文案（中英成对）

新增字符串统一 `sync_` 前缀，同时进 `res/values/strings.xml` 与
`res/values-en/strings.xml`（与现有导入文案的组织方式一致，参考
`res/values/strings.xml:318-352`）。挑关键项：

| key | 中文 | English |
|---|---|---|
| `sync_title` | 云同步（WebDAV） | Cloud sync (WebDAV) |
| `sync_settings_hint` | 用你自己的 WebDAV 网盘在设备间同步聊天数据 | Sync chats between devices using your own WebDAV storage |
| `sync_unconfigured` | 未配置 | Not set up |
| `sync_connected` | 已连接 | Connected |
| `sync_last_at` | 上次同步 %1$s | Last synced %1$s |
| `sync_never` | 尚未同步 | Never synced |
| `sync_now` | 立即同步 | Sync now |
| `sync_syncing` | 同步中… | Syncing… |
| `sync_auto` | 自动同步（Wi-Fi） | Auto sync (Wi-Fi only) |
| `sync_wifi_only` | 仅在 Wi-Fi 下同步 | Sync on Wi-Fi only |
| `sync_device_name` | 本机设备 | This device |
| `sync_devices` | 同步的设备（%1$d） | Synced devices (%1$d) |
| `sync_conflicts` | 冲突记录（%1$d） | Conflicts (%1$d) |
| `sync_conflict_body` | 「%1$s」在两端都改过，已保留为两份。副本：「%2$s」 | "%1$s" changed on both devices; both copies were kept as "%2$s" |
| `sync_conflict_keep_copy` | 知道了 | Got it |
| `sync_conflict_delete_copy` | 删除副本（以远端为准） | Delete copy (keep remote) |
| `sync_progress_scan` | 正在扫描本机会话… | Scanning local chats… |
| `sync_progress_upload` | 正在上传 %1$s（%2$d%%） | Uploading %1$s (%2$d%%) |
| `sync_progress_download` | 正在下载 %1$s（%2$d%%） | Downloading %1$s (%2$d%%) |
| `sync_result` | 上传 %1$d · 下载 %2$d · 删除 %3$d · 冲突 %4$d · 跳过 %5$d | Uploaded %1$d · Downloaded %2$d · Deleted %3$d · Conflicts %4$d · Skipped %5$d |
| `sync_error_auth` | 认证失败：请检查账号与应用密码 | Sign-in failed: check the account and app password |
| `sync_error_tls` | 无法建立安全连接：请检查地址与证书 | Could not establish a secure connection: check the URL and certificate |
| `sync_error_http` | 服务器返回错误（HTTP %1$d） | Server error (HTTP %1$d) |
| `sync_error_quota` | 网盘空间不足或已超出配额 | Storage full or quota exceeded |
| `sync_error_too_large` | 「%1$s」超过单文件上限，已跳过（%2$s） | "%1$s" exceeds the per-file limit and was skipped (%2$s) |
| `sync_error_not_dav` | 这个地址不像是 WebDAV 目录 | This URL does not look like a WebDAV folder |
| `sync_error_network` | 网络中断，已传的内容会在下次继续 | Connection lost; finished items resume next time |
| `sync_error_state` | 本地同步状态损坏，将重新建立基线 | Local sync state was damaged; rebuilding the baseline |
| `sync_wizard_title` | 连接 WebDAV 网盘 | Connect WebDAV storage |
| `sync_wizard_url_hint` | 服务器地址（https://） | Server URL (https://) |
| `sync_wizard_password_hint` | 应用专用密码 | App-specific password |
| `sync_wizard_password_note` | 开启两步验证时请使用应用专用密码，不要填网页登录密码 | With 2FA, use an app-specific password, not your web password |
| `sync_wizard_policy` | 首次同步如何处理两边都有的会话？ | How should the first sync handle chats that exist on both devices? |
| `sync_wizard_policy_keep_both` | 保留两份（推荐） | Keep both (recommended) |
| `sync_wizard_policy_local` | 以本机为准 | Use this device |
| `sync_wizard_policy_remote` | 以远端为准 | Use remote |
| `sync_privacy_warning` | 同步到网盘的是明文聊天内容；请使用专用目录与应用密码 | Synced files are plain text; use a dedicated folder and app password |
| `sync_delete_remote` | 删除云端同步数据 | Delete cloud sync data |
| `sync_delete_remote_confirm` | 只删除云端数据，本机聊天记录不受影响。此操作不可恢复。 | Deletes cloud data only; local chats stay. This cannot be undone. |
| `sync_sign_out` | 退出同步 | Sign out of sync |
| `sync_sign_out_confirm` | 停止同步并删除本机保存的密码；不会删除云端数据 | Stops syncing and removes the saved password; cloud data is kept |

> 文案约束同 [09-文档规范](09-文档规范.md)：中文全角标点、中英之间空格、占位符 `%1$s`；
> 所有错误句必须包含「下一步做什么」，不能只报错因（同 `net/NetworkErrors.kt:12-36` 的原则）。

### 50.11.6 无障碍

| 元素 | 要求 |
|---|---|
| 同步按钮 | `contentDescription` 随状态变（「立即同步」/「同步中」）；`stateDescription` 报进度 |
| 进度条 | 有确定进度时用 `progressBarRangeInfo`；无进度时说明「正在准备」 |
| 设备/冲突列表 | 行合并描述：设备名 + 最后同步时间；冲突行读出两个标题 |
| 危险操作 | 触发按钮带「不可恢复」语义；确认对话框首焦点不落在「确认」上 |
| 触控 | 所有可点区域 ≥ 46 dp（与现有设置页一致） |

## 50.12 与现有备份/恢复的关系

### 50.12.1 复用关系

| 能力 | 关系 |
|---|---|
| `BackupFile` / `BackupStream` | **同一格式**：分片 = 单会话备份；`version = 2` 不变 |
| `OwnBackupStream` | **同一解析器**：远端分片下载后可直接交给现有导入链路；版本校验逻辑不变（`data/import/OwnBackupStream.kt:194-195`） |
| `importBackup` / `importParsed` | 复用其三模式；同步只新增 `SYNC_APPLY`，不改现有三模式行为 |
| 导入报告 | 复用 `ImportReport` 的字段与对账理念（`data/ImportReport.kt:28-54`） |
| 全量 JSON 导出 | 仍是**唯一完整出口**：收藏、设置、草稿（刻意不备份）、壁纸/字体都不在同步 MVP 里；换机仍需全量备份或 P1 后的完整同步 |
| ZIP 归档 | 不参与同步；仍是「导给人看/单文件传输」的出口（`data/archive/ChatArchive.kt:14-36`） |
| 回收站 | 不参与同步；墓碑只对永久删除生成 |

### 50.12.2 版本升级策略

| 场景 | 规则 |
|---|---|
| 同步分片加字段 | 不升 `BackupFile.VERSION`（沿用 [04-数据模型](04-数据模型.md) §14.4 的「加字段不升版本」约定）；旧客户端 `ignoreUnknownKeys` 忽略（`data/Backup.kt:18-22`） |
| 同步分片升 v3（不兼容） | **本设计不允许在同步场景直接升**：升 v3 会让旧客户端 `check(version == VERSION)` 失败（`data/import/OwnBackupStream.kt:194-195`），整次同步失败。必须走「双版本期」：旧客户端只读不写、新客户端兼容读 v2；具体方案在真正需要时另写设计 |
| 同步语义版本 `syncSchema` | 设备文件里带；读到比自己高的 `syncSchema` → 进入**只读模式**（只拉不推），并提示升级 App，避免旧逻辑把新字段写没 |
| 旧客户端降级 | 旧客户端读到新字段会忽略；下次它推自己的分片时可能把新字段丢掉。因此读到「远端设备版本更高」时提示用户统一升级；MVP 不强制拦截 |
| `manifest.json` 版本 | 仅展示用；不匹配直接忽略/重建，不阻断同步 |

## 50.13 兼容性与已知风险

### 50.13.1 容器与文件系统（必须复用现有修复）

| 风险 | 现状 | 同步必须怎么做 |
|---|---|---|
| **鸿蒙/卓易通容器里 `File.renameTo()` 跨挂载点失败** | `renameTo` 失败不抛异常、只返回 false；仓库已在 `data/ExportFiles.kt:61-89` 提供 `writeFileAtomically`（失败回退 `copyTo` 并核对字节数），在 `net/UpdateDownloader.kt:110-134` 也加了同样兜底；**`data/FileApiTextCache.kt:38-41` 仍是旧的「失败就删临时文件」写法** | 同步的 `state.json`、下载的 `.part → 正式`、导出的分片落盘**一律用 `writeFileAtomically`**；禁止裸 `renameTo`。若失败必须有可行动错误，不能写 0 字节目标 |
| SAF/DocumentsProvider 写 0 字节（部分容器） | 现有导出已用 FD 兜底（`data/ExportFiles.kt:95-114`） | 同步走应用私有目录 + 网络，不经过 SAF，不受影响 |
| 私有目录被系统清理 | `cacheDir/sync/` 会被清；`filesDir/sync/` 不会 | state 放 `filesDir`；staging 放 `cacheDir`，丢了重导出即可 |
| 存储空间不足 | 导出分片会占空间 | 同步前检查 `filesDir.usableSpace`（现有导入也用 16 MB 阈值，`data/ChatRepository.kt:2141`）；不足则停止并提示 |

### 50.13.2 协议与服务器风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 服务器不支持 ETag / `If-Match` | 乐观锁失效，可能覆盖 | 降级为「同步前比对 ETag/哈希」，接受小概率竞态；UI 提示该服务器并发保护较弱 |
| PROPFIND 返回登录页/HTML | 误判为空目录 → 全量上传 | 响应不是 `207` 或 XML 解析失败 → 认证/地址错误，不继续 |
| 429 限流 | 同步失败 | 串行 + 退避 + `Retry-After` |
| 507 配额 | 传一半失败 | 停止并保留已传；state 一致 |
| 413 请求体过大 | 单分片失败 | 单分片上限 + 提示服务器限制 |
| 服务端目录被外部清空 | 本机看到「远端缺失」 | 不删本地，补传恢复（表 9） |
| 用户把地址填成非 WebDAV（或网盘根） | 可能误删 | marker 校验 + 只在 `SimpleChat/` 子目录内操作 |
| 服务器端文件被外部改名 | 本机视为「远端删除 + 新增」 | 按 §50.7.5 决策表第 9/12 行处理；不静默删 |
| WebDAV 服务器对并发 PUT 同一文件无锁 | 极小概率丢更新 | ETag 乐观锁 + 冲突副本兜底 |
| 时钟漂移 | 无正确性影响 | 所有判定用哈希/ETag；时间仅展示 |

### 50.13.3 本机风险

| 风险 | 缓解 |
|---|---|
| 同步中导出/导入同时发生 | `SyncCoordinator` 互斥；导入本身也在设置页串行（现有 UI 已禁用按钮） |
| 进程被杀 | state 分片提交 + 幂等 |
| 同一会话被两端同时编辑后产生多个副本 | 可预期；P1 提供合并/清理入口，MVP 用户可手动删除副本 |
| 用户误以为「同步 = 备份」 | 文案明确：同步不替代导出备份；云端数据可能被任意一端删除 |
| 大库首次同步耗时长 | 进度 + 可取消 + 提示 Wi-Fi |
| 收藏/设置不同步造成「两台不一致」 | 明确写在功能说明里；P1 补 |

## 50.14 测试与验收矩阵

### 50.14.1 分层原则

仓库 JVM 测试不碰 Android Room/网络真实服务器（`app/build.gradle.kts:161` 只有
`testImplementation(libs.junit)`）；所以：

- **纯函数 / 编排逻辑**走 JVM：`SyncEngine` 依赖 `SyncGateway` + `ShardSource` 接口，
  用假实现即可覆盖三向比较、冲突、墓碑、重试判定；
- **HTTP 协议**走 JVM：用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起假 WebDAV
  （仓库没有 MockWebServer 依赖，见 `gradle/libs.versions.toml`；JDK server 零依赖，
  足够模拟 207/412/429/507。若团队更偏好 MockWebServer，可只新增
  `testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")`，测试逻辑不变）；
- **Room 写库 / 图片落盘 / UI**走设备回归（现有 `DeviceRegression` 机制）。

### 50.14.2 JVM 单测清单

| 测试文件（建议） | 用例 |
|---|---|
| `SyncPlanTest` | 三向比较表 12 行逐行；`base=null` 的首次分支；墓碑优先；「删除让位修改」 |
| `SyncPathTest` | `sha256(id)` 文件名稳定；根目录拼接（尾斜杠/重复斜杠/大写 scheme）；拒绝 `http://`、拒绝含 userinfo；子目录名规范化；设备文件名 |
| `WebDavXmlTest` | `d:/D:/无前缀` 三种 multistatus；`href` 百分号编码；collection 跳过；缺属性；损坏 XML 返回错误 |
| `EtagTest` | 带引号/`W/`/空/null 的归一化；弱 ETag 不进 `If-Match`；`If-Match` 原样使用 |
| `SyncHashTest` | 同数据两次导出（`exportedAt=0`）哈希相同；改一个字符哈希不同；分片包含/不含挂载预设的差异 |
| `SyncStateTest` | JSON 往返；版本不匹配 → 重建；`remoteKey` 变化 → 失效；冲突/墓碑序列化 |
| `SyncErrorTest` | 401/403/404/409/412/423/429/413/507/5xx → `AppText` 资源映射；可重试判定（纯函数） |
| `SyncConflictTest` | 冲突副本标题生成（重名递增、后缀、超长截断）；`SYNC_APPLY` 决策（保留收藏链接、清旧消息） |
| `AtomicWriteTest` | 正常原子写；0 字节保护；目标写入后长度校验；失败清理（容器回退路径需设备验证） |
| `SyncSettingsTest` / `SyncRedactionTest` | 白名单投影不含 `api_key_enc`/`api_profiles_v1`/`search_api_config`；日志脱敏去掉 Authorization/密码/URL userinfo |

### 50.14.3 HTTP 协议测试（JDK HttpServer 假 WebDAV）

| # | 场景 | 断言 |
|---|---|---|
| 1 | PROPFIND 207 正常 | 解析出文件/目录、ETag、大小 |
| 2 | PUT 新建 | 返回 201 + ETag；文件内容逐字节一致 |
| 3 | PUT `If-None-Match: *` 已存在 | 412 → 进入冲突流程 |
| 4 | PUT `If-Match` 不匹配 | 412 → 冲突流程 |
| 5 | GET 404 | 视为远端缺失，不当作空文件 |
| 6 | DELETE 204/404 | 都算成功 |
| 7 | MKCOL 201/405 | 都算成功；逐级创建 |
| 8 | 429 + `Retry-After: 1` | 等待后重试成功，总尝试 ≤ 2 |
| 9 | 507 | 不重试，报配额错误 |
| 10 | 401 | 不重试，报认证错误 |
| 11 | 返回 HTML 登录页 | 报「不是 WebDAV」，不删任何东西 |
| 12 | 无 ETag 的服务器 | 用长度/时间 + 下载哈希判变化，流程仍收敛 |
| 13 | 响应体截断/超时 | 重试；临时文件清理 |
| 14 | 两客户端顺序同步 | A 推 → B 拉 → 内容一致；B 改 → A 拉 → 一致 |
| 15 | 两客户端并发改同一会话 | 先 PUT 者成功，后者 412 → 冲突副本，双方最终各两份 |
| 16 | 删除传播 | A 永久删 → 墓碑；B 拉 → B 本地会话删除 |
| 17 | 删除 vs 修改 | A 删、B 改 → B 保留并报冲突；不丢 B |
| 18 | 云端被清空 | 不删本地，补传并提示 |

### 50.14.4 设备回归（新增 `SyncRegression.kt`，`-e sync true`）

前置：一台可达的 WebDAV 测试服务（局域网 Nextcloud/自建 Nginx+DAV，或 `adb reverse` 暴露
JVM 测试服务器）；两台设备（或一台设备 + 清数据后模拟第二台）。

| # | 场景 | 断言 |
|---|---|---|
| 1 | 首次配置（坚果云示例地址） | 测试连接成功/失败文案正确；不写聊天数据 |
| 2 | 密码用网页登录密码（开启 2FA 时） | 401 → 文案提示应用密码 |
| 3 | 首次双向同步 | 两端会话集合一致；附件可显示；消息树/分支/压缩摘要一致 |
| 4 | 仅本机改一条会话 | 只上传该分片；其余分片无 PUT（服务端日志计数） |
| 5 | 仅远端改一条会话 | 只下载该分片；本机其它会话不动 |
| 6 | 两端改同一条 | 规范会话 = 远端内容；本机旧内容成为「同步副本」；两端最终都能看到两份 |
| 7 | 一端永久删除 | 另一端同步后删除；回收站不受影响 |
| 8 | 删除 vs 修改 | 修改端保留内容并提示冲突 |
| 9 | 断网中断再续 | 重跑只处理未完成项；无重复会话；无 0 字节文件 |
| 10 | 杀掉 App 后继续 | state 一致；无重复上传 |
| 11 | 大分片（接近 32 MiB） | 进度正确；完成后内容一致；超 64 MiB 跳过并列出 |
| 12 | 413/507 模拟 | 停止 + 正确文案，不重试风暴 |
| 13 | 云端目录被手动删除后同步 | MKCOL + 补传；本机不删 |
| 14 | 换账号/换地址 | 旧基线失效；进入首次同步向导；不串数据 |
| 15 | 退出同步 | 密码清除；本地数据保留；云端保留 |
| 16 | 删除云端数据 | marker 校验；只删 `SimpleChat/`；本机数据保留 |
| 17 | 中文/emoji 标题、超长标题 | 同步后两端标题一致（文件名哈希不受影响） |
| 18 | 2000 会话 / 带图库 | 同步可完成；内存不崩；耗时与进度合理（不设硬指标，记录实测） |
| 19 | 深色/壁纸/1.3× 字号 | 云同步页与向导不裁切、可读 |
| 20 | TalkBack | 同步按钮/进度/冲突列表可读，危险确认可操作 |

> 设备回归命令沿用现有机制（参考 [37-收藏夹功能设计](37-收藏夹功能设计.md) §48.14.4 的
> `adb shell am instrument -w -e … DeviceRegression`），新增 `-e sync true` 分支。

### 50.14.5 验收门槛

| 维度 | 门槛 |
|---|---|
| 数据 | 同步往返后会话/消息/分支/压缩摘要/附件一致；无重复会话、无孤儿文件 |
| 冲突 | 表 5/6 场景不丢任一端正文；副本可见、可删除 |
| 删除 | 软删除不传播；永久删除传播；删除 vs 修改保留数据 |
| 幂等 | 同一同步重复 3 次，第二次起 0 传输（除设备/manifest） |
| 协议 | 8 类状态码（401/403/404/409/412/413/429/507）文案与行为符合 §50.6.6 |
| 安全 | 密码不以明文出现在 DataStore/日志/崩溃记录；`http://` 被拒；删除云端前校验 marker |
| 兼容 | 有/无 ETag、有/无 HEAD 的服务器都能完成同步（能力降级） |
| 性能 | 2000 会话库同步内存峰值受控（不 OOM）；进度可见；可取消 |
| UI | 中英双语成对；深色/壁纸/大字号可用；TalkBack 可完成主流程 |
| 文档 | README/TODO/PRD/§48 口径同步修订（§50.2.4） |

## 50.15 分阶段交付

### 50.15.1 MVP（建议 1 个版本：v1.3.0，MINOR）

范围：**会话级双向同步 + 冲突副本 + 墓碑 + 手动同步 + 凭据加密 + 结果对账**。
不做收藏/设置/文件/自动后台。

| 层 | 文件 | 改动 |
|---|---|---|
| 网络 | `net/WebDavClient.kt`、`net/WebDavXml.kt` | 新建：Basic/HTTPS、PROPFIND、MKCOL、GET、PUT、DELETE、ETag、重试 |
| 编排 | `data/sync/SyncEngine.kt`、`SyncGateway.kt`、`ShardSource.kt`、`SyncState.kt`、`SyncCoordinator.kt` | 新建：三向比较、冲突、墓碑、state、互斥、进度 |
| 数据 | `data/ChatRepository.kt` | `exportSyncShard`；`importSyncShard`（走 `SYNC_APPLY`）；永久删除的墓碑 sink；按 `deletedAt` 过滤的枚举 |
| 数据 | `data/Backup.kt` | `ImportMode.SYNC_APPLY`；`RENAME` 可选后缀参数 |
| 数据 | `data/SettingsStore.kt` | §50.8.5 的键与凭据读写、清理 |
| 数据 | `data/ExportFiles.kt` | 复用 `writeFileAtomically`；同步侧禁止裸 `renameTo` |
| 状态 | `data/sync/…` | state/墓碑/冲突的原子读写 |
| 界面 | `ui/settings/SettingsScreen.kt` | 数据管理入口行；`SettingsPage.SYNC` |
| 界面 | `ui/settings/SyncPage.kt`、`SyncDialogs.kt` | 页面、向导、冲突列表、删除云端确认 |
| 资源 | `res/values/strings.xml`、`values-en` | §50.11.5 成对文案 |
| 测试 | `SyncPlanTest` / `SyncPathTest` / `WebDavXmlTest` / `EtagTest` / `SyncHashTest` / `SyncStateTest` / `SyncErrorTest` / `SyncConflictTest` / `AtomicWriteTest` / `SyncSettingsTest` / `SyncRedactionTest` | JVM |
| 测试 | `SyncRegression.kt`、`DeviceRegression.kt` | `-e sync true` |
| 文档 | `docs/README.md`、`TODO.md`、`docs/01-PRD.md`、`docs/37-…`、`docs/10-CHANGELOG.md` | §50.2.4 的口径修订 + 索引 |

**MVP 验收标准：** §50.14.5 全部通过；两台真机用手动 WebDAV 服务完成
「新增 → 更新 → 冲突 → 删除 → 断网续传 → 换账号 → 删云端」全流程。

**建议提交拆分**（按 [09-文档规范](09-文档规范.md) §2 的 commit 规范）：

| commit | 内容 |
|---|---|
| `docs: add webdav sync design` | 本文 + 索引/口径 |
| `feat(sync): add webdav client and state` | 网络层 + state + 协议测试 |
| `feat(sync): add conversation shard sync` | 导出/导入/三向比较/墓碑 |
| `feat(sync): add cloud sync settings ui` | 设置页与向导 |
| `test(sync): add sync regression matrix` | 设备回归 |
| `docs: update sync changelog and todo` | CHANGELOG/TODO |

### 50.15.2 P1

| 项 | 内容 | 验收 |
|---|---|---|
| 收藏夹同步 | `favorites.json`：夹 + 收藏（按 `messageId` 去重、归属并集、删除墓碑）；可关「不同步收藏图片」 | 两端收藏/夹一致；同一消息两端收藏不重复 |
| 设置同步（白名单） | `settings.json`：外观/对话行为/收藏分组；**API Key 与端点密钥永不入内**；默认关闭 | 两端外观一致；密钥字段永不出现在文件里 |
| 全局预设库 | `presets.json`，按 id 合并；删除传播需墓碑 | 未挂载预设也能跨端 |
| 自动同步 | App 前后台触发 + 节流 + Wi-Fi；仍不引 WorkManager | 一天内自动同步一次；蜂窝不触发 |
| 冲突处理增强 | 「以本机为准」把副本内容设为规范；批量清理副本；冲突会话在抽屉里标记 | 三种处理动作可用 |
| 脏标记增量 | Repository 写入口标脏；只导出变化会话；保留「强制全量校验」 | 1000+ 会话同步耗时显著下降 |
| 墓碑水位 GC | 所有设备都同步过 → 删墓碑 | 墓碑数量不无限增长 |
| 附件外置（可选） | 大附件按 `sha256` 存 `blobs/`，分片里改成引用 | 同一图片多会话复用、传输去重 |
| 后台同步评估 | 评估 WorkManager 的体积/行为后再决定是否引入 | 有明确数据（APK 增量、可靠性）再立 |

### 50.15.3 P2

- **口令加密（E2EE-ish）**：同步口令 + AES-256-GCM，服务器端不可读；口令丢失不可恢复。
- **内容寻址附件全面化**：所有图片/字体/壁纸走 `blobs/<sha256>`，分片与备份引用化（会触及备份格式，需单独立项）。
- **其它端**：分片协议已公开，桌面/Web 只需实现 `BackupFile` 读写与三向比较。
- **服务器端历史浏览**：列出网盘里的历史版本（如果服务商支持），恢复成「同步副本」。
- **同步诊断包**：一键导出「协议交互摘要（不含正文/密钥）」供排查。
- **P2 验收**：加密分片在服务器端不可读，换端解密后可正常读写；其它端用公开格式完成只读浏览。

## 50.16 未决问题（Open Questions）

每条给出**推荐默认值**，评审时只需确认/否决。

| # | 问题 | 推荐默认 | 需谁确认 |
|---|---|---|---|
| Q1 | MVP 是否传附件（跟随会话内联）？ | **传**。不传会让「多端一致」名不副实；用 64 MiB 上限 + Wi-Fi 提示控制成本 | 用户 |
| Q2 | 同步删除是否默认开启？ | **开启**（仅永久删除传播），删除 vs 修改时保留数据 | 用户 |
| Q3 | MVP 是否包含「自动同步」开关？ | **包含开关但默认关**；只在 App 前台机会式触发，不做后台常驻 | 用户 |
| Q4 | 冲突默认策略？ | **远端为规范 + 本机旧版转副本**（两份都留） | 用户 |
| Q5 | 远端明文风险是否接受？ | MVP 接受 + 首次强提示；P2 给口令加密 | 用户 |
| Q6 | 收藏夹进 MVP 还是 P1？ | **P1**；MVP 期间跨设备收藏仍靠全量备份 | 用户 |
| Q7 | 设置同步范围是否包含「对话行为」？ | P1 同步外观与对话行为，**语言/地区/音色/密钥不同步** | 产品 |
| Q8 | 单分片硬上限 64 MiB 是否合适？ | 是；超出跳过并列出，提示改用整包备份 | 工程 |
| Q9 | 是否允许 `http://`（局域网自建）？ | **不允许**；如未来支持，只对「本机/局域网」加白名单并显式确认 | 安全 |
| Q10 | 墓碑保留期？ | MVP 永久（很小）；P1 用设备水位 GC | 工程 |
| Q11 | 退出同步是否保留 state？ | **删除 state 与密码**；再开启走首次向导（避免旧基线套新账号） | 产品 |
| Q12 | 「删除所有历史对话」的默认行为？ | **只清本机 + 暂停自动同步**；云端保留，需要用户显式勾选 | 产品 |
| Q13 | 是否显示设备真实型号？ | 显示「厂商 + 型号」；用户可改名；设备文件在用户自己的网盘里 | 隐私 |
| Q14 | 同步失败是否重试到成功？ | 手动同步：失败即止并显示；自动同步：退避一次后止，不无限重试 | 工程 |

## 50.17 文档与索引变更（施工时执行）

| 文件 | 变更 |
|---|---|
| `docs/README.md` | 索引表加一行：`40-多端同步（WebDAV）设计.md` → §50；「发布与验收记录」不需要 |
| `TODO.md` | 修正 `:280` 的「明确不做」；新增「WebDAV 多端同步（MVP）」待办并链本文；验收后归档到 `docs/11-实现记录.md` |
| `docs/01-PRD.md` | §1.3 Non-Goals 与 §9.1 分级口径（§50.2.4） |
| `docs/37-收藏夹功能设计.md` | §48.2.3 的「云同步/跨设备」一行改为「官方云同步不做；收藏随 WebDAV 同步见 §50（P1）」 |
| `docs/04-数据模型.md` | §14.4 备份格式补一句：「同步分片 = 单会话备份，`exportedAt` 固定 0」；§19 凭据存储补「WebDAV 密码同口径」 |
| `release-notes/` | 发版时按 [09-文档规范](09-文档规范.md) §4 生成 `RELEASE_NOTES_v1.3.0.md`；tag `v1.3.0` |

> 文档是设计的唯一来源（[README](README.md)「改设计先改这里」）：代码与本文不一致时，以本文为准并同步修正。

