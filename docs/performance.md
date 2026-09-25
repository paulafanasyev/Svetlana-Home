# Performance (ТЗ §76)

## Что измеряем на POCO X3 NFC

| Метрика | Способ измерения |
|---------|------------------|
| Launcher startup | Время от `Activity.onCreate` до первого кадра |
| RAM | `ActivityManager.MemoryInfo` (`DeviceCapabilityManager`) |
| CPU | Количество ядер, загрузка профиля |
| GPU | OpenGL ES версия, наличие Vulkan |
| FPS | Compose frame clock в `AvatarEngine.updateFps()` |
| Battery | `BatteryManager.BATTERY_PROPERTY_CAPACITY` |
| Thermal | ThermalManager (там, где доступно) |
| Voice latency | STT → команда → ответ (мс) |
| Hands latency | action → результат (мс) |
| App launch latency | intent → `waitForPackage` (мс) |
| Local AI latency | `BenchmarkResult.firstTokenMs` |
| Remote AI latency | `AIResult.latencyMs` |
| Translation latency | STT → translate → TTS (мс) |
| Avatar FPS | frame clock |

## ResourceManager (ТЗ §62)

Учитывает: RAM, CPU, GPU, Storage, Battery, Thermal, Network, Server.

Пример правила:

```
Battery 10%  +  High thermal  =  не запускать тяжёлую локальную модель
```

Реализовано в `ModelRouter.route()` и `AvatarEngine.evaluate()`:
при низком заряде/высокой температуре выбирается более лёгкий режим.

## Benchmark (ТЗ §35)

`BenchmarkRunner.run()` измеряет:

- `loadTimeMs` — реальное время чтения файла модели;
- `firstTokenMs` — задержка первого токена (через runtime);
- `tokensPerSecond`;
- `ramUsedMb`;
- `cpuPercent`;
- `thermal`;
- `batteryImpact`;
- `contextStable`.

Только после фактического теста: `DEVICE VERIFIED`.

## Цели на POCO X3 NFC (справочно)

| Метрика | Цель |
|---------|------|
| Launcher startup | < 1.5 c |
| Orb FPS | 60 (с лёгкими анимациями) |
| Hands latency (click) | < 500 мс |
| Translation latency (text) | < 2 c (локальный словарь < 50 мс) |
| RAM приложения | < 250 МБ базово |

Фактические значения вносятся в device report после тестирования.
