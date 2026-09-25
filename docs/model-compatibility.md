# AI Model Compatibility Engine

`AIModelCompatibilityEngine` определяет, какая модель реально подходит
устройству (ТЗ §31).

## Уровни совместимости

| Уровень | Значение |
|---------|----------|
| `VERIFIED` | Подтверждено |
| `LIKELY COMPATIBLE` | Вероятно совместима |
| `NOT PROVEN` | Не доказано |
| `INCOMPATIBLE` | Несовместима |

## Критерии оценки

- **RAM** — с коэффициентом запаса 1.5 под систему и другие приложения;
- **Storage** — свободное место против требования модели;
- **CPU / ядра** — достаточно ли ядер;
- **GPU / NPU / NNAPI** — доступность ускорения;
- **Android SDK** — не ниже минимального;
- **Quantization / backend** — соответствие runtime;
- **Context** — объём контекста модели;
- **Thermal** — тепловое состояние;
- **Battery** — уровень заряда;
- **Benchmark** — фактический результат (после установки).

## Важное правило статусов

> `VERIFIED` на устройстве выставляется **только** после фактического
> benchmark (ТЗ §35). Предсказание совместимости никогда не
> выставляет `VERIFIED` — максимум `LIKELY COMPATIBLE`.

См. `BenchmarkRunner` в `local-ai.md`.

## Использование

```kotlin
val engine = AIModelCompatibilityEngine(device)
val report = engine.evaluate(model, caps)

report.canRunOnDevice   // true/false
report.level            // уровень совместимости
report.reasons          // почему
report.expectedPerf     // ожидаемая производительность
```

- `compatibleModels(registry)` — отфильтрованный и отсортированный по
  размеру список моделей, которые могут работать на устройстве.
- `bestFit(registry)` — лучшая кандидатура для предложения.

## Покрытие тестами

`AIModelCompatibilityEngineTest`:
- малая модель на 8 ГБ RAM — не INCOMPATIBLE;
- 7B на 2 ГБ RAM — INCOMPATIBLE;
- модель больше свободного storage — INCOMPATIBLE;
- `VERIFIED` не выставляется до benchmark;
- совместимые модели отсортированы по размеру.
