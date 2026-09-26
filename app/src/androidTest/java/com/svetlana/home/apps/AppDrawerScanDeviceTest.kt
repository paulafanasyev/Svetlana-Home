package com.svetlana.home.apps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Аудит P0: App Drawer должен строиться из РЕАЛЬНОГО PackageManager,
 * а не быть пустым. До исправления scan() нигде не вызывался, и drawer
 * на устройстве был пустым — это был главный провал.
 *
 * Цепочка: PackageManager → scan() → AppRepository → apps flow → App Drawer.
 */
@RunWith(AndroidJUnit4::class)
class AppDrawerScanDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun scan_returnsRealInstalledApps() {
        val apps = ServiceLocator.appRegistry.scan()
        // На любом реальном устройстве есть системные приложения.
        assertTrue("scan() должен вернуть приложения из PackageManager", apps.isNotEmpty())
        // Как минимум настройки присутствуют на любом устройстве.
        assertTrue("в списке должны быть системные приложения",
            apps.any { it.systemApp })
    }

    @Test
    fun scan_populatesRepositoryFlow() {
        ServiceLocator.appRegistry.scan()
        val fromFlow = ServiceLocator.appRegistry.apps.value
        assertTrue("сканирование должно заполнить StateFlow", fromFlow.isNotEmpty())
        // Метаданные корректны
        val first = fromFlow.first()
        assertTrue("у приложения есть label", first.label.isNotBlank())
        assertTrue("у приложения есть packageName", first.packageName.isNotBlank())
    }

    @Test
    fun everyAppHasCategory() {
        ServiceLocator.appRegistry.scan()
        val apps = ServiceLocator.appRegistry.apps.value
        // Все приложения получают категорию из публичного API (ТЗ §11)
        apps.forEach { app ->
            assertTrue("категория назначена для ${app.packageName}",
                app.category.isNotBlank())
        }
    }

    @Test
    fun searchFindsAppByLabel() {
        ServiceLocator.appRegistry.scan()
        val apps = ServiceLocator.appRegistry.apps.value
        val target = apps.first()
        val found = ServiceLocator.appRegistry.search(target.label)
        assertTrue("поиск находит приложение по label", found.any { it.packageName == target.packageName })
    }
}
