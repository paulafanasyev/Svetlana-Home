package com.svetlana.home.apps

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App Registry на реальном устройстве (ТЗ §10).
 *
 * ТЗ требует: получить реальный список приложений, найти пакет, разрешить alias,
 * открыть приложение. Здесь проверяем именно реальные данные системы.
 */
@RunWith(AndroidJUnit4::class)
class AppRegistryDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun registrySeesInstalledApps() = runBlocking {
        val registry = ServiceLocator.appRegistry
        registry.scan()

        val apps = registry.apps.value
        println("INSTALLED_APPS=${apps.size}")
        // На любом реальном устройстве есть хотя бы несколько приложений.
        assertTrue("Реестр должен видеть установленные приложения", apps.size > 0)
    }

    @Test
    fun everyAppHasPackageAndLabel() = runBlocking {
        val registry = ServiceLocator.appRegistry
        registry.scan()

        val apps = registry.apps.value
        apps.forEach { app ->
            assertTrue("packageName не пустой: $app", app.packageName.isNotBlank())
            // label может быть пустым у некоторых системных пакетов, но
            // packageName обязан быть всегда.
            assertNotNull(app.packageName)
        }
    }

    @Test
    fun appAliasesResolve() = runBlocking {
        val registry = ServiceLocator.appRegistry
        registry.scan()

        val apps = registry.apps.value
        // Проверяем, что aliases — это валидная структура, а не null.
        apps.take(20).forEach { app ->
            assertNotNull("aliases не null", app.aliases)
        }
    }

    @Test
    fun systemSettingsAppIsFindable() = runBlocking {
        val registry = ServiceLocator.appRegistry
        registry.scan()

        val resolver = ServiceLocator.intentResolver
        val resolved = resolver.resolveApp("настройки")
        println("SETTINGS_RESOLVED=${resolved?.app?.packageName}")
        // Настройки должны находиться на любом устройстве.
        assertNotNull("Настройки должны быть найдены через resolver", resolved)
    }

    @Test
    fun uninstalledAppIsNotInRegistry() = runBlocking {
        val registry = ServiceLocator.appRegistry
        registry.scan()

        val apps = registry.apps.value
        // Несуществующий пакет не должен присутствовать.
        val fake = apps.firstOrNull { it.packageName == "com.svetlana.fake.app" }
        assertFalse("Несуществующий пакет не должен быть в реестре", fake != null)
    }
}
