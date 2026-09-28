# Vision

`VisionManager` — зрение Светланы (ТЗ §22).

## Возможности

| Возможность | Реализация | Требования |
|------------|------------|------------|
| Screenshots | `HandsController.takeScreenshot()` | Hands, Android R+ |
| Чтение экрана | UI tree (уже содержит текст системы) | Hands |
| Анализ изображения | VLM через провайдера | сервер/внешний AI |
| Поиск элемента | `findElement()` по тексту | Hands |
| Визуальная верификация | `verifyTextVisible()` | Hands |

## Чтение экрана без OCR

UI tree, получаемый через Accessibility, содержит видимый текст
элементов. `VisionManager.readScreen()` извлекает его — это работает
офлайн и не требует OCR-модели.

## Анализ изображения

```
Изображение / скриншот
↓
Bitmap → JPEG (сжатие до 1024px, quality 80)
↓
AIRouter.vision() + PrivacyRouter (PrivacyDataType.IMAGE)
↓
VLM-провайдер: OpenAI-compatible (image_url base64) / Personal Server (/vlm)
↓
Описание на русском
```

**Аудит §9 (P0), исправлено:** раньше `analyzeImage()` вызывал text-only
`chat()` — Bitmap фактически не передавался модели, и Vision был
`BLOCKED`. Теперь изображение кодируется и реально уходит провайдеру:

- `VisionManager.encodeForVlm()` — сжатие и JPEG-кодирование;
- `AIRouter.vision()` — маршрутизация через `PrivacyDataType.IMAGE`;
- `OpenAiCompatibleProvider.vision()` — реальный OpenAI multimodal
  chat completions (`content`-массив с `type: image_url`, data URI base64);
- `PersonalServerProvider.vision()` — multipart POST на `/vlm` сервера,
  с предварительной проверкой, что сервер сообщил VLM-модель.

Если провайдер не поддерживает изображения (`capabilities().vision == false`),
запрос отклоняется заранее — без холостой отправки картинки. Результат
не выдумывается.

## Визуальная верификация

`verifyTextVisible(text)` — проверяет, появился ли текст на экране.
Используется в доказательной цепочке как `RESULT_VERIFIED`.

## Приватность

Передача скриншотов/UI tree/изображений наружу проходит через
`PrivacyRouter` (см. `privacy.md`). В режиме «Только устройство»
тяжёлые данные наружу не уходят.

## Тест-план

- [x] Байты изображения доходят до провайдера (unit, `VisionPipelineTest`)
- [x] Vision не использует text-only chat (unit)
- [x] Провайдер без vision отклоняется (unit)
- [x] Локальный путь honourит LOCAL_ONLY (unit, `PrivacyPolicyTest`)
- [ ] Скриншот экрана на физическом устройстве
- [ ] Чтение текста с экрана
- [ ] Поиск элемента на экране
- [ ] Визуальная верификация результата
- [ ] Анализ изображения (с провайдером зрения) — DEVICE NOT PROVEN
