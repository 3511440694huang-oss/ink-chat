package com.ink.chat.data.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore（AES-GCM）的密钥管理（§4.4 API Key 安全）。
 * 移植自 novel-agent；密钥丢失（如恢复出厂）→ getOrCreateKey 重新生成，
 * 密文无法解密时返回 null，由上层引导用户重输 API Key。
 */
object CryptoManager {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ink_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密字符串 → base64(iv + ciphertext)；空串加密为空串 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** 解密 base64(iv + ciphertext)。密钥失效/损坏返回 null，由上层引导重输 */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_SIZE)
            val ct = raw.copyOfRange(IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}