package com.deposplit.ui.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// PromptInfo.Builder rejects a negative button when DEVICE_CREDENTIAL is allowed and requires one
// when it is not, so this set and the absent negative button below belong together.
private val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

sealed class AuthAvailability {
    object Available : AuthAvailability()
    object NoneEnrolled : AuthAvailability()
    object NoHardware : AuthAvailability()
    data class Unavailable(val reasonCode: Int) : AuthAvailability()
}

fun biometricAvailability(context: Context): AuthAvailability =
    when (val status = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)) {
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
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
    if (subtitle != null) builder.setSubtitle(subtitle)
    prompt.authenticate(builder.build())
}
