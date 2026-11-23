# SimpleChat

> 使用 Jetpack Compose 构建的 Android AI 对话客户端，支持长对话、本地历史管理和内容导出。

**中文 | [English](README.en.md)**

[![Version](https://img.shields.io/badge/version-1.2.5-blue)](https://github.com/Pokanshuu/Simple-Chat/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

查看 [v1.2.5 正式版发布说明](release-notes/RELEASE_NOTES_v1.2.5.md)，了解主题、壁纸、第三方朗读、回收站和聊天工具。

## 简介

面向长文本连续创作的 Android 端 AI 对话客户端。接入任意 OpenAI 兼容端点，历史记录本地存储，无账号体系与云同步。启用文件 API 后，上传文件同时保存在所配置的服务端。

适用场景：

- 长文本续写、设定推演、角色扮演
- 多轮长会话，需要管理上下文占用并压缩历史
- 自建或本地 OpenAI 兼容服务

## 目录

- [简介](#简介)
- [特性](#特性)
- [不包含](#不包含)
- [快速开始](#快速开始)
- [使用示例](#使用示例)
- [配置](#配置)
- [开发](#开发)
- [技术选型](#技术选型)
- [项目结构](#项目结构)
- [隐私与安全](#隐私与安全)
- [文档](#文档)
- [贡献](#贡献)
- [许可证](#许可证)

## 特性

- 多服务端：任意 OpenAI 兼容端点，内置 DeepSeek 与 OpenCode Go 配置。
- **双协议通道**：DeepSeek 官方端点可走 **Anthropic 兼容**扩展通道（联网搜索 / 链接阅读 /
  正文引用 / 服务端标题 / 时间地点提醒），其余端点走 OpenAI 兼容。两条并存，可手动锁定。
- **智能搜索**：`deepseek_web_search`。过程以时间线呈现（搜索 / 浏览网页 / 页内查找 /
  阅读链接），正文带 `[ⁿ]` 引用角标，下方来源条与来源弹窗用同一套编号。
- 流式输出：SSE 逐字渲染，Markdown 实时渲染，定稿前后像素级一致。
- **加载词**：等待首个 token 时，每轮随机显示一条加载词，末尾三个点循环播放。可在「设置 → 对话 → 对话行为 → 显示 → 加载词」新增、编辑或删除自定义文案，并为文字、圆环和省略号启用主题色。自定义列表为空时使用默认的 8 句。
- 思考区：`reasoning_content` 折叠展示，仅计真实思考时长，正文出现即收起（可关闭）。
- **继续生成**：被用户停止、或被输出上限截断的回复可以接着写（末尾 assistant 前缀续写）。
- **数学公式**：`$…$` / `\(…\)` 与 Markdown 正文连续混排；`$$…$$` / `\[…\]` 独占一行时作为行间公式，同一行内时参与行内排版；使用 Compose 原生渲染。
- **双击选字**：双击 AI 回复进入原生文字选择，支持拖选、全选与复制；长按菜单入口继续保留。
- **Mermaid 图表**：` ```mermaid ` 渲染成图，可切回源码；脚本随包发布，离线可用。
- **按住说话**：长按空输入框开始录音，松手后转写并发送，上滑后松手取消。DeepSeek 官方接入使用官方 ASR，单段最长 120 秒。其他接入使用设备可用的 Android 系统语音识别。
- **朗读**：系统 TTS 逐条朗读回复，无需服务端与权限；设置里可切换音色（含试听），朗读前会剥掉代码块与表格。
- **自动朗读**：顶栏新建对话左侧的喇叭开关，开启后朗读新完成的回复，关闭即停止；使用已选朗读音色。
- **API 配置管理**：独立保存多组地址、模型、协议与加密 Key，支持新建、复制、重命名、删除和一键切换；修改自动保存，现有配置自动保留。
- **首次启动引导**：验证 DeepSeek API Key、选择模型、导入备份并设置主题色；已有配置的用户升级时跳过引导。
- 思考强度支持关闭、`low`、`high` 与 `max`，两种请求协议均可使用。
- **独立搜索与文件 API**：自定义地址、路径和加密密钥，或复用聊天密钥；兼容 DeepSeek Search / Fetch 与 Files 协议。
- **长文本文件传输**：启用文件 API 后，超过阈值的用户输入和文本附件上传一次并复用文件 ID，减少重复传输；本地保留原始消息。配置与限制见[工具 API 指南](docs/23-独立工具API与长文本文件传输.md)。
- 消息树：以 `parentId` 组织。重新生成为会话内分支，切换版本时下游整段跟随，不删除历史；长按支持会话间分叉。
- **上下文管理**：估算用量并在发送前检查预算。压缩保留最近的完整对话轮次，不改写本地原文。
  预算允许时，待压缩原文只发送一次。超时或输出截断会停止请求，保留已完成批次。摘要会根据内容选择重点，
  支持创作、技术和通用对话。详见[上下文压缩与恢复](docs/22-上下文压缩与恢复.md)。
- 附件：拍照、相册和文件三个入口；相册与文件支持多选，无需额外申请媒体读取权限。支持图片、纯文本、docx、PDF。
  导入的图片字节落在应用私有目录，数据库只存文件名，超大消息采用分块读取；
  实际容量受设备可用存储与内存影响。点图进入整屏查看，多图左右翻。
- **图片发送前校验**：按文件头判断真实格式，服务端不认的格式本地转码为 JPEG；坏图跳过并提示，不会让整个会话永远发不出去。
- **模型能力识别**：读取模型列表中的上下文与输出上限、图片输入和深度思考能力。中转站只返回模型 ID 时，参考内置模型资料。也可在 API 配置的「模型能力」中为所选模型手动调整上下文、识图和思考设置。
- 数据出口：JSON 备份、**ZIP 归档**（备份 + 每个会话的 Markdown 文稿）与 Markdown 文稿导出；
  支持导出纯 DeepSeek v3 格式（分支、搜索记录、读链签名、图片）；非图片附件原文、预设等完整恢复请使用 SimpleChat 原生备份。
  支持导入本应用备份、ZIP 归档、Chatbox 备份与 DeepSeek 官方导出
（`deepseek_data-*.zip`）；导入方式可选增量 / 覆盖同名 / 同名新命名。导入结果**逐项对账**（归档几条 / 进来几条 / 哪条因为什么没进来），失败条目不会静默消失。
- 多选分享：按问答组选择消息，导出长截图、分页图片或 Markdown，也可复制文本或使用系统分享。图片保留表格和数学公式。
- **置顶会话**：点击侧边栏「置顶」分组标题即可折叠或展开，关闭侧栏后仍保留展开状态。
- 搜索：标题与正文全文搜索，结果行显示命中片段，可选择是否包含非当前分支。
- 长消息：超 10 行自动收起，展开后整条可点。
- 设置：在“外观”中管理主题、主题色和字体大小，在“对话行为”中管理思考折叠和搜索范围。
- 多语言：界面、提示词与导出模板中英双语，设置内切换（跟随系统 / 简体中文 / English）；排版按语种分档（中文两端对齐，西文左对齐）。

## 不包含

- 客户端内容改写。请求原样送达所配置的服务端。
- 客户端工具调用（服务端搜索工具已支持，但不执行本地函数）、TTS 语音克隆。
- 自动备份、云同步、账号体系。
- 依赖 Navigation、Hilt、Koin、Retrofit、Markwon、material-icons-extended。

## 快速开始

### 环境要求

- JDK 17（Android Studio 自带 JBR 21 可用）
- Android SDK platform 37、build-tools 37.0.0
- Gradle 由 wrapper 自动下载

### 构建

```bash
git clone https://github.com/Pokanshuu/Simple-Chat.git
cd Simple-Chat
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

Windows 下需显式指定 JDK：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## 使用示例

1. 安装并启动。
2. 进入「设置 → API 配置」，选择服务商、填入 API Key，点击「测试连接」。
3. 返回对话页，输入消息发送。

## 配置

### API

| 项 | 说明 | 默认值 | 必填 |
|---|---|---|---|
| 服务商 | DeepSeek / OpenCode Go / 自定义 | DeepSeek | 是 |
| Base URL | OpenAI 兼容端点地址 | 随服务商 | 是 |
| 模型 | 对话模型 | `deepseek-flash` | 是 |
| API Key | 服务端密钥，本地加密保存 | 无 | 是 |

### 构建

发布签名读取 `keystore.properties`（不入库）：

```properties
storeFile=keystore/xxx.jks
storePassword=...
keyAlias=...
keyPassword=...
```

文件缺失时 `assembleRelease` **回退 debug 签名**（不是未签名包）—— 保证 clone 下来的人也能把发版流程跑通。

## 开发

```bash
./gradlew testDebugUnitTest    # 单元测试
./gradlew assembleDebug        # debug 包，包名后缀 .debug
./gradlew assembleRelease      # release 包，需签名配置
```

单元测试覆盖纯函数与协议层，无需模拟器。

## 技术选型

| 项 | 版本 |
|---|---|
| Gradle | 9.7.1 |
| AGP | 9.4.1（内置 Kotlin） |
| Kotlin | 2.4.10 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |
| JVM | 17 |
| Room | 2.8.5 |
| OkHttp | 5.5.0 |
| kotlinx.serialization | 1.11.0 |
| DataStore | 1.2.1 |

完整依赖清单见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

## 项目结构

```text
app/src/main/java/com/simplechat/app/
├─ data/          仓库层：会话粘合、备份导入导出、附件处理、Keystore 加解密、token 估算
│  └─ import/     Chatbox / DeepSeek 备份、docx 抽文本、图片尺寸
├─ db/            Room：实体、DAO、显式迁移、消息树纯函数
├─ net/           DTO、SSE 客户端与行解析、Provider 与模型能力表
└─ ui/
   ├─ chat/       对话页：消息列表、输入栏、各面板、编辑弹窗，以及纯函数（文本分块 / 请求组装 / 排序）
   ├─ history/    会话抽屉与全文搜索
   ├─ settings/   设置页（API 配置、数据管理、外观、关于）
   ├─ markdown/   Markdown 渲染与软换行修复
   ├─ common/     全应用唯一的控件与视觉 token（按钮、菜单、弹窗、图标、转场）
   └─ theme/      颜色、字体、形状
```

## 隐私与安全

- API Key 以 Android Keystore 的 AES-256-GCM 保存密文，密钥按 UID 隔离。仓库不含任何 Key。
- 聊天请求从设备直连所配置的端点。使用语音输入时，录音会交给 DeepSeek 官方 ASR 或 Android 系统语音识别服务。系统服务是否联网取决于设备配置。
- 数据库迁移使用显式 Migration，不使用 `fallbackToDestructiveMigration`。

## 文档

设计文档、实现记录与变更记录见 [`docs/README.md`](docs/README.md)，代码注释中的 `§` 编号对应这些文档。历史记录保留各版本的验证范围。当前稳定版为 v1.2.5（构建号 64）。

相关入口：

- [`TODO.md`](TODO.md)：当前状态与待办。

## 贡献

欢迎提 Issue 与 PR。请保持改动聚焦，提交前跑一遍 `./gradlew testDebugUnitTest`。

## 许可证

基于 [MIT](LICENSE) 许可证开源。




