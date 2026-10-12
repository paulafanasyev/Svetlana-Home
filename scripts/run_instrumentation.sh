#!/usr/bin/env bash
# Запуск instrumented-тестов на CI-эмуляторе.
#
# Важное правило CI:
# - sys.boot_completed=1 недостаточно: system_server может перезапускаться,
#   а Binder service "package" в этот момент отсутствует;
# - этот скрипт проверяет реальную готовность PackageManager;
# - при инфраструктурном сбое (эмулятор/ADB умер, не установился APK, не загрузился)
#   ADB в том же сломанном AVD НЕ перезапускается: эмулятор убивается и запускается
#   заново с чистыми данными (-wipe-data), и тесты прогоняются ещё раз (до
#   MAX_ATTEMPTS попыток). Настоящие падения тестов (assert) не повторяются.
set -e

MAX_ATTEMPTS="${INSTRUMENTATION_MAX_ATTEMPTS:-2}"
ATTEMPT="${INSTRUMENTATION_ATTEMPT:-1}"
WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
SHOTS_DEVICE_DIR="/data/local/tmp/svetlana-shots"
AVD_NAME="${AVD_NAME:-test}"
INFRA_REASON=""

set_paths() {
  RESULT_FILE="$WORKSPACE/instrumentation-result-${ATTEMPT}.env"
  LOG_FILE="$WORKSPACE/instrumentation-gradle-${ATTEMPT}.log"
  DIAG_FILE="$WORKSPACE/instrumentation-diagnostics-${ATTEMPT}.txt"
  EMU_LOG="$WORKSPACE/instrumentation-diagnostics-emulator-${ATTEMPT}.txt"
}

write_result() {
  local infra="$1"
  local status="$2"
  local reason="${3:-}"
  {
    echo "INFRA_FAILURE=${infra}"
    echo "STATUS=${status}"
    echo "REASON=${reason}"
  } > "$RESULT_FILE"
}

collect_diagnostics() {
  {
    echo "=== ATTEMPT=$ATTEMPT ==="
    echo "=== adb get-state ==="
    timeout 20 adb get-state 2>&1 || true
    echo "=== ro.build.version.sdk ==="
    timeout 20 adb shell getprop ro.build.version.sdk 2>&1 || true
    echo "=== sys.boot_completed ==="
    timeout 20 adb shell getprop sys.boot_completed 2>&1 || true
    echo "=== init.svc.bootanim ==="
    timeout 20 adb shell getprop init.svc.bootanim 2>&1 || true
    echo "=== service check package ==="
    timeout 20 adb shell service check package 2>&1 || true
    echo "=== pm path android ==="
    timeout 20 adb shell pm path android 2>&1 || true
    echo "=== system_server meminfo ==="
    timeout 30 adb shell dumpsys meminfo system_server 2>&1 || true
    echo "=== recent logcat ==="
    timeout 30 adb logcat -d -t 300 2>&1 || true
  } > "$DIAG_FILE"
}

# Скриншоты launcher (LauncherScreenshotDeviceTest).
#
# Безопасность: по умолчанию скриншоты НЕ уходят на сторонние сервисы.
# Они упаковываются в приватный артефакт GitHub Actions (доступ только у
# участников репозитория): instrumentation-diagnostics-screenshots-png.b64.txt
# (tar.gz в base64 — имя подходит под существующий шаблон загрузки артефактов).
# Восстановить: base64 -d instrumentation-diagnostics-screenshots-png.b64.txt | tar xz
#
# Публичная превью-ссылка (litterbox, срок жизни 72 часа, без постоянных
# хостингов) включается только явным флагом в репозитории: файл
# ci/public-screenshots.allow. Скриншоты делаются на чистом CI-эмуляторе без
# личных данных; файл добавляет только владелец, когда хочет посмотреть превью.
PUBLIC_SHOTS_FLAG="$WORKSPACE/ci/public-screenshots.allow"

