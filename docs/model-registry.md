# AI Model Registry

`AIModelRegistry` — каталог метаданных моделей (ТЗ §30).
Реестр содержит **только метаданные** — сами файлы моделей не
скачиваются и не хранятся.

## Поля модели

| Поле | Пример |
|------|--------|
| Name | Qwen2.5 1.5B Instruct (Q4_K_M) |
| Architecture | Qwen2 |
| Parameters | 1.5B |
| Quantization | Q4_K_M |
| Size | 1080 МБ |
| RAM requirement | 2048 МБ |
| Storage requirement | 1150 МБ |
| Backend | llama.cpp / onnxruntime |
| CPU support | да |
| GPU support | да/нет |
| NPU support | да/нет |
| Android support | minSdk |
| Context | 4096 |
| License | Apache 2.0 |
| Source | Qwen (Alibaba) |

## Модели в реестре

### LLM

- `qwen2.5-1.5b-instruct-q4` — Apache 2.0, Qwen
- `qwen2.5-3b-instruct-q4` — Apache 2.0, Qwen
- `qwen2.5-7b-instruct-q4` — Apache 2.0, Qwen (мощные устройства/сервер)
- `llama3.2-1b-instruct-q4` — Llama 3.2 Community License, Meta
- `gemma2-2b-instruct-q4` — Gemma Terms of Use, Google
- `aya-8b-q4` — CC BY-NC 4.0, Cohere For AI (RU+VI)

### STT / TTS

- `sherpa-onnx-ru-stt` — Apache 2.0
- `sherpa-onnx-ru-tts` — Apache 2.0

### Embeddings

- `rubert-embeddings-int8` — Apache 2.0, DeepPavlod

## Требования лицензий

Все модели в реестре имеют явную лицензию. Коммерчески ограниченные
модели (например CC BY-NC) помечены и не рекомендуются для
коммерческого использования (см. `THIRD_PARTY_NOTICES.md`).

## Добавление моделей

Реестр — это Kotlin-список `AIModel`. Добавление новой модели =
добавление записи с метаданными и проверкой лицензии.
