#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GLOBAL_ENV="${CODEX_HOME:-$HOME/.codex}/environments/kpal.env"

[ -f "$GLOBAL_ENV" ] && . "$GLOBAL_ENV"

resolve_device_id() {
  if [ -n "${KPAL_IOS_DEVICE_ID:-}" ]; then
    DEVICE_ID="$KPAL_IOS_DEVICE_ID"
    return
  fi

  local device_list_json connected_iphones connected_iphone_count
  device_list_json="$(mktemp "${TMPDIR:-/tmp}/kpal-devices.XXXXXX.json")"
  trap 'rm -f "$device_list_json"' RETURN

  xcrun devicectl list devices --json-output "$device_list_json" >/dev/null

  connected_iphones="$(
    jq -r '
      .result.devices[]
      | select(
          .hardwareProperties.deviceType == "iPhone"
          and (.connectionProperties.tunnelState // "") == "connected"
        )
      | [.hardwareProperties.udid, .deviceProperties.name]
      | @tsv
    ' "$device_list_json"
  )"

  connected_iphone_count="$(printf '%s\n' "$connected_iphones" | sed '/^$/d' | wc -l | tr -d ' ')"

  if [ "$connected_iphone_count" -eq 1 ]; then
    DEVICE_ID="$(printf '%s\n' "$connected_iphones" | cut -f1)"
    DEVICE_NAME="$(printf '%s\n' "$connected_iphones" | cut -f2-)"
    echo "Auto-discovered connected iPhone: $DEVICE_NAME ($DEVICE_ID)"
    return
  fi

  if [ "$connected_iphone_count" -eq 0 ]; then
    echo "No connected iPhone found. Set KPAL_IOS_DEVICE_ID in $GLOBAL_ENV or your shell environment." >&2
    exit 1
  fi

  echo "Multiple connected iPhones found. Set KPAL_IOS_DEVICE_ID in $GLOBAL_ENV or your shell environment." >&2
  printf '%s\n' "$connected_iphones" | while IFS="$(printf '\t')" read -r udid name; do
    echo "  $name ($udid)" >&2
  done
  exit 1
}

run_build_and_install() {
  xcodebuild \
    -project "$ROOT_DIR/device-qa-ios-app/module.xcodeproj" \
    -scheme app \
    -configuration Debug \
    -destination "id=$DEVICE_ID" \
    -derivedDataPath "$ROOT_DIR/.derived-data/ios-device" \
    -allowProvisioningUpdates \
    -allowProvisioningDeviceRegistration \
    build

  xcrun devicectl device install app \
    --device "$DEVICE_ID" \
    "$ROOT_DIR/.derived-data/ios-device/Build/Products/Debug-iphoneos/device-qa-ios-app.app"
}

main() {
  resolve_device_id

  case "${1:-}" in
    --resolve-device-only)
      printf '%s\n' "$DEVICE_ID"
      ;;
    "")
      run_build_and_install
      ;;
    *)
      echo "Usage: $0 [--resolve-device-only]" >&2
      exit 1
      ;;
  esac
}

main "$@"
