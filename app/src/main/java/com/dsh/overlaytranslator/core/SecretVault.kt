package com.dsh.overlaytranslator.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 加密 API Key 之後才寫進 DataStore。
 *
 * 為什麼不用 androidx.security:security-crypto 的 EncryptedSharedPreferences？
 * 該函式庫已被 Google 標記為棄用（deprecated）且不再積極維護，
 * 而我們需要的功能（AES-GCM + 硬體金鑰）用 Keystore 直接做只要幾十行，
 * 也少一個依賴。缺點是金鑰無法備份——換手機或刪除 App 後必須重新輸入 API Key。
 */
object SecretVault {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "overlay_translator_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 回傳 Base64(IV || 密文)；輸入空字串時回傳空字串。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(body, 0, combined, iv.size, body.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (t: Throwable) {
            AppLog.e("SecretVault", "加密 API Key 失敗", t)
            ""
        }
    }

    /** 解密失敗（例如換手機、Keystore 金鑰遺失）時回傳空字串，讓使用者重新輸入。 */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return ""
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val body = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.e("SecretVault", "無法解密已儲存的 API Key，請在設定中重新輸入", t)
            ""
        }
    }
}
