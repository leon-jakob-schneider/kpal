#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
GLOBAL_ENV="${CODEX_HOME:-$HOME/.codex}/environments/kpal.env"

[ -f "$GLOBAL_ENV" ] && . "$GLOBAL_ENV"

BUNDLE_ID="${KPAL_IOS_BUNDLE_ID:-device-qa-ios-app}"
PROCESS_NAME="${KPAL_IOS_PROCESS_NAME:-device-qa-ios-app}"
DEVICE_ID="${KPAL_IOS_DEVICE_ID:-}"
PROCESS_ID=""
CONTINUE_AFTER_ATTACH=0
SKIP_BUILD=0

cleanup_stale_debug_sessions() {
  local script_pid child_pid expect_pid
  local script_name="scripts/debug-iphone.sh"
  local expect_pattern='expect -c .*spawn xcrun lldb'

  for script_pid in ${(f)"$(pgrep -f "$script_name" || true)"}; do
    if [ -z "$script_pid" ] || [ "$script_pid" = "$$" ]; then
      continue
    fi

    for child_pid in ${(f)"$(pgrep -P "$script_pid" || true)"}; do
      kill -TERM "$child_pid" 2>/dev/null || true
    done

    kill -TERM "$script_pid" 2>/dev/null || true
  done

  for expect_pid in ${(f)"$(pgrep -f "$expect_pattern" || true)"}; do
    if [ -n "$expect_pid" ]; then
      kill -TERM "$expect_pid" 2>/dev/null || true
    fi
  done

  sleep 1
}

usage() {
  cat <<'EOF'
Usage: scripts/debug-iphone.sh [options]

Builds and installs the iOS QA app on a connected iPhone, launches it in a
stopped state, and opens an LLDB session attached to that process.

Options:
  --device <udid-or-name>     Override the device to use.
  --bundle-id <bundle-id>     Bundle identifier to launch.
  --process-name <name>       Process name to attach to in LLDB.
  --skip-build                Do not rebuild or reinstall before launching.
  --continue                  Continue execution automatically after LLDB attaches.
                              LLDB stays attached and returns to its prompt while
                              the process keeps running.
  -h, --help                  Show this help.

Environment overrides:
  KPAL_IOS_DEVICE_ID
  KPAL_IOS_BUNDLE_ID
  KPAL_IOS_PROCESS_NAME
EOF
}

resolve_device_id() {
  if [ -n "$DEVICE_ID" ]; then
    return
  fi

  local device_list_json connected_iphones connected_iphone_count
  device_list_json="$(mktemp "${TMPDIR:-/tmp}/kpal-devices.XXXXXX")"

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
    rm -f "$device_list_json"
    printf 'Auto-discovered connected iPhone: %s\n' "$DEVICE_ID"
    return
  fi

  if [ "$connected_iphone_count" -eq 0 ]; then
    rm -f "$device_list_json"
    echo "No connected iPhone found. Unlock and trust the device, then try again." >&2
    echo "You can also set KPAL_IOS_DEVICE_ID in $GLOBAL_ENV." >&2
    exit 1
  fi

  rm -f "$device_list_json"
  echo "Multiple connected iPhones found. Set KPAL_IOS_DEVICE_ID or pass --device." >&2
  printf '%s\n' "$connected_iphones" | while IFS="$(printf '\t')" read -r udid name; do
    echo "  $name ($udid)" >&2
  done
  exit 1
}

build_and_install() {
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

launch_start_stopped() {
  local launch_json
  launch_json="$(mktemp "${TMPDIR:-/tmp}/kpal-launch.XXXXXX")"

  xcrun devicectl device process launch \
    --device "$DEVICE_ID" \
    --terminate-existing \
    --start-stopped \
    --json-output "$launch_json" \
    "$BUNDLE_ID" >/dev/null

  PROCESS_ID="$(
    jq -r '
      [
        .. | objects
        | (.processIdentifier // .processID // .pid // empty)
      ][0] // empty
    ' "$launch_json"
  )"
  rm -f "$launch_json"

  if [ -n "$PROCESS_ID" ] && [ "$PROCESS_ID" != "null" ]; then
    echo "Launched $BUNDLE_ID on $DEVICE_ID with pid $PROCESS_ID"
  else
    echo "Launched $BUNDLE_ID on $DEVICE_ID, but could not determine pid from devicectl output." >&2
  fi
}

open_lldb() {
  local -a lldb_args
  lldb_args=(-o "device select $DEVICE_ID")

  if [ "$CONTINUE_AFTER_ATTACH" -eq 1 ]; then
    lldb_args+=(-o "process handle SIGSTOP -n false -p true -s false")
  fi

  if [ -n "$PROCESS_ID" ] && [ "$PROCESS_ID" != "null" ]; then
    lldb_args+=(-o "device process attach -p $PROCESS_ID")
  else
    lldb_args+=(-o "device process attach -n \"$PROCESS_NAME\" -i -w")
  fi

  exec xcrun lldb "${lldb_args[@]}"
}

main() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --device)
        DEVICE_ID="${2:?Missing value for --device}"
        shift 2
        ;;
      --bundle-id)
        BUNDLE_ID="${2:?Missing value for --bundle-id}"
        shift 2
        ;;
      --process-name)
        PROCESS_NAME="${2:?Missing value for --process-name}"
        shift 2
        ;;
      --skip-build)
        SKIP_BUILD=1
        shift
        ;;
      --continue)
        CONTINUE_AFTER_ATTACH=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        exit 1
        ;;
    esac
  done

  cleanup_stale_debug_sessions
  resolve_device_id

  if [ "$SKIP_BUILD" -eq 0 ]; then
    build_and_install
  fi

  launch_start_stopped
  open_lldb
}

main "$@"