upload_png_preview() {
  local f="$1" r
  r="$(curl -sS --max-time 60 -F reqtype=fileupload -F time=72h -F "fileToUpload=@$f" https://litterbox.catbox.moe/resources/internals/api.php 2>&1 || true)"
  case "$r" in https://litter.catbox.moe/*) echo "$r"; return 0 ;; esac
  echo "litterbox: ${r:0:200}" >&2
  echo "upload-failed"
}

publish_screenshots() {
  local dir="$WORKSPACE/svetlana-shots"
  local index="$WORKSPACE/instrumentation-diagnostics-screenshots.txt"
  local bundle="$WORKSPACE/instrumentation-diagnostics-screenshots-png.b64.txt"
  rm -rf "$dir"
  mkdir -p "$dir"
  : > "$index"
  local names errs public=false
  [ -f "$PUBLIC_SHOTS_FLAG" ] && public=true
  errs="$(timeout 20 adb shell cat "$SHOTS_DEVICE_DIR/errors.txt" 2>/dev/null | tr '\r\n' '  ' | cut -c1-900 || true)"
  if [ -n "$errs" ]; then
    echo "::warning title=SCREENSHOT_ERRORS::$errs"
  fi
  names="$(timeout 20 adb shell ls "$SHOTS_DEVICE_DIR" 2>/dev/null | tr -d '\r' | grep '\.png$' || true)"
  if [ -z "$names" ]; then
    echo "::warning::Скриншоты launcher не найдены на эмуляторе (попытка $ATTEMPT)"
    return 0
  fi
  if ! command -v tesseract >/dev/null 2>&1; then
    sudo apt-get install -y -qq tesseract-ocr tesseract-ocr-rus >/dev/null 2>&1 || true
  fi
  local n url ocr size
  for n in $names; do
    timeout 60 adb exec-out cat "$SHOTS_DEVICE_DIR/$n" > "$dir/$n" || continue
    size="$(wc -c < "$dir/$n" | tr -d ' ')"
    url="private-artifact"
    if [ "$public" = true ]; then
      url="$(upload_png_preview "$dir/$n")"
    fi
    ocr=""
    if command -v tesseract >/dev/null 2>&1; then
      ocr="$(tesseract "$dir/$n" - -l rus+eng 2>/dev/null | tr '\r\n\t' '   ' | tr -s ' ' | cut -c1-700 || true)"
    fi
    echo "$n bytes=$size url=$url ocr=$ocr" >> "$index"
    # OCR-текст в лог только вместе с явно разрешёнными превью; иначе — только в приватном артефакте.
    if [ "$public" = true ]; then
      echo "::notice title=SCREENSHOT ${n%.png}::$url | bytes=$size | OCR: $ocr"
    else
      echo "::notice title=SCREENSHOT ${n%.png}::$url | bytes=$size | ocr_chars=${#ocr}"
    fi
  done
  (cd "$dir" && tar czf - ./*.png | base64 -w 0) > "$bundle" || true
  echo "::notice title=SCREENSHOTS_ARTIFACT::$(basename "$bundle") ($(wc -c < "$bundle" | tr -d ' ') bytes, private)"
}

# Готовность эмулятора: загрузка, API level, PackageManager. 0 — готов, иначе INFRA_REASON.
wait_ready() {
  echo "==> Ждём полной загрузки эмулятора (попытка $ATTEMPT)..."
  timeout 600 adb wait-for-device || true

  local boot=""
  for _ in $(seq 1 180); do
    boot="$(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$boot" = "1" ]; then
      echo "==> sys.boot_completed=1"
      break
    fi
    sleep 10
  done
  if [ "$boot" != "1" ]; then
    INFRA_REASON="boot_timeout"
    return 1
  fi

  local api
  api="$(timeout 20 adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  if ! [[ "$api" =~ ^[0-9]+$ ]] || [ "$api" -lt 21 ]; then
    INFRA_REASON="invalid_api_level_${api:-unknown}"
    return 1
  fi
  echo "==> Android API level: $api"

  echo "==> Проверяем PackageManager/Binder service..."
  local service_check pm_path
  for _ in $(seq 1 60); do
    service_check="$(timeout 20 adb shell service check package 2>/dev/null | tr -d '\r' || true)"
    pm_path="$(timeout 20 adb shell pm path android 2>/dev/null | tr -d '\r' || true)"
    if echo "$service_check" | grep -qi "found" && echo "$pm_path" | grep -q "^package:"; then
      echo "==> PackageManager готов"
      return 0
    fi
    sleep 2
  done
  INFRA_REASON="package_service_not_ready"
  return 1
}

# Свежий эмулятор вместо сломанного: убиваем текущий и холодно запускаем тот же AVD с чистыми данными.
restart_emulator() {
  echo "::warning::Перезапускаем эмулятор с чистыми данными (попытка $ATTEMPT из $MAX_ATTEMPTS)"
  timeout 30 adb emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    pgrep -f "qemu-system" >/dev/null 2>&1 || break
    sleep 2
  done
  pkill -9 -f "qemu-system" >/dev/null 2>&1 || true
  timeout 30 adb kill-server >/dev/null 2>&1 || true
  timeout 30 adb start-server >/dev/null 2>&1 || true
  local emulator_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/emulator/emulator"
  if [ ! -x "$emulator_bin" ]; then
    echo "::error::emulator не найден: $emulator_bin"
    return 1
  fi
  nohup "$emulator_bin" -avd "$AVD_NAME" -no-window -gpu swiftshader_indirect -no-snapshot \
    -noaudio -no-boot-anim -memory 2048 -cores 2 -wipe-data > "$EMU_LOG" 2>&1 &
  sleep 20
  if ! wait_ready; then
    return 1
  fi
  # Как у android-emulator-runner (disable-animations: true).
  timeout 20 adb shell settings put global window_animation_scale 0.0 || true
  timeout 20 adb shell settings put global transition_animation_scale 0.0 || true
  timeout 20 adb shell settings put global animator_duration_scale 0.0 || true
  timeout 20 adb shell input keyevent 82 >/dev/null 2>&1 || true
  return 0
}

# Только явные признаки сбоя эмулятора/ADB. Реальные assertion/test failures здесь не ретраятся.
INFRA_PATTERN="Can't find service: package|API level=1|ShellCommandUnresponsiveException|Failed to install split APK\(s\)|Starting 0 tests|EmulatorConsole.*Failed to start|device offline|device '.*' not found|INSTALL_FAILED|Instrumentation run failed due to 'Process crashed'|DeviceException|no devices/emulators found"

set_paths
READY=true
wait_ready || READY=false

while true; do
  TEST_RC=20
  if [ "$READY" = true ]; then
    echo "==> Запускаем connectedDebugAndroidTest (попытка $ATTEMPT)..."
    set +e
    ./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tee "$LOG_FILE"
    TEST_RC="${PIPESTATUS[0]}"
    set -e

    publish_screenshots || echo "::warning::Не удалось опубликовать скриншоты launcher"

    if [ "$TEST_RC" -eq 0 ]; then
      write_result false passed "tests_passed"
      exit 0
    fi

    if grep -Eq "$INFRA_PATTERN" "$LOG_FILE"; then
      INFRA_REASON="emulator_install_or_device_readiness"
    else
      write_result false failed "test_or_build_failure"
      exit "$TEST_RC"
    fi
  fi

  echo "::warning::INSTRUMENTATION_INFRA_FAILURE=$INFRA_REASON (попытка $ATTEMPT из $MAX_ATTEMPTS)"
  collect_diagnostics
  write_result true failed "$INFRA_REASON"

  if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
    echo "::error::INSTRUMENTATION_INFRA_FAILURE=$INFRA_REASON: эмулятор/ADB сбоили во всех $MAX_ATTEMPTS попытках"
    exit "$TEST_RC"
  fi

  ATTEMPT=$((ATTEMPT + 1))
  set_paths
  READY=true
  restart_emulator || READY=false
done
