#!/usr/bin/env bash
# Запуск instrumented-тестов на CI-эмуляторе.
#
# reactivecircus/android-emulator-runner выполняет каждую строку поля
# `script:` отдельным `sh -c`, поэтому многострочные shell-конструкции
# (циклы, if) там не работают — выносим логику в отдельный файл.
#
# Эмулятор на hosted-раннере грузится ~340с под swiftshader; обычный
# `adb wait-for-device` ловит «device offline» ещё до полной загрузки,
# поэтому явно ждём sys.boot_completed.
set -e

echo "==> Ждём полную загрузку эмулятора..."
adb wait-for-device || true

BOOT=""
for _ in $(seq 1 180); do
  BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$BOOT" = "1" ]; then
    echo "==> Эмулятор загружен"
    break
  fi
  sleep 10
done

if [ "$BOOT" != "1" ]; then
  echo "::error::Эмулятор не загрузился за отведённое время"
  exit 1
fi

echo "==> Запускаем connectedDebugAndroidTest..."
LOG_FILE="$(mktemp)"
set +e
./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tee "$LOG_FILE"
TEST_RC="${PIPESTATUS[0]}"
set -e

if [ "$TEST_RC" -eq 0 ]; then
  exit 0
fi

# Hosted Android emulators can occasionally lose the ADB install path before
# any test starts. Do not retry real test failures: retry ONLY the explicit
# infrastructure signature "Failed to install split APK(s)" + "Starting 0 tests".
if grep -q "Failed to install split APK(s)" "$LOG_FILE" &&    grep -q "Starting 0 tests" "$LOG_FILE"; then
  echo "::warning::Emulator install infrastructure failure before tests; restarting ADB and retrying once."
  adb kill-server || true
  adb start-server || true
  adb wait-for-device || true

  BOOT=""
  for _ in $(seq 1 60); do
    BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$BOOT" = "1" ]; then
      break
    fi
    sleep 2
  done
  if [ "$BOOT" != "1" ]; then
    echo "::error::Emulator did not return to sys.boot_completed=1 after ADB recovery"
    exit "$TEST_RC"
  fi

  echo "==> Повторяем connectedDebugAndroidTest после восстановления ADB..."
  ./gradlew connectedDebugAndroidTest --no-daemon
  exit $?
fi

exit "$TEST_RC"
