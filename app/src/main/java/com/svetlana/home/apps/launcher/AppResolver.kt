package com.svetlana.home.apps.launcher

import com.svetlana.home.apps.AppModel

/**
 * Аудит §7 (архитектура VoxApps воспроизведена самостоятельно, GPL-код не
 * копируется): декларативный каталог намерений и распознаватель цели.
 *
 * Resolver отвечает на вопрос: «что пользователь имел в виду»?
 * Например:
 *   «Открой Telegram»       → приложение по псевдониму
 *   «Открой настройки Wi-Fi» → системный экран (deep link в Settings)
 *   «Позвони Ивану»          → действие ACTION_CALL + контакт
 *   «Открой камеру»          → системное приложение + intent
 *
 * Важное свойство: Resolver **не выполняет** действие. Он только
 * определяет цель и способ. Выполнение и проверка — в ActionRouter.
 */
class AppResolver(
    private val installed: InstalledPackageRegistry,
    private val launchable: LaunchableActivityRegistry
) {

    /**
     * Результат распознавания: что запускать.
     * launchIntent готов к startActivity.
     */
    sealed class ResolveResult {
        /** Нашли launchable activity. */
        data class Launchable(val activity: LaunchableActivity) : ResolveResult()
        /** Пакет установлен, но launcher activity не найдена — пробуем пакетный intent. */
        data class PackageOnly(val packageName: String) : ResolveResult()
        /** Запрошено системное действие (звонок, настройки и т.д.). */
        data class SystemAction(val intent: android.content.Intent) : ResolveResult()
        /** Ничего не найдено. Причина — для честного ответа пользователю. */
        data class NotFound(val query: String, val reason: String) : ResolveResult()
    }

    /**
     * Разрешает приложение по имени или русскому псевдониму.
     * Приоритет: точное совпадение label → точное package → псевдоним → частичное.
     */
    fun resolveApp(spokenName: String, apps: List<AppModel>): ResolveResult {
        val q = spokenName.trim().lowercase()
        if (q.isEmpty()) return ResolveResult.NotFound(spokenName, "Пустой запрос")

        // 1. launchable activity с точным совпадением метки.
        val byLabel = launchable.scanLaunchable().firstOrNull {
            it.label.lowercase() == q
        }
        if (byLabel != null) return ResolveResult.Launchable(byLabel)

        // 2. Приложение из реестра (учитывая русские псевдонимы).
        val model = apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.aliases.any { a -> a.lowercase() == q } }
            ?: apps.firstOrNull { it.aliases.any { a -> q.contains(a.lowercase()) } }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }

        if (model != null) {
            // Проверяем, есть ли launchable activity у найденного пакета.
            val launchables = launchable.scanLaunchable()
                .filter { it.packageName == model.packageName }
            if (launchables.isNotEmpty()) {
                return ResolveResult.Launchable(launchables.first())
            }
            // Пакет есть, но иконки запуска нет.
            return if (installed.hasLauncherActivity(model.packageName)) {
                ResolveResult.PackageOnly(model.packageName)
            } else {
                ResolveResult.NotFound(
                    spokenName,
                    "«${model.label}» установлено, но не имеет экрана запуска"
                )
            }
        }
        return ResolveResult.NotFound(spokenName, "Приложение «$spokenName» не найдено")
    }

    /**
     * Разрешает системные команды, которые не сводятся к приложению:
     * настройки, звонки, камера и т.д. (декларативный IntentCatalog).
     */
    fun resolveSystem(query: String): ResolveResult? {
        val q = query.trim().lowercase()
        for ((patterns, intentFactory) in IntentCatalog) {
            if (patterns.any { q.contains(it) }) return ResolveResult.SystemAction(intentFactory())
        }
        return null
    }

    /** Предикат совпадения → фабрика системного intent. */
    private val IntentCatalog: List<Pair<List<String>, () -> android.content.Intent>> = listOf(
        listOf("настройки wi-fi", "вайфай", "вай фай", "wi-fi") to {
            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        },
        listOf("настройки bluetooth", "bluetooth", "блютуз") to {
            android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        },
        listOf("настройки", "settings") to {
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        },
        listOf("камера", "camera") to {
            android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        },
        listOf("звонки", "телефон", "набор номера") to {
            android.content.Intent(android.content.Intent.ACTION_DIAL)
        },
        listOf("сообщения", "смс", "sms") to {
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_APP_MESSAGING)
        },
        listOf("браузер", "хром") to {
            android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://"))
        },
        listOf("галерея", "фотографии") to {
            android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("content://media/external/images/media"))
        }
    )
}
