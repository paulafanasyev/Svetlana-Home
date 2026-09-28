# Owner Identity (ТЗ §23, §24, §89)

## MVP

1 устройство · 1 основной владелец · 1 основной профиль Светланы.

## Реализация

`OwnerIdentity`:

- Ключ владельца живёт в **Android Keystore** (`AndroidKeyStore`,
  AES-256-GCM, hardware-backed там, где доступно).
- **Ключ auth-bound** (аудит §19, исправлено): генерируется с
  `setUserAuthenticationRequired(true)` — операции этим ключом
  невозможны до успешной системной аутентификации. Раньше ключ не
  требовал аутентификации, и «owner verified» был просто фактом его
  наличия — это и было основанием для `NOT PROVEN`.
- Верификация — через `BiometricAuth` → системный диалог
  (`BiometricPrompt` + `CryptoObject`): биометрия / PIN / пароль
  устройства. Успешный колбэк разблокирует ключ — это и есть
  `OWNER VERIFIED`.
- `prepareAuthCipher()` — Cipher в ENCRYPT_MODE auth-bound ключом;
  `prepareChallenge()` / `verifyChallenge()` — challenge-response.
- `biometricAvailable()` — проверка доступности биометрии.

### Цепочка

```
Действие владельца
        ↓
BiometricPrompt (BIOMETRIC_STRONG | DEVICE_CREDENTIAL)
        ↓
CryptoObject с auth-bound ключом
        ↓
onAuthenticationSucceeded → ключ разблокирован
        ↓
recordSuccessfulAuth() → OWNER VERIFIED
```

Кнопка «Проверить владельца» — в Настройки → Владелец.

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
AI, Personal Server, Model Manager, Avatar, Settings.

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
