# Roadmap

## Текущий статус

| Подсистема | Статус |
|------------|--------|
| Launcher (ROLE_HOME) | CODE VERIFIED |
| App Registry + App Drawer | CODE VERIFIED |
| App Control Engine + ActionRouter | CODE VERIFIED |
| Hands (Accessibility) | CODE VERIFIED (требует системного включения) |
| Voice (STT/TTS/Wake word) | CODE VERIFIED |
| Device Capability Manager | CODE VERIFIED |
| AI Model Registry + Compatibility | CODE VERIFIED |
| AI Provider abstraction + маршрутизаторы | CODE VERIFIED |
| Personal Server Manager | CODE VERIFIED |
| Translator RU ↔ VI | CODE VERIFIED |
| Vision | CODE VERIFIED |
| Avatar Engine | CODE VERIFIED |
| Owner Identity (Keystore) | CODE VERIFIED |
| Permission Manager + Center | CODE VERIFIED |
| History / Memory | CODE VERIFIED |
| Onboarding | CODE VERIFIED |

`DEVICE VERIFIED` присваивается только после теста на POCO X3 NFC
по `docs/device-test.md`.

## Что требует устройства

- Включение Hands пользователем и реальные UI-операции;
- ROLE_HOME назначение/снятие/перезагрузка;
- Реальный Mobile Harness pipeline;
- Benchmark установленной модели;
- Camera translation с провайдером зрения;
- Измерение performance-метрик.

## Следующие шаги

1. Тест на устройстве по `device-test.md`, заполнение device report.
2. Подключение inference runtime (llama.cpp/ONNX) как отдельного
   скачиваемого компонента — строго по правилу «предложить → решение
   пользователя → скачать».
3. Рендер Real Avatar (реалистичный аватар с синхронизацией речи и губ)
   — отдельный движок, подключается при наличии ресурсов.
4. Локальный OCR для camera translation без провайдера.
5. Инструментальные тесты (Espresso/Compose UI test) на устройстве.

## Не в планах (запрещено ТЗ)

- root, Termux, shell execution;
- скрытые загрузки моделей;
- скрытое управление;
- обход Android security model;
- WebbGL/Three.js как основа launcher;
- anime / cyberpunk / тяжёлая 3D-графика.
