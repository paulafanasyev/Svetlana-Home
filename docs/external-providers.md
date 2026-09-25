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

- [ ] Пользователь выбирает провайдера
- [ ] Provider можно отключить
- [ ] Provider можно заменить
- [ ] API keys защищены (SecureKeyStore)
- [ ] Проверка соединения работает
