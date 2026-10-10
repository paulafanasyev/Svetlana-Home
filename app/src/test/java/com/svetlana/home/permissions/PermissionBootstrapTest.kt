package com.svetlana.home.permissions

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionBootstrapTest {
    @Test
    fun android13PlusAsksForNotificationsAndMediaImages() {
        val list = PermissionBootstrap.runtimePermissions(33)
        assertThat(list).containsAtLeast(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
        assertThat(list).doesNotContain(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @Test
    fun olderAndroidUsesLegacyStorage() {
        val list = PermissionBootstrap.runtimePermissions(30)
        assertThat(list).contains(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(list).doesNotContain(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(list).doesNotContain(Manifest.permission.READ_MEDIA_IMAGES)
    }

    @Test
    fun noDuplicates() {
        val list = PermissionBootstrap.runtimePermissions(36)
        assertThat(list).containsNoDuplicates()
    }
}
