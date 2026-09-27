package com.svetlana.home

import android.app.Application
import android.content.ComponentCallbacks2
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.voice.VoiceAssistantService

/**
 * Точка входа приложения SVETLANA HOME.
 * Здесь созраняются только ссылки на подсистемы — без скрытой логики.
 */
class SvetlanaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServiceLocator.init(this)
        // ТЗ §21: фоновый голосовой ассистент. Запускается только если
        // пользователь включил wake word и выдал разрешение на микрофон.
        VoiceAssistantService.startIfEnabled(this)
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
