package com.svetlana.home.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Аудит P0:
 *  - ROLE_HOME flow: homeRoleIntent() должен возвращать системный intent.
 *  - Геолокация: два независимых состояния (разрешение vs системная
 *    геолокация) должны диагностироваться раздельно.
 */
@RunWith(AndroidJUnit4::class)
class RoleAndLocationDeviceTest {

    private lateinit var context: Context
    private lateinit var pm: PermissionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pm = PermissionManager(context)
    }

    @Test
    fun homeRoleIntentIsAvailable() {
        // На Android 10+ RoleManager обязан существовать.
        val intent = pm.homeRoleIntent()
        assertNotNull("ROLE_HOME intent должен создаваться", intent)
        assertTrue("intent должен иметь action", intent.action != null ||
                intent.categories.isNotEmpty() || intent.data != null)
    }

    @Test
    fun isHomeLauncherReturnsRealState() {
        // До назначения разработчиком — false. Это не ошибка, а факт:
        // пользователь ещё не назначил Светлану главным экраном.
        val state = pm.isHomeLauncher()
        assertFalse("приложение не должно быть Home по умолчанию", state)
    }

    @Test
    fun locationDiagnosticsReportsBothStates() {
        val diag = pm.locationDiagnostics()
        // Оба поля независимы и читаемы — это и есть суть аудита п.7.
        // Не должно быть общего «Выключено» без объяснения.
        assertTrue("сводка описывает состояние", diag.summary.isNotBlank())
        // Сводка разделяет два состояния
        val mentionsPermission = diag.summary.contains("разреш", ignoreCase = true)
        val mentionsServices = diag.summary.contains("геолокаци", ignoreCase = true)
        assertTrue("сводка упоминает разрешение и системную геолокацию",
            mentionsPermission && mentionsServices)
    }

    @Test
    fun locationServicesIntentOpensSystemScreen() {
        val intent = pm.locationServicesIntent()
        assertNotNull(intent)
        assertTrue("intent ведёт в системные настройки геолокации",
            intent.action == android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }
}
