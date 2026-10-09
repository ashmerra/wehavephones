#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_JAVA17="/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"
GRADLE_RESET=0
RELEASE=0
# The release signing key: four settings, read from the environment or else from
# this file (KEY=value lines; WEHAVEPHONES_SIGNING_ENV names another file).
SIGNING_ENV_FILE="${WEHAVEPHONES_SIGNING_ENV:-$HOME/.config/wehavephones/signing.env}"
SIGNING_VARS=(WEHAVEPHONES_KEYSTORE WEHAVEPHONES_KEYSTORE_PASS WEHAVEPHONES_KEY_ALIAS WEHAVEPHONES_KEY_PASS)

if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi

java_major_version() {
  local java_home="$1"
  if [[ -x "$java_home/bin/java" ]]; then
    "$java_home/bin/java" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/'
  fi
}

usage() {
  cat <<'USAGE'
Build the WeHavePhones APK (wehavephones flavor).

Usage:
  scripts/build-wehavephones.sh [--release] [--reset-gradle]

Without --release this builds the debug APK. It installs as com.wehavephones.dev
("WeHavePhones Dev"), next to a release build, and is signed with your local debug
key unless WEHAVEPHONES_KEYSTORE (with WEHAVEPHONES_KEYSTORE_PASS,
WEHAVEPHONES_KEY_ALIAS and WEHAVEPHONES_KEY_PASS) names another key.
  app/build/outputs/apk/wehavephones/debug/app-wehavephones-debug.apk

--release builds com.wehavephones (WeHavePhones), signed with the release key.
The four WEHAVEPHONES_* signing settings above come from the environment or, for
those not set there, from ~/.config/wehavephones/signing.env (KEY=value lines;
WEHAVEPHONES_SIGNING_ENV names another file). The file is read, never executed,
and no value from it is printed. The script then verifies the signature and the
manifest and writes
  out/release/WeHavePhones-<version>.apk
  out/release/SHA256SUMS

Options:
  --release        Build, verify and stage the signed release APK.
  --reset-gradle   Clear Gradle native and wrapper caches (~/.gradle) before build.
USAGE
}

