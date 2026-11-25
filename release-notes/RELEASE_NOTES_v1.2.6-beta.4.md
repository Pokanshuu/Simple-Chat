# SimpleChat v1.2.6-beta.4

2026 年 11 月 25 日 · Android 预发布 · 构建号 68

此版本改进联网工具的稳定性，并减少发送长对话和图片时的准备工作。

## 改进和修复

- **联网搜索与工具。** 改进兼容服务的工具调用。遇到过长参数或重复调用时，应用会跳过受影响的调用，当前回复可以继续生成。续写会保留已有回复的开头。
- **消息发送。** 减少发送长对话和图片时重复准备请求、读取附件的开销。使用官方 Claude API 时，应用会为可复用的对话内容设置缓存断点。
- **聊天界面。** 输入面板展开和收起时过渡更平滑。聊天中会标出上下文压缩的位置，方便确认哪些较早的消息已被摘要代替。
- **备份与恢复。** JSON 和 ZIP 备份会保留有效的上下文摘要，恢复会话后可以继续使用这些摘要。

## 已知问题

- 在发送前极短时间内反复切换 Flash 和 Pro，并立即发送，实际请求可能使用上一个模型。正常逐轮切换已通过组合测试。

## 下载

签名 APK 支持 Android 8.0 及以上版本，可覆盖安装使用同一签名的旧版。

- [下载 APK](https://github.com/Pokanshuu/Simple-Chat/releases/download/v1.2.6-beta.4/SimpleChat-1.2.6-beta.4-release.apk)（5.0 MB）
- [下载 SHA-256 校验文件](https://github.com/Pokanshuu/Simple-Chat/releases/download/v1.2.6-beta.4/SimpleChat-1.2.6-beta.4-release.apk.sha256)
- SHA-256 `a7fec3c189a18c91ad7a5b31b771a1027ee4df243786790fe572f968433a0e54`

## 验证

- 全量 JVM 单测 920 项通过、13 项跳过、0 项失败。
- 模拟器完成 17 轮正常切换，覆盖 16 种有序转换。DeepSeek 官方模型完成 8 轮验证，Flash 使用原生搜索，Pro 使用模拟独立搜索后端。
- 签名 APK 在模拟器覆盖安装 beta.2 后冷启动通过。

[查看自 v1.2.6-beta.3 以来的完整改动](https://github.com/Pokanshuu/Simple-Chat/compare/v1.2.6-beta.3...v1.2.6-beta.4)
