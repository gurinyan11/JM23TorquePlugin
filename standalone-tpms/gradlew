#!/bin/sh
# Minimal self-bootstrapping Gradle launcher for AndroidIDE.
# Downloads Gradle 8.9 on first run, then reuses it from ~/.gradle/jb23-wrapper.
set -eu
GRADLE_VERSION=8.9
BASE="${HOME:-/data/data/com.itsaky.androidide/files/home}/.gradle/jb23-wrapper"
DIST="$BASE/gradle-$GRADLE_VERSION"
ZIP="$BASE/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  rm -f "$ZIP"
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 -o "$ZIP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "ERROR: curl/wget not found. Run AndroidIDE setup (idesetup -c) first." >&2
    exit 127
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$BASE"
  else
    echo "ERROR: unzip not found. Run AndroidIDE setup (idesetup -c) first." >&2
    exit 127
  fi
fi
exec "$DIST/bin/gradle" "$@"
