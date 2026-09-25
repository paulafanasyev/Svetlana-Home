# Remote AI

Удалённый ИИ — вычисления за пределами телефона.

## Источники remote inference

1. **Personal Server** — собственный compute пользователя
   (см. `personal-server.md`).
2. **External Provider** — OpenAI-compatible провайдеры
   (см. `external-providers.md`).

## Удалённая мощность (ТЗ §45)

Удалённая RAM/VRAM **не являются** физической RAM телефона:

```
Телефон:      8 GB RAM
Мой сервер:  32 GB RAM, 24 GB VRAM
```

Пользовательский режим называется «Удалённая мощность».

## Когда выбирается remote

`ModelRouter.route()` выбирает `REMOTE`/`HYBRID`, когда:

- задача `HEAVY` (тяжёлая: большой контекст, vision, генерация);
- сеть доступна;
- сервер/провайдер доступен;
- тепловое состояние и батарея не критичные;
- режим это разрешает (`AUTO`/`MY_SERVER`/`EXTERNAL`, не `LOCAL_ONLY`).

## Гибрид (HYBRID)

```
Local preprocessing (телефон) → Remote inference (сервер) → Local UI
```

Например, `VisionManager.analyzeImage()` предобрабатывает изображение
локально (сжатие, кадр), отправляет на VLM, результат показывает
в локальном UI.

## Fallback

При ошибке remote:

1. Другой разрешённый бэкенд;
2. Локальная модель;
3. Честное сообщение пользователю.

Никогда remote не включается автоматически в режиме
`LOCAL_ONLY` (ТЗ §50).

## Латентность

Измеряется в `AIResult.latencyMs` и записывается в историю:
```
chat → PERSONAL_SERVER OK (842мс)
```
