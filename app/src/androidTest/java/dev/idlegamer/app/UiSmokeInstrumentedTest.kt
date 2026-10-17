package dev.idlegamer.app

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test on a fresh install: no stored session, so the login screen (password form) must appear with the risk
 * notice, and the window must be FLAG_SECURE because it contains a password field.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshInstallShowsLoginWithRiskNotice() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Sign in with Steam")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Sign in with Steam").assertIsDisplayed()
        compose.onNodeWithText("Before you sign in").assertIsDisplayed()
        val flags = compose.activity.window.attributes.flags
        compose.onNodeWithText("Sign in with a QR code instead").assertExists()
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
}
