package dev.idlegamer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.idlegamer.app.domain.repository.AppSettings
import dev.idlegamer.app.presentation.navigation.AppRoot
import dev.idlegamer.app.presentation.theme.IdleGamerTheme

/**
 * Single activity. Extends FragmentActivity only because BiometricPrompt requires it.
 *
 * App lock (SECURITY_DESIGN.md §5): a UI gate, re-armed whenever the activity goes to the background.
 * It does not gate the vault key, so it cannot break background session restoration — and, equally,
 * it adds no protection for data at rest.
 */
class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(true)
    private var lockEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as IdleGamerApplication).container

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            val auth by container.sessionManager.authState.collectAsStateWithLifecycle()
            val effective = settings ?: AppSettings()
            lockEnabled = effective.appLockEnabled
            IdleGamerTheme(effective.themeMode) {
                AppRoot(
                    authState = auth,
                    locked = settings == null || (effective.appLockEnabled && locked),
                    onUnlock = ::promptUnlock,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (lockEnabled && locked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) locked = true
    }

    private fun promptUnlock() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_prompt_title))
                .setSubtitle(getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }
}
