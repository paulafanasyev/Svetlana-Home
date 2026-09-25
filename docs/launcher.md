# Launcher (Главный экран)

## Официальный механизм

Svetlana Home — настоящий Android launcher и поддерживает
официальный механизм `ROLE_HOME` (Android 10+) и
`CATEGORY_HOME` для более старых версий.

- Запрос роли: `RoleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)`
  через `PermissionManager.homeRoleIntent()`.
- Проверка: `RoleManager.getRoleHolders(RoleManager.ROLE_HOME)`.
- Запасной путь для API < 29: `Settings.ACTION_HOME_SETTINGS` и
  разрешение `CATEGORY_HOME` в манифесте `HomeActivity`.

## Декларация в манифесте

```xml
<activity android:name=".launcher.HomeActivity"
    android:launchMode="singleTask"
    android:stateNotNeeded="true"
    android:taskAffinity="">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

## Главный экран (ТЗ §5)

Минималистичный, не перегруженный:
часы → Living Orb → имя → последняя реплика Светланы →
поле ввода (текст/микрофон) → быстрые действия (Приложения, Переводчик,
История, Настройки).

## Поведение после перезагрузки

`BootCompletedReceiver` принимает `BOOT_COMPLETED` /
`LOCKED_BOOT_COMPLETED`. Launcher остаётся главным экраном после
перезагрузки (роль хранится системой), тёмная тема и орб работают
сразу. Сервис Hands не запускается автоматически — пользователь
включает его сам в системных настройках.

## Адаптивный аватар

На главном экране орб дышит, реагирует на состояния:
`idle / listening / thinking / speaking`. Уровень (Orb → Light →
Realistic → Full Real) выбирается `AvatarEngine` (см. `avatar.md`).

## Тест-план launcher

- [ ] Назначить Светлану главным экраном
- [ ] Снять назначение, назначить повторно
- [ ] Проверить работу после перезагрузки
- [ ] Кнопка Home возвращает на главный экран Светланы
- [ ] Возврат из приложения жестом свайпа вверх
- [ ] Орб реагирует на слушание и ответ
