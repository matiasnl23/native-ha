package com.matiasnl.hakiosk.data.ha.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM cipher whose key lives in the Android Keystore and never leaves it.
 * Ciphertext format: `v1:<base64 iv>:<base64 ciphertext+tag>`.
 */
class KeystoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : TokenCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$VERSION:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(encrypted)}"
    }

    override fun decrypt(ciphertext: String): String {
        val parts = ciphertext.split(':')
        if (parts.size != 3 || parts[0] != VERSION) {
            throw GeneralSecurityException("Unsupported ciphertext format")
        }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        val encrypted = decoder.decode(parts[2])
        val key = existingKey() ?: throw GeneralSecurityException("Encryption key not found")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as? SecretKey

    @Synchronized
    private fun getOrCreateKey(): SecretKey = existingKey() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "ha_kiosk_token_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION = "v1"
    }
}
