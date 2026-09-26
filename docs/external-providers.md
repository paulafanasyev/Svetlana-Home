# Внешние AI-провайдеры (ТЗ §36–§41, §88)

## Принцип

Пользователь сам выбирает, какой внешний AI-провайдер использовать.
Провайдеры не спрятаны внутри приложения — все видны в разделе
«Провайдеры ИИ».

Внешние AI **не обязательны**: launcher, Hands, переводчик и локальный
словарь работают и без них (ТЗ §51).

## Абстракция AIProvider

```kotlin
interface AIProvider {
    val id: String
    val displayName: String
    val type: ProviderType     // LOCAL / OPENAI_COMPATIBLE / PERSONAL_SERVER / ...
    val backend: AIBackend
    fun capabilities(): ProviderCapabilities
    fun isConfigured(): Boolean
    fun isAvailable(): Boolean
    suspend fun chat(prompt: String, systemPrompt: String? = null): AIResult
    suspend fun vision(prompt: String, imageBytes: ByteArray): AIResult
    suspend fun testConnection(): AIResult
    fun redactedConfig(): String
}
```

## Шаблоны провайдеров (после исследования API)

| Провайдер | Endpoint | Модель по умолчанию |
|-----------|----------|---------------------|
| OpenAI | `https://api.openai.com` | gpt-4o-mini |
| Groq | `https://api.groq.com/openai` | llama-3.3-70b-versatile |
| OpenRouter | `https://openrouter.ai/api` | qwen/qwen-2.5-7b-instruct |
| Together AI | `https://api.together.xyz` | Qwen/Qwen2.5-7B-Instruct-Turbo |
| DeepInfra | `https://api.deepinfra.com` | Qwen/Qwen2.5-7B-Instruct |
| Локальный llama.cpp сервер | `http://127.0.0.1:8080` | local |

Все OpenAI-compatible endpoint'ы поддерживаются одним клиентом
`OpenAiCompatibleProvider` (`/v1/chat/completions`).

## Настройки (ТЗ §38)

Для каждого провайдера: Название, Тип, Статус, Модель, Endpoint,
API Key, Проверить соединение, Использовать, Отключить, Удалить.

API Key хранятся в `SecureKeyStore` (AndroidX Security + Android
Keystore) — **не попадают в репозиторий**, в логи и аналитику.
`redactedConfig()` всегда выводит ключ как `скрыт`.

### Полная цепочка настройки (аудит п.1)

`ProviderEditScreen` реализует цепочку, где HTTP 200 на `/models` ещё
**не** считается успехом:

```
Провайдер (шаблон)
  ↓
Endpoint       https://api.openai.com/v1
  ↓
API Key        ••••••••••••••••
  ↓
[Проверить подключение]  → testConnection() = реальный inference
  ↓                     «✓ Подключено» / «✕ Ошибка: ...»
[Получить модели]        → GET /v1/models
  ↓                     список реальных моделей с сервера
выбор модели из списка    ○ gpt-4o  ● gpt-4o-mini
  ↓
[Проверить модель]       → testModel(modelId) = реальный inference
  ↓                     «✓ Модель ответила: ... (280мс)»
[Сохранить]
```

Реализация: `OpenAiCompatibleProvider.listModels()` (GET `/v1/models`) +
`testModel()` (реальный `/v1/chat/completions` с выбранной моделью).

**Почему так:** ключ может быть действительным для endpoint, но не иметь
доступа к выбранной модели. Поэтому успех — это ответ модели на тестовый
запрос, а не 200 на список моделей.

### Безопасность ключа

OpenAI прямо не рекомендует размещать API keys в client-side/mobile
приложениях. Поддерживается два режима:

- **Прямое подключение (OpenAI-compatible)** — ключ в Keystore на
  устройстве, предупреждение в UI. Подходит для self-hosted endpoint'ов
  (vLLM, llama.cpp server, локальный сервер).
- **Personal Gateway** — ключ остаётся на вашем сервере, приложение
  обращается к нему (см. `personal-server.md`). Для production с OpenAI.

## Выбор провайдера (ТЗ §39)

- Основной AI
- Резервный
- Локальный
- Мой сервер

## Не переписываем выбор (ТЗ §41)

Если пользователь сказал «Используй только локальный ИИ», Светлана
**не** переключается на облако из-за ошибки. Ответ:

> Локальная модель не может выполнить эту задачу.

И варианты:
- Попробовать другую локальную модель
- Использовать мой сервер
- Использовать внешний AI
- Отмена

## Прозрачность (ТЗ §48)

Команда «Света, какой ИИ сейчас отвечает?» возвращает фактический
backend:

- «Сейчас используется локальная модель на устройстве.»
- «Сейчас используется ваш удалённый сервер.»
- «Сейчас используется внешний AI-провайдер.»

## Тест-план (ТЗ §71)

```
Configuration → Authentication → Connection test →
Model discovery → Inference → Result
```

- [x] Пользователь выбирает провайдера (`AiProvidersScreen`)
- [x] Provider можно отключить (`provider_disable`)
- [x] Provider можно заменить (`provider_use`)
- [x] API keys защищены (SecureKeyStore, нет insecure fallback)
- [x] Проверка соединения работает (`testConnection`)
- [x] Получение реальных моделей (`listModels` → `/v1/models`)
- [x] Проверка выбранной модели (`testModel` — реальный inference)
- [ ] Полный device flow с реальным ключом пользователя (device, NOT PROVEN)
