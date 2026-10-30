package com.botdesk.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Biometric {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("BotDesk")
            .setSubtitle("Déverrouillez l'application")
            .setAllowedAuthenticators(AUTH)
            .setNegativeButtonText("Utiliser le PIN")
            .build()
        prompt.authenticate(info)
    }
}
