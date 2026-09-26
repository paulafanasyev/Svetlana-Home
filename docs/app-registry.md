# App Registry

`AppRegistry` обнаруживает доступные приложения через Android API и
хранит их метаданные. Ограничения Android на видимость пакетов
учитываются.

## Источники обнаружения

1. `LauncherApps.getActivityList()` — основной путь для launcher
   (уважает политику видимости пакетов).
2. `PackageManager.queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)` —
   запасной путь.
3. Системные точки входа (настройки, телефон, контакты) добавляются
   отдельно с предустановленной capability matrix.

## Хранимые поля (ТЗ §10)

| Поле | Тип | Описание |
|------|-----|----------|
| `packageName` | String | Идентификатор пакета |
| `label` | String | Имя, как зарегистрировано в Android |
| `icon` | Drawable | Иконка приложения |
| `launchIntent` | Intent | Намерение запуска |
| `aliases` | List<String> | Русские псевдонимы (включая `AppAliases.builtIn`) |
| `favorite` | Boolean | Избранное |
| `hidden` | Boolean | Скрытое |
| `lastUsedAt` | Long | Время последнего запуска |
| `controlCapabilities` | ControlCapabilities | Capability matrix |

## Capability Matrix (ТЗ §66)

Для каждого приложения хранятся реальные возможности:

`CanLaunch`, `CanDeepLink`, `CanIntent`, `CanAccessibility`,
`CanReadUI`, `CanClick`, `CanInput`, `CanScreenshot`, `CanVerify`.

Приоритет способа: `intent → deeplink → launch → accessibility → none`.

### Видимость для пользователя

App Drawer показывает краткую сводку возможностей под названием каждого
приложения: «управление: запуск, intent, hands» или «управление
недоступно». Пользователь видит реальные возможности до того, как
даст команду (ТЗ §66: показывать пользователю реальные возможности).

### Категории (ТЗ §11)

`AppCategoryResolver` определяет категорию по пакету и флагам
`ApplicationInfo` (только публичный API):

- Системные (`FLAG_SYSTEM`)
- Игры (`FLAG_IS_GAME` или `game` в пакете)
- Общение (мессенджеры)
- Браузеры
- Медиа (видео/музыка/галерея/камера)
- Инструменты
- Другие

Логика вынесена в чистый объект и покрывается unit-тестами без
Android-зависимостей. В App Drawer доступна вкладка «Категории» с
фильтрацией.

## Отслеживание изменений

`AppRegistry.startWatching()` регистрирует `BroadcastReceiver` на
`ACTION_PACKAGE_ADDED / REPLACED / CHANGED / REMOVED` и обновляет реестр
в реальном времени.

## Псевдонимы

`AppAliases.builtIn` содержит русские псевдонимы популярных приложений:
телеграм, ватсап, вк, яндекс карты и т.д. Пользователь может добавить
свои через `AppRepository.addAlias()`.

Названия приложений сохраняются такими, как они зарегистрированы
в Android, но Светлана понимает русские псевдонимы.

## Ограничения (ТЗ §67)

Если приложение не предоставляет Intent, блокирует Accessibility или
требует собственное подтверждение, Светлана сообщает:
«Это действие недоступно через Android или ограничено самим приложением»
и не пытается обойти ограничение.
