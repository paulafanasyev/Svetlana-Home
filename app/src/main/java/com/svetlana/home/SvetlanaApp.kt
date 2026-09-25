package com.svetlana.home

import android.app.Application
import android.content.ComponentCallbacks2
import com.svetlana.home.core.ServiceLocator

/**
 * Точка входа приложения SVETLANA HOME.
 * Здесь созраняются только ссылки на подсистемы — без скрытой логики.
 */
class SvetlanaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServiceLocator.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ServiceLocator.onTrimMemory(level)
    }

    companion object {
        @Volatile
        lateinit var instance: SvetlanaApp
            private set
    }
}
