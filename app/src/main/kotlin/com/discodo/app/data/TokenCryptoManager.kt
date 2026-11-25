package com.discodo.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts sensitive strings (the Discord token) using an AES-256-GCM key
 * stored exclusively inside AndroidKeyStore.
 */
class TokenCryptoManager {

  fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

    val payload = ByteArray(1 + iv.size + ciphertext.size)
    payload[0] = iv.size.toByte()
    System.arraycopy(iv, 0, payload, 1, iv.size)
    System.arraycopy(ciphertext, 0, payload, 1 + iv.size, ciphertext.size)
    return Base64.encodeToString(payload, Base64.NO_WRAP)
  }

  fun decrypt(encoded: String): String? {
    return runCatching {
      val payload = Base64.decode(encoded, Base64.NO_WRAP)
      if (payload.size < 2) return null
      val ivLength = payload[0].toInt() and 0xFF
      if (ivLength <= 0 || payload.size <= 1 + ivLength) return null

      val iv = payload.copyOfRange(1, 1 + ivLength)
      val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)

      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
      String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "discodo_discord_token_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
  }
}
