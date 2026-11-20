package com.simplechat.app.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepSeek 官方余额查询。
 *
 * 只有官方端点（`api.deepseek.com`）提供 `GET /user/balance`；其它 OpenAI 兼容端点没有这个
 * 接口，请求会 404。所以界面先判断 [isOfficialDeepSeekEndpoint]，只对官方端点查询，
 * 其余端点显示「当前端点无余额接口」而不是报错。
 *
 * ### 为什么单独一个客户端
 *
 * 对话用的客户端 `readTimeout = 0`，是给流式响应准备的；余额查询必须有超时，
 * 否则网络半死不活时「刷新余额」会一直转圈（与 [UpdateChecker] 同一处理）。
 */
object DeepSeekBalance {

    /** 官方 `/user/balance` 响应。 */
    @Serializable
    data class Snapshot(
        @SerialName("is_available") val isAvailable: Boolean = false,
        @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList(),
    ) {
        /** 主币种（通常只有 CNY 一条）。 */
        val primary: BalanceInfo? get() = balanceInfos.firstOrNull()
    }

    @Serializable
    data class BalanceInfo(
        val currency: String = "CNY",
        @SerialName("total_balance") val totalBalance: String = "0.00",
        @SerialName("granted_balance") val grantedBalance: String = "0.00",
        @SerialName("topped_up_balance") val toppedUpBalance: String = "0.00",
    )

    /** 查询结果。 */
    sealed interface Result {
        /** 查到了。 */
        data class Ok(val snapshot: Snapshot) : Result

        /** 没查到：网络、鉴权、响应结构都能落到这里，[reason] 只用于日志与兜底文案。 */
        data class Failed(val reason: String) : Result
    }

    private val client: OkHttpClient by lazy {
        ChatApi.defaultClient().newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 查询余额。
     *
     * [baseUrl] 允许带 `/v1` 后缀（有的自定义端点这么填），拼路径前先剥掉，
     * 避免出现 `/v1/user/balance`。查询失败不重试：一次失败就是一次失败。
     */
    suspend fun fetch(baseUrl: String, apiKey: String): Result = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        val url = root.let { if (it.endsWith("/v1")) it.removeSuffix("/v1") else it } + "/user/balance"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Failed("http ${response.code}")
                }
                val body = response.body.string()
                Result.Ok(json.decodeFromString<Snapshot>(body))
            }
        } catch (t: Throwable) {
            Result.Failed(t.message ?: t::class.simpleName ?: "unknown")
        }
    }
}
