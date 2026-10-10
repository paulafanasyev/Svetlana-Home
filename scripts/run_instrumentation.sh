#!/usr/bin/env bash
# Запуск instrumented-тестов на CI-эмуляторе.
#
# Важное правило CI:
# - sys.boot_completed=1 недостаточно: system_server может перезапускаться,
#   а Binder service "package" в этот момент отсутствует;
# - этот скрипт проверяет реальную готовность PackageManager;
# - при инфраструктурном сбое он НЕ перезапускает ADB в том же сломанном AVD.
#   Свежий эмулятор запускается уровнем выше в GitHub Actions.
set -e

ATTEMPT="${INSTRUMENTATION_ATTEMPT:-1}"
WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
RESULT_FILE="$WORKSPACE/instrumentation-result-${ATTEMPT}.env"
LOG_FILE="$WORKSPACE/instrumentation-gradle-${ATTEMPT}.log"
DIAG_FILE="$WORKSPACE/instrumentation-diagnostics-${ATTEMPT}.txt"
SHOTS_DEVICE_DIR="/data/local/tmp/svetlana-shots"

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
    adb get-state 2>&1 || true
    echo "=== ro.build.version.sdk ==="
    adb shell getprop ro.build.version.sdk 2>&1 || true
    echo "=== sys.boot_completed ==="
    adb shell getprop sys.boot_completed 2>&1 || true
    echo "=== init.svc.bootanim ==="
    adb shell getprop init.svc.bootanim 2>&1 || true
    echo "=== service check package ==="
    adb shell service check package 2>&1 || true
    echo "=== pm path android ==="
    adb shell pm path android 2>&1 || true
    echo "=== system_server meminfo ==="
    adb shell dumpsys meminfo system_server 2>&1 || true
    echo "=== recent logcat ==="
    adb logcat -d -t 300 2>&1 || true
  } > "$DIAG_FILE"
}

# Скриншоты launcher (LauncherScreenshotDeviceTest): забираем с эмулятора,
# распознаём текст (OCR) для проверки и публикуем ссылки в аннотациях CI,
# чтобы их можно было посмотреть без скачивания артефактов.
publish_screenshots() {
  local dir="$WORKSPACE/svetlana-shots"
  local index="$WORKSPACE/instrumentation-diagnostics-screenshots.txt"
  rm -rf "$dir"
  mkdir -p "$dir"
  : > "$index"
  local names
  names="$(adb shell ls "$SHOTS_DEVICE_DIR" 2>/dev/null | tr -d '\r' | grep '\.png$' || true)"
  if [ -z "$names" ]; then
    echo "::warning::Скриншоты launcher не найдены на эмуляторе"
    return 0
  fi
  if ! command -v tesseract >/dev/null 2>&1; then
    sudo apt-get install -y -qq tesseract-ocr tesseract-ocr-rus >/dev/null 2>&1 || true
  fi
  local n url ocr size
  for n in $names; do
    adb exec-out cat "$SHOTS_DEVICE_DIR/$n" > "$dir/$n" || continue
    size="$(wc -c < "$dir/$n" | tr -d ' ')"
    url="$(curl -sS --max-time 90 -F reqtype=fileupload -F "fileToUpload=@$dir/$n" https://catbox.moe/user/api.php 2>/dev/null || true)"
    case "$url" in
      https://*) ;;
      *) url="$(curl -sS --max-time 90 -A 'svetlana-ci' -F "file=@$dir/$n" https://0x0.st 2>/dev/null || true)" ;;
    esac
    case "$url" in https://*) ;; *) url="upload-failed" ;; esac
    ocr=""
    if command -v tesseract >/dev/null 2>&1; then
      ocr="$(tesseract "$dir/$n" - -l rus+eng 2>/dev/null | tr '\r\n\t' '   ' | tr -s ' ' | cut -c1-700 || true)"
    fi
    echo "$n bytes=$size url=$url ocr=$ocr" >> "$index"
    echo "::notice title=SCREENSHOT ${n%.png}::$url | bytes=$size | OCR: $ocr"
  done
}

fail_infra() {
  local reason="$1"
  echo "::error::INSTRUMENTATION_INFRA_FAILURE=$reason"
  collect_diagnostics
  write_result true failed "$reason"
  exit 20
}

echo "==> Ждём полной загрузки эмулятора..."
adb wait-for-device || true

BOOT=""
for _ in $(seq 1 180); do
  BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$BOOT" = "1" ]; then
    echo "==> sys.boot_completed=1"
    break
  fi
  sleep 10
done

if [ "$BOOT" != "1" ]; then
  fail_infra "boot_timeout"
fi

API_LEVEL="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
if ! [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || [ "$API_LEVEL" -lt 21 ]; then
  fail_infra "invalid_api_level_${API_LEVEL:-unknown}"
fi
echo "==> Android API level: $API_LEVEL"

echo "==> Проверяем PackageManager/Binder service..."
PACKAGE_READY=false
for _ in $(seq 1 60); do
  SERVICE_CHECK="$(adb shell service check package 2>/dev/null | tr -d '\r' || true)"
  PM_PATH="$(adb shell pm path android 2>/dev/null | tr -d '\r' || true)"
  if echo "$SERVICE_CHECK" | grep -qi "found" && echo "$PM_PATH" | grep -q "^package:"; then
    PACKAGE_READY=true
    break
  fi
  sleep 2
done

if [ "$PACKAGE_READY" != "true" ]; then
  fail_infra "package_service_not_ready"
fi

echo "==> PackageManager готов"
echo "==> Запускаем connectedDebugAndroidTest..."
set +e
./gradlew connectedDebugAndroidTest --no-daemon 2>&1 | tee "$LOG_FILE"
TEST_RC="${PIPESTATUS[0]}"
set -e

publish_screenshots || echo "::warning::Не удалось опубликовать скриншоты launcher"

if [ "$TEST_RC" -eq 0 ]; then
  write_result false passed "tests_passed"
  exit 0
fi

# Только явные признаки сбоя эмулятора/ADB до выполнения тестов.
# Реальные assertion/test failures здесь не ретраятся.
if grep -Eq   "Can't find service: package|API level=1|ShellCommandUnresponsiveException|Failed to install split APK\(s\)|Starting 0 tests|EmulatorConsole.*Failed to start|device offline|INSTALL_FAILED"   "$LOG_FILE"; then
  echo "::warning::Detected emulator/ADB infrastructure failure; the workflow will retry on a fresh emulator."
  collect_diagnostics
  write_result true failed "emulator_install_or_device_readiness"
else
  write_result false failed "test_or_build_failure"
fi

exit "$TEST_RC"
