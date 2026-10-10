package com.svetlana.home

import android.app.Application
import android.content.ComponentCallbacks2
import com.svetlana.home.bridge.BridgeController
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
        // Мост к ядру Svetlana 2.0 (веб / Windows): http://<ip телефона>:8080,
        // защищён кодом подключения из уведомления. Стартует в фоне и не может
        // уронить приложение.
        BridgeController.startAsync(this)
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
