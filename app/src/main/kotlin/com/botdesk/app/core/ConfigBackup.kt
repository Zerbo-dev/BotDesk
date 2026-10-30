package com.botdesk.app.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Export/import chiffré de la configuration locale (jamais de session Telegram, PIN ou clés API). */
object ConfigBackup {
    private val MAGIC = "BDCFG1".toByteArray(Charsets.US_ASCII)

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 200_000, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(data: ByteArray, password: CharArray): String {
        require(data.size > MAGIC.size + 28 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Fichier de configuration invalide"
        }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = data.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val ct = data.copyOfRange(MAGIC.size + 28, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
