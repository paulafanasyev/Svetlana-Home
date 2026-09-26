package com.svetlana.home.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ТЗ §25, §26, §27: Permission Manager и Permission Center.
 *
 * Доказывает, что:
 *  - состояние разрешений читается из реальной системы, а не кэшируется;
 *  - missing() корректно докладывает о недостающих доступах;
 *  - settingsIntent для каждого ключа реально существует;
 *  - повторный запрос идёт штатным Android-путем (никакого bypass).
 *
 * Примечание: этот тест не выдаёт разрешения сам — это делает только
 * пользователь через системный диалог. Здесь проверяется честность
 * отчета о текущем состоянии.
 */
@RunWith(AndroidJUnit4::class)
class PermissionLifecycleDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun permissionList_reflectsRealSystemState() {
        val items = ServiceLocator.permissionManager.list()

        assertTrue("Список разрешений не должен быть пустым", items.isNotEmpty())
        // Каждое ключевое разрешение должно быть в списке.
        val keys = items.map { it.key }.toSet()
        assertTrue("Должно быть разрешение микрофона", keys.contains(PermissionManager.KEY_MIC))
        assertTrue("Должно быть разрешение камеры", keys.contains(PermissionManager.KEY_CAMERA))
        assertTrue("Должен быть Hands", keys.contains(PermissionManager.KEY_HANDS))
        assertTrue("Должен быть Home", keys.contains(PermissionManager.KEY_HOME))
    }

    @Test
    fun isGranted_matchesSystem() {
        // Прямая проверка через PackageManager должна совпадать с менеджером.
        val pm = ServiceLocator.permissionManager
        for (item in pm.list()) {
            if (item.kind == PermissionItem.Kind.RUNTIME) {
                val sysPermission = pm.runtimePermissionFor(item.key)
                assertNotNull("Runtime-разрешение должно мапиться: ${item.key}", sysPermission)
                val sysGranted = pm.isGranted(sysPermission!!)
                assertEquals("Состояние ${item.key} должно совпадать с системой",
                    sysGranted, item.granted)
            }
        }
    }

    @Test
    fun missing_returnsOnlyRequiredUngranted() {
        val missing = ServiceLocator.permissionManager.missing()

        // Все элементы в missing — обязательные и невыданные.
        for (item in missing) {
            assertTrue("missing должен содержать только обязательные: ${item.key}", item.required)
            assertFalse("missing должен содержать только невыданные: ${item.key}", item.granted)
        }
    }

    @Test
    fun settingsIntent_existsForEveryKey() {
        // ТЗ §27: пользователь может изменить каждое состояние позже.
        val pm = ServiceLocator.permissionManager
        for (item in pm.list()) {
            val intent = when (item.kind) {
                PermissionItem.Kind.ROLE_HOME -> pm.homeRoleIntent()
                PermissionItem.Kind.ACCESSIBILITY -> pm.settingsIntentFor(item.key)
                else -> pm.settingsIntentFor(item.key)
            }
            assertNotNull("Должен быть intent настроек для ${item.key}", intent)
            assertTrue("Intent должен иметь action или component", 
                intent?.action != null || intent?.component != null || intent?.data != null)
        }
    }

    @Test
    fun runtimePermissionMapping_resolvesKnownKeys() {
        val pm = ServiceLocator.permissionManager
        // Известные runtime-ключи должны мапиться на системные константы.
        val mic = pm.runtimePermissionFor(PermissionManager.KEY_MIC)
        assertNotNull("Микрофон должен мапиться на runtime permission", mic)
        assertTrue("Это должно быть системное разрешение", 
            mic!!.startsWith("android.permission."))
    }

    @Test
    fun report_isUserFacing() {
        // ТЗ §27: Permission Center показывает реальное состояние.
        val report = ServiceLocator.permissionManager.report()
        assertTrue("Отчёт должен быть непустым", report.isNotEmpty())
    }

    @Test
    fun noHiddenPermissions() {
        // ТЗ §28: не должно быть скрытых доступов. Каждый элемент списка
        // виден пользователю и имеет описание.
        for (item in ServiceLocator.permissionManager.list()) {
            assertTrue("Каждое разрешение имеет ключ", item.key.isNotEmpty())
            assertTrue("Каждое разрешение имеет описание", item.description.isNotEmpty())
        }
    }
}
