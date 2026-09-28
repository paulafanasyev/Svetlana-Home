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
| `Click` | **target-specific** (аудит §7): изменился целевой узел (текст / bounds / focus / enabled) ИЛИ сменился foreground пакет ИЛИ изменилось дерево |
| `TypeText` | `node.text == expected` |
| `ClearText` | `node.text == ""` |
| `Screenshot` | `Bitmap != null && width > 0 && encode ok` |
| `Scroll` | хэш видимого дерева ИЛИ число узлов изменились (не только count) |
| `Swipe` | состояние целевого экрана изменилось (та же логика, что у Scroll) |
| `SendMessage` | текст сообщения появился на экране (`verifyTextAppeared`) |

### Почему не «изменилось число узлов»

Старая проверка `verifyClick` сравнивала только `nodeCount`. Это давало
ложные FAIL на успешных кликах (когда дерево не изменилось) и ложные
VERIFIED на случайных изменениях. Теперь `PostconditionLogic.verifyClick`
проверяет по убыванию специфичности:

1. состояние целевого узла (найденного по id, иначе по visible text);
2. foreground пакет;
3. хэш дерева;
4. число узлов.

Чистая логика вынесена в `PostconditionLogic` и покрыта unit-тестами
(`PostconditionLogicTest`) без AccessibilityService.

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
