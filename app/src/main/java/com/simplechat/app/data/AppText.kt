package com.simplechat.app.data

import android.app.Application
import androidx.annotation.BoolRes
import androidx.annotation.StringRes
import com.simplechat.app.R
import com.simplechat.app.net.isRawNetworkError
import com.simplechat.app.net.networkFailureRes
import com.simplechat.app.net.serverErrorText

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

    /** 读布尔资源（目前只有 `ui_is_cjk`：界面语言是不是中文）。 */
    fun bool(@BoolRes resId: Int): Boolean {
        val app = checkNotNull(app) { "Res 还没 init —— App.onCreate 里漏了 Res.init(this)" }
        return LocaleHelper.localizedContext(app).resources.getBoolean(resId)
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
 * - [AppTextException] 走翻译；
 * - **网络类异常**（`IOException` / `SocketException` / 超时 / 断网 / 流被重置）
 *   翻成人话。它们的 `message` 是 OkHttp 或系统 socket 的英文原文：
 *   `Software caused connection abort`、`stream was reset: INTERNAL_ERROR` ——
 *   用户既看不懂，也不知道该不该重试（真机截图反馈，v1.1.3 仍是原文上屏）；
 * - 其它异常保留 `message`：那多半是我们自己的解析/断言错误，排查时要原文。
 */
fun Throwable.userMessage(): String = when (this) {
    is AppTextException -> text.resolve()
    /*
     * OkHttp 对非法请求头抛的是 `IllegalArgumentException`：
     * `Unexpected char 0x4f55 at 8 in Authorization value`。
     * 实际原因几乎总是 API Key 里粘进了中文 / 全角字符 / 换行 —— 见 `ApiKey`。
     * 这里兜一层：请求已经发出去才发现的情况（比如旧配置）也不再上屏一串码点。
     */
    is IllegalArgumentException ->
        if (message?.contains("Authorization", ignoreCase = true) == true) {
            Res.get(R.string.error_api_key_chars)
        } else {
            message?.takeIf { it.isNotBlank() } ?: Res.get(R.string.error_unknown)
        }

    is java.io.IOException -> Res.get(networkFailureRes(this))
    else -> message?.takeIf { it.isNotBlank() } ?: Res.get(R.string.error_unknown)
}

/**
 * **历史消息里存着的**错误文案 → 人话。
 *
 * v1.1.3 及以前，[userMessage] 会把异常原文直接落库，于是旧会话里躺着
 * `Software caused connection abort`、`stream was reset: INTERNAL_ERROR` 这类英文。
 * 新版本不再产生它们，但**旧数据还在** —— 读回来时顺手翻一下：
 * 只改显示、不改库（万一以后要排查，原文仍在库里）。
 */
fun storedErrorText(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return text
    if (isRawNetworkError(text)) return Res.get(R.string.error_network_interrupted)
    // 服务商侧原文（`No available channel for model …` 等）也一并翻；认不出原样返回
    return serverErrorText(text)
}
