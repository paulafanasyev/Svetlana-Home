package com.svetlana.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.svetlana.home.core.ServiceLocator
import org.junit.Before
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Базовый класс для device-тестов Светланы.
 *
 * Это НЕ unit-тесты. Они запускаются на реальном Android-устройстве
 * (или эмуляторе) через connectedDebugAndroidTest и проверяют реальную
 * работу подсистем: launcher, app registry, hands, permissions, owner.
 *
 * Статусы по ТЗ §82: DEVICE VERIFIED можно поставить только после того,
 * как эти тесты прошли на физическом POCO X3 NFC.
 */
@RunWith(AndroidJUnit4::class)
abstract class SvetlanaDeviceTest {

    protected val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    open fun setUp() {
        ServiceLocator.init(context as android.app.Application)
    }

    /** Сетевые вызовы в тестах не используем — офлайн-режим должен работать. */
    protected fun assumeOffline(): Boolean = true
}
