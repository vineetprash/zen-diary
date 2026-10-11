package app.zendiary.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock via AndroidX BiometricPrompt: fingerprint/face (Class 3) with the device PIN/pattern/password
 * as fallback. API 28-29 cannot combine STRONG with DEVICE_CREDENTIAL, so those use WEAK|CREDENTIAL.
 */
object AppLock {
    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun canAuthenticate(ctx: Context): Int = BiometricManager.from(ctx).canAuthenticate(authenticators)
    fun isAvailable(ctx: Context) = canAuthenticate(ctx) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, subtitle: String?, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val bp = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(authenticators).build()
        bp.authenticate(info)
    }
}
