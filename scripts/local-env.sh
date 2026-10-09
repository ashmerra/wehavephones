#!/usr/bin/env bash
# Shared by the device scripts: source it, do not run it. Loads machine-local
# settings and picks the phone when none is configured.
#
# Settings live in local.env at the repository root (gitignored; set
# WEHAVEPHONES_LOCAL_ENV to read another file): plain KEY=value lines and #
# comments, for example
#
#   ANDROID_DEVICE=192.0.2.10:5555
#   ANDROID_TAILSCALE_IP=192.0.2.10
#   WEHAVEPHONES_PACKAGE=com.wehavephones.dev
#
# The file is parsed, never executed, and a variable that is already set in the
# environment keeps its value, so command-line overrides always win.
# scripts/wehavephones-device.py reads the same file the same way for the
# Python helpers.
#
# load_local_env runs when this file is sourced. single_adb_device ADB [FLAG]
# prints the serial of the only device `adb devices` lists in state "device".
# With none or several it explains how to choose (FLAG is the calling script's
# own serial option, such as --serial) and returns 1.

_local_env_trim() {
  local text="$1"
  text="${text#"${text%%[![:space:]]*}"}"
  printf '%s' "${text%"${text##*[![:space:]]}"}"
}

load_local_env() {
  local root file line key value number=0
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  file="${WEHAVEPHONES_LOCAL_ENV:-$root/local.env}"
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    number=$((number + 1))
    line="$(_local_env_trim "${line%$'\r'}")"
    if [[ -z "$line" || "$line" == \#* ]]; then
      continue
    fi
    line="${line#export }"
    key="$(_local_env_trim "${line%%=*}")"
    if [[ "$line" != *=* || ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      echo "[local.env] $file:$number ignored, expected KEY=value" >&2
      continue
    fi
    value="$(_local_env_trim "${line#*=}")"
    case "$value" in
      \"*\" | \'*\') value="${value:1:${#value}-2}" ;;
    esac
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done < "$file"
}

single_adb_device() {
  local adb_bin="$1" flag="${2:-}" listing serial state listed=0 count=0 ready="" first="" others=""
  local choose="set ANDROID_DEVICE in local.env${flag:+ or pass $flag}"
  listing="$("$adb_bin" devices 2>/dev/null | tr -d '\r' || true)"
  while read -r serial state _; do
    if [[ "$serial" == "List" ]]; then
      listed=1
    elif [[ $listed -eq 1 && -n "$serial" ]]; then
      if [[ "$state" == "device" ]]; then
        count=$((count + 1))
        first="$serial"
        ready="${ready:+$ready, }$serial"
      else
        others="${others:+$others, }$serial: $state"
      fi
    fi
  done <<<"$listing"
  if [[ $count -eq 1 ]]; then
    printf '%s\n' "$first"
    return 0
  fi
  if [[ $count -gt 1 ]]; then
    echo "${0##*/}: No device is configured and several are connected ($ready); $choose." >&2
  else
    echo "${0##*/}: No device is configured and none is ready${others:+ (adb lists $others)}; connect the phone or $choose." >&2
  fi
  return 1
}

load_local_env
