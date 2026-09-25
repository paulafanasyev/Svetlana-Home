# App Control

## Действия (ТЗ §17)

`AppControlEngine` поддерживает:

`OpenApp`, `OpenAppScreen`, `Click`, `LongClick`, `Swipe`, `Scroll`,
`TypeText`, `ClearText`, `ReadScreen`, `FindElement`, `TakeScreenshot`,
`PressBack`, `PressHome`, `OpenRecents`, `OpenSettings`, `SendMessage`,
`MakeCall`, `Share`, `Translate`.

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
