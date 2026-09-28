# Model Status — жизненный цикл локальной модели

> `isAvailable()` слишком оптимистично. Установленный файл ≠ рабочая модель.

## Конечный автомат

```
NOT_INSTALLED
  ↓  (пользователь явно нажал «Скачать»)
INSTALLED
  ↓  (magic bytes / формат проверены)
FORMAT_VERIFIED
  ↓  (runtime загружен в процесс)
RUNTIME_READY
  ↓  (модель загружена в память)
MODEL_LOADED
  ↓  (реальный inference, ответ непустой)
INFERENCE_VERIFIED
  ↓  (vision inference через multimodal input)
VISION_VERIFIED
  ↓  (прогон на физическом устройстве)
DEVICE_VERIFIED
```

Каждый шаг строгий:

```
Installed         ≠ usable
RuntimeReady      ≠ modelLoaded
ModelLoaded       ≠ inferenceVerified
InferenceVerified ≠ deviceVerified
```

## Итоговый статус выводится из фактов

```
status = when {
    deviceVerified     -> DEVICE_VERIFIED
    inferenceVerified  -> INFERENCE_VERIFIED
    modelLoaded        -> MODEL_LOADED
    runtimeReady       -> RUNTIME_READY
    formatVerified     -> FORMAT_VERIFIED
    installed          -> INSTALLED
    else               -> NOT_INSTALLED
}
```

Статус **вычисляется**, а не проставляется вручную.

## ModelManifest

Скачать и посчитать SHA-256 — недостаточно. Нужно **сверить** с доверенным
значением из манифеста:

```json
{
  "modelId": "gemma-4-e2b-it",
  "format": "litertlm",
  "size": 2590000000,
  "sha256": "...",
  "capabilities": ["text", "vision", "audio", "tools"],
  "backends": ["cpu", "gpu", "npu"]
}
```

Проверка при установке:

```
download
  ↓ HTTPS
  ↓ HTTP code == 200
  ↓ size == manifest.size
  ↓ sha256(file) == manifest.sha256     ← сверка, не просто вычисление
  ↓ format magic bytes
  ↓ INSTALLED + FORMAT_VERIFIED
```

## Критическое правило (ТЗ §32, §33, §87)

Локальная модель **никогда** не скачивается автоматически. Только:

```
Предложить
  ↓ Показать размер
  ↓ Показать требования
  ↓ Пользователь решает
  ↓ Скачать
```

## UI выбора модели

```
Model A   [Используется]
Model B   [Сделать основной]
```

После выбора:

```
selected
  ↓ load
  ↓ test inference
  ↓ VERIFIED
```

Удалять **активную** модель запрещено.
