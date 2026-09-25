# Owner Identity (ТЗ §23, §24, §89)

## MVP

1 устройство · 1 основной владелец · 1 основной профиль Светланы.

## Реализация

`OwnerIdentity`:

- Ключ владельца живёт в **Android Keystore** (`AndroidKeyStore`,
  AES-256-GCM, hardware-backed там, где доступно).
- Верификация — через системный диалог аутентификации
  (`BiometricPrompt`: биометрия / PIN / пароль устройства).
- `prepareChallenge()` / `verifyChallenge()` — challenge-response
  ключом из Keystore.
- `biometricAvailable()` — проверка доступности биометрии.

## Чего здесь НЕТ

- PIN и пароль пользователя **не сохраняются** и не запрашиваются
  приложением;
- ключи не выгружаются из Keystore;
- профиль владельца **не даёт** root, скрытый Accessibility, скрытый
  микрофон, скрытую камеру, обход permission или обход системного
  подтверждения (ТЗ §28).

## Owner Mode

После успешной настройки владельцу доступны все разрешённые функции:
Voice, Hands, Vision, App Control, Mobile Harness, Local AI, External
AI, Personal Server, Model Manager, Translator, Avatar, Settings.

Но Owner Mode не обходит Android security model.

## Постоянный доступ ≠ безусловный доступ (ТЗ §59)

Даже если пользователь один раз дал разрешение, Светлана может
использовать его повторно — но только в пределах разрешённой функции
и Android security model.

## Тест-план

- [ ] Owner создан
- [ ] Owner verification работает
- [ ] Android Keystore используется (`keyStoreBacked() == true`)
- [ ] Биометрия/PIN через системный диалог
- [ ] Опасные действия всё равно требуют подтверждения
