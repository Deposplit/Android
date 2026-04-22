package com.deposplit.ui.biometric

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// API 29 does not support BIOMETRIC_STRONG | DEVICE_CREDENTIAL combined; use biometric-only there.
private fun allowedAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_STRONG
    }

sealed class AuthAvailability {
    object Available : AuthAvailability()
    object NoneEnrolled : AuthAvailability()
    object NoHardware : AuthAvailability()
    data class Unavailable(val reasonCode: Int) : AuthAvailability()
}

fun biometricAvailability(context: Context): AuthAvailability =
    when (val status = BiometricManager.from(context).canAuthenticate(allowedAuthenticators())) {
        BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NoneEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AuthAvailability.NoHardware
        else -> AuthAvailability.Unavailable(status)
    }

sealed class AuthResult {
    object Succeeded : AuthResult()
    data class Failed(val errorCode: Int, val message: String) : AuthResult()
}

suspend fun authenticate(
    activity: FragmentActivity,
    title: String,
    subtitle: String? = null,
): AuthResult = suspendCancellableCoroutine { cont ->
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (cont.isActive) cont.resume(AuthResult.Succeeded)
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (cont.isActive) cont.resume(AuthResult.Failed(errorCode, errString.toString()))
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val authenticators = allowedAuthenticators()
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(authenticators)
    if (subtitle != null) builder.setSubtitle(subtitle)
    // Negative button is required when and only when DEVICE_CREDENTIAL is not allowed.
    if (authenticators and DEVICE_CREDENTIAL == 0) {
        builder.setNegativeButtonText("Cancel")
    }
    prompt.authenticate(builder.build())
}
