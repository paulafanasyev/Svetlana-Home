# Roadmap

## Текущий статус

Статусы присваиваются строго по ТЗ §82: `VERIFIED` / `CODE VERIFIED` /
`CI VERIFIED` / `DEVICE VERIFIED` / `NOT PROVEN` / `BLOCKED`.

| Подсистема | Статус | Примечание |
|------------|--------|------------|
| Repository + CI | CI VERIFIED | public repo, dev gate + production gate зелёные |
| Lint | VERIFIED | 0 errors (исправлены 3 ошибки аудита) |
| Unit tests | CI VERIFIED | 73 теста, 0 неудач |
| Debug APK | CI VERIFIED | собирается в CI, артефакт `svetlana-home-debug-apk` |
| Release APK (R8) | CODE VERIFIED | собирается локально; подпись требует keystore в env |
| Launcher (ROLE_HOME) | CODE VERIFIED | DEVICE VERIFIED — requires POCO X3 NFC |
| App Registry + App Drawer | CODE VERIFIED | + device test `AppRegistryDeviceTest` |
| App Control Engine | CODE VERIFIED | proof chain с реальным `waitForPackage` |
| Hands (Accessibility) | CODE VERIFIED | async dispatchGesture исправлен и покрыт тестом |
| Mobile Harness | CODE VERIFIED | полная цепочка launcher → tree → action → verify |
| Voice (STT/TTS) | CODE VERIFIED | wake word — STT-polling, а не low-power детектор |
| Device Capability Manager | CODE VERIFIED | GPU через EGL, thermal через PowerManager |
| AI Model Registry + Compatibility | CODE VERIFIED | RAM/storage — жёсткие критерии |
| Local AI runtime | CODE VERIFIED | llama.cpp (GGUF) интегрирован; inference требует устройства |
| AI Provider abstraction + маршрутизаторы | CODE VERIFIED | HybridPipeline — реальный local→remote pipeline |
| Personal Server Manager | CODE VERIFIED | /health, /capabilities, /inference |
| Translator RU ↔ VI | CODE VERIFIED | локальный фразовый + AI-провайдер |
| Vision | CODE VERIFIED | camera translation требует устройства |
| Avatar Engine | CODE VERIFIED | L0/L1 доступны; L2/L3 не заявляются |
| Owner Identity (Keystore) | CODE VERIFIED | `Build.SERIAL` удалён |
| Permission Manager + Center | CODE VERIFIED | + device test |
| SecureKeyStore | CODE VERIFIED | небезопасный fallback отсутствует |
| History / Memory | CODE VERIFIED | |
| Onboarding | CODE VERIFIED | включает этап создания владельца |
| Физический POCO X3 NFC | NOT PROVEN | главное оставшееся условие |
| Production release (signing/AAB) | NOT PROVEN | ожидает keystore и device acceptance |

`DEVICE VERIFIED` присваивается только после теста на POCO X3 NFC
по `docs/device-test.md`.

## Что требует устройства

- Включение Hands пользователем и реальные UI-операции;
- ROLE_HOME назначение/снятие/перезагрузка;
- Реальный Mobile Harness pipeline;
- Установка локальной модели (решение пользователя) → load → inference → benchmark;
- Camera translation с провайдером зрения;
- Измерение performance-метрик;
- Permission revoke/regrant циклы;
- Offline-тест и LOCAL_ONLY data-egress тест.

## Следующие шаги

1. Тест на устройстве по `device-test.md`, заполнение device report.
2. Реальный inference тест на arm64: `LocalAiDeviceTest` с маленькой GGUF-моделью.
3. Рендер Real Avatar (реалистичный аватар с синхронизацией речи и губ)
   — отдельный движок, подключается при наличии ресурсов; AvatarEngine
   выбирает только зарегистрированные renderer'ы.
4. Локальный OCR для camera translation без провайдера.
5. Подписанный release: keystore в CI-секретах → signed APK + AAB + SHA-256.

## Не в планах (запрещено ТЗ)

- root, Termux, shell execution;
- скрытые загрузки моделей;
- скрытое управление;
- обход Android security model;
- WebbGL/Three.js как основа launcher;
- anime / cyberpunk / тяжёлая 3D-графика.
