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

- [ ] APK собирается
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

- [ ] Device Capability Manager работает
- [ ] RAM определена
- [ ] CPU определён
- [ ] GPU определён
- [ ] Storage определён
- [ ] Thermal определён
- [ ] Backend capabilities определены

### Локальный ИИ

- [ ] AI Model Registry работает
- [ ] Compatibility Engine работает
- [ ] Подходящие модели определяются
- [ ] Несовместимые модели определяются
- [ ] Модель НЕ скачивается автоматически
- [ ] Download происходит только после выбора пользователя
- [ ] Local AI работает после добровольной установки
- [ ] Benchmark работает

### Внешний AI

- [ ] External AI Providers работают
- [ ] Пользователь выбирает провайдера
- [ ] Provider можно отключить
- [ ] Provider можно заменить
- [ ] API keys защищены

### Personal Server

- [ ] Personal Server работает
- [ ] Server health check
- [ ] Server capabilities
- [ ] Remote inference

### Hybrid / offline

- [ ] Hybrid AI работает
- [ ] Local-only работает
- [ ] Offline работает

### Переводчик

- [ ] RU → VI
- [ ] VI → RU
- [ ] Voice translation
- [ ] Sync translation
- [ ] Camera translation

### Avatar

- [ ] Orb работает
- [ ] Avatar Engine работает
- [ ] Real Avatar capability проверена
- [ ] Resource-based fallback работает

### Безопасность

- [ ] Нет root
- [ ] Нет Termux
- [ ] Нет shell execution
- [ ] Нет обхода permissions
- [ ] Нет скрытых загрузок
- [ ] Нет скрытого управления
- [ ] Нет автоматического подтверждения опасных действий

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
| `LocalOnlyDeviceTest` | LOCAL_ONLY физически блокирует egress любых данных |

