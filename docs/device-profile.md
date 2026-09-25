# Device Profile

## Целевое устройство

**POCO X3 NFC** — первый физический тестовый аппарат.

Характеристики **не предполагаются заранее** — приложение определяет
реальные параметры при подключении через `DeviceCapabilityManager`:

- `ro.product.model`
- `ro.build.version.release`
- `ro.build.version.sdk`
- `ro.product.cpu.abi`
- CPU и количество ядер
- RAM / доступная RAM
- storage / свободное storage
- GPU
- Vulkan
- OpenGL ES
- NNAPI
- NPU/ускорители, если доступны
- thermal state
- battery
- screen
- camera
- microphone
- audio
- network

## Замер профиля

`DeviceCapabilityManager.refresh()` собирает все параметры через
Android API и кеширует. `report()` формирует текстовый дамп для
диагностики:

```
model=POCO X3 NFC
android=13 sdk=33 abi=arm64-v8a
cpu_cores=8 ram=6144MB free=2048MB
storage=...MB free=...MB
gpu=3.2 vulkan=true nnapi=true
thermal=none battery=85%
network=wifi camera=true mic=true
backend_support=BackendSupport(cpu=true, gpu=true, npu=true, nnapi=true)
status=NOT PROVEN
```

## Использование

- `AIModelCompatibilityEngine` — какие модели потянет устройство;
- `ModelRouter` — local/remote/hybrid с учётом RAM/thermal/battery/network;
- `AvatarEngine` — уровень аватара по ресурсам;
- `ResourceManager` логика: `Battery 10% + High thermal = не запускать
  тяжёлую локальную модель`.

## Device Capability Matrix (ТЗ §29)

Менеджер постоянно знает:

CPU · GPU · RAM · Free RAM · Storage · Free Storage · NPU · Backend ·
Thermal · Battery · Network · Android.

Все эти значения показываются в разделе «Настройки → Устройство».

## Сводка по POCO X3 NFC (типичный профиль)

Параметры ниже — справочные; приложение измеряет реальные значения:

| Параметр | Значение |
|----------|----------|
| SoC | Qualcomm SM7150-AC Snapdragon 732G |
| Ядра | 8 (2×2.3 ГГц Kryo 470 Gold + 6×1.8 ГГц Kryo 470 Silver) |
| RAM | 6/8 ГБ |
| GPU | Adreno 618 |
| Экран | 6.67″ 120 Гц |
| Накопитель | 64/128 ГБ (UFS 2.1) |
| Android | до 13 (MIUI 14) |

Поддержка Vulkan и Adreno 618 делает доступными GPU-ускоренные варианты
моделей; NPU как таковой отсутствует, NNAPI эмулируется через GPU.
