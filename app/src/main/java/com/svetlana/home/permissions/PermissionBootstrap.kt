package com.svetlana.home.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Все runtime-разрешения, которые Светлане нужны для работы прямо на телефоне,
 * без компьютера. Запрашиваются одним системным диалогом при первом запуске.
 *
 * Android не позволяет выдать их при установке: runtime-разрешения
 * подтверждает только пользователь. Специальные доступы (Hands /
 * специальные возможности, главный экран) включаются в системных
 * настройках через мастер «Настроить Светлану».
 */
object PermissionBootstrap {

    fun runtimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun missing(context: Context): List<String> = runtimePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /** В instrumented-тестах системный диалог перекрыл бы проверяемый UI. */
    fun isUnderInstrumentation(): Boolean = try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        true
    } catch (e: ClassNotFoundException) {
        false
    }
}
