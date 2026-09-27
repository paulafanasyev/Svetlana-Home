# App Control

## Действия (ТЗ §17)

`AppControlEngine` поддерживает:

`OpenApp`, `OpenAppScreen`, `Click`, `LongClick`, `Swipe`, `Scroll`,
`TypeText`, `ClearText`, `ReadScreen`, `FindElement`, `TakeScreenshot`,
`PressBack`, `PressHome`, `OpenRecents`, `OpenSettings`, `SendMessage`,
`MakeCall`, `Share`.

**Модель не может выполнять произвольные shell-команды.** Список действий
закрыт — `SvetlanaAction` это sealed class.

## Приоритет способа управления (ТЗ §16)

Для каждого действия:

1. Android Intent
2. Public API
3. Deep Link
4. PackageManager
5. Accessibility / Hands
6. Подтверждение пользователя

`IntentResolver` сначала пытается стандартным способом. Только если это
не удалось, в дело вступает `HandsController`.

## Доказательная цепочка (ТЗ §19, §20)

Нельзя считать «команда отправлена» равным «действие выполнено».

```
PLAN
↓
TARGET_APP_IDENTIFIED
↓
PERMISSION_CHECKED
↓
ACTION_ATTEMPTED
↓
ACTION_PERFORMED    ← только после реального выполнения
↓
RESULT_VERIFIED
```

Формат лога:

```
PLAN0_TARGET=OPEN_MOBILE_HARNESS
PLAN0_STATUS=ACTION_PERFORMED
PLAN0_RESULT=VERIFIED
```

`ACTION_PERFORMED` выставляется только после реального выполнения.

### Реальная верификация запуска (аудит п.7)

Раньше `openApp()` выставлял `ACTION_PERFORMED` сразу после
`startActivity()`. Это нарушало модель доказательства: команда отправлена
≠ приложение в foreground.

Теперь используется `LaunchVerifier.awaitForeground(pkg)`, который
фактически ждёт перехода и возвращает источник проверки:

- foreground пакет **совпал** → `ACTION_PERFORMED=OK`,
  `RESULT_VERIFIED=OK` (`PLAN0_RESULT=VERIFIED`);
- foreground пакет **другой** → `FAILED`, `PLAN0_RESULT=NOT VERIFIED`;
- **нет источника** проверки (Hands выключен, USAGE_STATS не выдан) →
  `UNVERIFIED`, `PLAN0_RESULT=NOT PROVEN` — приложение не считается
  открытым, но цепочка честно фиксирует причину.

Покрытие: `AppControlProofDeviceTest` — полный proof chain запуска
«Настройки», отказ для несуществующего приложения, требования Hands
для click/screenshot.

## Опасные действия (ТЗ §58)

Перед выполнением подтверждение запрашивается для:
отправка сообщений, звонки, удаление приложений, покупки, публикации,
финансовые действия, передача конфиденциальных данных, критические
системные изменения.

Классификация вынесена в `ActionRiskPolicy.riskOf(action)` — чистую
функцию, не зависящую от Context. `AppControlEngine.riskOf()` делегирует
в неё, `ActionRouter.execute()` использует результат:

```kotlin
if (riskOf(action) == ActionRisk.DANGEROUS && !confirmed) {
    // действие НЕ выполняется; выставляем requiresUserConfirmation
}
```

**Это блокировка, а не маркировка** (аудит п.27): опасное действие не
выполняется, пока пользователь явно не подтвердит. «Света, отправь SMS
Ивану» не превращается в реальную отправку.

Покрытие unit-тестами: `DangerousActionBlockTest` — классификация всех
типов действий (отправка/звонок = DANGEROUS, шеринг = MODERATE, Hands =
SAFE). На устройстве: `DangerousActionDeviceTest` — проверка реальной
блокировки и записи запроса подтверждения в историю.

## Ограничения приложений (ТЗ §67)

Если приложение не предоставляет Intent или блокирует Accessibility,
Светлана честно сообщает об ограничении и не пытается его обойти.

## Длинные многошаговые Hands-цепочки

Светлана понимает составные команды — действия, соединённые союзом «и»:

> «Света, открой Whatsapp и напиши контакту Серый привет как дела»

Разбор (`CommandParser`) выделяет:
- `ComposeMessage(appTarget="whatsapp", contact="серый", text="привет как дела")`

Исполнение (`AppControlEngine.composeMessage`) — последовательность шагов,
каждый со своей proof-цепочкой:

1. **Открыть приложение** и дождаться реального перехода в foreground
   (`LaunchVerifier.awaitForeground`).
2. **Открыть чат с контактом** — сначала напрямую из списка чатов, затем
   через поиск контакта.
3. **Найти поле ввода** (`findEditableField`) и напечатать текст, проверить
   `verifyTextEntered`.
4. **Нажать кнопку отправки** (`findSendButton`).
5. **Проверить результат** — сообщение появилось в чате.

Провал любого шага останавливает всю цепочку: нельзя отправить то, что
не введено.

Общий случай — `Compound(steps)`: произвольная последовательность действий
(«открой Telegram и нажми поиск»). Действия справа от союза выполняются
в контексте приложения слева (retarget).

### Безопасность

`ComposeMessage` классифицируется как `DANGEROUS` (отправка сообщения
необратима). `ActionRouter` не выполняет его без явного подтверждения
пользователя (ТЗ §58). При запросе из фонового сервиса Светлана
произносит, что нужно подтверждение, и открывает приложение —
подтверждение в фоне невозможно.

## Стриминг ответов ИИ

`AIRouter.chatStream` отдаёт ответ по мере генерации (SSE для
OpenAI-compatible провайдеров). `VoiceAgent` начинает говорить
немедленно: сначала filler-фразу («Дай подумать…»), затем — сам ответ,
как только он готов. Это требование: пользователь должен слышать отклик
сразу, не дожидаясь полного ответа.
