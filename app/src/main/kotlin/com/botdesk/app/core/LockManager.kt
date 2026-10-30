package com.botdesk.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN (haché PBKDF2) + option biométrique. Verrouillage automatique à la mise en arrière-plan. */
class LockManager(private val secure: SecureStore, context: Context) {
    private val flags = context.getSharedPreferences("botdesk_flags", Context.MODE_PRIVATE)

    val isPinSet: Boolean get() = secure.getString("pin_hash") != null
    val locked = MutableStateFlow(isPinSet)

    var biometricEnabled: Boolean
        get() = flags.getBoolean("bio", false)
        set(v) { flags.edit().putBoolean("bio", v).apply() }

    /** À activer juste avant d'ouvrir un sélecteur système (évite de reverrouiller au retour). */
    @Volatile var skipNextLock = false

    private var failures = 0
    private var blockedUntil = 0L

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        secure.putString("pin_salt", SecureStore.enc(salt))
        secure.putString("pin_hash", SecureStore.enc(hash(pin, salt)))
    }

    fun clearPin() {
        secure.remove("pin_salt")
        secure.remove("pin_hash")
        biometricEnabled = false
        locked.value = false
    }

    fun blockedSeconds(): Long = maxOf(0L, (blockedUntil - System.currentTimeMillis()) / 1000)

    fun verify(pin: String): Boolean {
        if (blockedSeconds() > 0) return false
        val salt = secure.getString("pin_salt")?.let { SecureStore.dec(it) } ?: return false
        val expected = secure.getString("pin_hash")?.let { SecureStore.dec(it) } ?: return false
        val ok = java.security.MessageDigest.isEqual(hash(pin, salt), expected)
        if (ok) {
            failures = 0
        } else {
            failures++
            if (failures >= 5) {
                blockedUntil = System.currentTimeMillis() + 30_000L * (failures - 4)
            }
        }
        return ok
    }

    fun unlock() { locked.value = false; failures = 0 }

    fun lockNow() {
        if (skipNextLock) { skipNextLock = false; return }
        if (isPinSet) locked.value = true
    }
}
