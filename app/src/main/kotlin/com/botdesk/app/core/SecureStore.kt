package com.botdesk.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage de secrets chiffrés (AES-GCM, clé non exportable dans l'Android Keystore). */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("botdesk_secure", Context.MODE_PRIVATE)
    private val alias = "botdesk_master_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun putString(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, enc(c.iv) + ":" + enc(ct)).apply()
    }

    fun getString(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return try {
            val parts = raw.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec(parts[0])))
            String(c.doFinal(dec(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    companion object {
        fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    }
    private fun enc(b: ByteArray) = Companion.enc(b)
    private fun dec(s: String) = Companion.dec(s)
}
