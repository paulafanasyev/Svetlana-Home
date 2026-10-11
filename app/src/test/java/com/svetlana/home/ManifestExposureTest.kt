package com.svetlana.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Поверхность атаки из других приложений: наружу открыты только главный экран и два системных сервиса,
 * которые может вызвать только система (через BIND_*-разрешения). Приёмник загрузки не экспортирован:
 * BOOT_COMPLETED присылает система, а другие приложения до него достучаться не могут.
 */
class ManifestExposureTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun manifest(): org.w3c.dom.Document {
        val file = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.isFile }
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(file)
    }

    private fun components(tag: String): List<Element> {
        val nodes = manifest().getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun bootReceiverIsNotExported() {
        val boot = components("receiver").single { it.getAttributeNS(ns, "name").endsWith("BootCompletedReceiver") }
        assertThat(boot.getAttributeNS(ns, "exported")).isEqualTo("false")
    }

    @Test
    fun onlyExpectedComponentsAreExported() {
        val exported = listOf("activity", "service", "receiver", "provider").flatMap(::components)
            .filter { it.getAttributeNS(ns, "exported") == "true" }
            .map { it.getAttributeNS(ns, "name") }
        assertThat(exported).containsExactly(
            ".ui.launcher.HomeActivity",
            ".hands.SvetlanaAccessibilityService",
            ".ui.launcher.NotificationDotsService"
        )
    }

    @Test
    fun exportedServicesRequireSystemBindPermission() {
        val services = components("service").filter { it.getAttributeNS(ns, "exported") == "true" }
        for (s in services) assertThat(s.getAttributeNS(ns, "permission")).startsWith("android.permission.BIND_")
    }
}
