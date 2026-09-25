# Архитектура SVETLANA HOME

> Персональный AI-телефон. Пользователь управляет телефоном голосом или текстом,
> Светлана сама выбирает способ выполнения задачи в рамках разрешённых
> пользователем возможностей Android.

## Общая схема (ТЗ §56)

```
                         СВЕТЛАНА
                             │
            ┌────────────────┼────────────────┐
            │                │                │
          Voice            Text             Vision
            │                │                │
            └────────────────┼────────────────┘
                             │
                         AI Router
                             │
             ┌───────────────┼───────────────┐
             │               │               │
           Local          Personal        External
             AI            Server         Provider
             │               │               │
             └───────────────┼───────────────┘
                             │
                       Action Router
                             │
             ┌───────────────┴───────────────┐
             │                               │
          Android                         Hands
             │                               │
             └───────────────┬───────────────┘
                             │
                         Applications
```

## Слои приложения

| Слой | Компонент | Назначение |
|------|-----------|------------|
| Ввод | `WakeWordEngine`, `SvetlanaSpeechRecognizer` | Слово пробуждения, русский STT |
| Вывод | `SvetlanaTts` | Русский TTS |
| Понимание | `CommandParser`, `ActionRouter` | Разбор команд на русском, маршрутизация |
| Управление | `AppControlEngine`, `IntentResolver`, `HandsController` | Выполнение действий |
| Доказательство | `ProofBuilder`, `ProofStep` | Доказательная цепочка |
| Приложения | `AppRegistry`, `AppRepository` | Обнаружение, capability matrix |
| Зрение | `VisionManager` | Скриншоты, чтение экрана, анализ |
| ИИ | `AIModelRegistry`, `AIModelCompatibilityEngine`, `AIRouter`, `ModelRouter`, `PrivacyRouter` | Модели, маршрутизация, приватность |
| Провайдеры | `AIProvider` + реализации | Local / OpenAI-compatible / Personal Server |
| Сервер | `PersonalServerManager` | Подключение пользовательского compute |
| Перевод | `TranslatorProviderManager`, `SvetlanaTranslator` | RU ↔ VI |
| Аватар | `AvatarEngine` | Адаптивный выбор режима |
| Владелец | `OwnerIdentity` | Профиль владельца, Android Keystore |
| Разрешения | `PermissionManager` | Мастер и Permission Center |
| Состояние | `DeviceCapabilityManager` | Реальные ресурсы устройства |
| Память | `PersonalMemory`, `HistoryManager` | История и персональная память |

## Принципы

1. **Приоритет способа управления** (ТЗ §16): Intent → Public API → Deep Link →
   PackageManager → Accessibility/Hands → подтверждение пользователя.
   Hands используется только когда стандартный API не справляется.
2. **Доказательная цепочка** (ТЗ §19): `PLAN → TARGET_APP_IDENTIFIED →
   PERMISSION_CHECKED → ACTION_ATTEMPTED → ACTION_PERFORMED → RESULT_VERIFIED`.
   «Команда отправлена» ≠ «действие выполнено».
3. **Не переписываем выбор пользователя** (ТЗ §41): при режиме «Только устройство»
   Светлана не уходит в облако при ошибке, а предлагает варианты.
4. **Никаких скрытых загрузок** (ТЗ §33): модель скачивается только после
   явного решения пользователя.
5. **Безопасность** (ТЗ §57): нет root, нет Termux, нет shell, нет обхода
   разрешений, опасные действия подтверждаются.
6. **Офлайн-способность** (ТЗ §63): launcher, реестр приложений, Hands,
   локальный ИИ работают без сети.

## Сборка и статусы

Статусные метки строго ограничены (ТЗ §82):
`VERIFIED`, `CODE VERIFIED`, `CI VERIFIED`, `DEVICE VERIFIED`, `NOT PROVEN`, `BLOCKED`.
Метка `DEVICE VERIFIED` ставится только после фактического теста на устройстве
(см. `docs/device-test.md`).