trim() {
  local text="$1"
  text="${text#"${text%%[![:space:]]*}"}"
  printf '%s' "${text%"${text##*[![:space:]]}"}"
}

# Fill each release signing setting that the environment leaves empty from the
# signing file. The file is parsed and never sourced, and only the names of
# missing settings are ever printed, never a value.
load_release_signing() {
  local file="$SIGNING_ENV_FILE" line key value number=0 name missing="" note=""
  if [[ -f "$file" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      number=$((number + 1))
      line="$(trim "${line%$'\r'}")"
      if [[ -z "$line" || "$line" == \#* ]]; then
        continue
      fi
      line="${line#export }"
      key="$(trim "${line%%=*}")"
      if [[ "$line" != *=* || ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "[build-wehavephones] $file:$number ignored, expected KEY=value" >&2
        continue
      fi
      value="$(trim "${line#*=}")"
      case "$value" in
        \"*\" | \'*\') value="${value:1:${#value}-2}" ;;
      esac
      case "$key" in
        WEHAVEPHONES_KEYSTORE | WEHAVEPHONES_KEYSTORE_PASS | WEHAVEPHONES_KEY_ALIAS | WEHAVEPHONES_KEY_PASS)
          if [[ -z "${!key:-}" ]]; then
            printf -v "$key" '%s' "$value"
          fi
          ;;
      esac
    done < "$file"
  else
    note=" (that file does not exist)"
  fi
  for name in "${SIGNING_VARS[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      missing="${missing:+$missing, }$name"
    fi
  done
  if [[ -n "$missing" ]]; then
    {
      echo "[build-wehavephones] --release needs the release signing key, but these are not set: $missing"
      echo "[build-wehavephones] Set WEHAVEPHONES_KEYSTORE, WEHAVEPHONES_KEYSTORE_PASS, WEHAVEPHONES_KEY_ALIAS and"
      echo "[build-wehavephones] WEHAVEPHONES_KEY_PASS in the environment, or as KEY=value lines in $file$note."
    } >&2
    return 1
  fi
  case "$WEHAVEPHONES_KEYSTORE" in
    "~/"*) WEHAVEPHONES_KEYSTORE="$HOME/${WEHAVEPHONES_KEYSTORE#"~/"}" ;;
    /*) ;;
    *) WEHAVEPHONES_KEYSTORE="$ROOT_DIR/$WEHAVEPHONES_KEYSTORE" ;;
  esac
  if [[ ! -f "$WEHAVEPHONES_KEYSTORE" ]]; then
    echo "[build-wehavephones] The release keystore does not exist: $WEHAVEPHONES_KEYSTORE" >&2
    return 1
  fi
  return 0
}

# Check the signed release APK and copy it to out/release/ with a checksum file.
package_release() {
  local apk="$1" apksigner badging signer package_name version_name release_dir release_apk checksum
  apksigner="${APKSIGNER:-$(dirname "$AAPT2")/apksigner}"
  if [[ ! -x "$apksigner" ]]; then
    apksigner="$(command -v apksigner || true)"
  fi
  if [[ -z "$apksigner" ]]; then
    echo "[build-wehavephones] apksigner not found next to $AAPT2. Run scripts/setup-dev.sh or set APKSIGNER." >&2
    return 1
  fi

  echo "[build-wehavephones] Verifying the release signature ..."
  # apksigner starts whichever java is first on PATH; use the JDK this script selected.
  if ! signer="$(PATH="$JAVA_HOME/bin:$PATH" "$apksigner" verify --print-certs "$apk" 2>&1)"; then
    printf '%s\n' "$signer" >&2
    echo "[build-wehavephones] apksigner could not verify $apk" >&2
    return 1
  fi
  printf '%s\n' "$signer"

  echo "[build-wehavephones] Checking the release manifest ..."
  badging="$("$AAPT2" dump badging "$apk")"
  package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  version_name="$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  if [[ "$package_name" != "com.wehavephones" ]]; then
    echo "[build-wehavephones] The release APK is package '$package_name', expected com.wehavephones." >&2
    return 1
  fi
  if grep -q '^application-debuggable' <<<"$badging"; then
    echo "[build-wehavephones] The release APK is debuggable; refusing to stage it." >&2
    return 1
  fi
  if [[ ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._+-]*$ ]]; then
    echo "[build-wehavephones] Unexpected versionName '$version_name' in the release APK." >&2
    return 1
  fi

  release_dir="$ROOT_DIR/out/release"
  release_apk="$release_dir/WeHavePhones-$version_name.apk"
  mkdir -p "$release_dir"
  cp -f "$apk" "$release_apk"
  (cd "$release_dir" && sha256sum "WeHavePhones-$version_name.apk" > SHA256SUMS)
  checksum="$(awk '{ print $1 }' "$release_dir/SHA256SUMS")"
  echo "[build-wehavephones] Release APK: $release_apk"
  echo "[build-wehavephones] SHA-256: $checksum"
}

for arg in "$@"; do
  case "$arg" in
    --release)
      RELEASE=1
      ;;
    --reset-gradle)
      GRADLE_RESET=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# Fail before any download or compilation when the release key is not available.
if [[ "$RELEASE" -eq 1 ]]; then
  load_release_signing
fi

if [[ "$GRADLE_RESET" -eq 1 ]]; then
  echo "[build-wehavephones] Resetting Gradle caches in ~/.gradle ..."
  rm -rf "$HOME/.gradle/wrapper/dists/gradle-7.4-bin"* "$HOME/.gradle/native"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  major="$(java_major_version "$JAVA_HOME")"
  if [[ "$major" != "17" ]]; then
    echo "[build-wehavephones] Detected JAVA_HOME=$JAVA_HOME (java major=$major), overriding to JDK 17 for compatibility."
    unset JAVA_HOME
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "$DEFAULT_JAVA17" ]]; then
    export JAVA_HOME="$DEFAULT_JAVA17"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  else
    echo "[build-wehavephones] Could not find JDK 17. Run scripts/setup-dev.sh or set JAVA_HOME." >&2
    exit 1
  fi
fi

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[build-wehavephones] JAVA_HOME does not contain a usable java binary: $JAVA_HOME" >&2
  exit 1
fi

echo "[build-wehavephones] Using JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

cd "$ROOT_DIR"

echo "[build-wehavephones] Ensuring native dependencies are available..."
"$ROOT_DIR/scripts/fetch-native-deps.sh"

echo "[build-wehavephones] Ensuring large assets are available..."
"$ROOT_DIR/scripts/fetch-large-assets.sh"

echo "[build-wehavephones] Verifying the pinned Mesa Turnip bundle..."
python3 "$ROOT_DIR/scripts/verify-wehavephones-turnip.py"

echo "[build-wehavephones] Ensuring the bundled ConsolePort addon is available..."
"$ROOT_DIR/scripts/fetch-consoleport.sh"

echo "[build-wehavephones] Ensuring the pinned GE-Proton layer is bundled..."
python3 "$ROOT_DIR/scripts/fetch-wehavephones-wine.py"

if [[ "$RELEASE" -eq 1 ]]; then
  VARIANT="release"
  GRADLE_TASK=":app:assembleWehavephonesRelease"
else
  VARIANT="debug"
  GRADLE_TASK=":app:assembleWehavephonesDebug"
fi
echo "[build-wehavephones] Running Gradle ${GRADLE_TASK#:app:} ..."
GRADLE_SIGN_ARGS=()
if [[ "$RELEASE" -eq 1 ]]; then
  # Never echo these arguments: they carry the key passwords.
  echo "[build-wehavephones] Release signing: key alias $WEHAVEPHONES_KEY_ALIAS in $WEHAVEPHONES_KEYSTORE"
  GRADLE_SIGN_ARGS+=(
    "-Pandroid.injected.signing.store.file=$WEHAVEPHONES_KEYSTORE"
    "-Pandroid.injected.signing.store.password=$WEHAVEPHONES_KEYSTORE_PASS"
    "-Pandroid.injected.signing.key.alias=$WEHAVEPHONES_KEY_ALIAS"
    "-Pandroid.injected.signing.key.password=$WEHAVEPHONES_KEY_PASS"
  )
elif [[ -n "${WEHAVEPHONES_KEYSTORE:-}" ]]; then
  echo "[build-wehavephones] Signing overrides: WEHAVEPHONES_KEYSTORE=$WEHAVEPHONES_KEYSTORE"
  GRADLE_SIGN_ARGS+=("-Pandroid.injected.signing.store.file=$WEHAVEPHONES_KEYSTORE")
  if [[ -n "${WEHAVEPHONES_KEYSTORE_PASS:-}" ]]; then
    GRADLE_SIGN_ARGS+=("-Pandroid.injected.signing.store.password=$WEHAVEPHONES_KEYSTORE_PASS")
  fi
  if [[ -n "${WEHAVEPHONES_KEY_ALIAS:-}" ]]; then
    GRADLE_SIGN_ARGS+=("-Pandroid.injected.signing.key.alias=$WEHAVEPHONES_KEY_ALIAS")
  fi
  if [[ -n "${WEHAVEPHONES_KEY_PASS:-}" ]]; then
    GRADLE_SIGN_ARGS+=("-Pandroid.injected.signing.key.password=$WEHAVEPHONES_KEY_PASS")
  fi
fi
# AGP 7.4's bundled aapt2 cannot read the compileSdk 36 android.jar, which
# provides android:pageSizeCompat. Use the matching build-tools aapt2 instead.
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" && -f local.properties ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
AAPT2="${AAPT2:-$SDK_DIR/build-tools/36.0.0/aapt2}"
if [[ ! -x "$AAPT2" ]]; then
  echo "[build-wehavephones] aapt2 36.0.0 not found at $AAPT2. Run scripts/setup-dev.sh or set AAPT2." >&2
  exit 1
fi
./gradlew "$GRADLE_TASK" "-Pandroid.aapt2FromMavenOverride=$AAPT2" "${GRADLE_SIGN_ARGS[@]}"

APK_PATH="app/build/outputs/apk/wehavephones/$VARIANT/app-wehavephones-$VARIANT.apk"
if [[ -f "$APK_PATH" ]]; then
  echo "[build-wehavephones] Build succeeded: $APK_PATH"
else
  echo "[build-wehavephones] Build completed but APK not found at $APK_PATH" >&2
  exit 1
fi

if [[ "$RELEASE" -eq 1 ]]; then
  package_release "$APK_PATH"
fi
