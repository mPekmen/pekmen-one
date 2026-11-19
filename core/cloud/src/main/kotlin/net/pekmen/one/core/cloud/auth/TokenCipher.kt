package net.pekmen.one.core.cloud.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric authenticated encryption contract for token persistence.
 * Production uses AndroidKeyStore AES-GCM; JVM/Robolectric tests inject an in-memory AES-GCM cipher.
 */
interface TokenCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

class AndroidKeystoreTokenCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : TokenCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        require(iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(HEADER_BYTES + iv.size + encrypted.size).apply {
            this[0] = FORMAT_VERSION
            this[1] = iv.size.toByte()
            iv.copyInto(this, destinationOffset = HEADER_BYTES)
            encrypted.copyInto(this, destinationOffset = HEADER_BYTES + iv.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > HEADER_BYTES + MIN_IV_BYTES) { "Encrypted payload too short" }
        require(ciphertext[0] == FORMAT_VERSION) { "Unsupported cipher payload version: ${ciphertext[0]}" }
        val ivLength = ciphertext[1].toInt() and UNSIGNED_BYTE_MASK
        require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid GCM IV length: $ivLength" }
        val payloadOffset = HEADER_BYTES + ivLength
        require(ciphertext.size > payloadOffset) { "Missing GCM ciphertext body" }
        val iv = ciphertext.copyOfRange(HEADER_BYTES, payloadOffset)
        val encryptedBody = ciphertext.copyOfRange(payloadOffset, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encryptedBody)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "pekmen_one_cloud_auth_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/" +
            "${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 0x01
        private const val HEADER_BYTES = 2
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 16
        private const val UNSIGNED_BYTE_MASK = 0xFF
    }
}
