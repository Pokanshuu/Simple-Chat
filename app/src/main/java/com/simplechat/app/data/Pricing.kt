package com.simplechat.app.data

/**
 * 费用估算。
 *
 * 价格用 DeepSeek 官方 2026 年公布的人民币价（每百万 token）：
 * 输入命中缓存 ¥0.50、未命中 ¥2.00、输出 ¥8.00。
 *
 * **只是估算**：换厂商、官方调价、或网关另计费时都会偏。UI 上必须写明，
 * 不能让用户以为这是账单金额。
 */
object Pricing {

    const val INPUT_CACHE_HIT_PER_MILLION = 0.50
    const val INPUT_CACHE_MISS_PER_MILLION = 2.00
    const val OUTPUT_PER_MILLION = 8.00

    /**
     * 用量的估算费用（元）。
     *
     * [cacheHitTokens] 为 null（服务端没报缓存字段）时全部输入按未命中价算 ——
     * 宁可估高，也不假装知道哪部分命中了。
     */
    fun cost(promptTokens: Int, completionTokens: Int, cacheHitTokens: Int?): Double {
        val prompt = promptTokens.coerceAtLeast(0)
        val hit = (cacheHitTokens ?: 0).coerceIn(0, prompt)
        val miss = prompt - hit
        return hit / 1_000_000.0 * INPUT_CACHE_HIT_PER_MILLION +
            miss / 1_000_000.0 * INPUT_CACHE_MISS_PER_MILLION +
            completionTokens.coerceAtLeast(0) / 1_000_000.0 * OUTPUT_PER_MILLION
    }

    /** 金额文案：不足一分钱时多给两位小数，免得一堆 ¥0.00。 */
    fun format(yuan: Double): String =
        if (yuan > 0 && yuan < 0.01) String.format("¥%.4f", yuan) else String.format("¥%.2f", yuan)
}
