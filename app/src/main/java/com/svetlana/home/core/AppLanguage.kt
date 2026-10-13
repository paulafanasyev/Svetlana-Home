package com.svetlana.home.core

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale
import java.util.WeakHashMap

/**
 * Язык интерфейса Светланы: русский, английский или как в системе.
 *
 * Android 13+ (API 33): штатный язык приложения через [LocaleManager] — система сама
 * хранит выбор, перезапускает экраны, а язык можно поменять и в системных
 * настройках приложения (список языков — res/xml/locales_config.xml).
 * Android 8–12: выбор хранится у нас и применяется к ресурсам приложения и каждого
 * экрана при его создании ([install]); при смене языка открытые экраны пересоздаются.
 */
object AppLanguage {

    enum class Choice(val tag: String) {
        SYSTEM(""), RU("ru"), EN("en");

        companion object {
            fun fromTag(tag: String?): Choice {
                val lang = tag.orEmpty().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
                return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == lang } ?: SYSTEM
            }
        }
    }

    private const val PREFS = "svetlana_language"
    private const val KEY_TAG = "tag"
    private const val KEY_CHOSEN = "chosen"

    private val live = WeakHashMap<Activity, Unit>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Язык уже выбран (первый запуск пройден или язык задан в системных настройках). */
    fun isChosen(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CHOSEN, false) || systemAppLocaleTag(ctx).isNotEmpty()

    /** Текущий выбор. На Android 13+ источник правды — система (язык могли поменять там). */
    fun current(ctx: Context): Choice =
        if (Build.VERSION.SDK_INT >= 33) Choice.fromTag(systemAppLocaleTag(ctx))
        else Choice.fromTag(prefs(ctx).getString(KEY_TAG, ""))

    /** Отмечает первый запуск как пройденный и применяет язык. */
    fun set(activity: Activity, choice: Choice) {
        val before = current(activity)
        prefs(activity).edit().putString(KEY_TAG, choice.tag).putBoolean(KEY_CHOSEN, true).apply()
        if (before == choice) return
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = activity.getSystemService(LocaleManager::class.java) ?: return
            // Система сама пересоздаст открытые экраны с новым языком.
            lm.applicationLocales =
                if (choice.tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(choice.tag)
        } else {
            applyTo(activity.applicationContext)
            live.keys.toList().forEach { it.recreate() }
        }
    }

    /** Подключается в Application.onCreate: на Android 8–12 применяет язык к каждому экрану. */
    fun install(app: Application) {
        if (Build.VERSION.SDK_INT >= 33) return
        applyTo(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyTo(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // До API 29 onActivityPreCreated не вызывается; здесь всё ещё до setContent.
                applyTo(activity)
                live[activity] = Unit
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                live.remove(activity)
            }
        })
    }

    /** Android 8–12: язык для ресурсов контекста («как в системе» — язык телефона). */
    @Suppress("DEPRECATION")
    private fun applyTo(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = prefs(ctx).getString(KEY_TAG, "").orEmpty()
        val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
        val res = ctx.resources
        if (res.configuration.locales[0] == locale) return
        Locale.setDefault(locale)
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        res.updateConfiguration(config, res.displayMetrics)
    }

    private fun systemAppLocaleTag(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        val lm = ctx.getSystemService(LocaleManager::class.java) ?: return ""
        val list = lm.applicationLocales
        return if (list.isEmpty) "" else list[0].toLanguageTag()
    }
}
