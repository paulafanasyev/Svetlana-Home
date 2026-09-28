# Capability Matrix — реальные возможности приложений

> ТЗ §66: для каждого приложения хранятся реальные возможности.
> Оптимистичные default'ы (`canClick = true` для всех) — это ложное
> заявление capability. Возможности определяются состоянием системы.

## Возможности

| Поле | Чем определяется |
|------|------------------|
| `canLaunch` | launchable activity существует |
| `canDeepLink` | app_links/intent-filters зарегистрированы |
| `canIntent` | экспортированные activities в манифесте |
| `canAccessibility` | **Hands включён** (AccessibilityService активен) |
| `canReadUI` | **Hands включён** + `canRetrieveWindowContent` |
| `canClick` | **Hands включён** + `canPerformGestures` |
| `canInput` | **Hands включён** + целевое поле ввода найдено |
| `canScreenshot` | **Hands включён** + `canTakeScreenshots` |
| `canVerify` | **Hands включён** + postcondition проверяем |

Ключ: `canAccessibility` и всё, что от него зависит, **не могут** быть
`true`, если Hands не включён. Раньше capability matrix заявляла полный
набор на пустом месте.

## Приоритет способа управления (ТЗ §16)

```
1. Android Intent
2. Public API
3. Deep Link
4. PackageManager
5. Accessibility / Hands
6. User confirmation
```

Accessibility не используется, если стандартный Android API выполняет
действие надёжнее.

## Ограничения приложений (ТЗ §67)

Если приложение не предоставляет Intent, блокирует Accessibility или
требует собственное подтверждение — Светлана сообщает:

> «Это действие недоступно через Android или ограничено самим приложением.»

Попыток обойти ограничение нет.
