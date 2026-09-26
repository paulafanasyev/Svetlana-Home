# Personal AI Server (ТЗ §42–§45)

## Идея

Пользователь может подключить собственный compute:
домашний ПК, VPS, сервер, NAS, другой компьютер, GPU server,
бесплатный/условно бесплатный cloud compute.

## Бесплатные серверы (ТЗ §43)

Приложение **не утверждает**, что ресурс гарантированно бесплатен.
Всегда показывается статус:

- Бесплатно
- Есть ограничения
- Пробный период
- Требуется платёжный метод
- Недоступно

Известные варианты для самостоятельной проверки:
Oracle Cloud Free Tier, другие free compute, free GPU environments,
self-hosted, домашний компьютер. Условия пользователь проверяет сам.

## PersonalServerManager (ТЗ §44)

Функции: `Подключить`, `Проверить`, `Настроить`, `Установить runtime`,
`Установить модель`, `Проверить GPU`, `Проверить RAM`, `Проверить VRAM`,
`Проверить inference`, `Остановить`, `Удалить`, `Отключить`.

Реализовано: подключение (endpoint + токен), health check
(`/health`), capabilities (`/capabilities`: CPU/RAM/GPU/VRAM/модели),
inference (`/inference`), отключение.

## Удалённая мощность (ТЗ §45)

Удалённая RAM/VRAM **не являются** физической RAM телефона.

```
Телефон:      8 GB RAM
Мой сервер:  32 GB RAM, 24 GB VRAM
```

Пользовательский режим называется «Удалённая мощность».

## Безопасность

Токен доступа хранится в `SecureKeyStore`, не попадает в репозиторий.
`PersonalServerProvider.redactedConfig()` выводит токен как `скрыт`.

## Граница ответственности: bootstrap сервера (аудит п.19, п.43)

Svetlana Home — клиентское приложение. Установка runtime и моделей
происходит **на стороне сервера** владельцем; приложение не выполняет
и не имитирует этот процесс. Приложение отвечает за:

- подключение (`setBaseUrl`, `setEnabled`);
- аутентификацию (`setToken` — токен в Keystore);
- health check (`/health`);
- обнаружение возможностей (`/capabilities` — CPU, RAM, GPU, VRAM);
- выбор модели и inference (`/inference`);
- измерение latency и отчёт пользователю.

Развёртывание самого сервера (docker/ollama/vllm и т.п.) — отдельная
серверная часть, не входит в APK. Приложение честно показывает статус
«сервер недоступен», если bootstrap не выполнен.

## Тест-план (ТЗ §72)

```
Server connection → Authentication → Health check →
CPU/RAM/GPU detection → Model discovery → Inference → Latency → Result
```

- [x] healthCheck реализован (реальный HTTP)
- [x] capabilities: CPU/RAM/GPU/VRAM парсятся из ответа
- [x] inference: реальный POST, latency измеряется
- [x] Токен в Keystore, redacted в логах
- [ ] Server health check на реальном сервере владельца
- [ ] Remote inference на реальном сервере владельца
