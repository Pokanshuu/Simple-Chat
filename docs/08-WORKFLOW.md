# 08 · 工作流（构建 · 测试 · 发版）

> 本文与其余 docs 共用原《设计文档》的章节编号（如 §4.1、§15）。
> 跨文件引用照旧有效；完整文件索引见 [README](README.md)。

---

## 常用命令

需要 **JDK 17 / 21** 与 **Android SDK（platform 37）**。

```bash
./gradlew assembleDebug        # debug 包，包名后缀 .debug
./gradlew testDebugUnitTest    # 单元测试（计数以当次报告为准）
./gradlew assembleRelease      # release 包，需要签名配置
```

| 目标 | 产物 |
|---|---|
| debug | `app/build/outputs/apk/debug/SimpleChat-<版本>-debug.apk`（未混淆） |
| release | `app/build/outputs/apk/release/SimpleChat-<版本>-release.apk`（R8 优化并签名） |

> 文件名由 `app/build.gradle.kts` 的 `androidComponents.onVariants` 生成，
> 版本号来自同一处的 `appVersionName` —— 与 Release 附件同名，发版不用手动改。

发版流程（详见下方 §20）：

1. 改 `versionName` / `versionCode`（必要时同步 `10-CHANGELOG.md` 与 `../TODO.md`）。
2. `./gradlew testDebugUnitTest` 全绿。
3. `./gradlew assembleRelease`，确认签名配置就位。
4. 真机验证最容易被 R8 裁坏的三样：Markdown 渲染、代码高亮、Keystore 加解密。

---

## 构建环境

### 2.4 构建命令（本机已实测通过）

本机 PATH 中**没有 java 和 gradle**，必须显式指定 Android Studio 自带的 JBR（21）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd <仓库目录>
.\gradlew.bat assembleDebug
```

- `local.properties` 内容：`sdk.dir=<本机 SDK 路径>`（各机器不同，不入库）
- Gradle 发行版由 wrapper 自动下载到 `~/.gradle/wrapper/dists`
- 已安装 SDK 平台：`android-37.0`；build-tools：`37.0.0`

---

## 20. 发版

### 20.1 签名

| 项 | 位置 |
|---|---|
| 密钥库 | `keystore/` 下的 `.jks`（RSA 2048，有效期 30 年；**整个目录不入库**） |
| 口令 | `keystore.properties`（**不要外传、不要提交**） |

`build.gradle.kts` 里 `keystore.properties` **不存在时回落到 debug 签名** ——
保证别人 clone 下来也能跑通 `assembleRelease`，而不是卡在"缺密钥"的报错上。

> ⚠️ **密钥丢了就再也发不出同一个应用的更新。** 请单独备份这两样东西。

### 20.2 R8

`isMinifyEnabled + isShrinkResources`。历史早期产物约 2 MB；当前体积以对应 Release 附件为准。

`proguard-rules.pro` 里保留了 kotlinx.serialization 的 Companion/$$serializer
规则（反射查找序列化器）与 Room 的构造器规则。

**release 包已真机验证**：Markdown 渲染、代码语法高亮、Keystore 加解密全部正常 ——
这几样恰好是最容易被 R8 裁坏的东西。

### 20.3 图标

源图 `ICON.png`（1080×1080，**四角透明** + 圆角方形浅底 + 左上角深蓝 "SC" + 底部蓝色尾巴；源图不入库）。

| 项 | 做法 |
|---|---|
| 前景层 | 五档密度生成 `mipmap-*/ic_launcher_foreground.png`（**108 / 162 / 216 / 324 / 432**），**整幅铺满、不缩安全区** |
| 背景层 | `#FFF3F5F8` —— 取自图标本体色，与前景里的底**同色** |
| 启动图 | 底色同步 `#FFF3F5F8`，直接复用自适应图标 |
| monochrome | **不做**：前景是位图 + 品牌字，主题化后只是一坨剪影 |

**为什么铺满而不缩到 72dp 安全区**：这张图四角本来就是透明的，铺满之后
启动器蒙版裁掉的是透明区、底下是同色背景 —— 看起来是"一整块底 + 一个图形"；
缩到安全区反而会变成"一个小图形浮在大色块中央"。

> 演变过程与当时的取舍见 **§25.6**（v1.14 那次改版）。
> 本轮（v0.9.0-beta）只是换源图重新生成，约定不变。

#### ⚠️ 已知并**接受**的局限：圆形蒙版

"SC" 在左上角，实测包围盒 `x 16.2..50.6dp, y 14.4..37.2dp`，而系统只保证
**中心 66dp 圆**（半径 33dp）可见 —— SC 的左上角离画布中心 54.7dp，
所以在**圆形蒙版**的启动器（Pixel 原生 / 三星等）上会被裁掉大半。

**这个风险是明确知情的取舍**：本项目**不考虑原生启动器用户**，
圆角方形蒙版（多数第三方启动器与 AOSP）上 SC 完整可见。

要改的话有三条路（记在这里备查）：① 把 SC 挪进安全区（失去左上角构图）；
② 前景层整体缩到安全区；③ 不管。
