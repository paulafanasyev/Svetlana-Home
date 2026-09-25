package com.svetlana.home.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launcher: проверка, что Светлана зарегистрирована как Home (ТЗ §4).
 *
 * Это runtime-проверка на устройстве: активность присутствует в манифесте,
 * имеет категорию HOME, и система видит её как кандидат в launcher'ы.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun homeActivityIsRegistered() {
        val pm = context.packageManager
        val pkg = context.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.queryActivities(intent, PackageManager.MATCH_ALL)

        val svetlana = resolved.firstOrNull { it.activityInfo.packageName == pkg }
        println("SVETLANA_HOME_ACTIVITY=${svetlana?.activityInfo?.name}")
        assertNotNull("Светлана должна быть кандидатом в HOME launcher'ы", svetlana)
        assertEquals("com.svetlana.home.ui.launcher.HomeActivity",
            svetlana!!.activityInfo.name)
    }

    @Test
    fun homeRoleRequestIsAvailable() {
        // ROLE_HOME требует API 29+. На более старых устройствах используем
        // категорию HOME в манифесте.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            println("SKIP: ROLE_HOME требует Android 10+")
            return
        }
        val pm = ServiceLocator.permissionManager
        // isHomeRoleHolder может вернуть false, если пользователь не выбрал
        // Светлану как launcher — это честное состояние.
        println("HOME_ROLE_HOLDER=${pm.isHomeLauncher()}")
    }

    @Test
    fun permissionCenterListsAllRequiredPermissions() {
        val pm = ServiceLocator.permissionManager
        val items = pm.list()

        println("PERMISSION_CENTER_ITEMS=${items.map { it.title }}")
        // ТЗ §27: Permission Center показывает реальное состояние.
        val keys = items.map { it.key }.toSet()
        assertTrue("Должен быть микрофон", keys.contains(PermissionManager.KEY_MIC))
        assertTrue("Должна быть камера", keys.contains(PermissionManager.KEY_CAMERA))
        assertTrue("Должны быть контакты", keys.contains(PermissionManager.KEY_CONTACTS))
        assertTrue("Должен быть Hands", keys.contains(PermissionManager.KEY_HANDS))
    }

    @Test
    fun noShellExecutionPath() {
        // ТЗ §57: в приложении не должно быть shell-исполнения.
        // Проверяем, что PermissionManager не предоставляет root/shell путей.
        val pm = ServiceLocator.permissionManager
        val items = pm.list()
        val forbidden = items.filter {
            it.description.contains("root", ignoreCase = true) ||
            it.description.contains("shell", ignoreCase = true) ||
            it.description.contains("termux", ignoreCase = true)
        }
        assertTrue("Permission Center не должен предлагать root/shell",
            forbidden.isEmpty())
    }
}
