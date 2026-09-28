# Device Acceptance — приёмочные тесты на физическом устройстве

> Подход заимствован у AndroidWorld: `task → environment → action →
> observation → success criteria`. В сам APK AndroidWorld не тащится —
> только метод.

## Сценарии

| ID | Сценарий | Критерий успеха |
|----|----------|-----------------|
| TEST-APP-001 | Открыть Telegram | foreground package == `org.telegram.messenger` |
| TEST-HANDS-001 | Нажать конкретную кнопку | target state изменился |
| TEST-HANDS-002 | Ввести текст | `node.text == expected` |
| TEST-HANDS-003 | Пролистать вниз | дерево/экран изменилось |
| TEST-HANDS-004 | Сделать скриншот | `Bitmap != null && size > 0` |
| TEST-VISION-001 | Показать известную картинку | ответ содержит известный объект |
| TEST-AI-001 | Local model inference | ответ непустой |
| TEST-AI-002 | Local vision inference | ответ зависит от изображения |
| TEST-VOICE-001 | «Света, открой …» | команда разобрана и исполнена |
| TEST-OWNER-001 | BiometricPrompt | ключ требует аутентификации |

## Уровни доказательства

```
Code           — класс существует, логика написана
CI VERIFIED    — собирается, lint/unit/instrumentation прошли
DEVICE VERIFIED — прогон на физическом ARM64, реальные критерии успеха
```

Instrumentation на CI-эмуляторе (x86_64) **не является** device verification:
native llama.cpp собирается только под arm64-v8a, эмулятор не доказывает
ни производительность, ни Hands на реальном экране.

## Физическое устройство

Тестовый аппарат определяется через `DeviceCapabilityManager.probe()`:
`ro.product.model`, `ro.build.version.release/sdk`, `ro.product.cpu.abi`.

Отчёт сохраняется в `docs/device-profile.md`.
