package com.svetlana.home.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssistLaunchTest {

    @Test
    fun `listens only on a real assist call when Svetlana is the assistant`() {
        assertThat(AssistLaunch.shouldListen("android.intent.action.ASSIST", true)).isTrue()
        assertThat(AssistLaunch.shouldListen("android.intent.action.ASSIST", false)).isFalse()
        assertThat(AssistLaunch.shouldListen("android.intent.action.MAIN", true)).isFalse()
        assertThat(AssistLaunch.shouldListen(null, true)).isFalse()
    }

    @Test
    fun `legacy assistant setting must name this package`() {
        assertThat(AssistLaunch.isAssistantSetting("com.svetlana.home/.ui.launcher.AssistActivity", "com.svetlana.home")).isTrue()
        assertThat(AssistLaunch.isAssistantSetting("com.svetlana.home", "com.svetlana.home")).isTrue()
        assertThat(AssistLaunch.isAssistantSetting("com.google.android.googlequicksearchbox/x", "com.svetlana.home")).isFalse()
        assertThat(AssistLaunch.isAssistantSetting("com.svetlana.home.evil/x", "com.svetlana.home")).isFalse()
        assertThat(AssistLaunch.isAssistantSetting(null, "com.svetlana.home")).isFalse()
        assertThat(AssistLaunch.isAssistantSetting("", "com.svetlana.home")).isFalse()
    }
}
