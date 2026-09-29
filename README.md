# SVETLANA HOME

**Персональный AI Launcher для Android**

Репозиторий: https://github.com/paulafanasyev/Svetlana-Home

Hands + Voice + Vision + управление приложениями + Mobile Harness +
локальный ИИ + внешние AI-провайдеры + персональный сервер +
адаптивный аватар.

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
- [Avatar](#avatar)
- [Owner](#owner)
- [Permissions](#permissions)
- [Security](#security)
- [Privacy](#privacy)
- [Тестирование](#тестирование)
- [Физическое устройство](#физическое-устройство)
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

### Скачать готовый APK

Подписанный release APK доступен в [GitHub Releases](https://github.com/paulafanasyev/Svetlana-Home/releases/latest):

[⬇ Скачать Svetlana Home](https://github.com/paulafanasyev/Svetlana-Home/releases/latest/download/Svetlana-Home-v1.2.0-arm64.apk)

> **v1.2.0** — исправлен краш при запуске (`LocalLifecycleOwner`, Lifecycle 2.8.3),
> все нативные библиотеки 16 KB-совместимы, автоматически публикуется из CI
> по тегу `v*` с проверкой подписи через `apksigner`.

1. Откройте ссылку на Android-устройстве и скачайте APK;
2. Разрешите установку из неизвестных источников
   (Настройки → Приложения → Специальный доступ → Установка неизвестных приложений);
3. Откройте скачанный APK и установите;
4. Запустите Svetlana Home и пройдите онбординг.

Проверить целостность можно по SHA-256 из `checksums.txt` в том же релизе.
 APK подписан сертификатом `CN=Svetlana Home, OU=Mobile AI, O=Paul Afanasyev`.
Сборка рассчитана на **arm64-v8a** (большинство современных устройств).

### Собрать из исходного кода

```bash
# Сборка debug APK
./gradlew assembleDebug

# Установка на устройство
adb install app/build/outputs/apk/debug/app-debug.apk

# Подписанный release APK (нужен keystore в переменных окружения)
SVETLANA_STORE_FILE=<путь> SVETLANA_STORE_PASSWORD=<пароль> \
SVETLANA_KEY_ALIAS=svetlana SVETLANA_KEY_PASSWORD=<пароль> \
./gradlew assembleRelease
```

Требования: Android 8.0 (API 26)+. Приложение использует
compileSdk 36, AGP 8.11.0, Kotlin 1.9.22, Java 17.

После первого запуска — onboarding и мастер **«Настроить Светлану»**
(последовательный запрос необходимых разрешений).

## Launcher

- Поддержка официального механизма `ROLE_HOME` (Android 10+) и
  `CATEGORY_HOME` для старых версий;
- Главный экран — три страницы (свайп между ними):
  1. **Голос** — Living Orb и голосовое общение; отдельной кнопки микрофона нет, ручное прослушивание запускается касанием орба;
  2. **Чат** — текстовый диалог со Светланой;
  3. **Приложения и настройки** — App Drawer и разделы настроек;
- Работает после перезагрузки;
- App Drawer: список, поиск, избранное, недавние, скрытые;
- **Светлая и тёмная тема** (по системе / тёмная / светлая) — раздел
  «О Светлане» → «Тема»; в светлой теме используется логотип.

Подробно: [docs/launcher.md](docs/launcher.md),
[docs/app-registry.md](docs/app-registry.md).

## Voice

- Русский STT и TTS через системные движки;
- Wake words: **Света**, **Светочка**, **Светлана**; ручной запуск голосового ввода — касанием орба;
- Команды: «открой Telegram», «сделай скриншот», «пролистай вниз»,
  «нажми кнопку», «введи это значение»;
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
- При сохранении настроек провайдер **автоматически становится активным**
  (режим переключается на «Внешний провайдер»);
- Полная цепочка проверки: Endpoint → Ключ → `/models` → выбор модели
  (можно ввести вручную) → тестовый inference → сохранение;
- Нормализация endpoint: `https://api.openai.com` и
  `https://api.openai.com/v1` оба дают `/v1/chat/completions`;
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

## Физическое устройство

Первый физический тестный аппарат — определяется автоматически при подключении; конкретная модель не фиксируется в проекте. Приложение не предполагает
характеристики устройства, а определяет реальные: model, Android, SDK,
ABI, CPU, RAM, storage, GPU, Vulkan, OpenGL ES, NNAPI, thermal,
battery, screen, camera, mic, network.

Профиль: [docs/device-profile.md](docs/device-profile.md).

## Известные ограничения

- **Локальный ИИ — CPU-only.** В сборку встроен llama.cpp runtime
  (`llama-android`, arm64-v8a), но без Vulkan/OpenCL backends. Метаданные
  моделей согласованы с этим (`gpuSupport=false`), чтобы не заявлять GPU
  там, где его нет (аудит 7.1). Вывод идёт на CPU/NEON — медленнее, но
  честно; пользователь видит реальную скорость в benchmark'е.
- **OCR на устройстве** ограничен: анализ изображений идёт через
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
| Сборка APK | VERIFIED | debug + release (R8, подписанный), arm64-v8a; **release собирается с LiteRT-LM** — liblitertlm_jni.so (21.8 MB) в APK, все нативные библиотеки 16 KB-совместимы |
| Lint | VERIFIED | 0 errors, 60 warnings + 1 hint (CI artifact run #74) |
| Unit-тесты | VERIFIED | 195 тестов, 0 неудач (VisionPipeline, PostconditionLogic, VoiceSessionStateMachine, CompositeRuntime, ModelFormat magic, расширение .litertlm, trusted SHA-256) |
| Instrumented-тесты | CI VERIFIED | 24 androidTest-класса; эмулятор ≠ устройство |
| Launcher (ROLE_HOME) | CODE VERIFIED | Home-подсказка + раздел «Главный экран» с ActivityResult; **onboarding при первом запуске** |
| App Registry / Drawer | CODE VERIFIED | **scan() теперь вызывается** — drawer реален; категории + capability matrix |
| App Control + proof chain | CODE VERIFIED | `LaunchVerifier` ждёт foreground-переход |
| **Длинные Hands-цепочки** | CODE VERIFIED | **ComposeMessage**: открыть→найти чат→напечатать→отправить→проверить; Compound с retarget |
| Hands (dispatchGesture) | CODE VERIFIED | async-callback исправлен; **target-specific postconditions** (`PostconditionLogic`); нужен device-тест |
| Mobile Harness | CODE VERIFIED | нужны device-тесты |
| Voice (STT/TTS) | CODE VERIFIED | системные STT/TTS; **on-device recognizer приоритетнее** (API 31+); **VoiceSessionStateMachine** против гонок |
| **Фоновый голосовой агент** | CODE VERIFIED | **VoiceAssistantService** (foreground, тип microphone) + переключатель в настройках |
| **Мгновенный голосовой отклик** | CODE VERIFIED | **filler «Дай подумать…» + SSE-стриминг** ответа |
| Wake word | NOT PROVEN | не always-on low-power детектор (STT-polling); state machine защищает от гонок |
| Vision | CODE VERIFIED | **изображение реально передаётся**: JPEG→`AIRouter.vision()`→OpenAI image_url base64 / `/vlm` сервера / **локальный LiteRT-LM**; раньше был BLOCKED (вызывался text-only chat) |
| Local AI runtime | CODE VERIFIED | **два runtime'а**: llama.cpp (GGUF, text) + **LiteRT-LM 0.17.1** (.litertlm, multimodal, tool use); диспетчер `CompositeInferenceRuntime` по формату модели; проверка модели после установки |
| Model Registry / Compatibility | CODE VERIFIED | LiteRT-модели указывают на **реальные публичные файлы** (gemma-4-E2B-it, Qwen2.5-1.5B q8) с **доверенным SHA-256**; **magic-проверка настоящего контейнера "LITERTLM"** (не TFL); файлы сохраняются как `.litertlm` (Engine отклоняет .bin) |
| External AI Providers | CODE VERIFIED | **полный config UI**: endpoint→key→/models→выбор→test inference→save; Custom preset; **нормализация /v1** |
| Personal Server | CODE VERIFIED | /health, /capabilities, /inference + кнопка «Проверить inference» в UI |
| Hybrid AI | CODE VERIFIED | `HybridPipeline`: privacy→preprocess→sanitize→remote→postprocess |
| Privacy / LOCAL_ONLY | CODE VERIFIED | `PrivacyPolicy` + device-тест блокировки egress |
| Owner Identity | CODE VERIFIED | Android Keystore; `Build.SERIAL` убран |
| Permissions | CODE VERIFIED | **геолокация разделена**: permission vs location services |
| Avatar Engine | CODE VERIFIED | `AvatarFallback`: L0/L1 доступны; false-capability покрыт unit-тестами |
| Device Capability | CODE VERIFIED | GPU через EGL + vendor; thermal через PowerManager |
| Опасные действия | CODE VERIFIED | `ActionRiskPolicy` блокирует SendMessage/MakeCall/Share/ComposeMessage |
| Настройки | CODE VERIFIED | **разделены**: настройки Светланы vs «Системные настройки телефона» |
| Physical device | NOT PROVEN | устройство не подключено к этой среде |
| Production release | NOT PROVEN | R8+AAB собираются; подпись требует keystore в секретах |

---

© 2025 SVETLANA HOME
