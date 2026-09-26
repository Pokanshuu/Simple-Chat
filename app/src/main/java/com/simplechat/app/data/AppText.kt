package com.simplechat.app.data

import android.app.Application
import androidx.annotation.StringRes
import com.simplechat.app.R

/**
 * 非 composable 侧的取词入口。
 *
 * composable 里一律用 `stringResource(...)`；ViewModel、数据层要拼**用户看得见**
 * 的文字时才用这里。它走 Application 的 Resources —— 而 Application 的 base context
 * 已经被 [LocaleHelper] 按语言设置包过一层，所以取到的就是当前语言的文案。
 *
 * 必须在 `App.onCreate` 里 [init] 一次。
 *
 * ⚠️ **纯 JVM 单测不要走到这里**（没有 Android Resources，会直接抛）。
 * 被单测覆盖的纯函数只有两条正路：
 * 1. 把文案当**参数**收进来（见 `derivedTitle` 的 `fallback`）；
 * 2. 抛 [AppTextException]，把"怎么说"留给界面层（见 `ExternalBackup`）。
 */
object Res {

    @Volatile
    private var app: Application? = null

    fun init(application: Application) {
        app = application
    }

    fun get(@StringRes resId: Int, vararg args: Any): String {
        val app = checkNotNull(app) { "Res 还没 init —— App.onCreate 里漏了 Res.init(this)" }
        // 必须经 LocaleHelper 现算：Application 的 resources 是启动时冻结的，
        // 换语言后不换它的话，取到的永远是旧语言（见 localizedContext 的说明）
        val context = LocaleHelper.localizedContext(app)
        return if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }
}

/**
 * 一条**待翻译**的文案：数据层只带资源 ID 与参数，到界面层才落成具体语言的文字。
 *
 * 为什么不让数据层直接吐 String：导入/解析这类逻辑是纯函数、有单测，
 * 一旦在里面 `getString`，单测就得整个搬去真机 —— 而错误文案恰恰是
 * 最需要钉住的行为。带 ID 抛上来，翻译与测试各得其所。
 */
data class AppText(@StringRes val resId: Int, val args: List<Any> = emptyList()) {

    /** 落成当前语言的句子。 */
    fun resolve(): String = Res.get(resId, *args.toTypedArray())

    companion object {
        fun of(@StringRes resId: Int, vararg args: Any): AppText =
            AppText(resId, args.toList())
    }
}

/**
 * 带翻译的报错：数据层不碰资源，把 ID 抛上来，界面层决定怎么说。
 *
 * 继承 [IllegalArgumentException] 只为兼容 `require { }` 那类用法的调用方；
 * 语义上就是"这份输入有问题 / 这个操作做不成"。
 */
class AppTextException(val text: AppText) : IllegalArgumentException("AppText(#${text.resId})") {

    constructor(@StringRes resId: Int, vararg args: Any) : this(AppText(resId, args.toList()))
}

/**
 * 任何异常 → 一句能直接给用户看的话。
 *
 * [AppTextException] 走翻译；其余异常（OkHttp、IO、JSON……）的 `message`
 * 本来就是英文系统文案，原样透出，总比空着强。
 */
fun Throwable.userMessage(): String = when (this) {
    is AppTextException -> text.resolve()
    else -> message?.takeIf { it.isNotBlank() } ?: Res.get(R.string.error_unknown)
}
