# Hands (Специальные возможности)

`SvetlanaAccessibilityService` + `HandsController`.

## Принцип доступа

Hands используется для управления другими приложениями **только** при
наличии необходимого системного доступа, который предоставляет сам
пользователь через системные настройки Android.

- Декларация: `SvetlanaAccessibilityService` с
  `BIND_ACCESSIBILITY_SERVICE`, конфигурация в
  `res/xml/accessibility_service_config.xml`.
- Проверка включения: `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
  (см. `PermissionManager.accessibilityEnabled()`).
- Сервис не пытается включить себя сам.

## Поддерживаемые операции (ТЗ §15)

| Операция | Реализация |
|----------|------------|
| UI tree | `snapshotUiTree()` — `rootInActiveWindow` + обход |
| Click | `ACTION_CLICK` + резерв `dispatchGesture` по координатам |
| Long click | `ACTION_LONG_CLICK` |
| Swipe | `GestureDescription` + `StrokeDescription` |
| Scroll | `ACTION_SCROLL_FORWARD/BACKWARD` + жестовый резерв |
| Input | `ACTION_SET_TEXT` |
| Global actions | `GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS` |
| Screenshot | `takeScreenshot()` (Android R+) |
| Поиск элементов | текст / contentDescription / id / className |
| Проверка результата | `verifyPackage()`, `verifyTextVisible()`, `waitForPackage()` |

## Структура UI tree

```kotlin
data class UiNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val id: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean,
    val depth: Int,
    val bounds: Rect
)
```

## Уведомление

Сервис работает в foreground с постоянным низкоприоритетным
уведомлением «Hands активен», чтобы пользователь всегда знал, что
Светлана может управлять интерфейсом.

## Тест-план (ТЗ §69)

- [ ] UI tree получается
- [ ] Click
- [ ] LongClick
- [ ] Swipe
- [ ] Scroll
- [ ] Input
- [ ] Screenshot
- [ ] Back / Home
- [ ] Verification (текст появился на экране)

## Ограничения (ТЗ §28, §59)

Постоянный доступ не равен безусловному. Hands выполняет действия
только в пределах разрешённой функции и Android security model:
нет скрытого включения, нет подтверждения опасных действий за
пользователя, нет обхода системных диалогов.
