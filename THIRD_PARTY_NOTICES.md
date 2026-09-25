# THIRD PARTY NOTICES

SVETLANA HOME использует следующие библиотеки, SDK и технологии.
Все лицензии проверены и совместимы с использованием в этом проекте.

## AndroidX / Jetpack

| Компонент | Версия | Лицензия |
|-----------|--------|----------|
| androidx.core:core-ktx | 1.13.1 | Apache 2.0 |
| androidx.appcompat:appcompat | 1.7.0 | Apache 2.0 |
| androidx.activity:activity-compose | 1.9.0 | Apache 2.0 |
| androidx.lifecycle:lifecycle-* | 2.8.0 | Apache 2.0 |
| androidx.compose:compose-bom | 2024.05.00 | Apache 2.0 |
| androidx.compose.material3 | (BOM) | Apache 2.0 |
| androidx.compose.ui / foundation / animation | (BOM) | Apache 2.0 |
| androidx.compose.material:material-icons-extended | (BOM) | Apache 2.0 |
| androidx.datastore:datastore-preferences | 1.1.1 | Apache 2.0 |
| androidx.security:security-crypto | 1.1.0-alpha06 | Apache 2.0 |
| androidx.biometric:biometric | 1.2.0-alpha05 | Apache 2.0 |
| androidx.camera:camera-* | 1.3.3 | Apache 2.0 |
| androidx.test.ext:junit / espresso | 1.1.5 / 3.5.1 | Apache 2.0 |
| androidx.compose.ui:ui-test-junit4 | (BOM) | Apache 2.0 |

Compose Compiler 1.5.8 и Kotlin Compiler распространяются по
Apache 2.0 (JetBrains).

## Kotlin

| Компонент | Версия | Лицензия |
|-----------|--------|----------|
| Kotlin stdlib / coroutines | 1.9.22 / 1.8.1 | Apache 2.0 |
| kotlinx-serialization-json | 1.6.3 | Apache 2.0 |

## Сеть

| Компонент | Версия | Лицензия |
|-----------|--------|----------|
| OkHttp | 4.12.0 | Apache 2.0 |

## Инструменты сборки

| Компонент | Версия | Лицензия |
|-----------|--------|----------|
| Android Gradle Plugin | 8.11.0 | Apache 2.0 |
| Gradle | 8.14.3 | Apache 2.0 |
| Android SDK Build Tools (aapt2) | 35.0.0 | Android SDK License |

## Модели (реестр, опциональная установка)

Модели **не входят** в приложение и не скачиваются автоматически
(см. `docs/local-ai.md`). Указаны метаданные для реестра:

| Модель | Лицензия | Источник |
|--------|----------|----------|
| Qwen2.5 (1.5B / 3B / 7B) Instruct | Apache 2.0 | Alibaba |
| Llama 3.2 1B Instruct | Llama 3.2 Community License | Meta |
| Gemma 2 2B Instruct | Gemma Terms of Use | Google |
| Aya 8B | CC BY-NC 4.0 | Cohere For AI |
| Sherpa-ONNX STT/TTS | Apache 2.0 | Sherpa-ONNX |
| RuBERT | Apache 2.0 | DeepPavlov |

**Внимание:** модели с лицензией CC BY-NC не рекомендуются для
коммерческого использования. Пользователь принимает решение об установке
самостоятельно.

## Системные сервисы

Приложение использует системные сервисы Android, доступные через
публичный API:

- SpeechRecognizer (STT);
- TextToSpeech (TTS);
- AccessibilityService (Hands) — включается пользователем;
- RoleManager (ROLE_HOME) — назначается пользователем;
- Android Keystore (владелец, секреты);
- CameraX (камера);
- PackageManager / LauncherApps (реестр приложений).

## Условия использования

Распространение исходного кода проекта — на усмотрение владельца
репозитория. Приведённые выше лицензии третьих сторон сохраняют свою
юридическую силу независимо от условий распространения проекта.
