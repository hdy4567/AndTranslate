package com.viitor.security

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ★ AES256Encryptor: 암호화/복호화
 */
class AES256Encryptor {
    private val transformation = "AES/CBC/PKCS5Padding"
    private val algorithm = "AES"

    fun encrypt(data: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray().copyOf(32), algorithm)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(ByteArray(16)))
        val encrypted = cipher.doFinal(data.toByteArray())
        return Base64.encodeToString(encrypted, Base64.DEFAULT)
    }

    fun decrypt(encrypted: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray().copyOf(32), algorithm)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ByteArray(16)))
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        return String(cipher.doFinal(decoded))
    }
}
