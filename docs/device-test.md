# Device Test Plan

План тестирования на физическом устройстве. Эмулятор не считается
физическим устройством (ТЗ §77).

## Подготовка

1. Собрать APK: `./gradlew :app:assembleDebug`.
2. Установить на POCO X3 NFC:
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Пройти onboarding и мастер «Настроить Светлану».
4. Включить Hands: Настройки → Специальные возможности → Светлана Hands.

## Acceptance Test (ТЗ §83)

### Репозиторий и сборка

- [x] APK собирается (debug 26 MB + release 8.6 MB R8, CI VERIFIED)
- [x] CI работает (dev gate + production gate + instrumentation)
- [x] Unit-тесты проходят (80 тестов)
- [x] Нет секретов в репозитории (secret scan в CI)
- [ ] APK установлен на POCO X3 NFC

### Владелец

- [ ] Owner создан
- [ ] Owner verification работает
- [ ] Android Keystore используется

### Разрешения

- [ ] Permission Center работает
- [ ] Разрешения выдаются штатно
- [ ] Разрешения можно изменить позже
- [ ] Постоянные разрешения используются повторно

### Launcher

- [ ] ROLE_HOME работает
- [ ] Launcher работает после reboot
- [ ] App Registry работает
- [ ] App Drawer работает
- [ ] Search работает

### Voice

- [ ] Voice работает
- [ ] Русский STT
- [ ] Русский TTS
- [ ] Wake word architecture

### Hands

- [ ] Hands работает
- [ ] UI tree
- [ ] Click
- [ ] Swipe
- [ ] Scroll
- [ ] Input
- [ ] Screenshot
- [ ] Verification

### Mobile Harness

- [ ] Mobile Harness найден
- [ ] Mobile Harness запущен
- [ ] Mobile Harness управляется голосом
- [ ] Mobile Harness управляется Hands
- [ ] Реальное действие выполнено
- [ ] Результат подтверждён

### Устройство

- [x] Device Capability Manager работает (device test)
- [x] RAM определена
- [x] CPU определён
- [x] GPU определён (EGL renderer + vendor)
- [x] Storage определён
- [x] Thermal определён (PowerManager)
- [x] Backend capabilities определены
- [ ] Реальные значения на POCO X3 NFC сняты в device report

### Локальный ИИ

- [x] AI Model Registry работает
- [x] Compatibility Engine работает (RAM/storage — жёсткие критерии)
- [x] Подходящие модели определяются
- [x] Несовместимые модели определяются
- [x] Модель НЕ скачивается автоматически (нет авто-вызовов install)
- [x] Download происходит только после выбора пользователя (UI: размер, RAM, free space)
- [x] Inference runtime подключён (llama.cpp, arm64)
- [ ] Local AI работает после добровольной установки (требует устройства)
- [ ] Benchmark работает (требует установленной модели)

### Внешний AI

- [x] External AI Providers работают (OpenAI-compatible, реальный HTTP)
- [x] Пользователь выбирает провайдера
- [x] Provider можно отключить
- [x] Provider можно заменить
- [x] API keys защищены (Keystore, нет insecure fallback)
- [ ] Реальный inference с ключом пользователя

### Personal Server

- [x] Personal Server работает (health/capabilities/inference)
- [x] Server health check
- [x] Server capabilities (CPU/RAM/GPU/VRAM)
- [ ] Remote inference на реальном сервере владельца

### Hybrid / offline

- [x] Hybrid AI работает (HybridPipeline: preprocess→sanitize→remote→postprocess)
- [x] Local-only работает (PrivacyPolicy + device test блокировки egress)
- [x] Offline работает (launcher/registry/hands/translator без сети)
- [ ] Offline-тест на устройстве

### Переводчик

- [x] RU → VI (unit + device test)
- [x] VI → RU (unit + device test)
- [x] Voice translation (multilingual STT + TTS на целевом языке)
- [x] Sync translation (translateConversationCycle, автоопределение)
- [ ] Camera translation (требует устройства)

### Avatar

- [x] Orb работает
- [x] Avatar Engine работает (decide: requested → available → reason)
- [x] Real Avatar capability проверена (L2/L3 не заявляются без renderer)
- [x] Resource-based fallback работает (highestAvailableAtOrBelow)
- [ ] FPS/thermal замеры на устройстве

### Безопасность

- [x] Нет root
- [x] Нет Termux
- [x] Нет shell execution
- [x] Нет обхода permissions
- [x] Нет скрытых загрузок
- [x] Нет скрытого управления
- [x] Нет автоматического подтверждения опасных действий

### Финал

- [ ] Performance проверен на POCO X3 NFC
- [ ] Device report готов

## Доказательные цепочки (ТЗ §84)

### Mobile Harness

```
Code → CI → APK → POCO X3 NFC → Hands enabled →
Mobile Harness → UI element → Real action → Result verified
```

### Local AI

```
Device scan → Compatibility → User chooses model → Download →
Install → Inference → Benchmark → DEVICE VERIFIED
```

### External AI

```
User selects provider → Configuration → Authentication →
Connection → Inference → Verified
```

### Personal Server

```
User connects server → Authentication → Capability scan →
Model → Inference → Verified
```

## Снятие device report

Диагностика → Устройство показывает все измеренные параметры.
Лог доказательной цепочки пишется в историю
(`История Светланы` → Mobile Harness / Hands).

## Автоматизированные instrumented-тесты

Инструментальные тесты (`app/src/androidTest/`) реализуют те же
цепочки в автоматическом режиме. Они работают на эмуляторе/устройстве
через CI и не заменяют ручной acceptance-тест выше, но доказывают, что
каждая подсистема действительно выполняет свои операции на Android
runtime, а не только компилируется.

| Тест | Что доказывает |
|------|----------------|
| `SvetlanaDeviceTest` | Базовый запуск, ServiceLocator, настройки |
| `LauncherDeviceTest` | CATEGORY_HOME, resolve launcher Activity, ROLE_HOME API |
| `OwnerIdentityDeviceTest` | Создание владельца, Keystore, challenge/verify |
| `SecureKeyStoreDeviceTest` | Keystore без небезопасного fallback, отказ при отсутствии |
| `AppRegistryDeviceTest` | Реальный список приложений, aliases, favorites |
| `AppControlDeviceTest` | Действия над приложениями, capability matrix |
| `AppControlProofDeviceTest` | Proof chain: PLAN → IDENTIFIED → PERFORMED → VERIFIED |
| `DangerousActionDeviceTest` | DANGEROUS действия блокируются без подтверждения |
| `HandsDeviceTest` | Async dispatchGesture: await callback → ACTION_PERFORMED |
| `MobileHarnessDeviceTest` | Обнаружение → запуск → UI tree → действие → verification |
| `DeviceCapabilityManagerDeviceTest` | CPU/RAM/GPU/storage/thermal — реальные значения |
| `AvatarEngineDeviceTest` | Выбирается только доступный renderer (no false capability) |
| `ExternalProviderDeviceTest` | Конфигурация → соединение → inference |
| `LocalAiDeviceTest` | Совместимость → приоритеты → отчёт о inference |
| `TranslatorDeviceTest` | RU → VI и VI → RU, backend указывается |
| `VisionDeviceTest` | Скриншот, анализ изображения, честный отказ без провайдера |
| `PerformanceDeviceTest` | Реальные замеры: probe, registry scan, перевод, proof chain |
| `LocalOnlyDeviceTest` | LOCAL_ONLY физически блокирует egress любых данных |

