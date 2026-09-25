package com.svetlana.home.apps

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppAliasesTest {

    @Test
    fun `known russian aliases present`() {
        assertThat(AppAliases.builtIn["org.telegram.messenger"]).contains("телеграм")
        assertThat(AppAliases.builtIn["com.whatsapp"]).contains("ватсап")
    }

    @Test
    fun `harness aliases present`() {
        assertThat(AppAliases.builtIn["com.mobile.harness"]).isNotNull()
        assertThat(AppAliases.builtIn["com.mobile.harness"]).contains("harness")
    }

    @Test
    fun `svetlana aliases present`() {
        assertThat(AppAliases.builtIn["com.svetlana.home"]).contains("света")
    }

    @Test
    fun `capabilities preferred way follows priority`() {
        // ТЗ §16: intent/deeplink/launch раньше accessibility
        val intentCaps = ControlCapabilities(canLaunch = true, canIntent = true)
        assertThat(intentCaps.preferredWay()).isEqualTo("intent")

        val deepCaps = ControlCapabilities(canLaunch = true, canDeepLink = true)
        assertThat(deepCaps.preferredWay()).isEqualTo("deeplink")

        val launchCaps = ControlCapabilities(canLaunch = true)
        assertThat(launchCaps.preferredWay()).isEqualTo("launch")

        val accOnly = ControlCapabilities(canLaunch = false, canAccessibility = true)
        assertThat(accOnly.preferredWay()).isEqualTo("accessibility")

        val none = ControlCapabilities(canLaunch = false, canAccessibility = false)
        assertThat(none.preferredWay()).isEqualTo("none")
    }
}
