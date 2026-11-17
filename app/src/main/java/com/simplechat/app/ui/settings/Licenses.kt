package com.simplechat.app.ui.settings

import androidx.compose.runtime.Immutable
import com.simplechat.app.R

/**
 * 第三方组件的**版权与许可清单**。
 *
 * 合规上每个条目要能回答五件事（缺一样都不完整）：
 * 1. 项目名称与版本号；2. 许可证类型；3. 版权所有；4. 官方链接；5. 许可证全文。
 *
 * 两处刻意：
 * - **版本号与 `gradle/libs.versions.toml` 对齐**：升级依赖时这张表也要跟着改，
 *   否则清单会变成"写着 1.0 其实跑的 2.0"的假账。升级时顺手改这里（源码里有 TODO 注释提醒）。
 * - **许可证全文给的是规范地址**（apache.org / opensource.org），不是抄一份正文：
 *   正文几千字，抄错一个字就是错的法律文本；给官方地址反而永远是对的。
 *
 * 只列**随包分发**的组件。JUnit 只在测试期存在，不进 APK，因此不在此列。
 */
@Immutable
data class LicenseEntry(
    val name: String,
    /** 与 `libs.versions.toml` 一致的版本号；BOM 管理的写 BOM 版本。 */
    val version: String,
    /** SPDX 标识（`Apache-2.0` / `MIT` / `ISC`）。 */
    val license: String,
    /** 版权所有方声明，按上游 LICENSE 文件的写法。 */
    val copyright: String,
    /** 项目官方链接。 */
    val projectUrl: String,
) {
    /** 许可证全文地址（SPDX 标识 → 官方文本）。 */
    val licenseUrl: String get() = licenseTextUrl(license)
}

/** SPDX 标识 → 许可证全文地址。 */
internal fun licenseTextUrl(license: String): String = when (license) {
    "Apache-2.0" -> "https://www.apache.org/licenses/LICENSE-2.0"
    "MIT" -> "https://opensource.org/license/mit"
    "ISC" -> "https://opensource.org/license/isc"
    else -> "https://spdx.org/licenses/"
}

/** 本应用自己的许可与版权。 */
internal const val APP_LICENSE = "MIT"
internal const val APP_COPYRIGHT = "Copyright © 2026 Pokanshu"

/**
 * 参考实现（**未随包分发代码**，仅作署名）。
 *
 * 只留 DeepSeek Chat Demo，并写明**参考的是哪一部分**（聊天记录导入），
 * 避免笼统的「参考了某项目」变成模糊的版权主张。
 */
internal val referenceCredits: List<Int> = listOf(
    R.string.credit_deepseek_demo,
)

/**
 * 随包分发的第三方组件。
 *
 * ⚠️ 升级依赖时同步这里（版本号 + 若有改名）。
 */
internal val thirdPartyLicenses: List<LicenseEntry> = listOf(
    LicenseEntry(
        name = "AndroidX Core / SplashScreen / Activity / Lifecycle",
        version = "1.19.0 / 1.2.0 / 1.13.0 / 2.11.0",
        license = "Apache-2.0",
        copyright = "Copyright © The Android Open Source Project",
        projectUrl = "https://developer.android.com/jetpack/androidx",
    ),
    LicenseEntry(
        name = "Jetpack Compose (BOM)",
        version = "2026.09.00",
        license = "Apache-2.0",
        copyright = "Copyright © The Android Open Source Project",
        projectUrl = "https://developer.android.com/jetpack/compose",
    ),
    LicenseEntry(
        name = "Room / DataStore",
        version = "2.8.5 / 1.2.1",
        license = "Apache-2.0",
        copyright = "Copyright © The Android Open Source Project",
        projectUrl = "https://developer.android.com/jetpack/androidx",
    ),
    LicenseEntry(
        name = "OkHttp",
        version = "5.5.0",
        license = "Apache-2.0",
        copyright = "Copyright © 2019 Square, Inc.",
        projectUrl = "https://square.github.io/okhttp/",
    ),
    LicenseEntry(
        name = "kotlinx.serialization",
        version = "1.11.0",
        license = "Apache-2.0",
        copyright = "Copyright © 2017-2025 JetBrains s.r.o.",
        projectUrl = "https://github.com/Kotlin/kotlinx.serialization",
    ),
    LicenseEntry(
        name = "Kotlin Coroutines",
        version = "1.11.0",
        license = "Apache-2.0",
        copyright = "Copyright © 2016-2025 JetBrains s.r.o.",
        projectUrl = "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    LicenseEntry(
        name = "multiplatform-markdown-renderer",
        version = "0.45.0",
        license = "Apache-2.0",
        copyright = "Copyright © 2022 Mike Penz",
        projectUrl = "https://github.com/mikepenz/multiplatform-markdown-renderer",
    ),
    LicenseEntry(
        name = "JetBrains Markdown",
        version = "0.7.9",
        license = "Apache-2.0",
        copyright = "Copyright © 2018-2025 JetBrains s.r.o.",
        projectUrl = "https://github.com/JetBrains/markdown",
    ),
    LicenseEntry(
        name = "Highlights（代码高亮）",
        version = "1.1.0",
        license = "Apache-2.0",
        copyright = "Copyright © 2023 dev.snipme",
        projectUrl = "https://github.com/dev-snipme/highlights",
    ),
    LicenseEntry(
        name = "latex-renderer",
        version = "1.7.3",
        license = "MIT",
        copyright = "Copyright © 2024 huarangmeng",
        projectUrl = "https://github.com/huarangmeng/latex",
    ),
    LicenseEntry(
        name = "KaTeX fonts（随 latex-fonts-katex 分发）",
        version = "1.7.3",
        license = "MIT",
        copyright = "Copyright © 2013-2020 Khan Academy",
        projectUrl = "https://katex.org",
    ),
    LicenseEntry(
        name = "Lucide（图标）",
        version = "ISC",
        license = "ISC",
        copyright = "Copyright © 2022 Lucide Contributors",
        projectUrl = "https://lucide.dev",
    ),
    LicenseEntry(
        name = "Mermaid（图表脚本）",
        version = "11.17.2",
        license = "MIT",
        copyright = "Copyright © 2014-2024 Knut Sveidqvist",
        projectUrl = "https://mermaid.js.org",
    ),
)
