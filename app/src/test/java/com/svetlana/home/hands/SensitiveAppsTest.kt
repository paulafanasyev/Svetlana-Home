package com.svetlana.home.hands

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SensitiveAppsTest {
    @Test
    fun bankingAndPasswordAppsAreNeverScreenshotted() {
        listOf(
            "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android", "com.google.android.apps.authenticator2", "com.x8bit.bitwarden",
            "com.agilebits.onepassword", "ru.rostel".plus("").let { "ru.gosuslugi.app" }, "com.google.android.apps.walletnfcrel", "",
        ).forEach { pkg ->
            assertWithMessage("$pkg must be sensitive").that(SensitiveApps.isSensitive(pkg)).isTrue()
        }
    }

    @Test
    fun ordinaryAppsAreAllowed() {
        listOf("org.telegram.messenger", "com.whatsapp", "com.mobile.harness", "com.android.settings").forEach { pkg ->
            assertWithMessage("$pkg must not be sensitive").that(SensitiveApps.isSensitive(pkg)).isFalse()
        }
    }
}
