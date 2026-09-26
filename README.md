# SimpleChat

> 轻量、原生性能的 Android AI 对话客户端。Jetpack Compose 实现，release 经 R8 压缩后约 2.17 MB。

**中文 | [English](README.en.md)**

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/Pokanshuu/Simple-Chat/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## 简介

面向长文本连续创作的 Android 端 AI 对话客户端。接入任意 OpenAI 兼容端点，数据完全本地存储，无账号体系与云同步。

适用场景：

- 长文本续写、设定推演、角色扮演
- 多轮长会话，需要上下文占用管理与压缩
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
- 流式输出：SSE 逐字渲染，Markdown 实时渲染，定稿前后像素级一致。
- 思考区：`reasoning_content` 折叠展示，仅计真实思考时长，正文出现即收起。
- 消息树：以 `parentId` 组织。重新生成为会话内分支，切换版本时下游整段跟随，不删除历史；长按支持会话间分叉。
- 上下文管理：CJK 加权 token 估算、输入栏占用圆环、增量滚动压缩（保留最近 6 条完整消息）。
- 附件：拍照 / 相册 / 文件三个入口，均无需申请权限；支持图片、纯文本、docx、PDF。
- 数据出口：JSON 备份与 Markdown 文稿导出；支持导入 Chatbox 备份。
- 搜索：标题与正文全文搜索，结果行显示命中片段。
- 长消息：超 10 行自动收起，展开后整条可点。
- 外观：深色模式、主题色、字号倍率、跟随系统。
- 多语言：界面、提示词与导出模板中英双语，设置内切换（跟随系统 / 简体中文 / English）；排版按语种分档（中文两端对齐，西文左对齐）。

## 不包含

- 客户端内容改写。请求原样送达所配置的服务端。
- 工具调用、数学公式渲染、语音输入、TTS。
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
./gradlew testDebugUnitTest    # 单元测试（271 项）
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
│  └─ import/     Chatbox 备份、docx 抽文本、图片尺寸
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
- 请求从设备直连所配置端点，无第三方中转。
- 数据库迁移使用显式 Migration，不使用 `fallbackToDestructiveMigration`。

## 文档

设计文档、实现记录与变更记录是**内部资料，不随仓库公开**（代码注释里的 `§` 编号指的就是它们）。
仓库内公开的只有：

- [`TODO.md`](TODO.md)：当前状态与待办。

## 贡献

欢迎提 Issue 与 PR。请保持改动聚焦，提交前跑一遍 `./gradlew testDebugUnitTest`。

## 许可证

基于 [MIT](LICENSE) 许可证开源。

