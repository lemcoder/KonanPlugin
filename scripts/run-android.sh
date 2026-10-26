#!/usr/bin/env bash
#
# Build and run the Android example without switching IDE root folders.
#
# The Android example (examples/android) is a standalone Gradle build that builds the plugin
# from this checkout (includeBuild), run with the repository's wrapper. It needs
# konan.config.cargo and konan.config.jextract, e.g. in ~/.gradle/gradle.properties, and an Android
# NDK: ANDROID_NDK_HOME, else the SDK's newest.
#
# Usage: scripts/run-android.sh [options]
#
#   -c, --clean          clean the example build before assembling
#   -n, --no-launch      install only, don't start the activity
#   -l, --logcat         tail the app's logcat after launching (Ctrl-C to stop)
#   -e, --emulator       start an emulator if no device is connected
#   -d, --device SERIAL  target a specific adb device (else the only connected one)
#   -h, --help           show this help
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE_DIR="$REPO_ROOT/examples/android"
APP_ID="com.example.nativeapp"
ACTIVITY="$APP_ID/.MainActivity"

CLEAN=0
LAUNCH=1
LOGCAT=0
START_EMULATOR=0
DEVICE=""

# Print the header comment block (everything after the shebang, up to the first code line).
usage() { awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        -c|--clean)        CLEAN=1 ;;
        -n|--no-launch)    LAUNCH=0 ;;
        -l|--logcat)       LOGCAT=1 ;;
        -e|--emulator)     START_EMULATOR=1 ;;
        -d|--device)       DEVICE="${2:?--device needs a serial}"; shift ;;
        -h|--help)         usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

step() { printf '\n\033[1;34m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# --- locate the Android SDK -------------------------------------------------
# Prefer sdk.dir from local.properties (what Gradle uses) so the script and the build
# never disagree about which SDK is in play.
sdk_dir() {
    local props
    for props in "$EXAMPLE_DIR/local.properties" "$REPO_ROOT/local.properties"; do
        if [[ -f "$props" ]]; then
            local dir
            dir="$(sed -n 's/^sdk\.dir=//p' "$props" | tail -1)"
            [[ -n "$dir" && -d "$dir" ]] && { echo "$dir"; return; }
        fi
    done
    for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [[ -n "$dir" && -d "$dir" ]] && { echo "$dir"; return; }
    done
}

SDK="$(sdk_dir)"
[[ -n "$SDK" ]] || die "Android SDK not found. Set sdk.dir in $EXAMPLE_DIR/local.properties or ANDROID_HOME."
ADB="$SDK/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="$(command -v adb || true)"
[[ -n "$ADB" && -x "$ADB" ]] || die "adb not found under $SDK/platform-tools."

# --- Android NDK ------------------------------------------------------------
# The plugin requires one for Android (konan.config.androidNdk, else ANDROID_NDK_HOME) and never looks for
# it; this script does, for convenience: the SDK's newest, unless one is already named.
if [[ -z "${ANDROID_NDK_HOME:-}" && -d "$SDK/ndk" ]]; then
    ndk="$(ls -1 "$SDK/ndk" | sort -V | tail -1)"
    [[ -n "$ndk" ]] && export ANDROID_NDK_HOME="$SDK/ndk/$ndk" && echo "Using ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
fi

# --- device selection -------------------------------------------------------
connected_devices() {
    "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'
}

boot_emulator() {
    local emu="$SDK/emulator/emulator"
    [[ -x "$emu" ]] || die "emulator binary not found at $emu."
    local avd
    avd="$("$emu" -list-avds | head -1)"
    [[ -n "$avd" ]] || die "No AVDs found. Create one in Android Studio's Device Manager."
    step "Starting emulator: $avd"
    "$emu" -avd "$avd" -no-snapshot-save >/dev/null 2>&1 &
    "$ADB" wait-for-device
    printf 'Waiting for boot'
    until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        printf '.'; sleep 2
    done
    printf ' ready\n'
}

if [[ -z "$DEVICE" ]]; then
    # Word-splitting is fine and intentional here: adb serials never contain whitespace.
    # (Avoids mapfile, which macOS's stock bash 3.2 doesn't have.)
    devices=($(connected_devices))
    if [[ ${#devices[@]} -eq 0 ]]; then
        if [[ $START_EMULATOR -eq 1 ]]; then
            boot_emulator
            devices=($(connected_devices))
        elif [[ $LAUNCH -eq 1 ]]; then
            die "No device/emulator connected. Plug one in, or re-run with --emulator."
        fi
    fi
    if [[ ${#devices[@]} -gt 1 ]]; then
        die "Multiple devices connected (${devices[*]}). Pick one with --device SERIAL."
    fi
    DEVICE="${devices[0]:-}"
fi
[[ -n "$DEVICE" ]] && export ANDROID_SERIAL="$DEVICE"

# --- 1) build + install the app ---------------------------------------------
GRADLE_TASKS=()
[[ $CLEAN -eq 1 ]] && GRADLE_TASKS+=(clean)
GRADLE_TASKS+=(installDebug)

step "Building + installing android-example (${GRADLE_TASKS[*]})"
"$REPO_ROOT/gradlew" -p "$EXAMPLE_DIR" "${GRADLE_TASKS[@]}"

# --- 2) launch --------------------------------------------------------------
if [[ $LAUNCH -eq 0 ]]; then
    step "Installed on ${DEVICE:-device}. Launch skipped (--no-launch)."
    exit 0
fi

step "Launching $ACTIVITY on $DEVICE"
"$ADB" shell am start -n "$ACTIVITY" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

if [[ $LOGCAT -eq 1 ]]; then
    step "Tailing logcat for $APP_ID (Ctrl-C to stop)"
    pid=""
    for _ in {1..15}; do
        pid="$("$ADB" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')"
        [[ -n "$pid" ]] && break
        sleep 1
    done
    if [[ -n "$pid" ]]; then
        "$ADB" logcat --pid="$pid"
    else
        warn "Could not resolve pid for $APP_ID; showing unfiltered logcat."
        "$ADB" logcat
    fi
fi
