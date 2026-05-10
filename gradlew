#!/usr/bin/env sh

set -eu

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "Gradle is not installed on this machine. Install Gradle or use Android Studio to sync the project." >&2
exit 1
