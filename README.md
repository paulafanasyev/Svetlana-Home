# SVETLANA HOME

**Персональный AI Launcher для Android**

Репозиторий: https://github.com/paulafanasyev/Svetlana-Home

Hands + Voice + Vision + управление приложениями + Mobile Harness +
локальный ИИ + внешние AI-провайдеры + персональный сервер + RU↔VI
переводчик + адаптивный аватар.

Главная идея: пользователь управляет телефоном и установленными
приложениями естественным голосом или текстом, а Светлана сама
выбирает подходящий способ выполнения задачи в пределах разрешённых
пользователем возможностей Android.

---

## Содержание

- [Назначение](#назначение)
- [Архитектура](#архитектура)
- [Установка](#установка)
- [Launcher](#launcher)
- [Voice](#voice)
- [Hands](#hands)
- [Mobile Harness](#mobile-harness)
- [Vision](#vision)
- [Local AI](#local-ai)
- [Model Manager](#model-manager)
- [External Providers](#external-providers)
- [Personal Server](#personal-server)
- [Hybrid AI](#hybrid-ai)
- [Translator](#translator)
- [Avatar](#avatar)
- [Owner](#owner)
- [Permissions](#permissions)
- [Security](#security)
- [Privacy](#privacy)
- [Тестирование](#тестирование)
- [POCO X3 NFC](#poco-x3-nfc)
- [Известные ограничения](#известные-ограничения)
- [Документация](#документация)

---

## Назначение

Не «ещё один Android launcher», а персональный AI-телефон.

Пользователь говорит: «Света, открой Mobile Harness и сделай это».
Светлана:

```
понимает → находит приложение → проверяет разрешения →
запускает → использует Intent или Hands → выполняет действие →
проверяет результат → сообщает результат
```

## Архитектура

```
                         СВЕТЛАНА
                             │
            ┌────────────────┼────────────────┐
          Voice            Text             Vision
            └────────────────┼────────────────┘
                             │
                         AI Router
                             │
             ┌───────────────┼───────────────┐
           Local        Personal Server   External
                             │
                       Action Router
                             │
             ┌───────────────┴───────────────┐
          Android                         Hands
                             │
                         Applications
```

Подробно: [docs/architecture.md](docs/architecture.md).

## Установка

```bash
# Сборка debug APK
./gradlew assembleDebug

# Установка на устройство
adb install app/build/outputs/apk/debug/app-debug.apk
```

Требования: Android 8.0 (API 26)+. Приложение использует
compileSdk 36, AGP 8.11.0, Kotlin 1.9.22, Java 17.

После первого запуска — onboarding и мастер **«Настроить Светлану»**
(последовательный запрос необходимых разрешений).

## Launcher

- Поддержка официального механизма `ROLE_HOME` (Android 10+) и
  `CATEGORY_HOME` для старых версий;
- Главный экран минималистичен: часы, Living Orb, имя, реплика,
  поле ввода, быстрые действия;
- Работает после перезагрузки;
- App Drawer: список, поиск, избранное, недавние, скрытые.

Подробно: [docs/launcher.md](docs/launcher.md),
[docs/app-registry.md](docs/app-registry.md).

## Voice

- Русский STT и TTS через системные движки;
- Wake words: **Света**, **Светочка**, **Светлана**;
- Команды: «открой Telegram», «сделай скриншот», «пролистай вниз»,
  «нажми кнопку», «введи это значение», «переведи на вьетнамский»;
- Управление режимами ИИ голосом.

Подробно: [docs/voice.md](docs/voice.md).

## Hands

`SvetlanaAccessibilityService` — управление другими приложениями
только при наличии системного доступа, который предоставляет
сам пользователь.

Возможности: UI tree, click, long click, swipe, scroll, input,
global actions, screenshot, поиск элементов, проверка результата.

Доказательная цепочка:

```
PLAN → TARGET_APP_IDENTIFIED → PERMISSION_CHECKED →
ACTION_ATTEMPTED → ACTION_PERFORMED → RESULT_VERIFIED
```

«Команда отправлена» не равна «действие выполнено».

Подробно: [docs/hands.md](docs/hands.md), [docs/app-control.md](docs/app-control.md).

## Mobile Harness

Mobile Harness обнаруживается автоматически и управляется голосом
и Hands: UI tree, поиск элементов, нажатия, ввод, скриншоты,
проверка результата.

```
Голос → STT → Intent → Resolver → Launch → UI tree →
Target Finder → Action → Verification → Ответ Светланы
```

Подробно: [docs/mobile-harness.md](docs/mobile-harness.md).

## Vision

- Чтение экрана (UI tree, офлайн, без OCR);
- Скриншоты (Android 11+);
- Анализ изображения через VLM-провайдера;
- Camera translation (камера → OCR → перевод).

Подробно: [docs/vision.md](docs/vision.md).

## Local AI

**Главное правило**: локальная модель никогда не скачивается
автоматически — даже если найдена идеально совместимая модель.
Только после явного решения пользователя:

```
Предложить → Показать размер → Показать требования →
Решение пользователя → Скачать
```

После установки: Compatibility → Download → Install → Load →
Inference → Benchmark → Result.

Подробно: [docs/local-ai.md](docs/local-ai.md).

## Model Manager

- Реестр моделей с метаданными (RAM/storage/backend/GPU/NPU/лицензия);
- Compatibility Engine: VERIFIED / LIKELY COMPATIBLE / NOT PROVEN /
  INCOMPATIBLE;
- Benchmark: load time, first token latency, tokens/sec, RAM, thermal.

Подробно: [docs/model-registry.md](docs/model-registry.md),
[docs/model-compatibility.md](docs/model-compatibility.md).

## External Providers

Пользователь сам выбирает внешнего AI-провайдера. Поддерживаются
OpenAI-compatible endpoint'ы: OpenAI, Groq, OpenRouter, Together AI,
DeepInfra, локальный llama.cpp server и др.

- API keys хранятся в Android Keystore и не попадают в репозиторий;
- Провайдера можно выбрать, сменить, отключить;
- Прозрачность: «Света, какой ИИ сейчас отвечает?»

Подробно: [docs/external-providers.md](docs/external-providers.md).

## Personal Server

Пользователь подключает собственный compute: домашний ПК, VPS, NAS,
GPU server, бесплатный/условно бесплатный cloud compute.

- Health check, capabilities (CPU/RAM/GPU/VRAM), remote inference;
- Удалённая RAM/VRAM не считается физической RAM телефона —
  это режим **«Удалённая мощность»**;
- Приложение не гарантирует, что ресурс бесплатен.

Подробно: [docs/personal-server.md](docs/personal-server.md).

## Hybrid AI

- Телефон: Voice, Hands, Launcher, preprocessing, lightweight AI;
- Сервер: Heavy LLM, Heavy Vision, RAG, embeddings, Avatar AI;
- Model Router: `LOCAL` / `REMOTE` / `HYBRID` с учётом сложности,
  приватности, батареи, сети, thermal, latency;
- Режим `LOCAL_ONLY` запрещает любую передачу данных наружу.

Подробно: [docs/hybrid-ai.md](docs/hybrid-ai.md),
[docs/remote-ai.md](docs/remote-ai.md).

## Translator

Русский ↔ Вьетнамский:

- текст;
- голос (STT → перевод → TTS);
- синхронный режим разговора с автоопределением языка;
- перевод через камеру.

Backend выбирает пользователь: AI-перевод или локальный словарь
(работает офлайн).

Подробно: [docs/translator.md](docs/translator.md).

## Avatar

Адаптивный режим по реальным ресурсам:

- Level 0 — **Living Orb** (световой шар);
- Level 1 — **Light Avatar**;
- Level 2 — **Realistic Avatar**;
- Level 3 — **Full Real Avatar**.

Деградация при нехватке ресурсов: Real → Light → Orb.

Стиль: Premium AI / Dark Glass / Liquid Light. Без anime, cyberpunk,
тяжёлой 3D-графики, WebGL/Three.js.

Подробно: [docs/avatar.md](docs/avatar.md).

## Owner

Один верифицированный владелец, один профиль Светланы.

- Android Keystore (hardware-backed, где доступно);
- системный диалог биометрии/PIN/пароля;
- PIN/пароль пользователя не сохраняются;
- статус владельца не обходит Android security model.

Подробно: [docs/owner-identity.md](docs/owner-identity.md).

## Permissions

- Мастер «Настроить Светлану» запрашивает только нужные доступы;
- Permission Center показывает реальные состояния;
- постоянные разрешения используются повторно в рамках разрешённой
  функции;
- постоянный доступ ≠ безусловный доступ.

Подробно: [docs/permissions.md](docs/permissions.md).

## Security

Строго запрещено: root, Termux, произвольный shell, скрытые команды,
скрытая установка моделей и приложений, обход permissions и
Accessibility, скрытое управление, автоматическое подтверждение
опасных действий.

Опасные действия (звонки, сообщения, покупки, публикации, финансовые
действия, передача конфиденциальных данных) требуют подтверждения
пользователя.

Подробно: [docs/security.md](docs/security.md).

## Privacy

- PrivacyRouter решает, можно ли передавать текст, скриншот, UI tree,
  изображение, аудио, историю, документы конкретному бэкенду;
- Local-Only Mode запрещает external AI, personal server и cloud;
- персональная память не отправляется внешнему AI автоматически;
- внешние провайдеры необязательны.

Подробно: [docs/privacy.md](docs/privacy.md).

## Тестирование

```bash
# Unit-тесты
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# Сборка
./gradlew assembleDebug
```

CI: GitHub Actions (lint, unit tests, assembleDebug,
instrumentation tests, APK artifact).

Эмулятор не считается физическим устройством. План тестирования на
устройстве: [docs/device-test.md](docs/device-test.md).

## POCO X3 NFC

Первый физический тестный аппарат. Приложение не предполагает
характеристики устройства, а определяет реальные: model, Android, SDK,
ABI, CPU, RAM, storage, GPU, Vulkan, OpenGL ES, NNAPI, thermal,
battery, screen, camera, mic, network.

Профиль: [docs/device-profile.md](docs/device-profile.md).

## Известные ограничения

- **Inference runtime не bundled.** Локальный вывод требует
  подключаемого runtime (llama.cpp/ONNX). До его подключения
  локальный ИИ честно сообщает о недоступности — никаких
  симуляций ответов.
- **OCR на устройстве** ограничен: camera translation использует
  VLM-провайдера; офлайн-чтение экрана работает через UI tree.
- **Hands требует ручного включения** в системных настройках —
  это требование Android, приложение не может включить сервис само.
- **ROLE_HOME требует подтверждения пользователя** через системный
  диалог.
- **Wake word** использует системный распознаватель речи: на
  устройствах без распознавателя доступен текстовый ввод.
- **Реалистичный аватар** (Real Svetlana) — это отдельный движок,
  который подключается при наличии ресурсов и по тому же правилу
  «предложить → решение пользователя → скачать».

## Документация

Полная документация: [docs/](docs/) —
[architecture](docs/architecture.md),
[launcher](docs/launcher.md),
[app-registry](docs/app-registry.md),
[app-control](docs/app-control.md),
[mobile-harness](docs/mobile-harness.md),
[hands](docs/hands.md),
[voice](docs/voice.md),
[vision](docs/vision.md),
[translator](docs/translator.md),
[local-ai](docs/local-ai.md),
[model-registry](docs/model-registry.md),
[model-compatibility](docs/model-compatibility.md),
[external-providers](docs/external-providers.md),
[personal-server](docs/personal-server.md),
[remote-ai](docs/remote-ai.md),
[hybrid-ai](docs/hybrid-ai.md),
[avatar](docs/avatar.md),
[owner-identity](docs/owner-identity.md),
[permissions](docs/permissions.md),
[security](docs/security.md),
[privacy](docs/privacy.md),
[device-profile](docs/device-profile.md),
[device-test](docs/device-test.md),
[performance](docs/performance.md),
[roadmap](docs/roadmap.md).

Лицензии третьих сторон: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Статусные метки

Используются только: `VERIFIED`, `CODE VERIFIED`, `CI VERIFIED`,
`DEVICE VERIFIED`, `NOT PROVEN`, `BLOCKED`. Метка `DEVICE VERIFIED`
ставится только после фактического теста на устройстве.

## Текущий статус подсистем

> Жёсткое маркирование (аудит п.30): архитектура ≠ работающая функция.
> Обновляется по мере прохождения CI и device-тестов.

| Подсистема | Статус | Основание |
|------------|--------|-----------|
| Repository / CI | VERIFIED | public repo, GitHub Actions, APK artifact |
| Сборка APK | VERIFIED | debug 26.8 MB + release (R8), arm64-v8a |
| Lint | VERIFIED | 0 errors |
| Unit-тесты | VERIFIED | 101 тест, 0 неудач |
| Instrumented-тесты | CI VERIFIED | 23 androidTest-класса; эмулятор ≠ устройство |
| Launcher (ROLE_HOME) | CODE VERIFIED | Home-подсказка + раздел «Главный экран» с ActivityResult |
| App Registry / Drawer | CODE VERIFIED | **scan() теперь вызывается** — drawer реален; категории + capability matrix |
| App Control + proof chain | CODE VERIFIED | `LaunchVerifier` ждёт foreground-переход |
| Hands (dispatchGesture) | CODE VERIFIED | async-callback исправлен; нужен device-тест |
| Mobile Harness | CODE VERIFIED | нужны device-тесты |
| Voice (STT/TTS) | CODE VERIFIED | системные STT/TTS; wake word — polling |
| Wake word | NOT PROVEN | не always-on low-power детектор |
| Vision | CODE VERIFIED | нужны device-тесты |
| Local AI runtime | CODE VERIFIED | llama.cpp встроен; **проверка модели после установки** (chain) |
| Model Registry / Compatibility | CODE VERIFIED | требуется device-проверка совместимости |
| External AI Providers | CODE VERIFIED | **полный config UI**: endpoint→key→/models→выбор→test inference→save |
| Personal Server | CODE VERIFIED | /health, /capabilities, /inference |
| Hybrid AI | CODE VERIFIED | `HybridPipeline`: privacy→preprocess→sanitize→remote→postprocess |
| Privacy / LOCAL_ONLY | CODE VERIFIED | `PrivacyPolicy` + device-тест блокировки egress |
| Translator RU↔VI | CODE VERIFIED | multilingual STT + device-тест обоих направлений |
| Owner Identity | CODE VERIFIED | Android Keystore; `Build.SERIAL` убран |
| Permissions | CODE VERIFIED | **геолокация разделена**: permission vs location services |
| Avatar Engine | CODE VERIFIED | `AvatarFallback`: L0/L1 доступны; false-capability покрыт unit-тестами |
| Device Capability | CODE VERIFIED | GPU через EGL + vendor; thermal через PowerManager |
| Опасные действия | CODE VERIFIED | `ActionRiskPolicy` блокирует SendMessage/MakeCall/Share |
| Настройки | CODE VERIFIED | **разделены**: настройки Светланы vs «Системные настройки телефона» |
| Physical POCO X3 NFC | NOT PROVEN | устройство не подключено к этой среде |
| Production release | NOT PROVEN | R8+AAB собираются; подпись требует keystore в секретах |

---

© 2025 SVETLANA HOME
