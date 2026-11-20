# DeepSeek 端点切换长上下文实测

检查 Simple Chat 能否在同一段长会话中交替使用 OpenAI 兼容端和 Anthropic 兼容 A 端，同时切换 Flash / Pro，继续使用历史文字，并按目标模型发送搜索和读链接工具。

## 运行

使用 Windows PowerShell 7、可用的 JDK 与 Android SDK，并启动 Android 模拟器。把 `adb` 加入 `PATH`，将 `DEEPSEEK_API_KEY` 设置在当前进程或 Windows 用户环境中。在仓库根目录执行以下命令。

```powershell
pwsh -NoProfile -File .\scripts\test-deepseek-port-switch.ps1
```

默认使用 `emulator-5560`。如果使用其他已授权调试的设备，将该设备的序列号传给 `-Serial`。

脚本编译并安装 Debug App 和 instrumentation APK，最多发送六个付费 API 请求。它使用合成测试数据和独立的临时会话。密钥经标准输入暂存到调试应用的私有缓存，设备测试读取后立即删除，脚本退出时也会清理该文件。不要并行使用同一台设备运行该脚本。

## 测试矩阵与通过条件

六轮共用同一个会话，依次交替 `/chat/completions` 和 `/anthropic/v1/messages`。初始历史包含 20 轮合成库存数据，纯文本基线为约 27 万输入 token。Flash 使用 `high` 思考，Pro 使用 `max`。

| 轮次 | 端点与模型 | 验证项 |
|---:|---|---|
| 1 | OpenAI 兼容端 · Flash | 发送搜索工具声明，回复含结构化引用 |
| 2 | A 端 · Flash | 切端点并执行搜索，回复含非空搜索结果块 |
| 3 | OpenAI 兼容端 · Pro | 带着有搜索历史的会话切回，保留文字并省略不兼容的工具块 |
| 4 | A 端 · Flash | 发送读链接与搜索工具声明，收到成功的读链接结果块 |
| 5 | OpenAI 兼容端 · Flash | 带着读链接历史切回并搜索，回复含结构化引用 |
| 6 | A 端 · Pro | 带着搜索与读链接历史切回，保留文字并省略不兼容的工具块 |

每轮确认实际请求路径、模型、工具声明和历史工具块，逐条比对发出的 20 条原始长消息，并检查本轮只提交一次、成功收到正文且已落库。两轮 Pro 继续用已有文字历史，省略 Flash 专用工具。`lookupMatched` 记录模型是否复述首条消息的校验码，历史是否完整以实际请求中的逐条比对判定。

成功时输出 `ALL DEVICE REGRESSIONS PASSED`。模型是否调用服务端工具存在波动。测试输出会记录引用、搜索结果、读链接结果和签名状态，便于定位工具执行或回包差异。

## 2026-11-20 实测结果

代码基于 `fc18d49`，包含当时工作区的跨会话记忆改动。Android 模拟器 `emulator-5560` 上，六轮均 HTTP 200，逐条比对全部通过，也都正确复述了首条消息里的校验码。请求体约 718–724 KB。表中的输入 token 为服务端返回的 usage。

| 轮次 | 输入 token | 结果 |
|---:|---:|---|
| 1 | 1,110,686 | 1 条搜索引用 |
| 2 | 827,221 | 搜索结果块成功，4 条引用 |
| 3 | 272,990 | Pro 继续回复，未发送工具声明或旧工具块 |
| 4 | 822,584 | 读链接结果块成功，1 条引用 |
| 5 | 826,160 | 切回 OpenAI 兼容端，仍返回 1 条搜索引用 |
| 6 | 273,515 | 切回 A 端后 Pro 继续回复，未发送工具声明或旧工具块 |

开启工具的轮次报告了更高的服务端 usage。实际 HTTP 请求里逐条保留了同一套长历史，对比历史是否完整时应看请求内容。A 端这次返回的读取结果未带签名，带签名的历史重放由现有请求构建器单测覆盖。

测试实现在 [DeepSeekLiveRegression.kt](../app/src/androidTest/java/com/simplechat/app/DeepSeekLiveRegression.kt)，由 [DeviceRegression](../app/src/androidTest/java/com/simplechat/app/DeviceRegression.kt) 启动。
