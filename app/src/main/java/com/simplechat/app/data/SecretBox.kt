package com.simplechat.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小段敏感数据的加密盒（当前只服务 API Key）。
 *
 * **为什么自己写而不用 `androidx.security:security-crypto`**：
 * 那个库（`EncryptedSharedPreferences`）已经停止维护，且它是"把整份
 * SharedPreferences 加密"，而我们只需要保护**一个字符串** ——
 * 用它等于为一行数据引入一套存储层。直接用 Keystore 反而更短、更透明。
 *
 * 方案：Keystore 里生成一个**不可导出**的 AES-256 密钥，用它做 AES-GCM 加密。
 * 密钥永不离开 Keystore（连 root 也导不出），密文里带上 GCM 的随机 IV。
 *
 * ```
 * 存储格式:  base64( IV(12B) || 密文 || GCM Tag(16B) )
 * ```
 *
 * 密钥丢了（用户清除应用数据 / 换机）→ 密文解不开 → [decrypt] 返回空串，
 * 用户重新填一次 Key 即可。**不会崩，也不会把密文当 Key 发出去**。
 */
internal object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "simplechat.api_key.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM 认证标签长度（位）。 */
    private const val TAG_BITS = 128

    /** GCM 推荐 IV 长度（字节），Android Keystore 默认也是 12。 */
    private const val IV_BYTES = 12

    /**
     * 上次解密的结果缓存。
     *
     * `SettingsStore.flow` 每次配置变化都会重读一遍，而 Keystore 调用要过 Binder，
     * 比纯内存读贵几个数量级。密文没变就没必要再解一次。
     */
    @Volatile
    private var cachedCipherText: String? = null

    @Volatile
    private var cachedPlainText: String = ""

    /** 加密。失败返回 null（此时调用方**不应**回落成明文存储）。 */
    fun encrypt(plainText: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // IV 由 Keystore 每次随机生成，随密文一起存
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    /** 解密。解不开（密钥失效 / 数据损坏）返回空串，绝不抛。 */
    fun decrypt(cipherText: String): String {
        if (cipherText == cachedCipherText) return cachedPlainText

        val plain = runCatching {
            val raw = Base64.decode(cipherText, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return@runCatching ""

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrDefault("")

        cachedCipherText = cipherText
        cachedPlainText = plain
        return plain
    }

    /** Keystore 里的密钥，没有就现生成一个。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：要求的话每次发请求前都得弹指纹，
                // 对一个"点开就能写"的 App 是灾难
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
