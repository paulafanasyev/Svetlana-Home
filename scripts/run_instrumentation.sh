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
./gradlew connectedDebugAndroidTest --no-daemon
