# Mobile Harness

Mobile Harness — обязательный приоритетный сценарий тестирования (ТЗ §13).

## Обнаружение

`MobileHarnessController.detect()` ищет приложение по:
- известным package names (`com.mobile.harness` и др.);
- метке, содержащей «Mobile Harness»;
- package name, содержащему «harness».

Если Mobile Harness уже установлен, Svetlana Home находит его
автоматически.

## Pipeline (ТЗ §14)

```
Голос
↓
STT
↓
Intent (команда)
↓
Mobile Harness Resolver
↓
Launch
↓
Accessibility UI tree
↓
Target Finder
↓
Action
↓
Verification
↓
Ответ Светланы
```

## Возможности

- найти Mobile Harness;
- запустить;
- получить UI tree (`uiTree()`);
- найти элемент (`findElement(text)`);
- нажать (`tapElement(text)`);
- удержать (`longPressElement(text)`);
- свайпнуть / прокрутить (`scrollDown() / scrollUp()`);
- ввести текст (`typeIntoField(hint, value)`);
- вернуться (`goBack()`);
- сделать screenshot (`screenshot()`);
- проверить результат (`verifyResult(text)`).

## Доказательная цепочка запуска

`MobileHarnessController.launch()` возвращает:

```
PLAN                                              OK   PLAN: открыть Mobile Harness
TARGET_APP_IDENTIFIED                             OK   target=com.mobile.harness
PERMISSION_CHECKED                                OK   intent-based запуск
ACTION_ATTEMPTED                                  OK   startActivity выполнен
ACTION_PERFORMED                                  OK   PLAN0_TARGET=OPEN_MOBILE_HARNESS
RESULT_VERIFIED                                   OK   PLAN0_RESULT=VERIFIED
```

Если Intent-запуск не удался, применяется резервный путь через Hands
(только если пользователь его включил).

## Доказательство (ТЗ §84)

```
Code → CI → APK → POCO X3 NFC → Hands enabled →
Mobile Harness → UI element → Real action → Result verified
```

## Тест-план

- [ ] Mobile Harness найден
- [ ] Mobile Harness запущен
- [ ] Управление голосом: «Света, открой Mobile Harness»
- [ ] Управление Hands: нажать элемент, ввести текст, сделать скриншот
- [ ] Реальное действие выполнено
- [ ] Результат подтверждён (`PLAN0_RESULT=VERIFIED`)
