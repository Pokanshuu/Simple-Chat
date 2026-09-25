package com.simplechat.app.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 语言档位。持久化的是枚举名（`languageId`），认不出来（回滚旧版本后被改坏之类）
 * 回落 [SYSTEM]。
 */
enum class AppLanguage {
    /** 跟随系统：中文系统跟中文，其余一律英文（只做中英两档，没档位可回落时用英文）。 */
    SYSTEM,

    /** 简体中文。 */
    ZH,

    /** English。 */
    EN,
    ;

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

/**
 * 语言的落地方式：**给 context 的配置换 locale**，不做 per-app locale 那套。
 *
 * 两个入口都要包：
 * - `App.attachBaseContext` —— 数据层 / ViewModel 通过 [Res] 取词走它；
 * - `MainActivity.attachBaseContext` —— composable 的 `stringResource` 走它。
 *
 * 换语言 = 改缓存 + `Activity.recreate()`，重建后两端自然都是新语言。
 * 同步读一次 DataStore 是这里唯一的取舍：`attachBaseContext` 早于一切异步入口，
 * 而这个值只有一行，阻塞读在毫秒级，换来"不用双份存储"。
 */
object LocaleHelper {

    /** attachBaseContext 时实际应用的档位；设置里改语言后由 [onLanguageChanged] 更新。 */
    @Volatile
    private var cached: AppLanguage? = null

    /** 当前生效的档位（缓存，未读过则从 DataStore 同步读一次）。 */
    fun currentLanguage(context: Context): AppLanguage =
        cached ?: readStoredLanguageSync(context).also { cached = it }

    /** 设置页换了语言：先改缓存，随后 `recreate()` 让两个 context 都重包一遍。 */
    fun onLanguageChanged(language: AppLanguage) {
        cached = language
    }

    /** 给 [base] 包一层当前语言的配置。档位不需要改配置时原样返回。 */
    fun wrap(base: Context): Context {
        val language = currentLanguage(base)
        val locale = resolveLocale(language, base.resources.configuration.locales[0]) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * 这个 context 的界面语言是不是中文档 —— 排版按它分档（§多语言 4）：
     * 中文两端对齐，西文左对齐。
     *
     * 取的是**配置里的 locale**，不是 `Locale.getDefault()`：
     * 前者跟着 [wrap] 走，后者永远是系统的，设置里选了 English 也不动。
     */
    fun isCjkUi(context: Context): Boolean =
        context.resources.configuration.locales[0].language == "zh"

    /**
     * 档位 → 实际 locale。返回 null 表示"不用动系统的"。
     *
     * [AppLanguage.SYSTEM] 的语义：**中文系统跟中文（默认资源就是中文，原样即可），
     * 其余系统一律英文** —— 只有中英两套文案，与其让法语系统掉进中文，
     * 不如明确落到英文，用户还能在设置里改。
     */
    private fun resolveLocale(language: AppLanguage, system: Locale): Locale? = when (language) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.ENGLISH
        AppLanguage.SYSTEM -> if (system.language == "zh") null else Locale.ENGLISH
    }
}
