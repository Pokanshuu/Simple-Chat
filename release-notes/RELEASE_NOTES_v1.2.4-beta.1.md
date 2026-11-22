# SimpleChat v1.2.4-beta.1

本次预发布新增独立生图 API、聊天模型的生图工具调用，以及会话内的生图交互，并修复跨对话记忆检索可能耗尽内存导致闪退的问题。

## 生图

- 在「设置 > API > 独立工具 API > 生图 API」按 API 配置档案保存生图模型、连接地址、尺寸和凭证。API Key 沿用加密存储；可复用当前聊天 Key，也可单独设置。
- 独立生图适配器包括 OpenAI Images、Gemini 与 Qwen/DashScope。Gemini 同时支持 generateContent 与 Interactions API；Interactions 路由优先用于 Nano Banana 模型。自定义模型 ID 可填写，不假定所有兼容端点具有相同返回格式。
- 在聊天页的「＋ > 生成图片」输入画面描述并提交。设置了独立 API 时，聊天模型可通过 OpenAI-compatible function calling 或 Anthropic-compatible tool_use 请求 generate_image。工具响应完成后，应用调用对应生图 API，并将图片挂到当前助手回复。
- 已识别的 Gemini 原生图像模型可在关闭独立 API 时直接从当前聊天连接生成。Responses 协议可启用原生 image_generation 工具；OpenAI 官方 Responses 路径使用其默认生图模型，其他兼容端点沿用服务端默认值。
- 等待态显示带节奏的点阵动画；支持停止、错误重试、同一提示词新建助手版本和图片查看。生成图片保存为会话附件，数据库升级至 v11；备份会保留生图消息标记。

## 修复

- **跨对话记忆内存溢出（OOM）**：检索历史时限制扫描的会话数、分支深度、消息数和单条文本长度，只读取匹配所需的文本片段，避免大量或超长历史消息在检索时耗尽内存。既有聊天记录会完整保留。

## 验证

- `:app:testDebugUnitTest` 通过（800 项通过，10 项跳过）。覆盖 OpenAI-compatible tool call、Anthropic-compatible tool use、Gemini inline image、Interactions、DashScope 和 Responses 工具结果解析。
- :app:assembleRelease：通过；Release APK 使用本机 release keystore 签名并通过 APK 签名校验。
- 签名包已在 Android 模拟器安装并启动。聊天中的生图入口和配置跳转已在 Debug 包验证。第三方服务真实出图仍待联调。

## 版本与安装包

- Android 版本：1.2.4-beta.1，构建号 44。
- APK：SimpleChat-1.2.4-beta.1-release.apk。
- APK 的 SHA-256 为 `73a4b0efbcff7e8053d150453ff0acefc35d36c58be5311ddaa7e40e4698594d`。
- 这是预发布版本；稳定版仍为 v1.2.3。
