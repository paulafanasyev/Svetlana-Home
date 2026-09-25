# Remote AI / Hybrid AI

## Hybrid AI (ТЗ §46)

Задачи разделяются между телефоном и сервером:

```
Телефон
├── Voice
├── Hands
├── Launcher
├── preprocessing
└── lightweight AI

Server
├── Heavy LLM
├── Heavy Vision
├── RAG
├── embeddings
└── Avatar AI
```

## ModelRouter (ТЗ §47)

Определяет `LOCAL` / `REMOTE` / `HYBRID` с учётом:

- сложности задачи;
- выбранного провайдера;
- выбранного режима;
- privacy;
- battery;
- network;
- thermal;
- latency;
- model capability.

```kotlin
val routing = modelRouter.route(TaskComplexity.HEAVY)
// Routing(decision=HYBRID, backend=HYBRID, reason="Авто: гибрид local+server")
```

## Режимы AI (ТЗ §40)

| Режим | Поведение |
|-------|-----------|
| `LOCAL_ONLY` | Только устройство. Всё наружу запрещено |
| `LOCAL_FIRST` | Сначала локальная модель, сервер для тяжёлых задач |
| `AUTO` | Адаптивный выбор по ресурсам и сети |
| `MY_SERVER` | Запросы идут на сервер пользователя |
| `EXTERNAL` | Запросы идут к выбранному внешнему провайдеру |

## AIRouter

- `chat(prompt, complexity)` — основной цикл;
- `currentBackend()` / `currentBackendLabel()` — прозрачность для
  пользователя;
- `fallbackOptions()` — варианты, когда локальный ИИ не справился.

## Fallback стратегия

1. Локальная модель (если установлена).
2. Персональный сервер (если доступен и разрешён).
3. Внешний провайдер (если настроен и разрешён).
4. Честное сообщение о недоступности.

При `LOCAL_ONLY` шаги 2–3 блокируются — см. `privacy.md`.

## Тест-план (ТЗ §73)

```
Local preprocessing → Remote inference → Local UI → Result
```

- [ ] Hybrid AI работает
- [ ] Local-only работает
- [ ] Offline работает (облачные функции честно сообщают отсутствие сети)
- [ ] Fallback корректно переключается
