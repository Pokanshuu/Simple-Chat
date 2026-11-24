# SimpleChat v1.2.6-beta.2

预发布 · Android 构建号 66 · 2026 年 11 月 24 日

## 抽屉

- 修复抽屉面板圆角外侧出现的一块亮角：压暗层改为沿面板形状挖空，圆角、拖动偏移与搜索态收角都跟随面板本身。
- 受影响的只有抽屉边缘的视觉，交互、遮罩点击关闭和面板宽度均未改变。

## 宽屏与平板

- 常驻侧栏与聊天区改用同一张按整个窗口适配的壁纸，各自只绘制自己那一段，接缝处不再出现两段缩放不同的画面。
- 壁纸取景计入左右安全区内边距，带刘海或挖孔的设备横向遮挡下接缝仍然对齐。
- 常驻侧栏优先使用当前会话的自定义壁纸，与右侧聊天内容保持一致；此前固定使用全局壁纸。

## 下载与校验

下载签名 APK 即可覆盖安装同签名的旧版（1.2.5 及更早）。升级前可以导出一份 SimpleChat 原生备份。

- [SimpleChat-1.2.6-beta.2-release.apk](https://github.com/Pokanshuu/Simple-Chat/releases/download/v1.2.6-beta.2/SimpleChat-1.2.6-beta.2-release.apk)（5.0 MB，R8 压缩，minSdk 26）
- SHA-256 `e31dc84d4e284181bce5e71282ea99e451c150ca6a1ec62b1ec56ccbc76e3ece`。[下载校验文件](https://github.com/Pokanshuu/Simple-Chat/releases/download/v1.2.6-beta.2/SimpleChat-1.2.6-beta.2-release.apk.sha256)

## 验收

- Debug 单测 897 项，885 项通过、12 项跳过、0 项失败。
- 签名 R8 包覆盖安装 1.2.5 后冷启动正常，抽屉圆角与横向双栏壁纸在包内复验通过。
- Android 12（实时模糊）与 Android 10（亚克力降级）模拟器：竖屏抽屉、搜索态撑满、横屏双栏布局。

[查看自 v1.2.6-beta.1 以来的完整提交](https://github.com/Pokanshuu/Simple-Chat/compare/v1.2.6-beta.1...v1.2.6-beta.2)
