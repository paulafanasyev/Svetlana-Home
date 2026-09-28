# Action Proof — контракт доказательного выполнения

> Главная проблема текущей реализации: `performAction() == true` считается
> доказательством действия. **API принял команду ≠ действие выполнено.**

## Формальная модель

```
data class ActionProof(
    val planId: String,
    val target: String,
    val precondition: VerificationState,
    val attempted: Boolean,
    val platformAccepted: Boolean,   // Android API вернул true
    val postcondition: VerificationState,
    val result: VerificationState
)
```

Ключевое правило:

```
platformAccepted  ⟹  attempted
result == VERIFIED ⟹  platformAccepted && postcondition == VERIFIED
```

`platformAccepted` **никогда** не означает `performed`.

## Цепочка

```
PLAN
  ↓
TARGET_IDENTIFIED
  ↓
PRECONDITIONS_OK
  ↓
ACTION_ATTEMPTED        ← вызван Android API
  ↓
ACTION_ACCEPTED         ← API вернул true (platformAccepted)
  ↓
POSTCONDITION_CHECKED   ← сняли snapshot, сравнили состояние
  ↓
RESULT_VERIFIED         ← только теперь ACTION_PERFORMED = true
```

## Постусловия по типу действия

| Действие | Постусловие |
|----------|-------------|
| `OpenApp` | `currentForegroundPackage == targetPackage` (waitForPackage) |
| `Click` | `snapshot_after` отличается от `snapshot_before` в области цели |
| `TypeText` | `node.text == expected` |
| `ClearText` | `node.text == ""` |
| `Screenshot` | `Bitmap != null && width > 0 && encode ok` |
| `Scroll` | дерево/экран изменилось (content или bounds) |
| `Swipe` | жест завершён `onCompleted` (не `onCancelled`) |

## Статусы

```
VERIFIED
CODE VERIFIED
CI VERIFIED
DEVICE VERIFIED
NOT PROVEN
BLOCKED
```

`PASS` не используется, если доказательство не соответствует уровню утверждения.

## Что доказывает ProofBuilder, а что нет

Наличие `ProofBuilder` доказывает **структуру данных**. Не доказывает, что
телефон реально нажал кнопку. Для этого нужен runtime proof test на
физическом устройстве (см. `device-acceptance.md`).
