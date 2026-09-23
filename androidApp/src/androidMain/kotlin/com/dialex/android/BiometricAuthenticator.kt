package com.dialex.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** App-unlock gate: fingerprint/face before the debate list is shown, same idea as any
 * banking/notes app's lock screen. Gates entry at launch only — re-locking when the app
 * returns from the background is a real, separate piece of behavior (has to not fight with
 * the biometric prompt's own dialog triggering the same lifecycle callbacks) that needs a
 * real device to verify and isn't done here; see the roadmap note. */
object BiometricAuthenticator {
    /** False when there's no enrolled fingerprint/face at all (or no hardware) — callers
     * should skip the lock entirely then, never trap the user with a prompt that can only
     * fail. */
    fun isAvailable(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            // onAuthenticationFailed (one bad fingerprint read) fires per-attempt, not per
            // prompt session — the system dialog stays open and lets the user retry; nothing
            // for this callback to do.
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Roundtable")
            .setSubtitle("Use your fingerprint or face to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }
}
