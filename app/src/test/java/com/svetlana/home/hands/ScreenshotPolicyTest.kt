package com.svetlana.home.hands

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotPolicyTest {
    private val own = "com.svetlana.home"

    @Test
    fun trustedAppsNeedNoConsent() {
        assertThat(ScreenshotPolicy.mayCapture(own, own)).isTrue()
        assertThat(ScreenshotPolicy.mayCapture("com.mobile.harness", own)).isTrue()
        assertThat(ScreenshotPolicy.mayCapture("com.android.settings", own)).isTrue()
    }

    @Test
    fun otherAppsAreDeniedByDefault() {
        assertThat(ScreenshotPolicy.mayCapture("org.telegram.messenger", own)).isFalse()
        assertThat(ScreenshotPolicy.mayCapture("", own)).isFalse()
    }

    @Test
    fun consentIsOneTimeAndBoundToPackage() {
        ScreenshotPolicy.allowOnce("org.telegram.messenger", now = 1_000)
        assertThat(ScreenshotPolicy.mayCapture("com.whatsapp", own, now = 1_001)).isFalse()
        ScreenshotPolicy.allowOnce("org.telegram.messenger", now = 1_000)
        assertThat(ScreenshotPolicy.mayCapture("org.telegram.messenger", own, now = 1_001)).isTrue()
        assertThat(ScreenshotPolicy.mayCapture("org.telegram.messenger", own, now = 1_002)).isFalse()
    }

    @Test
    fun consentExpires() {
        ScreenshotPolicy.allowOnce("org.telegram.messenger", now = 0)
        assertThat(ScreenshotPolicy.mayCapture("org.telegram.messenger", own, now = ScreenshotPolicy.CONSENT_TTL_MS + 1)).isFalse()
    }

    @Test
    fun sensitiveAppsNeverEvenWithConsent() {
        ScreenshotPolicy.allowOnce("ru.sberbankmobile", now = 0)
        assertThat(ScreenshotPolicy.mayCapture("ru.sberbankmobile", own, now = 1)).isFalse()
    }
}
